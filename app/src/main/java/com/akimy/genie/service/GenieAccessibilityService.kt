package com.akimy.genie.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.akimy.genie.ui.AgentUIState
import com.akimy.genie.ui.GenieOverlayUI
import kotlinx.coroutines.flow.MutableStateFlow
import android.media.MediaPlayer
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import android.os.Looper
import com.akimy.genie.R
import com.akimy.genie.agent.*
import com.akimy.genie.data.GenieDatabase
import com.akimy.genie.engine.DownloadState
import com.akimy.genie.engine.GenieEngine
import com.akimy.genie.engine.AgentResponse
import com.akimy.genie.engine.GenieModelConfig
import com.akimy.genie.engine.ModelDownloadManager
import com.akimy.genie.engine.ModelPrefs
import com.akimy.genie.engine.awaitTerminalDownloadState
import com.akimy.genie.telemetry.EventLogger
import com.akimy.genie.telemetry.GenieEvent
import com.akimy.genie.tools.ToolRegistry
import com.akimy.genie.tools.ToolProfile
import com.akimy.genie.tools.ToolProfilePrefs
import com.akimy.genie.tools.ToolServiceContext
import com.akimy.genie.tools.DebugPrefs
import com.akimy.genie.ui.DebugDocumentPanel
import com.akimy.genie.ui.DebugToolTestOverlay
import com.akimy.genie.tools.ViewportInfo
import com.akimy.genie.tools.VisualizerSceneStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model as VoskModel
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

private const val TAG = "GenieA11yService"
private const val MAX_VISION_IMAGE_EDGE_PX = 512

/**
 * Core Accessibility Service for Genie — the autonomous OS-level agent.
 *
 * This service owns bootstrap, voice I/O, accessibility awareness,
 *
 *
 *
 *
 *
 *
 * and OS action dispatch for the agent tool layer.
 *
 *
 *
 *
 */
private class FakeLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

