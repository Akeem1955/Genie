# 🧞 Genie — The Story of an Autonomous Android AI Agent

> *"What if your phone didn't just listen to you — what if it could think, plan, act, learn from its mistakes, and remember what you like?"*

Genie is not a chatbot. It is not a voice assistant. Genie is an **autonomous AI agent** that lives inside Android's Accessibility Service layer and can **see your screen, touch your apps, read your content, and execute multi-step tasks** — all powered by a 2-billion-parameter language model running entirely on your device, with zero cloud dependency.

This document tells the full story of how Genie works, from the moment a user installs it to the moment it completes its hundredth task faster than its first.

---

## Table of Contents

- [Chapter 1: The First Launch — Bootstrap](#chapter-1-the-first-launch--bootstrap)
- [Chapter 2: The Wake Word — Listening for "Gemma"](#chapter-2-the-wake-word--listening-for-gemma)
- [Chapter 3: The Brain Thinks — LiteRT-LM Inference](#chapter-3-the-brain-thinks--litert-lm-inference)
- [Chapter 4: The Agent Loop — Planning Like a Human](#chapter-4-the-agent-loop--planning-like-a-human)
- [Chapter 5: The Hands — Touching the OS](#chapter-5-the-hands--touching-the-os)
- [Chapter 6: The Safety Net — Human-in-the-Loop](#chapter-6-the-safety-net--human-in-the-loop)
- [Chapter 7: The Memory — Learning and Evolving](#chapter-7-the-memory--learning-and-evolving)
- [Chapter 8: When Things Go Wrong — Error Taxonomy](#chapter-8-when-things-go-wrong--error-taxonomy)
- [Chapter 9: The Nervous System — Observability](#chapter-9-the-nervous-system--observability)
- [Appendix: The Full File Map](#appendix-the-full-file-map)

---

## Chapter 1: The First Launch — Bootstrap

### The Story

A user installs Genie for the first time. They open the app and see a minimal setup screen — not a chat interface, not a tutorial, just three things:

1. **A text field** asking for their HuggingFace access token
2. **A button** to enable the Accessibility Service
3. **A status card** showing what's been done

The user pastes their HuggingFace token (required because Gemma 4 weights are gated) and taps "Save Token." The token is encrypted using AES-256-GCM via Android's `EncryptedSharedPreferences` and never leaves the device.

They tap "Enable Genie Accessibility Service" and are taken to Android's Accessibility Settings. They toggle Genie on. The moment they do, the **bootstrap sequence** fires.

### What Happens Under the Hood

```
GenieAccessibilityService.onServiceConnected()
    │
    ├── 1. Check RECORD_AUDIO permission
    │       └── If missing → launch MainActivity → disableSelf()
    │
    ├── 2. Initialize EventLogger
    │       └── Starts the async Channel<GenieEvent> consumer
    │
    └── 3. Launch Bootstrap coroutine (Dispatchers.Default)
            │
            ├── 3a. Check if model exists locally
            │       └── GenieModelConfig.isDownloaded(context)
            │           checks: getExternalFilesDir/litert_community_Gemma4.../main/gemma4-2b-it-4b.litertlm
            │
            ├── 3b. If not downloaded → ModelDownloadManager.ensureModelReady()
            │       └── Creates a OneTimeWorkRequest<ModelDownloadWorker>
            │       └── WorkManager enqueues with REPLACE policy
            │       └── Worker runs as foreground service (notification: "Downloading Gemma 4 2B")
            │       └── HTTP GET to HuggingFace with Bearer token
            │       └── Writes to .genietmp → renames to final on complete
            │       └── Reports progress via setProgress() every 200ms
            │       └── Manager observes via getWorkInfoByIdLiveData → emits StateFlow<DownloadState>
            │
            ├── 3c. Initialize GenieEngine
            │       └── EngineConfig(modelPath, Backend.GPU(), maxNumTokens=1024)
            │       └── Engine(engineConfig).initialize()  ← This is the heavy call (~3-5 seconds)
            │       └── ConversationConfig(samplerConfig, systemInstruction, automaticToolCalling=false)
            │       └── engine.createConversation(config)
            │
            ├── 3d. Initialize AgentOrchestrator
            │       └── Wires: GenieEngine + ToolRegistry + PromptBuilder + Planner + FactDao + SkillDao
            │
            └── 3e. Initialize Voice Stack
                    ├── Vosk wake-word engine (unpacks model from assets)
                    ├── TextToSpeech engine
                    ├── SpeechRecognizer for command capture
                    └── TTS: "Hi! I'm Genie, your AI accessibility agent. Say Gemma to wake me up."
```

### The Key Files

| File | Role |
|------|------|
| [`MainActivity.kt`](app/src/main/java/com/akimy/genie/MainActivity.kt) | One-time setup UI: HF token input (encrypted), permission grant, A11y enable |
| [`GenieModelConfig.kt`](app/src/main/java/com/akimy/genie/engine/GenieModelConfig.kt) | Model metadata: HF repo ID, file name, size, local path computation |
| [`ModelDownloadWorker.kt`](app/src/main/java/com/akimy/genie/engine/ModelDownloadWorker.kt) | Background download with resume, Bearer auth, foreground notification |
| [`ModelDownloadManager.kt`](app/src/main/java/com/akimy/genie/engine/ModelDownloadManager.kt) | WorkManager orchestration, `StateFlow<DownloadState>` emission |
| [`GenieAccessibilityService.kt`](app/src/main/java/com/akimy/genie/service/GenieAccessibilityService.kt) | The bootstrap entry point (`onServiceConnected()`) |

### Where This Code Came From

The download pipeline is adapted from Google's **AI Edge Gallery** app. Gallery's `DownloadWorker.kt` (370 lines) handles HuggingFace downloads with resume support, Bearer tokens, and temporary file management. We extracted the core HTTP streaming loop, progress reporting, and foreground service pattern, then stripped Firebase analytics, deep-link notifications, and zip extraction logic that Genie doesn't need.

---

## Chapter 2: The Wake Word — Listening for "Gemma"

### The Story

After bootstrap completes, Genie goes silent. No UI. No overlay. The phone looks completely normal. But a `Recognizer` from the **Vosk** offline speech recognition library is running in the background, listening to the microphone at 16kHz, waiting for one word: **"Gemma."**

The user is browsing their phone. They say: *"Gemma."*

Instantly:
1. Vosk detects "gemma" in its partial result hypothesis
2. Vosk stops listening (to free the microphone)
3. A processing overlay appears: *"🧞 Genie is thinking..."*
4. Android's built-in `SpeechRecognizer` takes over for the actual command
5. The user says: *"Open Settings and turn on Wi-Fi"*
6. STT captures the text, overlay disappears
7. The text is dispatched to the AgentOrchestrator

### What Happens Under the Hood

```kotlin
// Vosk RecognitionListener (in GenieAccessibilityService)
override fun onPartialResult(hypothesis: String?) {
    val json = JSONObject(hypothesis)
    val partial = json.optString("partial", "")
    if (partial.lowercase().contains("gemma")) {
        speechService?.stop()           // Kill Vosk
        showProcessingOverlay()          // Show "thinking" overlay
        startSttListening()              // Start Android STT
    }
}

// STT RecognitionListener callback
onResults { results ->
    val text = results.getStringArrayList(RESULTS_RECOGNITION)?.getOrNull(0) ?: ""
    dispatchToAgent(text)  // Route to agent orchestrator
}
```

### Why Two Speech Engines?

- **Vosk** runs continuously but is lightweight — it only needs to detect one word. It runs offline, uses ~30MB RAM, and has near-zero latency for wake-word detection.
- **Android SpeechRecognizer** is heavy but accurate — it handles full sentence recognition with proper grammar and punctuation. It only activates after the wake word.

This dual-engine pattern was borrowed directly from **BetaAssist**, where it was proven to work reliably on mid-range Android devices.

---

## Chapter 3: The Brain Thinks — LiteRT-LM Inference

### The Story

The user said: *"Open Settings and turn on Wi-Fi."*

This text string arrives at `GenieEngine.sendAgentMessage()`. But GenieEngine doesn't just generate text — it streams tokens through a **Kotlin Flow**, converting LiteRT-LM's callback-based API into something the agent loop can consume asynchronously.

### What Happens Under the Hood

```kotlin
// Inside GenieEngine — the callback-to-Flow conversion
fun sendAgentMessage(text: String): Flow<AgentResponse> = callbackFlow {
    conversation.sendMessageAsync(
        Contents.of(Content.Text(text)),
        object : MessageCallback {
            override fun onMessage(message: Message) {
                // Each token is emitted as it's generated
                trySend(AgentResponse.Token(message.toString()))
            }
            override fun onDone() {
                trySend(AgentResponse.Done)
                close()
            }
            override fun onError(throwable: Throwable) {
                // CancellationException → TransientErr (can retry)
                // Everything else → FatalErr (must stop)
                trySend(AgentResponse.Error(classifyError(throwable)))
                close()
            }
        }
    )
    awaitClose { }
}
```

### The Critical Configuration

When the engine creates a conversation, it passes `automaticToolCalling = false`. This is the **single most important line of code in the entire project.**

In default mode, LiteRT-LM would see a tool schema, detect a tool call in the model's output, automatically execute it, and feed the result back — all without the application's knowledge. That's fine for a chatbot. It's catastrophic for an agent that can **tap buttons, type passwords, and open apps on your phone.**

By setting `automaticToolCalling = false`, every tool call request from the model is intercepted by our code. We see it, we validate it, we optionally ask for biometric approval, and only then do we execute it and feed the result back. This is the foundation of the Human-in-the-Loop safety system.

### Where This Code Came From

The `EngineConfig` → `Engine` → `engine.initialize()` → `engine.createConversation(ConversationConfig)` → `conversation.sendMessageAsync(contents, MessageCallback)` pattern is a near-verbatim extraction from Gallery's `LlmChatModelHelper.kt`. The `SamplerConfig(topK, topP, temperature)` configuration, the `Backend.GPU()` selection, the cleanup sequence (`conversation.close()` then `engine.close()`) — all of this was proven working in Gallery and transplanted into Genie.

---

## Chapter 4: The Agent Loop — Planning Like a Human

### The Story

Here is where Genie diverges from every chatbot and voice assistant on Android. The user said *"Open Settings and turn on Wi-Fi"* — this is a **multi-step task.** A chatbot would try to answer in one shot. Genie **plans.**

The `AgentOrchestrator` runs a loop that mimics how a human would approach the task:

1. **Think:** "What's my goal? Open Settings and turn on Wi-Fi."
2. **Look:** "I don't know what's on screen. Let me read it."
3. **Act:** "I see the home screen. Let me open Settings."
4. **Look again:** "I'm in Settings now. Let me find Wi-Fi."
5. **Act:** "I see 'Network & internet'. Let me click it."
6. **Act:** "I see 'Wi-Fi' toggle. Let me click it."
7. **Evaluate:** "Wi-Fi is now ON. Goal complete."

Each iteration of this loop is one call to the LLM planner.

### What Happens Under the Hood

```
AgentOrchestrator.executeGoal("Open Settings and turn on Wi-Fi")
    │
    ├── Initialize AgentState(goal = "Open Settings and turn on Wi-Fi")
    │   └── history = [UserMessage("Open Settings and turn on Wi-Fi")]
    │
    ├── Check SkillLibrary → no match (first time)
    │
    └── LOOP (max 20 iterations):
        │
        ├── 1. BUILD PROMPT
        │   │   PromptBuilder.buildPrompt(state, facts)
        │   │
        │   ├── Inject User Preferences: "preferred_language: English"
        │   ├── Inject Goal: "Open Settings and turn on Wi-Fi"
        │   ├── Inject History Window (last 10 entries):
        │   │     [USER] Open Settings and turn on Wi-Fi
        │   │     [AGENT] Called tool: read_screen({})
        │   │     [RESULT] read_screen → OK: Home · Chrome · Settings · Camera...
        │   │     [AGENT] Called tool: open_app({name: Settings})
        │   │     [RESULT] open_app → OK: Opened 'Settings'
        │   └── Append: "Based on the goal and history above, output your next JSON decision:"
        │
        ├── 2. PLAN (LLM inference)
        │   │   Planner.plan(prompt) → sends to GenieEngine
        │   │   Model outputs: {"action": "act", "tool": "click", "args": {"target": "Network & internet"}}
        │   │
        │   └── parseDecision() strips markdown fences, extracts JSON, returns Decision.Act
        │
        ├── 3. EXECUTE
        │   │   ToolRegistry.getTool("click") → ClickTool
        │   │   ClickTool.execute(args, serviceContext)
        │   │     → UINodeParser.findClickableNode(root, "Network & internet")
        │   │     → BFS traversal of accessibility tree
        │   │     → Found node! → performAction(ACTION_CLICK) → true
        │   │
        │   └── Returns ToolOutcome.Ok("Clicked on 'Network & internet'")
        │
        ├── 4. EVALUATE
        │   │   outcome is Ok → add to history, prune transient errors
        │   │   Continue loop...
        │
        └── Eventually: Model outputs {"action": "finish", "summary": "Wi-Fi has been turned on."}
            │
            ├── TTS: "Wi-Fi has been turned on."
            ├── Write to SkillLibrary (novel plan → self-evolution)
            └── Resume wake-word listening
```

### The Sliding Window

The agent's history grows with every step. But the LLM has a limited context window (1024 tokens). The `SlidingWindowManager` solves this:

- Keeps the **first entry** (the user's goal — always visible)
- Keeps the **last 9 entries** (recent actions and results)
- When a tool succeeds, it **prunes** any preceding `TransientErr` entries for that same tool — they're noise that wastes context tokens

This means the LLM always sees the goal and the most recent context, even during long multi-step tasks.

### The Prompt

The system prompt in `PromptBuilder.AGENT_SYSTEM_PROMPT` forces the model to output **strict JSON** — either `{"action": "act", "tool": "...", "args": {...}}` or `{"action": "finish", "summary": "..."}`. No free-text. No markdown. Just structured decisions that the orchestrator can parse deterministically.

The prompt also lists every available tool with its arguments, preventing the model from hallucinating tools that don't exist.

### The Key Files

| File | Role |
|------|------|
| [`AgentOrchestrator.kt`](app/src/main/java/com/akimy/genie/agent/AgentOrchestrator.kt) | The main loop: prompt→plan→execute→evaluate→repeat |
| [`AgentState.kt`](app/src/main/java/com/akimy/genie/agent/AgentState.kt) | `Decision.Act`, `Decision.Finish`, `HistoryEntry`, `ToolOutcome` |
| [`SlidingWindowManager.kt`](app/src/main/java/com/akimy/genie/agent/SlidingWindowManager.kt) | Context window truncation + error pruning |
| [`PromptBuilder.kt`](app/src/main/java/com/akimy/genie/agent/PromptBuilder.kt) | System prompt + history assembly |
| [`Planner.kt`](app/src/main/java/com/akimy/genie/agent/Planner.kt) | Skill cache check → LLM call → JSON parsing |

---

## Chapter 5: The Hands — Touching the OS

### The Story

When the Planner decides to act, the agent needs hands. These hands are the **11 tools** registered in the `ToolRegistry`, each backed by Android's Accessibility APIs.

Let's trace what happens when the agent decides `{"action": "act", "tool": "click", "args": {"target": "Wi-Fi"}}`:

### What Happens Under the Hood

```
ToolRegistry.execute("click", {"target": "Wi-Fi"}, serviceContext)
    │
    ├── Look up tool: tools["click"] → ClickTool
    │
    ├── ClickTool.execute(args, serviceContext)
    │   │
    │   └── serviceContext.clickElement("Wi-Fi")
    │       │
    │       └── GenieAccessibilityService.clickElement("Wi-Fi")
    │           │
    │           ├── rootInActiveWindow  ← Gets the current UI tree from the OS
    │           │
    │           ├── UINodeParser.findClickableNode(root, "Wi-Fi")
    │           │   │
    │           │   ├── BFS traversal of entire accessibility tree
    │           │   ├── Checks every node's text and contentDescription
    │           │   ├── First tries exact match: node.text == "Wi-Fi"
    │           │   ├── Then tries contains: node.text.contains("Wi-Fi")
    │           │   ├── Found node! But is it clickable?
    │           │   │   ├── If yes → return it
    │           │   │   └── If no → walk up parent chain until clickable ancestor found
    │           │   │
    │           │   └── Returns the nearest clickable ancestor (e.g., the SettingsRow)
    │           │
    │           └── node.performAction(ACTION_CLICK) → OS performs the tap
    │
    └── Returns ToolOutcome.Ok("Clicked on 'Wi-Fi'")
```

### The Full Tool Arsenal

| Tool | What It Does | Backed By |
|------|-------------|-----------|
| `click` | Click a UI element by text/description | `UINodeParser` BFS + `performAction(ACTION_CLICK)` |
| `type_text` | Type text into focused input | `performAction(ACTION_SET_TEXT)` |
| `swipe` | Swipe in a direction (up/down/left/right) | `GestureDispatcher.swipe()` via `dispatchGesture()` |
| `scroll` | Scroll a list up or down | `GestureDispatcher.scroll()` — shorter distance than swipe |
| `read_screen` | Read all visible text | `UINodeParser.extractAllText()` BFS traversal |
| `take_screenshot` | Capture the screen as bitmap | `ScreenCapture.captureScreen()` via `takeScreenshot()` |
| `open_app` | Open any installed app by name | `PackageManager.getLaunchIntentForPackage()` |
| `go_back` | Press system back | `performGlobalAction(GLOBAL_ACTION_BACK)` |
| `go_home` | Press system home | `performGlobalAction(GLOBAL_ACTION_HOME)` |
| `save_fact` | Remember a user preference | Writes to Room `user_facts` table |
| `retrieve_fact` | Recall a preference | Queries Room `user_facts` table |

### The ToolServiceContext Pattern

Tools don't touch the `AccessibilityService` directly. They go through the `ToolServiceContext` interface:

```kotlin
interface ToolServiceContext {
    suspend fun clickElement(target: String): Boolean
    suspend fun typeText(text: String): Boolean
    suspend fun swipe(direction: String): Boolean
    ...
}
```

`GenieAccessibilityService` implements this interface. This means during unit tests, you can inject a **mock** `ToolServiceContext` that simulates all OS actions without needing a real device.

### The Gesture System

BetaAssist had four separate copy-pasted methods: `rightSwipeGesture()`, `leftSwipeGesture()`, `upSwipeGesture()`, `downSwipeGesture()`. We consolidated them into `GestureDispatcher` with a single `swipe(Direction)` method, then added `tap(x, y)` and `longPress(x, y, duration)` that BetaAssist didn't have.

Every gesture method is a **suspend function** that wraps `AccessibilityService.dispatchGesture()` callback into a coroutine using `suspendCancellableCoroutine`. This means the agent loop can `await` a gesture completing before moving to the next step.

### The Key Files

| File | Role |
|------|------|
| [`ToolRegistry.kt`](app/src/main/java/com/akimy/genie/tools/ToolRegistry.kt) | Central name→tool map, validates existence |
| [`GenieTool.kt`](app/src/main/java/com/akimy/genie/tools/GenieTool.kt) | Tool interface + `ToolServiceContext` abstraction |
| [`ToolImplementations.kt`](app/src/main/java/com/akimy/genie/tools/impl/ToolImplementations.kt) | All 11 tool classes |
| [`UINodeParser.kt`](app/src/main/java/com/akimy/genie/service/UINodeParser.kt) | BFS tree traversal, text extraction, node finding |
| [`GestureDispatcher.kt`](app/src/main/java/com/akimy/genie/service/GestureDispatcher.kt) | Swipe/tap/scroll as suspend functions |
| [`ScreenCapture.kt`](app/src/main/java/com/akimy/genie/service/ScreenCapture.kt) | `takeScreenshot()` → `Bitmap` coroutine wrapper |

---

## Chapter 6: The Safety Net — Human-in-the-Loop

### The Story

The user says: *"Gemma, send $50 to John via PayPal."*

This is a **dangerous action.** Genie should not blindly click "Send" on a payment screen. This is where the **Human-in-the-Loop (HITL) Safety Wrapper** activates.

Any tool can declare `requiresAuth = true`. When the AgentOrchestrator encounters such a tool, it doesn't execute directly. Instead:

1. **The wrapper fires an Intent** to `BiometricAuthActivity` — a completely invisible `FragmentActivity` with `Theme.Translucent.NoTitleBar`
2. **The activity triggers `BiometricPrompt`** — the OS biometric overlay appears (fingerprint/face)
3. **The user authenticates** (or cancels/times out)
4. **The result flows back** through a Kotlin `Channel<AuthResult>` to the `HITLInterceptionWrapper`
5. **If approved** → tool executes normally
6. **If denied** → `ToolOutcome.AuthErr` → agent replans or stops

### Why a Transparent Activity?

`BiometricPrompt` requires a `FragmentActivity` context. An `AccessibilityService` doesn't have one. The workaround is an activity that:
- Has no visible UI (`Theme.Translucent.NoTitleBar`)
- Is excluded from recents (`excludeFromRecents="true"`)
- Has its own task affinity (`taskAffinity=""`) to not interfere with the user's app stack
- Immediately `finish()`es after sending the auth result

The user sees the fingerprint overlay appear, authenticates, and the overlay vanishes. They never see or interact with the activity itself.

### The Key Files

| File | Role |
|------|------|
| [`HITLInterceptionWrapper.kt`](app/src/main/java/com/akimy/genie/tools/HITLInterceptionWrapper.kt) | Intercepts auth-required tools, launches biometric activity, waits for result |
| [`BiometricAuthActivity.kt`](app/src/main/java/com/akimy/genie/tools/BiometricAuthActivity.kt) | Transparent activity that triggers `BiometricPrompt` |

---

## Chapter 7: The Memory — Learning and Evolving

### The Story

Genie just completed "Open Settings and turn on Wi-Fi" for the first time. It took 5 steps and 3 LLM calls. The `AgentOrchestrator` notices this was a **novel plan** (`state.isNovelPlan == true`) and decides to **remember it.**

It serializes the successful step sequence as JSON and writes it to the `skills` table:

```json
{
    "goalPattern": "open settings and turn on wi-fi",
    "planJson": "[{\"tool\":\"open_app\",\"args\":{\"name\":\"Settings\"}},{\"tool\":\"click\",\"args\":{\"target\":\"Network & internet\"}},{\"tool\":\"click\",\"args\":{\"target\":\"Wi-Fi\"}}]",
    "successCount": 1
}
```

Two days later, the user says: *"Gemma, turn on Wi-Fi."*

The `Planner` checks the SkillLibrary first:

```kotlin
val skills = skillDao.findMatchingSkills("turn on wi-fi")
// SQL: SELECT * FROM skills WHERE goalPattern LIKE '%turn on wi-fi%'
```

It finds a match! Instead of calling the LLM at all, it **replays the cached plan** — executing each step sequentially. Zero inference latency. If any cached step fails (perhaps the UI has changed), it falls back to live planning.

Every time a cached skill succeeds, its `successCount` gets incremented. Skills with higher success counts are preferred when multiple matches exist. Over time, Genie gets faster at tasks it's done before. **This is self-evolution.**

### The Fact Store

Beyond skills, Genie also has a persistent key-value store for user facts:

- The user says: *"Remember that my favorite restaurant is Mama Cass"*
- Agent calls `save_fact(key="favorite_restaurant", value="Mama Cass")`
- This is stored in the Room `user_facts` table with timestamps
- Later: *"What's my favorite restaurant?"*
- Agent calls `retrieve_fact(key="favorite_restaurant")` → "Mama Cass"
- Facts are also injected into every prompt via `PromptBuilder`, so the LLM always knows the user's preferences

### The Key Files

| File | Role |
|------|------|
| [`Skill.kt`](app/src/main/java/com/akimy/genie/data/Skill.kt) | Room entity: `goalPattern`, `planJson`, `successCount` |
| [`SkillDao.kt`](app/src/main/java/com/akimy/genie/data/SkillDao.kt) | Find matching skills, increment success count |
| [`UserFact.kt`](app/src/main/java/com/akimy/genie/data/UserFact.kt) | Room entity: `key`, `value`, timestamps |
| [`FactDao.kt`](app/src/main/java/com/akimy/genie/data/FactDao.kt) | CRUD + upsert for user facts |
| [`GenieDatabase.kt`](app/src/main/java/com/akimy/genie/data/GenieDatabase.kt) | Room singleton housing both tables |

---

## Chapter 8: When Things Go Wrong — Error Taxonomy

### The Story

The agent says: `{"action": "act", "tool": "click", "args": {"target": "Wi-Fi"}}`. But the Settings app hasn't finished loading yet. `UINodeParser.findClickableNode()` returns null. The ClickTool returns `ToolOutcome.TransientErr("Could not find clickable element: 'Wi-Fi'")`.

The orchestrator classifies this and responds:

```
TransientErr → Retry with backoff
    Attempt 1: wait 1 second → retry
    Attempt 2: wait 2 seconds → retry
    Attempt 3: wait 4 seconds → retry
    Attempt 4 (maxRetries exceeded): "I couldn't complete this step."
```

Now imagine a different failure: The model outputs `{"action": "act", "tool": "send_money", "args": {...}}`. There is no `send_money` tool. The `ToolRegistry` returns `ToolOutcome.LogicErr("Unknown tool 'send_money'")`.

```
LogicErr → Replan
    The error is added to the history, so the model can see its mistake.
    On the next loop iteration, the model sees:
        [RESULT] send_money → LOGIC_ERROR: Unknown tool 'send_money'. Available tools: click, type_text...
    It should now choose a valid tool.
    If it keeps making logic errors (maxReplans exceeded): "I couldn't figure out how to do this."
```

### The Four Tiers

| Tier | Meaning | Recovery | Example |
|------|---------|----------|---------|
| `TransientErr` | Might work if we wait | Retry with exponential backoff | UI loading, network lag |
| `LogicErr` | Agent made a bad choice | Replan (model sees error in history) | Invalid tool name, wrong args |
| `AuthErr` | User denied authorization | Stop with notification | Biometric denied, HITL timeout |
| `FatalErr` | Unrecoverable | Hard stop immediately | OOM, engine crash, JNI error |

---

## Chapter 9: The Nervous System — Observability

### The Story

Every significant event in Genie's lifecycle flows through the `EventLogger` — an asynchronous event bus backed by a Kotlin `Channel`. Events are emitted with `trySend()` (non-blocking, never delays the agent loop) and consumed by a dedicated coroutine that writes to Logcat:

```
I/GenieEventLogger: 📦 Bootstrap: engine_init [3421ms]
I/GenieEventLogger: ⚡ State: idle → planning
I/GenieEventLogger: 🧠 Inference: 847ms, 156 tokens
I/GenieEventLogger: ✅ Tool: open_app({name=Settings}) [234ms]
I/GenieEventLogger: 🧠 Inference: 612ms, 89 tokens
I/GenieEventLogger: ✅ Tool: click({target=Network & internet}) [187ms]
I/GenieEventLogger: ✅ Tool: click({target=Wi-Fi}) [143ms]
I/GenieEventLogger: ⚡ State: executing → finished
I/GenieEventLogger: 📚 Skill written: 'open settings and turn on wi-fi' (3 steps)
```

This is the first thing you'd look at when debugging. Every state transition, every tool execution with its latency, every error with its classification, every skill write — all timestamped and categorized.

### The Key Files

| File | Role |
|------|------|
| [`EventLogger.kt`](app/src/main/java/com/akimy/genie/telemetry/EventLogger.kt) | `Channel<GenieEvent>` event bus, Logcat consumer |
| [`ErrorTaxonomy.kt`](app/src/main/java/com/akimy/genie/telemetry/ErrorTaxonomy.kt) | `TransientErr`, `LogicErr`, `AuthErr`, `FatalErr` |

---

## Appendix: The Full File Map

```
c:\Users\akimy\Documents\Gemma4Project\Genie\
│
├── settings.gradle.kts          ← Root project: "Genie", includes :app
├── build.gradle.kts             ← Top-level plugins
├── gradle/
│   └── libs.versions.toml      ← 16 dependencies from 3 sources
│
└── app/
    ├── build.gradle.kts         ← namespace, SDK, NDK, all dependencies
    ├── proguard-rules.pro       ← Keep LiteRT-LM + Vosk JNI classes
    │
    └── src/main/
        ├── AndroidManifest.xml  ← Merged permissions, services, activities
        │
        ├── res/
        │   ├── xml/genie_a11y_config.xml   ← A11y capabilities
        │   └── values/
        │       ├── strings.xml              ← App strings
        │       └── themes.xml               ← Minimal translucent theme
        │
        └── java/com/akimy/genie/
            │
            ├── MainActivity.kt              ← One-time setup UI
            │
            ├── engine/                      ← The Brain
            │   ├── DownloadConsts.kt        ← WorkManager data keys
            │   ├── GenieModelConfig.kt      ← Model metadata + paths
            │   ├── ModelDownloadWorker.kt    ← HTTP download with resume
            │   ├── ModelDownloadManager.kt   ← WorkManager orchestration
            │   ├── GenieEngine.kt           ← LiteRT-LM wrapper (Flow)
            │   └── PromptFormatting.kt      ← Content/Message builders
            │
            ├── agent/                       ← The Mind
            │   ├── AgentState.kt            ← State types & decisions
            │   ├── SlidingWindowManager.kt  ← Context window + pruning
            │   ├── PromptBuilder.kt         ← System prompt assembly
            │   ├── Planner.kt              ← Skill cache + LLM + parse
            │   └── AgentOrchestrator.kt     ← The Loop™
            │
            ├── service/                     ← The Hands & Eyes
            │   ├── GenieAccessibilityService.kt  ← Central nervous system
            │   ├── UINodeParser.kt               ← BFS tree traversal
            │   ├── GestureDispatcher.kt          ← Touch/swipe/scroll
            │   └── ScreenCapture.kt              ← Screenshot capture
            │
            ├── tools/                       ← The Toolbox
            │   ├── GenieTool.kt             ← Tool interface
            │   ├── ToolRegistry.kt          ← Name→implementation map
            │   ├── HITLInterceptionWrapper.kt    ← Biometric gate
            │   ├── BiometricAuthActivity.kt      ← Invisible auth activity
            │   └── impl/
            │       └── ToolImplementations.kt    ← All 11 tools
            │
            ├── data/                        ← The Memory
            │   ├── UserFact.kt              ← Fact entity
            │   ├── Skill.kt                 ← Skill entity
            │   ├── FactDao.kt               ← Fact CRUD + upsert
            │   ├── SkillDao.kt              ← Skill matching + ranking
            │   └── GenieDatabase.kt         ← Room singleton
            │
            └── telemetry/                   ← The Nervous System
                ├── EventLogger.kt           ← Async event bus
                └── ErrorTaxonomy.kt         ← 4-tier error classification
```

---

## The End-to-End Flow (One Picture)

```
User says "Gemma"
    │
    ▼
Vosk detects wake word → stops → starts SpeechRecognizer
    │
    ▼
User says "Open Settings and turn on Wi-Fi"
    │
    ▼
STT captures text → dispatches to AgentOrchestrator
    │
    ▼
AgentOrchestrator.executeGoal()
    │
    ├── Check SkillLibrary → miss
    │
    └── LOOP:
        ├── PromptBuilder assembles: goal + facts + history + tool list
        ├── Planner sends to GenieEngine (LiteRT-LM via Flow)
        ├── Model outputs: {"action":"act","tool":"open_app","args":{"name":"Settings"}}
        ├── ToolRegistry validates → ClickTool
        ├── HITL check: requiresAuth? → false → execute directly
        ├── GenieAccessibilityService.openApp("Settings") → success
        ├── History: [OK: Opened 'Settings']
        ├── SlidingWindowManager prunes old errors
        ├── ... (repeat for "click Network & internet", "click Wi-Fi")
        └── Model outputs: {"action":"finish","summary":"Wi-Fi turned on"}
            │
            ├── TTS: "Wi-Fi has been turned on."
            ├── SkillLibrary: writes 3-step plan for future reuse
            ├── EventLogger: emits SkillWritten event
            └── Resume Vosk wake-word listening
```

That's Genie. An agent that listens, thinks, acts, learns, and protects — all running locally on your phone.