package com.akimy.genie.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
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
import com.akimy.genie.engine.ModelPrefs
import com.akimy.genie.engine.awaitTerminalDownloadState
import com.akimy.genie.telemetry.EventLogger
import com.akimy.genie.telemetry.GenieEvent
import com.akimy.genie.tools.ToolRegistry
import com.akimy.genie.tools.ToolServiceContext
import com.akimy.genie.tools.BoardStyle
import com.akimy.genie.tools.ViewportInfo
import com.akimy.genie.tools.VisualizerSceneStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model as VoskModel
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max

private const val TAG = "GenieA11yService"
private const val MAX_VISION_IMAGE_EDGE_PX = 1280

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

    // -- Overlay components --
    private var windowManager: WindowManager? = null
    private var processingOverlay: FrameLayout? = null
    private var annotationOverlayController: AnnotationOverlayController? = null
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

        // Initialize event logger
        EventLogger.init(serviceScope)
        VisualizerSceneStore.initialize(applicationContext)
        ScreenMapStore.initialize(applicationContext)
        registerFingerprintGestures()

        // Bootstrap sequence: download model → init engine → init voice → listen
        bootstrap()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        unregisterFingerprintGestures()
        speechService?.stop()
        speechService = null
        tts?.shutdown()
        stt?.destroy()
        genieEngine.shutdown()
        serviceScope.cancel()
        removeProcessingOverlay()
        annotationOverlayController?.detach()
        annotationOverlayController = null
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
            val plannerToolProviders = geniePlannerToolProviders()

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
                systemPrompt = PromptBuilder.AGENT_SYSTEM_PROMPT,
                tools = plannerToolProviders,
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
                promptBuilder = PromptBuilder(),
                planner = planner,
                factDao = db.factDao(),
                skillDao = db.skillDao(),
                appContext = applicationContext,
            )

            // Step 4: Initialize voice
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
                Log.d(TAG, "Vosk model loaded, starting wake-word listening")
                startWakeWordListening()
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
        readTextAloud("Hi! I'm Genie, your AI accessibility agent. Say Gemma to wake me up.")
        serviceScope.launch {
            delay(8000)
            withContext(Dispatchers.Main) {
                startWakeWordListening()
            }
        }
    }

    /** Speak text through TTS. */
    private fun readTextAloud(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        val bundle = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, queueMode, bundle, UUID.randomUUID().toString())
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
    // Agent dispatch
    // ========================================================================

    private fun dispatchToAgent(userCommand: String) {
        Log.d(TAG, "Dispatching to agent: $userCommand")
        showProcessingOverlay()
        agentBusy = true

        serviceScope.launch {
            val result = try {
                orchestrator?.executeGoal(
                    goal = userCommand,
                    serviceContext = this@GenieAccessibilityService,
                    onStatusUpdate = { status ->
                        serviceScope.launch(Dispatchers.Main) {
                            readTextAloud(status)
                        }
                    }
                ) ?: "Agent not ready"
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
                "Agent failed: ${e.message ?: "unknown error"}"
            } finally {
                withContext(Dispatchers.Main) {
                    agentBusy = false
                    removeProcessingOverlay()
                    // Resume wake-word listening
                    startWakeWordListening()
                }
            }

            Log.d(TAG, "Agent result: $result")
        }
    }

    // ========================================================================
    // Overlay windows
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

    override suspend fun readFocusedNode(): String {
        return awarenessTracker.readFocused(rootInActiveWindow)
    }

    override suspend fun takeScreenshot(): String {
        val bitmap = ScreenCapture.captureScreen(this)
        if (bitmap == null) {
            latestScreenshotPngBytes = null
            return "Screenshot failed"
        }

        val pngBytes = withContext(Dispatchers.Default) {
            encodeVisionPng(bitmap)
        }

        latestScreenshotPngBytes = pngBytes
        return if (pngBytes != null) {
            "Screenshot captured (${bitmap.width}x${bitmap.height})"
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
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (prepared !== bitmap && !prepared.isRecycled) {
                prepared.recycle()
            }
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

    override suspend fun annotationStartSession(sessionId: String, title: String): Boolean {
        return withContext(Dispatchers.Main) {
            runCatching {
                val controller = annotationOverlayController ?: AnnotationOverlayController(this@GenieAccessibilityService)
                    .also { annotationOverlayController = it }
                controller.startSession(sessionId)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to start annotation overlay session", error)
                false
            }
        }
    }

    override suspend fun annotationDrawBox(
        sessionId: String,
        opId: String,
        delayMs: Long,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: String,
        style: BoardStyle,
    ): Boolean {
        return withContext(Dispatchers.Main) {
            runCatching {
                val controller = annotationOverlayController ?: AnnotationOverlayController(this@GenieAccessibilityService)
                    .also { annotationOverlayController = it }
                controller.addBox(sessionId, opId, delayMs, x, y, width, height, label, style)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to draw annotation box", error)
                false
            }
        }
    }

    override suspend fun annotationDrawLabel(
        sessionId: String,
        opId: String,
        delayMs: Long,
        x: Float,
        y: Float,
        text: String,
        style: BoardStyle,
    ): Boolean {
        return withContext(Dispatchers.Main) {
            runCatching {
                val controller = annotationOverlayController ?: AnnotationOverlayController(this@GenieAccessibilityService)
                    .also { annotationOverlayController = it }
                controller.addLabel(sessionId, opId, delayMs, x, y, text, style)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to draw annotation label", error)
                false
            }
        }
    }

    override suspend fun annotationDrawPointer(
        sessionId: String,
        opId: String,
        delayMs: Long,
        x: Float,
        y: Float,
        targetX: Float,
        targetY: Float,
        text: String,
        style: BoardStyle,
    ): Boolean {
        return withContext(Dispatchers.Main) {
            runCatching {
                val controller = annotationOverlayController ?: AnnotationOverlayController(this@GenieAccessibilityService)
                    .also { annotationOverlayController = it }
                controller.addPointer(sessionId, opId, delayMs, x, y, targetX, targetY, text, style)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to draw annotation pointer", error)
                false
            }
        }
    }

    override suspend fun annotationClearSession(sessionId: String): Boolean {
        return withContext(Dispatchers.Main) {
            runCatching {
                annotationOverlayController?.clearSession(sessionId)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to clear annotation session", error)
                false
            }
        }
    }

    override suspend fun annotationReplaySession(sessionId: String): Boolean {
        return withContext(Dispatchers.Main) {
            val controller = annotationOverlayController ?: return@withContext false
            runCatching {
                controller.replaySession(sessionId, serviceScope)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to replay annotation session", error)
                false
            }
        }
    }

    override suspend fun focusNext(): Boolean {
        return moveFocus(step = 1)
    }

    override suspend fun focusPrevious(): Boolean {
        return moveFocus(step = -1)
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
        val focused = UINodeParser.findAccessibilityFocusedNode(root) ?: return false
        val target = UINodeParser.findClickableAncestor(focused) ?: focused

        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            target.performAction(AccessibilityNodeInfo.ACTION_SELECT) ||
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
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
    // Utility
    // ========================================================================

    private fun moveFocus(step: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = UINodeParser.findNavigableNodes(root)
        if (nodes.isEmpty()) return false

        val current = UINodeParser.findAccessibilityFocusedNode(root)
        val currentIndex = current?.let { focused ->
            nodes.indexOfFirst { candidate ->
                isSameNode(candidate, focused)
            }
        } ?: -1

        val targetIndex = when {
            currentIndex == -1 && step > 0 -> 0
            currentIndex == -1 && step < 0 -> nodes.lastIndex
            else -> (currentIndex + step).coerceIn(0, nodes.lastIndex)
        }

        return requestAccessibilityFocus(nodes[targetIndex])
    }

    private fun requestAccessibilityFocus(node: AccessibilityNodeInfo): Boolean {
        if (node.isAccessibilityFocused) return true

        return node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ||
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun isSameNode(left: AccessibilityNodeInfo, right: AccessibilityNodeInfo): Boolean {
        val leftBounds = Rect()
        val rightBounds = Rect()
        left.getBoundsInScreen(leftBounds)
        right.getBoundsInScreen(rightBounds)

        return left.packageName == right.packageName &&
            left.className == right.className &&
            left.text == right.text &&
            left.contentDescription == right.contentDescription &&
            leftBounds == rightBounds
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