class GenieAccessibilityService : AccessibilityService(),
    org.vosk.android.RecognitionListener,
    ToolServiceContext {

    companion object {
        @Volatile private var activeInstance: GenieAccessibilityService? = null

        fun requestTeachingCommand(command: String): Boolean {
            val service = activeInstance ?: return false
            if (service.agentBusy) return false
            if (service.orchestrator == null) return false
            service.dispatchToAgent(command)
            return true
        }

        // AudioRecord configuration for 16kHz PCM (native Gemma format)
        const val SAMPLE_RATE = 16000 // 16kHz - Gemma native format
        const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    // -- Coroutine scopes --
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // -- Engine components --
    private val genieEngine = GenieEngine()
    private var downloadManager: ModelDownloadManager? = null

    // -- Agent components --
    private var orchestrator: AgentOrchestrator? = null
    private var activeToolProfile: ToolProfile = ToolProfile.DEFAULT
    private val toolRegistry = ToolRegistry()
    private val awarenessTracker = AccessibilityAwarenessTracker()
    private val narrationController = ContinuousNarrationController()

    // -- Voice components --
    private var voskModel: VoskModel? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stt: SpeechRecognizer? = null
    private var enabled = false
    @Volatile private var agentBusy = false
    @Volatile private var isWelcomeMessageFinished = false

    // -- Overlay components --
    private var windowManager: WindowManager? = null
    private var composeOverlay: ComposeView? = null
    private var fakeLifecycleOwner: FakeLifecycleOwner? = null
    private val uiStateFlow = MutableStateFlow<AgentUIState>(AgentUIState.Initializing)
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var latestScreenshotPngBytes: ByteArray? = null
    private val fingerprintGestureCallback = object : FingerprintGestureController.FingerprintGestureCallback() {
        override fun onGestureDetectionAvailabilityChanged(available: Boolean) {
            Log.d(TAG, "Fingerprint gesture detection available: $available")
        }

        override fun onGestureDetected(gesture: Int) {
            serviceScope.launch(Dispatchers.Main) {
                handleFingerprintGesture(gesture)
            }
        }
    }

    // -- STT recognition listener --
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
            setUiState(AgentUIState.Idle)
            startWakeWordListening()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            setUiState(AgentUIState.Thinking)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.getOrNull(0) ?: ""
            Log.d(TAG, "STT result: $text")

            if (text.isNotBlank()) {
                // Route recognized speech into the agent loop
                dispatchToAgent(text)
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}
    }

    // ========================================================================
    // Service lifecycle
    // ========================================================================

    override fun onServiceConnected() {
        Log.d(TAG, "=== Genie Service Connected ===")
        activeInstance = this

        // Initialize stores (needed for both normal and debug mode)
        EventLogger.init(serviceScope)
        VisualizerSceneStore.initialize(applicationContext)
        ScreenMapStore.initialize(applicationContext)

        // ── Debug mode: skip everything and show overlay ──
        if (DebugPrefs.isDebugMode(this)) {
            Log.d(TAG, "=== DEBUG MODE ACTIVE — Skipping LLM bootstrap ===")
            setupDebugOverlay()
            return
        }

        // Check audio permission
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

        registerFingerprintGestures()
        setupComposeOverlay()
        mediaPlayer = MediaPlayer.create(this, R.raw.genie)

        // Bootstrap sequence: download model → init engine → init voice → listen
        bootstrap()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackage.isNotBlank() &&
            eventPackage != packageName
        ) {
            Log.d(TAG, "Window changed: $eventPackage")
        }
        val record = awarenessTracker.onAccessibilityEvent(event, rootInActiveWindow ?: event?.source)
        if (!agentBusy) {
            narrateAwarenessRecord(record)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "=== Genie Service Destroying ===")
        if (activeInstance === this) {
            activeInstance = null
        }
        unregisterFingerprintGestures()
        speechService?.stop()
        speechService = null
        tts?.shutdown()
        stt?.destroy()
        genieEngine.shutdown()
        serviceScope.cancel()
        removeComposeOverlay()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    // ========================================================================
    // Bootstrap Sequence
    // ========================================================================

    private fun bootstrap() {
        val startTime = System.currentTimeMillis()
        downloadManager = ModelDownloadManager(this)

        serviceScope.launch {
            // Step 1: Check model — user must have selected and downloaded via MainActivity
            val modelConfig = ModelPrefs.getSelectedConfig(this@GenieAccessibilityService)
            val toolProfile = ToolProfilePrefs.getSelectedProfile(this@GenieAccessibilityService)
            activeToolProfile = toolProfile
            val plannerToolProviders = geniePlannerToolProviders(toolProfile)
            Log.d(TAG, "Using tool profile: ${toolProfile.displayName} (${toolProfile.toolNames.size} tools)")

            if (modelConfig == null || !modelConfig.isDownloaded(this@GenieAccessibilityService)) {
                Log.w(TAG, "Model not ready. Selection: ${modelConfig?.displayName}")
                withContext(Dispatchers.Main) {
                    readTextAloud("Please open the Genie app first to select and download an AI model.")
                }
                return@launch
            }

            // Step 2: Initialize LiteRT-LM engine
            val modelPath = modelConfig.getLocalPath(this@GenieAccessibilityService)
            val error = genieEngine.initialize(
                context = this@GenieAccessibilityService,
                modelPath = modelPath,
                systemPrompt = PromptBuilder.systemPromptForProfile(toolProfile),
                tools = plannerToolProviders,
                supportsAudio = modelConfig.supportsAudio,
                supportsImage = modelConfig.supportsImage,
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
            val planner: GeniePlanner = Planner(genieEngine, db.skillDao())
            orchestrator = AgentOrchestrator(
                engine = genieEngine,
                toolRegistry = toolRegistry,
                enabledToolNames = toolProfile.toolNames,
                promptBuilder = PromptBuilder(),
                planner = planner,
                factDao = db.factDao(),
                skillDao = db.skillDao(),
                appContext = applicationContext,
                toolProfile = toolProfile,
            )

            // Step 4: Initialize voice (skip for UI-driven profiles)
            withContext(Dispatchers.Main) {
                if (toolProfile == ToolProfile.Scribe || toolProfile == ToolProfile.Health) {
                    genieEngine.resetConversation(
                        systemPrompt = PromptBuilder.systemPromptForProfile(toolProfile),
                        tools = emptyList(),
                        constrainedDecoding = false,
                    )
                    Log.d(TAG, "${toolProfile.displayName} mode: skipping voice, showing overlay")
                    setupDebugOverlay()
                } else {
                    // Normal profiles: init voice and set idle state
                    initVosk(this@GenieAccessibilityService)
                    initTts()
                    initStt()
                    setUiState(AgentUIState.Idle)
                }
            }

            val duration = System.currentTimeMillis() - startTime
            EventLogger.emit(GenieEvent.BootstrapPhase(
                if (toolProfile == ToolProfile.Scribe || toolProfile == ToolProfile.Health) "ui_bootstrap" else "full_bootstrap",
                duration
            ))
            Log.d(TAG, "Bootstrap complete in ${duration}ms")
        }
    }

    // ========================================================================
    // Voice setup
    // ========================================================================

    /** Initialize the Vosk wake-word engine. */
    private fun initVosk(ctx: Context) {
        Log.d(TAG, "Initializing Vosk wake-word engine")
        StorageService.unpack(
            ctx, "vosk_model_small_en_us", "model",
            { model: VoskModel? ->
                voskModel = model
                enabled = true
                Log.d(TAG, "Vosk model loaded")
                if (isWelcomeMessageFinished) {
                    startWakeWordListening()
                }
            },
            { error ->
                Log.e(TAG, "Vosk init failed: ${error.message}")
                error.printStackTrace()
            }
        )
    }

    /** Start Vosk wake-word listening. */
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

    /** Initialize TTS. */
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "WELCOME_MESSAGE") {
                            isWelcomeMessageFinished = true
                        }
                        serviceScope.launch(Dispatchers.Main) {
                            if (uiStateFlow.value is AgentUIState.Speaking) {
                                setUiState(AgentUIState.Idle)
                            }
                            if (enabled && !agentBusy) {
                                startWakeWordListening()
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == "WELCOME_MESSAGE") {
                            isWelcomeMessageFinished = true
                        }
                        serviceScope.launch(Dispatchers.Main) {
                            if (uiStateFlow.value is AgentUIState.Speaking) {
                                setUiState(AgentUIState.Idle)
                            }
                            if (enabled && !agentBusy) {
                                startWakeWordListening()
                            }
                        }
                    }
                })
                welcomeUser()
            } else {
                Log.e(TAG, "TTS init failed")
            }
        }
    }

    /** Initialize STT. */
    private fun initStt() {
        stt = SpeechRecognizer.createSpeechRecognizer(this)
        stt?.setRecognitionListener(sttListener)
    }

    /** Speak the welcome message after voice startup. */
    private fun welcomeUser() {
        readTextAloud(
            text = "Hi! I'm Genie, your AI accessibility agent. Say Gemma to wake me up.", 
            utteranceId = "WELCOME_MESSAGE"
        )
    }

    /** Speak text through TTS. */
    private fun readTextAloud(
        text: String, 
        queueMode: Int = TextToSpeech.QUEUE_ADD, 
        utteranceId: String = java.util.UUID.randomUUID().toString()
    ) {
        val bundle = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, queueMode, bundle, utteranceId)
    }

    // ========================================================================
    // Vosk recognition listener
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
                    setUiState(AgentUIState.Waking)

                    // Switch to STT for command capture after the sound plays
                    if (mediaPlayer != null) {
                        mediaPlayer?.setOnCompletionListener {
                            setUiState(AgentUIState.Listening)
                            serviceScope.launch(Dispatchers.Main) { startSttListening() }
                        }
                        mediaPlayer?.setOnErrorListener { _, _, _ ->
                            setUiState(AgentUIState.Listening)
                            serviceScope.launch(Dispatchers.Main) { startSttListening() }
                            true
                        }
                        mediaPlayer?.start()
                    } else {
                        setUiState(AgentUIState.Listening)
                        serviceScope.launch(Dispatchers.Main) { startSttListening() }
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
    // Agent dispatch
    // ========================================================================

    private fun dispatchToAgent(userCommand: String) {
        Log.d(TAG, "Dispatching to agent: $userCommand")
        setUiState(AgentUIState.Thinking)
        agentBusy = true

        serviceScope.launch {
            val result = try {
                orchestrator?.executeGoal(
                    goal = userCommand,
                    serviceContext = this@GenieAccessibilityService,
                    onStatusUpdate = { status ->
                        serviceScope.launch(Dispatchers.Main) {
                            setUiState(AgentUIState.Speaking(status))
                            readTextAloud(status)
                        }
                    },
                    onToolExecuting = { title, subtitle ->
                        setUiState(AgentUIState.Executing(title, subtitle))
                    }
                ) ?: "Agent not ready"
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
                "Agent failed: ${e.message ?: "unknown error"}"
            }

            Log.d(TAG, "Agent result: $result")

            // Speak the final result and update UI state
            withContext(Dispatchers.Main) {
                agentBusy = false
                setUiState(AgentUIState.Speaking(result))
                readTextAloud(result)

                // Small delay to let TTS begin
                kotlinx.coroutines.delay(300)

                // Only restart if TTS hasn't started speaking
                // If it is speaking, UtteranceProgressListener.onDone will restart it
                if (tts?.isSpeaking != true) {
                    setUiState(AgentUIState.Idle)
                    startWakeWordListening()
                }
            }
        }
    }

    // ========================================================================
    // Overlay windows
    // ========================================================================

    private fun setupComposeOverlay() {
        // Accessibility services can reconnect without a clean visual teardown.
        // Remove any view owned by this service before adding a fresh collector,
        // otherwise an old "Initializing..." overlay can remain above the live one.
        removeComposeOverlay()

        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        fakeLifecycleOwner = FakeLifecycleOwner()
        
        composeOverlay = ComposeView(this).apply {
            setViewTreeLifecycleOwner(fakeLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(fakeLifecycleOwner)
            setViewTreeViewModelStoreOwner(fakeLifecycleOwner)
            setContent {
                GenieOverlayUI(uiStateFlow)
            }
        }
        composeOverlay?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(composeOverlay, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show compose overlay: ${e.message}")
        }
    }

    /**
     * Debug-only overlay: touchable panel with buttons for each teaching tool.
     * No LLM, no voice — purely manual tool execution + logcat output.
     */

    /**
     * Takes a screenshot, loads the model (if not ready), and uses
     * multimodal inference to extract & summarize text from the image.
     */
    private suspend fun detectPdfOnScreen(): String {
        // 1. Take screenshot of whatever is behind the overlay
        val bitmap = ScreenCapture.captureScreen(this)
            ?: return "✗ Screenshot failed — check permissions"

        // 2. Encode to PNG bytes at full resolution.
        // Don't pre-downscale — let the engine's internal preprocessor handle
        // optimal resizing. It can produce up to 2520 patches from a high-res
        // image vs only 720 from a 640px-downscaled one. More patches = more
        // text detail for document extraction.
        val pngBytes = withContext(Dispatchers.Default) {
            try {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            } catch (_: Exception) { null }
        }
        if (!bitmap.isRecycled) bitmap.recycle()

        if (pngBytes == null) return "✗ PNG encoding failed"
        Log.d(TAG, "detectPdf screenshot ready: ${pngBytes.size} bytes")

        // 4. Ensure model is loaded (lazy init for debug mode)
        if (!genieEngine.isReady()) {
            val modelConfig = ModelPrefs.getSelectedConfig(this)
            if (modelConfig == null || !modelConfig.isDownloaded(this)) {
                return "✗ No model downloaded. Select & download a model in the Genie app first."
            }
            val modelPath = modelConfig.getLocalPath(this)
            Log.d(TAG, "detectPdf: loading model for debug vision...")
            val err = genieEngine.initialize(
                context = this,
                modelPath = modelPath,
                systemPrompt = "You are a document reader. When given an image, extract all visible text and provide a brief summary.",
                tools = emptyList(),
            )
            if (err != null) return "✗ Model init failed: $err"
            Log.d(TAG, "detectPdf: model loaded ✓")
        }

        // 4b. Reset to clean vision-only conversation.
        // When the engine was already initialized from bootstrap, the conversation
        // carries the full agent system prompt, tool schemas, and constrained decoding.
        // That context poisons the vision query, causing truncated output.
        val visionPrompt = "Extract and summarize all the text visible in this image. Return the extracted text first, then a brief summary."
        genieEngine.resetConversation(
            systemPrompt = visionPrompt,
            tools = emptyList(),
            constrainedDecoding = false,
        )
        Log.d(TAG, "detectPdf: conversation reset to vision-only mode ✓")

        // 5. Send screenshot to model with extraction prompt
        val sb = StringBuilder()
        var gotResponse = false
        genieEngine.sendAgentMessage(
            text = visionPrompt,
            imagePngBytes = listOf(pngBytes),
        ).collect { response ->
            when (response) {
                is AgentResponse.Token -> { sb.append(response.text); gotResponse = true }
                is AgentResponse.Done -> { /* complete */ }
                is AgentResponse.Error -> { sb.append("\n✗ Model error: ${response.error}") }
                is AgentResponse.ToolCallRequest -> { /* ignore in this context */ }
            }
        }

        return if (gotResponse) {
            "✓ Model extracted text:\n\n${sb.toString().trim()}"
        } else {
            "✗ Model returned no output. ${sb.toString().trim()}"
        }
    }


    private fun setupDebugOverlay() {
        removeComposeOverlay()

        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        fakeLifecycleOwner = FakeLifecycleOwner()

        val selectedProfile = ToolProfilePrefs.getSelectedProfile(this)
        activeToolProfile = selectedProfile
        Log.d(TAG, "Debug mode — selected profile: ${selectedProfile.displayName}")

        val closeAction = {
            removeComposeOverlay()
            disableSelf()
        }

        composeOverlay = ComposeView(this).apply {
            setViewTreeLifecycleOwner(fakeLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(fakeLifecycleOwner)
            setViewTreeViewModelStoreOwner(fakeLifecycleOwner)
            setContent {
                when (selectedProfile) {
                    ToolProfile.Document -> {
                        DebugDocumentPanel(
                            onClose = closeAction,
                            onDetectPdf = { detectPdfOnScreen() },
                        )
                    }
                    ToolProfile.Scribe -> {
                        com.akimy.genie.ui.ScribeOverlay(
                            onClose = closeAction,
                            onStartRecording = { config ->
                                serviceScope.launch {
                                    startAudioRecording()
                                }
                            },
                            onStopRecording = {
                                serviceScope.launch {
                                    val audioPath = stopAudioRecording()
                                    if (audioPath != null) {
                                        val config = com.akimy.genie.tools.ScribeSessionStore.getConfig()
                                        if (config != null) {
                                            try {
                                                val transcription = transcribeAudio(audioPath, config.inputLanguage)
                                                val result = when (config.mode) {
                                                    com.akimy.genie.tools.ScribeMode.GENERAL -> {
                                                        val insights = extractInsights(transcription, config.outputLanguage)
                                                        com.akimy.genie.tools.ScribeResult.General(insights)
                                                    }
                                                    com.akimy.genie.tools.ScribeMode.DOCTOR_SCRIBE -> {
                                                        val soapNote = formatSoapNote(transcription, config.outputLanguage)
                                                        com.akimy.genie.tools.ScribeResult.Medical(soapNote)
                                                    }
                                                }
                                                com.akimy.genie.tools.ScribeSessionStore.setResult(result)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error processing recording", e)
                                                com.akimy.genie.tools.ScribeSessionStore.setResult(
                                                    com.akimy.genie.tools.ScribeResult.Error(e.message ?: "Unknown error")
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    ToolProfile.Health -> {
                        com.akimy.genie.ui.HealthOverlay(
                            onAnalyzeFoodFromScreen = {
                                serviceScope.launch {
                                    try {
                                        val bitmap = ScreenCapture.captureScreen(this@GenieAccessibilityService)
                                        if (bitmap != null) {
                                            analyzeFoodImage(bitmap)
                                        } else {
                                            com.akimy.genie.tools.HealthSessionStore.setResult(
                                                com.akimy.genie.tools.HealthResult.Error("Failed to capture screen")
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error analyzing food from screen", e)
                                        com.akimy.genie.tools.HealthSessionStore.setResult(
                                            com.akimy.genie.tools.HealthResult.Error(e.message ?: "Unknown error")
                                        )
                                    }
                                }
                            },
                            onRequestGalleryPick = {
                                com.akimy.genie.ImagePickerActivity.pendingCallback = { uri ->
                                    serviceScope.launch {
                                        try {
                                            val bitmap = loadBitmapFromUri(uri)
                                            if (bitmap != null) {
                                                analyzeFoodImage(bitmap)
                                            } else {
                                                com.akimy.genie.tools.HealthSessionStore.setResult(
                                                    com.akimy.genie.tools.HealthResult.Error("Failed to load image")
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error analyzing food from gallery", e)
                                            com.akimy.genie.tools.HealthSessionStore.setResult(
                                                com.akimy.genie.tools.HealthResult.Error(e.message ?: "Unknown error")
                                            )
                                        }
                                    }
                                }
                                val intent = android.content.Intent(this@GenieAccessibilityService, com.akimy.genie.ImagePickerActivity::class.java).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            },
                            onSearchHealthTopic = { query ->
                                serviceScope.launch {
                                    searchHealthTopic(query)
                                }
                            },
                            onStartVoiceSearch = {
                                startAndroidSTTForHealthQuery()
                            },
                            onToggleFullscreen = { isFullscreen ->
                                updateHealthWindowSize(isFullscreen)
                            },
                            onClose = closeAction
                        )
                    }
                    else -> {
                        // Teaching and all other profiles default to board debug
                        DebugToolTestOverlay(onClose = closeAction)
                    }
                }
            }
        }
        composeOverlay?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val isCompact = selectedProfile == ToolProfile.Document
        val isFullScreen = selectedProfile == ToolProfile.Scribe
        val isFloating = selectedProfile == ToolProfile.Health
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            if (isCompact) {
                gravity = Gravity.BOTTOM
                height = WindowManager.LayoutParams.WRAP_CONTENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else if (isFullScreen) {
                gravity = Gravity.CENTER
                height = WindowManager.LayoutParams.MATCH_PARENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else if (isFloating) {
                gravity = Gravity.TOP or Gravity.START
                height = WindowManager.LayoutParams.WRAP_CONTENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                gravity = Gravity.BOTTOM
                height = WindowManager.LayoutParams.MATCH_PARENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
        }

        try {
            windowManager?.addView(composeOverlay, lp)
            Log.d(TAG, "Debug overlay attached (profile: ${selectedProfile.displayName})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show debug overlay: ${e.message}")
        }
    }

    private fun setUiState(state: AgentUIState) {
        Log.d(TAG, "Overlay state -> ${state.javaClass.simpleName}")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiStateFlow.value = state
        } else {
            serviceScope.launch(Dispatchers.Main) {
                uiStateFlow.value = state
            }
        }
    }

    private fun removeComposeOverlay() {
        try {
            if (composeOverlay != null && windowManager != null) {
                windowManager?.removeView(composeOverlay)
                composeOverlay = null
            }
            fakeLifecycleOwner?.destroy()
            fakeLifecycleOwner = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove compose overlay: ${e.message}")
        }
    }

    private fun updateHealthWindowSize(isFullscreen: Boolean) {
        val overlay = composeOverlay ?: return
        val wm = windowManager ?: return

        serviceScope.launch(Dispatchers.Main) {
            try {
                val lp = overlay.layoutParams as WindowManager.LayoutParams
                if (isFullscreen) {
                    lp.width = WindowManager.LayoutParams.MATCH_PARENT
                    lp.height = WindowManager.LayoutParams.MATCH_PARENT
                    lp.gravity = Gravity.CENTER
                } else {
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.gravity = Gravity.TOP or Gravity.START
                }
                wm.updateViewLayout(overlay, lp)
                Log.d(TAG, "Health window size updated: fullscreen=$isFullscreen")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update health window size", e)
            }
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

    override suspend fun readFocusedNode(): String {
        return awarenessTracker.readFocused(rootInActiveWindow)
    }

    override suspend fun takeScreenshot(): String {
        val bitmap = ScreenCapture.captureScreen(this)
        if (bitmap == null) {
            latestScreenshotPngBytes = null
            return "Screenshot failed"
        }

        // Apply Set-of-Mark (SoM) bounding box markup.
        ScreenSomStore.clear()
        val root = rootInActiveWindow
        if (root != null) {
            val nodes = UINodeParser.parseNodeTree(root)
            val canvas = android.graphics.Canvas(bitmap)
            val paintBox = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
            }
            val paintTextBg = android.graphics.Paint().apply {
                color = android.graphics.Color.YELLOW
                style = android.graphics.Paint.Style.FILL
            }
            val paintText = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 42f
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            var currentId = 1
            for (node in nodes) {
                val bounds = node.boundsInScreen
                if (bounds != null && !bounds.isEmpty && node.isVisibleToUser) {
                    // Only markup actionable or relevant items that an agent could click
                    if (node.isClickable || node.isEditable || node.isScrollable || node.isLongClickable) {
                        ScreenSomStore.store(currentId, bounds)

                        val idString = " $currentId "
                        val textWidth = paintText.measureText(idString)
                        val textHeight = paintText.descent() - paintText.ascent()

                        val cx = bounds.centerX().toFloat()
                        val cy = bounds.centerY().toFloat()

                        canvas.drawRect(bounds, paintBox)

                        val bgRect = android.graphics.RectF(
                            cx - textWidth / 2f,
                            cy - textHeight / 2f + paintText.ascent(),
                            cx + textWidth / 2f,
                            cy - textHeight / 2f + paintText.descent()
                        )
                        canvas.drawRect(bgRect, paintTextBg)
                        canvas.drawText(idString, cx - textWidth / 2f, cy - textHeight / 2f, paintText)

                        currentId++
                    }
                }
            }
        }

        val savedPath = saveAnnotatedScreenshotToDownloads(bitmap)
        val pngBytes = withContext(Dispatchers.Default) {
            encodeVisionPng(bitmap)
        }

        latestScreenshotPngBytes = pngBytes
        return if (pngBytes != null) {
            val baseMessage = "Screenshot captured (${bitmap.width}x${bitmap.height}) with ${ScreenSomStore.getAll().size} annotated elements"
            if (savedPath != null) "$baseMessage. Saved annotated screenshot to $savedPath" else baseMessage
        } else {
            "Screenshot captured but PNG encoding failed"
        }
    }

    override suspend fun consumeLatestScreenshotPngBytes(): ByteArray? {
        val bytes = latestScreenshotPngBytes
        latestScreenshotPngBytes = null
        return bytes
    }

    private fun encodeVisionPng(bitmap: Bitmap): ByteArray? {
        val prepared = downscaleForVision(bitmap)
        return try {
            ByteArrayOutputStream().use { out ->
                val ok = prepared.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (!ok) return null
                val bytes = out.toByteArray()
                Log.d(TAG, "Vision screenshot encoded (${prepared.width}x${prepared.height}, ${bytes.size} bytes)")
                bytes
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (prepared !== bitmap && !prepared.isRecycled) {
                prepared.recycle()
            }
        }
    }

    private fun saveAnnotatedScreenshotToDownloads(bitmap: Bitmap): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Saving annotated screenshot to Downloads requires Android 10+")
            return null
        }

        val filename = "annotated_som_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Genie")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null

        val wrote = try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save annotated screenshot: ${e.message}")
            false
        }

        return if (wrote) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "Downloads/Genie/$filename"
        } else {
            contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun downscaleForVision(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_VISION_IMAGE_EDGE_PX) return bitmap

        val scale = MAX_VISION_IMAGE_EDGE_PX.toFloat() / longest.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
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

    override suspend fun getScreenContext(): com.akimy.genie.tools.ScreenContext {
        return UINodeParser.extractScreenContext(rootInActiveWindow)
    }

    override suspend fun getViewportInfo(): ViewportInfo {
        val metrics = resources.displayMetrics
        return ViewportInfo(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
        )
    }

    override suspend fun focusNext(): Boolean {
        val moved = moveFocus(step = 1)
        return moved
    }

    override suspend fun focusPrevious(): Boolean {
        val moved = moveFocus(step = -1)
        return moved
    }

    override suspend fun focusFirst(): Boolean {
        val root = rootInActiveWindow ?: return false
        val first = UINodeParser.findFirstNavigableNode(root) ?: return false
        return requestAccessibilityFocus(first)
    }

    override suspend fun focusElementByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = UINodeParser.findNodeByText(root, target) ?: return false
        return requestAccessibilityFocus(node)
    }

    override suspend fun focusElementByRole(role: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = UINodeParser.findNodeByRole(root, role) ?: return false
        return requestAccessibilityFocus(node)
    }

    override suspend fun activateFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = UINodeParser.findAccessibilityFocusedNode(root)
            ?: return false

        Log.d(TAG, "activateFocused: focused=${nodeLog(focused)}")

        // Walk up the ancestor chain trying activation at each level.
        // Some apps mark child nodes non-clickable but handle clicks on a parent.
        val candidates = generateSequence(focused) { it.parent }.take(6)
        for (candidate in candidates) {
            if (!candidate.isVisibleToUser || !candidate.isEnabled) continue

            if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "activateFocused: ACTION_CLICK on ${nodeLog(candidate)}")
                return true
            }
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
                Log.d(TAG, "activateFocused: ACTION_SELECT on ${nodeLog(candidate)}")
                return true
            }
        }

        Log.d(TAG, "activateFocused: no ancestor accepted activation")
        return false
    }

    override suspend fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = UINodeParser.findScrollableNode(root)
        return scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ?: gestureDispatcher.scroll(GestureDispatcher.Direction.UP)
    }

    override suspend fun scrollBackward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = UINodeParser.findScrollableNode(root)
        return scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ?: gestureDispatcher.scroll(GestureDispatcher.Direction.DOWN)
    }

    override suspend fun readScreenSummary(): String {
        return awarenessTracker.readScreenSummary(rootInActiveWindow)
    }

    override suspend fun readRecentEvents(limit: Int): String {
        return awarenessTracker.readRecentEvents(limit)
    }

    override suspend fun whereAmI(): String {
        return awarenessTracker.whereAmI(rootInActiveWindow)
    }

    override suspend fun readNearbyContext(): String {
        return awarenessTracker.readNearbyContext(rootInActiveWindow)
    }

    override suspend fun whatCanIDoHere(): String {
        return awarenessTracker.whatCanIDoHere(rootInActiveWindow)
    }

    override suspend fun readScreenChanges(): String {
        return awarenessTracker.readScreenChanges()
    }

    override suspend fun enableContinuousReader(): String {
        val message = narrationController.enable()
        narrationController.rememberNarration(message)
        serviceScope.launch(Dispatchers.Main) {
            readTextAloud(message, TextToSpeech.QUEUE_FLUSH)
        }
        return message
    }

    override suspend fun disableContinuousReader(): String {
        val message = narrationController.disable()
        narrationController.rememberNarration(message)
        serviceScope.launch(Dispatchers.Main) {
            tts?.stop()
            readTextAloud(message, TextToSpeech.QUEUE_FLUSH)
        }
        return message
    }

    override suspend fun readContinuousReaderStatus(): String {
        return narrationController.status()
    }

    override suspend fun repeatLastNarration(): String {
        val message = narrationController.repeatLastNarration()
        serviceScope.launch(Dispatchers.Main) {
            readTextAloud(message, TextToSpeech.QUEUE_FLUSH)
        }
        return message
    }

    override suspend fun readScreenMap(): String {
        val snapshot = awarenessTracker.latestScreenSnapshot.takeIf { it.visibleNodes.isNotEmpty() }
            ?: rootInActiveWindow?.let(UINodeParser::extractSemanticScreen)
            ?: return "No learned screen map is available for this screen yet."
        return ScreenMapStore.describeCurrentScreen(snapshot)
    }

    override suspend fun saveScreenHint(note: String): String {
        val snapshot = awarenessTracker.latestScreenSnapshot.takeIf { it.visibleNodes.isNotEmpty() }
            ?: rootInActiveWindow?.let(UINodeParser::extractSemanticScreen)
            ?: return "There is no stable screen visible to save a hint for."
        return ScreenMapStore.saveUserHint(snapshot, note)
    }

    override suspend fun readDialog(): String {
        return awarenessTracker.readDialog(rootInActiveWindow)
    }

    override suspend fun readNotifications(limit: Int): String {
        return awarenessTracker.readNotifications(limit)
    }

    override suspend fun readFormState(): String {
        return awarenessTracker.readFormState(rootInActiveWindow)
    }

    // ========================================================================
    // Scribe Profile Methods
    // ========================================================================

    // AudioRecord for 16kHz PCM recording (native Gemma format)
    private var audioRecord: android.media.AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private val recordedPcmBuffer = mutableListOf<ByteArray>()

    override suspend fun startAudioRecording(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (ContextCompat.checkSelfPermission(
                    this@GenieAccessibilityService,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Audio recording permission not granted")
                return@withContext false
            }

            // Calculate buffer size
            val minBufferSize = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == android.media.AudioRecord.ERROR ||
                minBufferSize == android.media.AudioRecord.ERROR_BAD_VALUE
            ) {
                Log.e(TAG, "Invalid AudioRecord buffer size")
                return@withContext false
            }

            // Clear previous recording buffer
            recordedPcmBuffer.clear()

            // Create AudioRecord instance
            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                audioRecord?.release()
                audioRecord = null
                return@withContext false
            }

            // Start recording
            audioRecord?.startRecording()
            isRecording = true

            // Start background thread to read audio data
            recordingThread = Thread {
                val buffer = ByteArray(minBufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Store PCM chunks
                        recordedPcmBuffer.add(buffer.copyOf(read))
                    }
                }
            }.apply { start() }

            Log.d(TAG, "AudioRecord started: 16kHz PCM mono")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording", e)
            audioRecord?.release()
            audioRecord = null
            isRecording = false
            false
        }
    }

    override suspend fun stopAudioRecording(): String? = withContext(Dispatchers.IO) {
        try {
            isRecording = false

            // Wait for recording thread to finish
            recordingThread?.join(1000)
            recordingThread = null

            // Stop AudioRecord
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null

            // Combine all PCM chunks into single ByteArray
            val totalSize = recordedPcmBuffer.sumOf { it.size }
            val combinedPcm = ByteArray(totalSize)
            var offset = 0
            recordedPcmBuffer.forEach { chunk ->
                System.arraycopy(chunk, 0, combinedPcm, offset, chunk.size)
                offset += chunk.size
            }

            Log.d(TAG, "AudioRecord stopped: ${combinedPcm.size} bytes (${combinedPcm.size / 1024} KB) PCM")

            // Return sentinel value to signal in-memory PCM is ready
            if (combinedPcm.isNotEmpty()) {
                "pcm:${combinedPcm.size}" // Signal that PCM is in recordedPcmBuffer
            } else {
                Log.e(TAG, "No audio data recorded")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
            audioRecord?.release()
            audioRecord = null
            isRecording = false
            null
        }
    }

    override suspend fun transcribeAudio(audioFilePath: String, language: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Transcribing audio (language: $language)")

            // Get PCM data from in-memory buffer
            val totalSize = recordedPcmBuffer.sumOf { it.size }
            val pcmBytes = ByteArray(totalSize)
            var offset = 0
            recordedPcmBuffer.forEach { chunk ->
                System.arraycopy(chunk, 0, pcmBytes, offset, chunk.size)
                offset += chunk.size
            }

            if (pcmBytes.isEmpty()) {
                return@withContext "No audio data available for transcription"
            }

            // Wrap PCM in WAV header so miniaudio can decode it
            val wavBytes = wrapPcmInWav(pcmBytes)
            Log.d(TAG, "WAV data size: ${wavBytes.size} bytes (${wavBytes.size / 1024} KB)")

            val prompt = """
                Transcribe the following audio recording accurately.
                Language: $language

                Provide only the transcribed text, no additional commentary or formatting.
            """.trimIndent()

            val sb = StringBuilder()
            genieEngine.sendAgentMessage(
                text = prompt,
                imagePngBytes = emptyList(),
                audioBytes = listOf(wavBytes),
            ).collect { response ->
                when (response) {
                    is AgentResponse.Token -> sb.append(response.text)
                    is AgentResponse.Done -> {}
                    is AgentResponse.Error -> Log.e(TAG, "Transcription error: ${response.error}")
                    is AgentResponse.ToolCallRequest -> {}
                }
            }

            val transcription = sb.toString().trim()
            Log.d(TAG, "Transcription complete: ${transcription.take(100)}...")
            transcription
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing audio", e)
            throw e
        }
    }

    // Wraps raw 16kHz mono 16-bit PCM in a standard 44-byte WAV header
    private fun wrapPcmInWav(pcmData: ByteArray): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        val header = java.nio.ByteBuffer.allocate(44).apply {
            order(java.nio.ByteOrder.LITTLE_ENDIAN)
            // RIFF header
            put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
            putInt(fileSize)
            put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
            // fmt sub-chunk
            put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
            putInt(16) // sub-chunk size
            putShort(1) // PCM format
            putShort(numChannels.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            // data sub-chunk
            put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
            putInt(dataSize)
        }.array()

        return header + pcmData
    }

    override suspend fun extractInsights(transcription: String, outputLanguage: String): com.akimy.genie.tools.GeneralInsights = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Analyze the following transcription and extract structured insights.

                Output language: $outputLanguage

                Transcription:
                $transcription

                Provide your response in the following JSON format:
                {
                  "summary": "A concise 2-3 sentence summary",
                  "keyPoints": ["Key point 1", "Key point 2", "Key point 3"],
                  "actionItems": ["Action item 1", "Action item 2"]
                }

                If there are no action items, use an empty array.
            """.trimIndent()

            val sb = StringBuilder()
            genieEngine.sendAgentMessage(
                text = prompt,
                imagePngBytes = emptyList(),
            ).collect { response ->
                when (response) {
                    is AgentResponse.Token -> sb.append(response.text)
                    is AgentResponse.Done -> {}
                    is AgentResponse.Error -> Log.e(TAG, "Insights extraction error: ${response.error}")
                    is AgentResponse.ToolCallRequest -> {}
                }
            }

            var jsonResponse = sb.toString().trim()

            // Strip markdown code blocks (```json ... ``` or ``` ... ```)
            if (jsonResponse.startsWith("```")) {
                val lines = jsonResponse.lines()
                val filtered = lines.drop(1).dropLast(1)
                jsonResponse = filtered.joinToString("\n").trim()
            }

            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

            val insights = try {
                val jsonStart = jsonResponse.indexOf("{")
                val jsonEnd = jsonResponse.lastIndexOf("}") + 1
                val cleanJson = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    jsonResponse.substring(jsonStart, jsonEnd)
                } else {
                    jsonResponse
                }

                val parsed = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(cleanJson)
                com.akimy.genie.tools.GeneralInsights(
                    summary = parsed["summary"]?.toString()?.removeSurrounding("\"") ?: "No summary available",
                    keyPoints = parsed["keyPoints"]?.let { element ->
                        if (element is kotlinx.serialization.json.JsonArray) {
                            element.map { it.toString().removeSurrounding("\"") }
                        } else emptyList()
                    } ?: emptyList(),
                    actionItems = parsed["actionItems"]?.let { element ->
                        if (element is kotlinx.serialization.json.JsonArray) {
                            element.map { it.toString().removeSurrounding("\"") }
                        } else emptyList()
                    } ?: emptyList(),
                    transcription = transcription
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse insights JSON, using fallback", e)
                com.akimy.genie.tools.GeneralInsights(
                    summary = jsonResponse,
                    keyPoints = emptyList(),
                    actionItems = emptyList(),
                    transcription = transcription
                )
            }

            Log.d(TAG, "Insights extracted: ${insights.summary}")
            insights
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting insights", e)
            throw e
        }
    }

    override suspend fun formatSoapNote(transcription: String, outputLanguage: String): com.akimy.genie.tools.SoapNote = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Format the following medical conversation transcription into a SOAP note.

                Output language: $outputLanguage

                Transcription:
                $transcription

                Provide your response in the following JSON format:
                {
                  "subjective": "Patient's complaints, symptoms, and reported history",
                  "objective": "Clinical observations, vital signs, physical examination findings",
                  "assessment": "Diagnosis, clinical impression, and differential diagnoses",
                  "plan": "Treatment plan, medications, follow-up instructions, and referrals"
                }

                Extract relevant medical information and organize it appropriately. If a section has no information, provide "Not documented" for that field.
            """.trimIndent()

            val sb = StringBuilder()
            genieEngine.sendAgentMessage(
                text = prompt,
                imagePngBytes = emptyList(),
            ).collect { response ->
                when (response) {
                    is AgentResponse.Token -> sb.append(response.text)
                    is AgentResponse.Done -> {}
                    is AgentResponse.Error -> Log.e(TAG, "SOAP formatting error: ${response.error}")
                    is AgentResponse.ToolCallRequest -> {}
                }
            }

            var jsonResponse = sb.toString().trim()

            // Strip markdown code blocks (```json ... ``` or ``` ... ```)
            if (jsonResponse.startsWith("```")) {
                val lines = jsonResponse.lines()
                val filtered = lines.drop(1).dropLast(1)
                jsonResponse = filtered.joinToString("\n").trim()
            }

            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

            val soapNote = try {
                val jsonStart = jsonResponse.indexOf("{")
                val jsonEnd = jsonResponse.lastIndexOf("}") + 1
                val cleanJson = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    jsonResponse.substring(jsonStart, jsonEnd)
                } else {
                    jsonResponse
                }

                val parsed = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(cleanJson)
                com.akimy.genie.tools.SoapNote(
                    subjective = parsed["subjective"]?.toString()?.removeSurrounding("\"") ?: "Not documented",
                    objective = parsed["objective"]?.toString()?.removeSurrounding("\"") ?: "Not documented",
                    assessment = parsed["assessment"]?.toString()?.removeSurrounding("\"") ?: "Not documented",
                    plan = parsed["plan"]?.toString()?.removeSurrounding("\"") ?: "Not documented",
                    transcription = transcription
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse SOAP JSON, using fallback", e)
                com.akimy.genie.tools.SoapNote(
                    subjective = "Error parsing response",
                    objective = "Error parsing response",
                    assessment = "Error parsing response",
                    plan = "Error parsing response",
                    transcription = transcription
                )
            }

            Log.d(TAG, "SOAP note formatted successfully")
            soapNote
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting SOAP note", e)
            throw e
        }
    }

    // ========================================================================
    // Health Profile Methods
    // ========================================================================

    private val healthLibrary by lazy { com.akimy.genie.tools.HealthLibraryManager(applicationContext) }

    private suspend fun analyzeFoodImage(bitmap: android.graphics.Bitmap) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Analyzing food image")

            // Convert bitmap to PNG bytes
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
            val pngBytes = outputStream.toByteArray()

            val prompt = """
                Analyze this food image. Return ONLY valid JSON with this structure:
                {
                  "foodName": "item name",
                  "servingSize": "100g",
                  "totalCalories": 250,
                  "macronutrients": [
                    {"name": "Protein", "amount": "25", "unit": "g", "explanation": "Builds tissues", "dailyValuePercent": 50}
                  ],
                  "vitamins": [
                    {"name": "Vitamin C", "amount": "10", "unit": "mg", "explanation": "Immune support", "dailyValuePercent": 15}
                  ],
                  "minerals": [
                    {"name": "Iron", "amount": "5", "unit": "mg", "explanation": "Oxygen transport", "dailyValuePercent": 28}
                  ],
                  "otherNutrients": [
                    {"name": "Fiber", "amount": "3", "unit": "g", "explanation": "Digestion", "dailyValuePercent": 12}
                  ],
                  "nutritionCoverage": {
                    "covered": ["High protein"],
                    "missing": ["Low vitamin C"],
                    "summary": "Summary"
                  }
                }
                Keep explanations short. Use null for missing dailyValuePercent. JSON only.
            """.trimIndent()

            // Reset conversation to vision-only mode to avoid agent context pollution
            genieEngine.resetConversation(
                systemPrompt = prompt,
                tools = emptyList(),
                constrainedDecoding = false,
            )
            Log.d(TAG, "analyzeFoodImage: conversation reset to vision-only mode")

            val sb = StringBuilder()
            genieEngine.sendAgentMessage(
                text = prompt,
                imagePngBytes = listOf(pngBytes),
            ).collect { response ->
                when (response) {
                    is AgentResponse.Token -> sb.append(response.text)
                    is AgentResponse.Done -> {}
                    is AgentResponse.Error -> Log.e(TAG, "Food analysis error: ${response.error}")
                    is AgentResponse.ToolCallRequest -> {}
                }
            }

            var jsonResponse = sb.toString().trim()

            // Strip markdown code blocks
            if (jsonResponse.startsWith("```")) {
                val lines = jsonResponse.lines()
                val filtered = lines.drop(1).dropLast(1)
                jsonResponse = filtered.joinToString("\n").trim()
            }

            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }

            val analysis = try {
                // Extract complete JSON
                val jsonStart = jsonResponse.indexOf("{")
                val jsonEnd = jsonResponse.lastIndexOf("}") + 1
                val cleanJson = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    jsonResponse.substring(jsonStart, jsonEnd)
                } else {
                    jsonResponse
                }

                Log.d(TAG, "Attempting to parse JSON (${cleanJson.length} chars)")
                json.decodeFromString<com.akimy.genie.tools.FoodNutritionAnalysis>(cleanJson)
            } catch (e: Exception) {
                Log.e(TAG, "JSON parsing failed, extracting basic info. Response:\n${jsonResponse.take(500)}", e)

                // Fallback: extract what we can and create minimal response
                val foodNameMatch = Regex(""""foodName"\s*:\s*"([^"]+)"""").find(jsonResponse)
                val caloriesMatch = Regex(""""totalCalories"\s*:\s*(\d+)""").find(jsonResponse)
                val servingMatch = Regex(""""servingSize"\s*:\s*"([^"]+)"""").find(jsonResponse)

                com.akimy.genie.tools.FoodNutritionAnalysis(
                    foodName = foodNameMatch?.groupValues?.get(1) ?: "Unknown Food",
                    servingSize = servingMatch?.groupValues?.get(1) ?: "Estimated 100g",
                    totalCalories = caloriesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    macronutrients = emptyList(),
                    vitamins = emptyList(),
                    minerals = emptyList(),
                    otherNutrients = emptyList(),
                    nutritionCoverage = com.akimy.genie.tools.NutritionCoverage(
                        covered = emptyList(),
                        missing = emptyList(),
                        summary = "Analysis incomplete - model response truncated"
                    )
                )
            }

            com.akimy.genie.tools.HealthSessionStore.setResult(
                com.akimy.genie.tools.HealthResult.FoodAnalysis(analysis)
            )

            Log.d(TAG, "Food analysis completed: ${analysis.foodName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing food image", e)
            com.akimy.genie.tools.HealthSessionStore.setResult(
                com.akimy.genie.tools.HealthResult.Error(e.message ?: "Unknown error")
            )
        }
    }

    private suspend fun searchHealthTopic(query: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Searching health topic: $query")

            // First, get topic index
            val topicIndex = healthLibrary.loadTopicIndex()

            // Ask model to identify the best matching topic
            val topicListStr = topicIndex.topics.joinToString(", ")
            val prompt = """
                The user is searching for health information about: "$query"

                Available health topics: $topicListStr

                Identify the single best matching topic from this list. If no exact match exists, suggest the closest related topic.

                Respond with ONLY the topic name from the list, nothing else.
            """.trimIndent()

            // Reset conversation to text-only mode
            genieEngine.resetConversation(
                systemPrompt = prompt,
                tools = emptyList(),
                constrainedDecoding = false,
            )
            Log.d(TAG, "searchHealthTopic: conversation reset to text-only mode")

            val sb = StringBuilder()
            genieEngine.sendAgentMessage(
                text = prompt,
                imagePngBytes = emptyList(),
            ).collect { response ->
                when (response) {
                    is AgentResponse.Token -> sb.append(response.text)
                    is AgentResponse.Done -> {}
                    is AgentResponse.Error -> Log.e(TAG, "Topic search error: ${response.error}")
                    is AgentResponse.ToolCallRequest -> {}
                }
            }

            val matchedTopic = sb.toString().trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")

            Log.d(TAG, "Matched topic: $matchedTopic")

            // Query the specific topic from JSON
            val record = healthLibrary.queryTopic(matchedTopic)

            if (record != null) {
                com.akimy.genie.tools.HealthSessionStore.setResult(
                    com.akimy.genie.tools.HealthResult.HealthTopic(record)
                )
                Log.d(TAG, "Health topic loaded: ${record.disease}")
            } else {
                com.akimy.genie.tools.HealthSessionStore.setResult(
                    com.akimy.genie.tools.HealthResult.Error("Topic not found: $matchedTopic")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching health topic", e)
            com.akimy.genie.tools.HealthSessionStore.setResult(
                com.akimy.genie.tools.HealthResult.Error(e.message ?: "Unknown error")
            )
        }
    }

    private fun startAndroidSTTForHealthQuery() {
        Log.d(TAG, "Starting Android STT for health query")
        com.akimy.genie.tools.HealthSessionStore.setResult(
            com.akimy.genie.tools.HealthResult.FoodAnalysis(
                com.akimy.genie.tools.FoodNutritionAnalysis(
                    foodName = "Listening...",
                    servingSize = "Speak your health topic",
                    totalCalories = 0,
                    macronutrients = emptyList(),
                    vitamins = emptyList(),
                    minerals = emptyList(),
                    otherNutrients = emptyList(),
                    nutritionCoverage = com.akimy.genie.tools.NutritionCoverage(
                        covered = emptyList(),
                        missing = emptyList(),
                        summary = "Listening..."
                    )
                )
            )
        )

        val healthSTT = SpeechRecognizer.createSpeechRecognizer(this)
        healthSTT.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e(TAG, "Health STT error: $error")
                com.akimy.genie.tools.HealthSessionStore.setResult(
                    com.akimy.genie.tools.HealthResult.Error("Voice recognition failed")
                )
                healthSTT.destroy()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.getOrNull(0) ?: ""
                Log.d(TAG, "Health STT result: $text")
                healthSTT.destroy()

                if (text.isNotBlank()) {
                    serviceScope.launch {
                        com.akimy.genie.tools.HealthSessionStore.setResult(
                            com.akimy.genie.tools.HealthResult.FoodAnalysis(
                                com.akimy.genie.tools.FoodNutritionAnalysis(
                                    foodName = "Searching...",
                                    servingSize = "Topic: $text",
                                    totalCalories = 0,
                                    macronutrients = emptyList(),
                                    vitamins = emptyList(),
                                    minerals = emptyList(),
                                    otherNutrients = emptyList(),
                                    nutritionCoverage = com.akimy.genie.tools.NutritionCoverage(
                                        covered = emptyList(),
                                        missing = emptyList(),
                                        summary = "Searching for: $text"
                                    )
                                )
                            )
                        )
                        searchHealthTopic(text)
                    }
                } else {
                    com.akimy.genie.tools.HealthSessionStore.setResult(
                        com.akimy.genie.tools.HealthResult.Error("No speech detected")
                    )
                }
            }
            override fun onRmsChanged(rmsdB: Float) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say health topic...")
        }
        healthSTT.startListening(intent)
    }

    private fun loadBitmapFromUri(uri: android.net.Uri): android.graphics.Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from URI", e)
            null
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private fun moveFocus(step: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = UINodeParser.findNavigableNodes(root)
        if (nodes.isEmpty()) return false

        val currentIndex = findFocusIndex(nodes, root)
        val startIndex = when {
            currentIndex == -1 && step > 0 -> -1
            currentIndex == -1 && step < 0 -> nodes.size
            else -> currentIndex
        }

        for (offset in 1..nodes.size) {
            val targetIndex = floorMod(startIndex + (step * offset), nodes.size)
            val target = nodes[targetIndex]
            if (requestAccessibilityFocus(target)) {
                return true
            }
        }

        return false
    }

    private fun findFocusIndex(
        nodes: List<AccessibilityNodeInfo>,
        root: AccessibilityNodeInfo,
    ): Int {
        val current = UINodeParser.findAccessibilityFocusedNode(root)
        return current?.let { focused ->
            nodes.indexOfFirst { candidate ->
                isSameNode(candidate, focused)
            }
        } ?: -1
    }

    private fun requestAccessibilityFocus(node: AccessibilityNodeInfo): Boolean {
        if (node.isAccessibilityFocused) return true

        return node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ||
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun nodeLog(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "text=${node.text} desc=${node.contentDescription} class=${node.className} " +
            "id=${node.viewIdResourceName} clickable=${node.isClickable} enabled=${node.isEnabled} " +
            "visible=${node.isVisibleToUser} a11yFocused=${node.isAccessibilityFocused} bounds=$bounds"
    }

    private fun floorMod(value: Int, modulus: Int): Int {
        val remainder = value % modulus
        return if (remainder < 0) remainder + modulus else remainder
    }

    private fun isSameNode(left: AccessibilityNodeInfo, right: AccessibilityNodeInfo): Boolean {
        // Match on stable semantic fields first
        if (left.packageName != right.packageName) return false
        if (left.className != right.className) return false
        if (left.viewIdResourceName != right.viewIdResourceName) return false
        if (left.text?.toString() != right.text?.toString()) return false
        if (left.contentDescription?.toString() != right.contentDescription?.toString()) return false

        // Only require bounds match when semantic fields are all empty/generic
        val hasStableId = !left.viewIdResourceName.isNullOrEmpty() ||
            !left.text.isNullOrEmpty() ||
            !left.contentDescription.isNullOrEmpty()

        return if (hasStableId) {
            true
        } else {
            val leftBounds = Rect()
            val rightBounds = Rect()
            left.getBoundsInScreen(leftBounds)
            right.getBoundsInScreen(rightBounds)
            leftBounds == rightBounds
        }
    }

    /**
     * Find a package name by app label.
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

    private fun registerFingerprintGestures() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        runCatching {
            getFingerprintGestureController().registerFingerprintGestureCallback(
                fingerprintGestureCallback,
                null,
            )
        }.onFailure { error ->
            Log.w(TAG, "Fingerprint gesture registration failed: ${error.message}")
        }
    }

    private fun unregisterFingerprintGestures() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        runCatching {
            getFingerprintGestureController().unregisterFingerprintGestureCallback(fingerprintGestureCallback)
        }.onFailure { error ->
            Log.w(TAG, "Fingerprint gesture unregistration failed: ${error.message}")
        }
    }

    private fun narrateAwarenessRecord(record: AwarenessEventRecord?) {
        val narration = narrationController.narrationFor(record) ?: return
        if (!ttsReady) return

        serviceScope.launch(Dispatchers.Main) {
            readTextAloud(narration, TextToSpeech.QUEUE_FLUSH)
        }
    }

    private suspend fun handleFingerprintGesture(gesture: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val message = when (gesture) {
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> {
                if (focusNext()) {
                    readFocusedNode()
                } else {
                    "Could not move to the next item."
                }
            }

            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> {
                if (focusPrevious()) {
                    readFocusedNode()
                } else {
                    "Could not move to the previous item."
                }
            }

            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> {
                val status = narrationController.toggle()
                if (!narrationController.isEnabled()) {
                    tts?.stop()
                }
                status
            }

            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> narrationController.repeatLastNarration()
            else -> "That fingerprint gesture is not assigned."
        }

        val narrationSignature = when (gesture) {
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN,
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> "focus|$message"
            else -> null
        }
        narrationController.rememberNarration(message, narrationSignature)
        readTextAloud(message, TextToSpeech.QUEUE_FLUSH)
    }

}
