package com.akimy.genie.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.akimy.genie.R
import com.akimy.genie.agent.*
import com.akimy.genie.data.GenieDatabase
import com.akimy.genie.engine.DownloadState
import com.akimy.genie.engine.GenieEngine
import com.akimy.genie.engine.GenieModelConfig
import com.akimy.genie.engine.ModelDownloadManager
import com.akimy.genie.telemetry.EventLogger
import com.akimy.genie.telemetry.GenieEvent
import com.akimy.genie.tools.GenieTool
import com.akimy.genie.tools.ToolRegistry
import com.akimy.genie.tools.ToolServiceContext
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model as VoskModel
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.UUID

private const val TAG = "GenieA11yService"

/**
 * Core Accessibility Service for Genie — the autonomous OS-level agent.
 *
 * Adapted from BetaAssist's BetaAssistService.kt:
 * - Vosk wake-word detection (initVosk, recognizeMicrophone, onResult)
 * - TTS output (readTextAloud)
 * - STT recognition (SpeechRecognizer + RecognitionListener)
 * - Overlay windows (showProcessing, removeProcessing)
 * - Service lifecycle (onServiceConnected, onDestroy)
 *
 * Key changes from BetaAssist:
 * - MediaPipe LlmInference → LiteRT-LM GenieEngine
 * - Linear fewshot→processCommand → LangGraph AgentOrchestrator
 * - onServiceConnected triggers full Bootstrap: check→download→init→listen
 * - Implements ToolServiceContext for agent tool dispatch
 */
