# Scribe Profile - Tool Configuration Explanation

## Overview

The **Scribe Profile** is fundamentally different from other profiles in Genie. It's a **UI-driven profile**, not an **agent-driven profile**.

## Profile Architecture Comparison

### Agent-Driven Profiles (Chat, Reader, Teaching, Document, SeeAndTap)
```
User Request → Agent Plans → Agent Calls Tools → Tool Executes → Result
```
- User makes a natural language request
- Agent (Gemma model) decides which tools to call
- Agent orchestrator executes those tools
- Tools interact with OS/system
- Results returned to agent, then to user

**Example (Chat Profile):**
```
User: "Remember my name is John"
Agent: Calls save_fact(key="name", value="John")
Tool: Saves to database
Agent: Replies "I'll remember that your name is John"
```

### UI-Driven Profile (Scribe)
```
User Clicks UI → UI Calls Functions Directly → Gemma Processes → UI Shows Results
```
- User interacts with visual UI elements (buttons, dialogs)
- UI directly calls service methods (no agent involved)
- Gemma model is only used for transcription/analysis
- Results displayed immediately in UI

**Example (Scribe Profile):**
```
User: Taps record button
UI: Shows language config dialog
User: Selects languages and mode
UI: Calls startAudioRecording() directly
User: Taps stop button
UI: Calls stopAudioRecording() directly
UI: Calls transcribeAudio() → extractInsights() directly
UI: Displays formatted results
```

## Why Scribe Has No Tools

### The Core Difference

**Other Profiles:**
- Tools are **agent commands** - the agent decides when/how to call them
- Tools are defined in ToolRegistry and callable by the agent
- Agent prompt describes what tools do and when to use them

**Scribe Profile:**
- No agent decision-making needed
- Workflow is fixed and predictable
- UI knows exactly what to do at each step
- Gemma is only used as a **processing engine**, not a decision maker

## Tool Configuration Details

### ToolProfile.kt
```kotlin
Scribe(
    id = "scribe",
    displayName = "Scribe",
    description = "Audio recording and transcription...",
    toolNames = emptySet(),  // ← No tools!
)
```

**Why empty?** Because the UI handles the entire workflow. There are no tools for the agent to call.

### PlannerToolSchema.kt
```kotlin
fun geniePlannerToolProviders(profile: ToolProfile = ToolProfile.DEFAULT): List<ToolProvider> {
    return when (profile) {
        // ... other profiles
        ToolProfile.Scribe -> emptyList() // ← No planner tools!
    }
}
```

**Why empty?** Because the agent never needs to plan or execute tools for Scribe.

### ToolRegistry.kt
```kotlin
// Scribe tools (RecordAudioTool, etc.) are NOT registered
// because Scribe is UI-driven, not agent-driven
```

**Why not registered?** Because the agent never calls them. The UI calls service methods directly.

### PromptBuilder.kt
```kotlin
if (profile == ToolProfile.Scribe) {
    return "You are Genie in Scribe mode. The UI handles all recording and transcription."
}
```

**Why minimal prompt?** Because the agent is never invoked in Scribe mode (except for transcription processing).

## What Actually Happens in Scribe Mode

### 1. Service Selection (MainActivity)
```kotlin
// User selects Scribe profile
ToolProfilePrefs.setSelectedProfile(context, ToolProfile.Scribe)
```

### 2. Overlay Display (GenieAccessibilityService)
```kotlin
when (selectedProfile) {
    ToolProfile.Scribe -> {
        ScribeOverlay(
            onStartRecording = { config -> ... },
            onStopRecording = { ... }
        )
    }
}
```

### 3. UI Workflow (ScribeRecordingUI.kt)
```kotlin
// State machine controls entire flow
sealed class ScribeUIState {
    Idle → ConfiguringLanguage → ConfiguringMode → Recording → Processing → ShowingResults
}
```

### 4. Direct Service Calls (No Agent)
```kotlin
// UI directly calls service methods
serviceScope.launch {
    // Start recording
    startAudioRecording()
    
    // Stop recording
    val audioPath = stopAudioRecording()
    
    // Transcribe (uses Gemma directly, not via agent)
    val transcription = transcribeAudio(audioPath, language)
    
    // Extract insights (uses Gemma directly, not via agent)
    when (mode) {
        GENERAL -> extractInsights(transcription, outputLang)
        DOCTOR_SCRIBE -> formatSoapNote(transcription, outputLang)
    }
}
```

### 5. Gemma Usage (Processing Engine Only)
```kotlin
// Gemma is called directly for text processing
genieEngine.sendAgentMessage(
    text = "Transcribe this audio...",
    imagePngBytes = emptyList()
).collect { response ->
    // Process streaming response
}
```

**Key Point:** Gemma is used as a **text processing engine** (transcription, summarization), NOT as an **autonomous agent** making tool calls.

## Comparison Table

| Aspect | Agent-Driven (Chat/Reader/etc) | UI-Driven (Scribe) |
|--------|-------------------------------|-------------------|
| **User Input** | Natural language request | Button taps, UI selections |
| **Decision Making** | Agent decides what to do | UI knows what to do |
| **Tool Calls** | Agent calls tools via orchestrator | UI calls methods directly |
| **Gemma Role** | Plan + Execute + Respond | Process text only |
| **Tools Needed** | Yes (read_screen, click, etc) | No tools |
| **Prompt Needed** | Complex system prompt | Minimal/none |
| **State Management** | AgentState + History | UI state machine |

## Benefits of UI-Driven Approach

### 1. Predictability
- Fixed workflow, no agent hallucination
- Always does exactly what user expects
- No unexpected tool calls

### 2. Performance
- No agent planning overhead
- Direct method calls are faster
- No token usage for planning

### 3. User Control
- User explicitly controls each step
- Clear visual feedback at each stage
- Can cancel/retry at any point

### 4. Simplicity
- Easier to debug (no agent decisions to trace)
- Simpler error handling
- More maintainable code

### 5. Reliability
- No risk of agent confusion
- No tool call parsing errors
- Deterministic behavior

## When to Use Each Approach

### Use Agent-Driven When:
- Task requires multiple possible paths
- Agent needs to make decisions
- Need natural language understanding
- Task involves complex OS interactions
- User wants conversational interface

**Example:** "Find and click the login button" (agent must search, decide, act)

### Use UI-Driven When:
- Workflow is fixed and linear
- User controls the flow explicitly
- No decision-making needed
- Processing is compute-heavy (transcription, analysis)
- Immediate feedback desired

**Example:** "Record audio → transcribe → format" (always same steps)

## Summary

**Scribe Profile Tool Configuration:**
- ✅ `toolNames = emptySet()` - No agent tools
- ✅ `geniePlannerToolProviders` returns `emptyList()` - No planner tools
- ✅ Tools not registered in ToolRegistry - Agent can't call them
- ✅ Minimal agent prompt - Agent not involved
- ✅ Direct service method calls from UI - No agent orchestration
- ✅ Gemma used only for text processing - Not for decision making

**This is the correct architecture** for a feature where:
1. User flow is deterministic (always the same steps)
2. User controls when each action happens (via UI buttons)
3. AI is used for processing, not decision-making
4. Visual feedback and configuration are important

The Scribe profile demonstrates that **not every feature needs to be agent-driven**. Sometimes a well-designed UI with direct function calls is the better approach.
