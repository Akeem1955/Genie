# Genie — Developer Brief

## `agent/`
The brain's decision loop. Contains the planning logic, prompt construction, and state management for the agent.

- **AgentOrchestrator** — the main loop: prompt → plan → execute → evaluate → repeat
- **AgentState** — holds the goal, action history, retry/replan counters, and tool outcome types
- **Planner** — sends prompts to GenieEngine, parses tool call responses into decisions
- **PlannerToolSchema** — declares all tools as LiteRT-LM ToolProvider objects (the schema the model sees)
- **PromptBuilder** — assembles the system prompt, injected facts, goal, and sliding history window
- **SlidingWindowManager** — keeps history within the LLM context limit; prunes transient error noise after successes
- **BuiltInSkillMatcher** — fast-path deterministic skill matching for visual/teaching goals before DB or LLM is involved

---

## `data/`
Persistent storage layer. All Room database entities, DAOs, and the database singleton live here.

- **GenieDatabase** — Room singleton (version 2); exposes FactDao, SkillDao, VisualizerSceneDao
- **UserFact / FactDao** — key-value store for user preferences (`save_fact` / `retrieve_fact` tools)
- **Skill / SkillDao** — stores successful agent plans (goalPattern + planJson + successCount) for self-improvement
- **VisualizerSceneEntity / VisualizerSceneDao** — persists teaching board and visualizer scene state across turns

---

## `engine/`
The AI inference layer. Wraps the LiteRT-LM SDK and handles model download and lifecycle.

- **GenieEngine** — initializes the LiteRT-LM Engine and Conversation; converts the callback-based `sendMessageAsync` API into a Kotlin Flow; enforces `automaticToolCalling = false` (the HITL safety requirement)
- **GenieModelConfig** — model metadata: S3 URL, filename, version hash, local path computation
- **ModelDownloadManager** — WorkManager orchestration; emits `StateFlow<DownloadState>` for progress tracking
- **ModelDownloadWorker** — foreground HTTP streaming download to a temp file, renamed on completion
- **PromptFormatting** — builds LiteRT-LM `Contents` and `Message` objects (text + optional PNG image bytes)
- **DownloadConsts** — notification IDs and timeout constants

---

## `service/`
The Android OS bridge. Everything that touches the accessibility tree, gestures, overlays, and screen analysis lives here.

- **GenieAccessibilityService** — the entry point: owns bootstrap, voice I/O (Vosk + STT + TTS), overlay windows, and implements `ToolServiceContext` so tools can perform OS actions
- **UINodeParser** — BFS traversal of the accessibility tree: finding nodes by text/role, extracting all text, semantic context, and screen state
- **GestureDispatcher** — swipe, tap, scroll, and long-press as suspend functions wrapping `dispatchGesture()`
- **ScreenCapture** — wraps `AccessibilityService.takeScreenshot()` into a coroutine; returns a `Bitmap`
- **AccessibilityAwarenessTracker** — tracks accessibility events to provide screen summaries, recent changes, dialog detection, and form state
- **AnnotationOverlayController** — draws boxes, labels, and pointer arrows on a live `TYPE_ACCESSIBILITY_OVERLAY` window with delay-aware replay
- **ContinuousNarrationController** — manages ongoing spoken ambient guidance (enable/disable/repeat)
- **ScreenMapStore** — in-memory per-screen landmark and user hint store (resets on service restart)
- **SemanticModels** — shared data classes for semantic screen information

---

## `telemetry/`
Observability. A lightweight async event bus that logs all significant agent events to Logcat.

- **EventLogger** — `Channel<GenieEvent>` event bus; consumed by a dedicated coroutine; emits via `trySend()` so it never blocks the agent loop
- **ErrorTaxonomy** — the four error classes: `TransientErr` (retry), `LogicErr` (replan), `AuthErr` (stop), `FatalErr` (hard stop)

Extract and summarize all the text visible in this image. Return the extracted text first, then a brief summary`type_text` calls against screen context using ≥2 signal threshold before requiring biometric auth
- **HITLInterceptionWrapper** — launches `BiometricAuthActivity` and suspends on a `Channel<AuthResult>` (30s timeout)
- **BiometricAuthActivity** — transparent activity that shows `BiometricPrompt`; sends result back through the channel
- **ScreenContext** — snapshot data class used by RiskAssessor (visible text, package, focused field metadata)
- **AnnotationSessionStore** — persists annotation operations and projects them into visualizer-compatible board scenes
- **VisualizerSceneStore** — state machine for teaching board and diagram scenes (create, update, highlight, step reveal, export)
- **VisualizerLayoutEngine** — deterministic layout generation for visualizer diagrams
- **VisualizerExportManager** — renders scenes to PNG and shares via FileProvider
- **TeachingBoardModels** — data classes for board objects, steps, and narration
- **impl/ToolImplementations** — all 53 tool classes in one file, each implementing `GenieTool`