class GenieAccessibilityService : AccessibilityService(),
    org.vosk.android.RecognitionListener,
    ToolServiceContext {

    // -- Coroutine scopes --
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // -- Engine components --
    private val genieEngine = GenieEngine()
    private var downloadManager: ModelDownloadManager? = null

    // -- Agent components --
    private var orchestrator: AgentOrchestrator? = null
    private val toolRegistry = ToolRegistry()

    // -- Voice components (from BetaAssist) --
    private var voskModel: VoskModel? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stt: SpeechRecognizer? = null
    private var enabled = false

    // -- Overlay components (from BetaAssist) --
    private var windowManager: WindowManager? = null
    private var processingOverlay: FrameLayout? = null

    // -- STT RecognitionListener (from BetaAssist) --
    private val sttListener = object : RecognitionListener {
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "STT: onBeginningOfSpeech")
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "STT: onEndOfSpeech")
        }

        override fun onError(error: Int) {
            Log.d(TAG, "STT: onError code=$error")
            removeProcessingOverlay()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            removeProcessingOverlay()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.getOrNull(0) ?: ""
            Log.d(TAG, "STT result: $text")

            if (text.isNotBlank()) {
                // Route to agent orchestrator instead of BetaAssist's modelNlp()
                dispatchToAgent(text)
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}
    }

    // ========================================================================
    // Service Lifecycle (from BetaAssist)
    // ========================================================================

    override fun onServiceConnected() {
        Log.d(TAG, "=== Genie Service Connected ===")

        // Check audio permission (from BetaAssist)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            val intent = Intent(this, com.akimy.genie.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            disableSelf()
            return
        }

        // Initialize event logger
        EventLogger.init(serviceScope)

        // Bootstrap sequence: download model → init engine → init voice → listen
        bootstrap()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Genie is agent-driven, not event-driven — no-op
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "=== Genie Service Destroying ===")
        speechService?.stop()
        speechService = null
        tts?.shutdown()
        stt?.destroy()
        genieEngine.shutdown()
        serviceScope.cancel()
        removeProcessingOverlay()
        super.onDestroy()
    }

    // ========================================================================
    // Bootstrap Sequence
    // ========================================================================

    private fun bootstrap() {
        val startTime = System.currentTimeMillis()
        downloadManager = ModelDownloadManager(this)

        serviceScope.launch {
            // Step 1: Check/download model
            val modelConfig = GenieModelConfig.DEFAULT
            val token = getHfToken()

            if (!modelConfig.isDownloaded(this@GenieAccessibilityService)) {
                withContext(Dispatchers.Main) {
                    readTextAloud("Hello! I'm Genie. Downloading the AI model for first-time setup.")
                }

                downloadManager?.ensureModelReady(modelConfig, token)

                // Wait for download to complete
                downloadManager?.downloadState?.collect { state ->
                    when (state) {
                        is DownloadState.Ready -> {
                            Log.d(TAG, "Model download complete")
                            return@collect
                        }
                        is DownloadState.Failed -> {
                            Log.e(TAG, "Download failed: ${state.message}")
                            withContext(Dispatchers.Main) {
                                readTextAloud("Model download failed. Please check your connection.")
                            }
                            return@collect
                        }
                        is DownloadState.Downloading -> {
                            Log.d(TAG, "Download progress: ${state.progressPercent}%")
                        }
                        else -> {}
                    }
                }
            }

            // Step 2: Initialize LiteRT-LM engine
            val modelPath = modelConfig.getLocalPath(this@GenieAccessibilityService)
            val error = genieEngine.initialize(
                context = this@GenieAccessibilityService,
                modelPath = modelPath,
                systemPrompt = PromptBuilder.AGENT_SYSTEM_PROMPT,
            )
            if (error != null) {
                Log.e(TAG, "Engine init failed: $error")
                withContext(Dispatchers.Main) {
                    readTextAloud("Failed to start the AI engine.")
                }
                return@launch
            }

            // Step 3: Initialize agent orchestrator
            val db = GenieDatabase.getInstance(this@GenieAccessibilityService)
            val planner = Planner(genieEngine, db.skillDao())
            orchestrator = AgentOrchestrator(
                engine = genieEngine,
                toolRegistry = toolRegistry,
                promptBuilder = PromptBuilder(),
                planner = planner,
                factDao = db.factDao(),
                skillDao = db.skillDao(),
                appContext = applicationContext,
            )

            // Step 4: Initialize voice (from BetaAssist)
            withContext(Dispatchers.Main) {
                initVosk(this@GenieAccessibilityService)
                initTts()
                initStt()
            }

            val duration = System.currentTimeMillis() - startTime
            EventLogger.emit(GenieEvent.BootstrapPhase("full_bootstrap", duration))
            Log.d(TAG, "Bootstrap complete in ${duration}ms")
        }
    }

    // ========================================================================
    // Voice Setup (from BetaAssist)
    // ========================================================================

    /** Initialize Vosk wake-word engine (from BetaAssist's initVosk) */
    private fun initVosk(ctx: Context) {
        Log.d(TAG, "Initializing Vosk wake-word engine")
        StorageService.unpack(
            ctx, "vosk_model_small_en_us", "model",
            { model: VoskModel? ->
                voskModel = model
                enabled = true
                Log.d(TAG, "Vosk model loaded, starting wake-word listening")
                startWakeWordListening()
            },
            { error ->
                Log.e(TAG, "Vosk init failed: ${error.message}")
                error.printStackTrace()
            }
        )
    }

    /** Start Vosk wake-word listening (from BetaAssist's recognizeMicrophone) */
    private fun startWakeWordListening() {
        if (!enabled) return
        try {
            speechService?.stop()
            val rec = Recognizer(voskModel, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            Log.d(TAG, "Wake-word listener active")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake-word: ${e.message}")
        }
    }

    /** Initialize TTS (from BetaAssist) */
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                welcomeUser()
            } else {
                Log.e(TAG, "TTS init failed")
            }
        }
    }

    /** Initialize STT (from BetaAssist) */
    private fun initStt() {
        stt = SpeechRecognizer.createSpeechRecognizer(this)
        stt?.setRecognitionListener(sttListener)
    }

    /** Welcome message (adapted from BetaAssist's welcomeUser) */
    private fun welcomeUser() {
        readTextAloud("Hi! I'm Genie, your AI accessibility agent. Say Gemma to wake me up.")
        serviceScope.launch {
            delay(8000)
            withContext(Dispatchers.Main) {
                startWakeWordListening()
            }
        }
    }

    /** TTS output (from BetaAssist's readTextAloud) */
    private fun readTextAloud(text: String) {
        val bundle = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, bundle, UUID.randomUUID().toString())
    }

    // ========================================================================
    // Vosk RecognitionListener (wake-word detection, from BetaAssist)
    // ========================================================================

    override fun onPartialResult(hypothesis: String?) {
        // Check for wake word "gemma" in partial results
        if (hypothesis != null) {
            try {
                val json = JSONObject(hypothesis)
                val partial = json.optString("partial", "")
                if (partial.lowercase().contains("gemma")) {
                    Log.d(TAG, "Wake word 'gemma' detected!")
                    speechService?.stop()
                    speechService = null

                    // Switch to STT for command capture
                    serviceScope.launch {
                        withContext(Dispatchers.Main) {
                            showProcessingOverlay()
                            startSttListening()
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore JSON parse errors
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        Log.d(TAG, "Vosk result: $hypothesis")
    }

    override fun onFinalResult(hypothesis: String?) {
        Log.d(TAG, "Vosk final: $hypothesis")
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk error: ${exception?.message}")
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk timeout")
        startWakeWordListening()
    }

    // ========================================================================
    // STT Command Capture
    // ========================================================================

    private fun startSttListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
        }
        stt?.startListening(intent)
    }

    // ========================================================================
    // Agent Dispatch (replaces BetaAssist's modelNlp/processCommand)
    // ========================================================================

    private fun dispatchToAgent(userCommand: String) {
        Log.d(TAG, "Dispatching to agent: $userCommand")
        showProcessingOverlay()

        serviceScope.launch {
            val result = orchestrator?.executeGoal(
                goal = userCommand,
                serviceContext = this@GenieAccessibilityService,
                onStatusUpdate = { status ->
                    serviceScope.launch(Dispatchers.Main) {
                        readTextAloud(status)
                    }
                }
            ) ?: "Agent not ready"

            withContext(Dispatchers.Main) {
                removeProcessingOverlay()
                // Resume wake-word listening
                startWakeWordListening()
            }

            Log.d(TAG, "Agent result: $result")
        }
    }

    // ========================================================================
    // Overlay Windows (from BetaAssist)
    // ========================================================================

    private fun showProcessingOverlay() {
        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        processingOverlay = FrameLayout(this)

        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
        }

        // Simple text overlay instead of custom layout
        val textView = TextView(this).apply {
            text = "🧞 Genie is thinking..."
            textSize = 18f
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 24, 48, 24)
        }
        processingOverlay?.addView(textView)

        try {
            windowManager?.addView(processingOverlay, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    private fun removeProcessingOverlay() {
        try {
            if (processingOverlay != null && windowManager != null) {
                windowManager?.removeView(processingOverlay)
                processingOverlay = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay: ${e.message}")
        }
    }

    // ========================================================================
    // ToolServiceContext Implementation (OS Bridge for agent tools)
    // ========================================================================

    private val gestureDispatcher by lazy { GestureDispatcher(this) }

    override suspend fun clickElement(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = UINodeParser.findClickableNode(root, target)
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    override suspend fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else false
    }

    override suspend fun swipe(direction: String): Boolean {
        val dir = GestureDispatcher.parseDirection(direction) ?: return false
        return gestureDispatcher.swipe(dir)
    }

    override suspend fun scroll(direction: String): Boolean {
        val dir = GestureDispatcher.parseDirection(direction) ?: return false
        return gestureDispatcher.scroll(dir)
    }

    override suspend fun readScreenText(): String {
        return UINodeParser.extractAllText(rootInActiveWindow)
    }

    override suspend fun takeScreenshot(): String {
        val bitmap = ScreenCapture.captureScreen(this)
        return if (bitmap != null) "Screenshot captured (${bitmap.width}x${bitmap.height})" else "Screenshot failed"
    }

    override suspend fun openApp(name: String): Boolean {
        val pm = applicationContext.packageManager
        val intent = pm.getLaunchIntentForPackage(findPackageName(name) ?: return false)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return true
        }
        return false
    }

    override suspend fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override suspend fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override suspend fun tap(x: Float, y: Float): Boolean {
        return gestureDispatcher.tap(x, y)
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Find package name by app label (adapted from BetaAssist's openApp).
     */
    private fun findPackageName(appName: String): String? {
        val pm = applicationContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val lowerName = appName.lowercase()

        for (app in apps) {
            val label = app.loadLabel(pm).toString().lowercase()
            if (label == lowerName || label.contains(lowerName)) {
                return app.activityInfo.packageName
            }
        }
        return null
    }

    /**
     * Get the HuggingFace token from encrypted SharedPreferences.
     */
    private fun getHfToken(): String? {
        return try {
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                "genie_secure_prefs",
                androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
                ),
                this,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs.getString("hf_token", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read HF token: ${e.message}")
            null
        }
    }
}
