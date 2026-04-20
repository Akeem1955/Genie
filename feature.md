# Annotating Skill - Implementation Report

## Feature Goal
Add a standalone annotation capability that lets Genie visually mark the live screen with:
- highlight boxes
- labels
- pointers/arrows
- timed reveal sequences
- clear and replay controls

This is implemented as a first-class tool family in the planner/orchestrator pipeline, with both:
- live accessibility overlay rendering, and
- persisted board-style session projection for inspection/replay in the visualizer flow.

## What Was Implemented

### 1) Planner Tool Surface (Schema)
Added annotation tool declarations to planner schema so the model can call them directly:
- `annotate_scene`
- `annotation_add_box`
- `annotation_add_label`
- `annotation_add_pointer`
- `annotation_clear`
- `annotation_replay`

File:
- `app/src/main/java/com/akimy/genie/agent/PlannerToolSchema.kt`

What this enables:
- Native tool-calling path for annotation operations.
- Structured args from planner to orchestrator.

---

### 2) Runtime Tool Registration
Registered annotation tools in runtime registry.

File:
- `app/src/main/java/com/akimy/genie/tools/ToolRegistry.kt`

Added registrations:
- `AnnotateSceneTool`
- `AnnotationAddBoxTool`
- `AnnotationAddLabelTool`
- `AnnotationAddPointerTool`
- `AnnotationClearTool`
- `AnnotationReplayTool`

---

### 3) Tool Implementations + Validation
Implemented annotation tool classes with deterministic argument checks.

File:
- `app/src/main/java/com/akimy/genie/tools/impl/ToolImplementations.kt`

Key behaviors:
- session/op id validation (safe id pattern)
- numeric checks for coordinates
- delay range checks (`0..20000` ms)
- support for box shape input in either:
  - `x,y,width,height`, or
  - `x,y,x2,y2`
- style payload size guard
- pointer target consistency checks

These tools return marker outcomes that are resolved by orchestrator-side handling.

---

### 4) Orchestrator Wiring + Side Effects
Added full annotation handling path in orchestrator.

File:
- `app/src/main/java/com/akimy/genie/agent/AgentOrchestrator.kt`

Implemented in orchestrator:
- annotation tool branch routing in special-tool handler
- session id validation and viewport checks
- coordinate normalization to screen pixels
- style parsing with defaults and bounds
- delay-aware execution (`delay_ms`)
- dispatch to service overlay APIs for live draw
- dispatch to session store for persisted board projection
- clear and replay handling

Data tracked in orchestrator:
- per-session annotation metadata
- per-session playback operation history

Important outcome:
- planner tool calls now produce immediate live overlays and persisted session state.

---

### 5) Annotation Session Store (New)
Created session store to keep annotation operations and project them into visualizer-compatible board scenes.

File:
- `app/src/main/java/com/akimy/genie/tools/AnnotationSessionStore.kt`

Responsibilities:
- start session
- append box/label/pointer operations
- normalize/clamp geometry to viewport
- convert operations to teaching-board objects/steps
- publish scene updates through visualizer scene store
- replay and clear hooks

Utility methods added:
- `normalizedToPx(...)`
- `spanToPx(...)`

This gives durable structure for annotation sessions beyond transient overlay drawing.

---

### 6) Service Contract Expansion
Extended tool-service interface to expose annotation capabilities and viewport info.

File:
- `app/src/main/java/com/akimy/genie/tools/GenieTool.kt`

Added to `ToolServiceContext`:
- `getViewportInfo()`
- `annotationStartSession(...)`
- `annotationDrawBox(...)`
- `annotationDrawLabel(...)`
- `annotationDrawPointer(...)`
- `annotationClearSession(...)`
- `annotationReplaySession(...)`

Also added:
- `ViewportInfo(widthPx, heightPx)`

---

### 7) Accessibility Service Integration
Implemented annotation interface methods in accessibility service and connected an overlay controller.

File:
- `app/src/main/java/com/akimy/genie/service/GenieAccessibilityService.kt`

Added service-side behavior:
- lazy creation/use of annotation overlay controller
- start/draw/clear/replay method implementations
- viewport reporting from display metrics
- overlay detach/cleanup in `onDestroy`

Result:
- orchestrator can invoke live annotation rendering directly on top of current screen.

---

### 8) Live Overlay Renderer (New)
Added dedicated overlay controller to render annotation items in real time.

File:
- `app/src/main/java/com/akimy/genie/service/AnnotationOverlayController.kt`

Capabilities:
- accessibility overlay window attachment
- draw primitives for:
  - boxes
  - labels
  - pointers/arrows
- style application (stroke/fill/text)
- per-session operation lists
- delay-aware replay sequencing via coroutine job
- clear by session
- detach when empty/destroyed

Replay lifecycle safeguards:
- cancel existing replay job before starting new replay
- cancel replay on clear/detach

---

### 9) Prompt Guidance Update
Updated planner prompt policy so model uses annotation tools for standalone annotation workflows.

File:
- `app/src/main/java/com/akimy/genie/agent/PromptBuilder.kt`

Prompt rule added to encourage:
- `annotate_scene` + `annotation_add_*` usage when user intent is to mark/explain on-screen regions with timed overlays.

---

### 10) Planner Parsing Test Coverage
Added parser test ensuring annotation tool calls are correctly parsed into `Decision.Act`.

File:
- `app/src/test/java/com/akimy/genie/agent/PlannerToolCallParsingTest.kt`

Coverage includes:
- `annotation_add_box` call parsing
- argument mapping validation (session/op/coords)

---

## End-to-End Flow (Implemented)
1. Planner emits annotation tool call.
2. Registry resolves to annotation tool implementation.
3. Tool validates args and returns marker.
4. Orchestrator intercepts annotation tool branch.
5. Orchestrator validates/normalizes coordinates and delay/style.
6. Orchestrator calls service annotation APIs for live overlay.
7. Orchestrator records operation in `AnnotationSessionStore`.
8. Session store projects/update board-style representation.
9. `annotation_replay` replays session in live overlay and session projection.
10. `annotation_clear` removes live overlays and stored session state.

## Behavior Details
- Coordinate input supports normalized values and pixel-like values.
- Delay is per operation (`delay_ms`) and bounded.
- Replay is sequence-based and delay-aware.
- Session IDs and op IDs are bounded and validated.
- Style JSON is optional; sensible defaults are used when missing.

## Validation Performed
- Static diagnostics were run on touched files during implementation.
- No relevant compile/lint issues were reported for the annotation path at the time of checks.

## Notes
- Tests were not executed in this environment, per workspace runtime constraint in AGENTS.md (static inspection only for this coding-agent flow).

## Files Touched for Annotating Skill
- `app/src/main/java/com/akimy/genie/agent/PlannerToolSchema.kt`
- `app/src/main/java/com/akimy/genie/tools/ToolRegistry.kt`
- `app/src/main/java/com/akimy/genie/tools/impl/ToolImplementations.kt`
- `app/src/main/java/com/akimy/genie/agent/AgentOrchestrator.kt`
- `app/src/main/java/com/akimy/genie/tools/AnnotationSessionStore.kt`
- `app/src/main/java/com/akimy/genie/tools/GenieTool.kt`
- `app/src/main/java/com/akimy/genie/service/GenieAccessibilityService.kt`
- `app/src/main/java/com/akimy/genie/service/AnnotationOverlayController.kt`
- `app/src/main/java/com/akimy/genie/agent/PromptBuilder.kt`
- `app/src/test/java/com/akimy/genie/agent/PlannerToolCallParsingTest.kt`

## Post-Review Behavior Patch (April 20, 2026)
Addressed four behavior gaps identified during review:

1. Pointer fallback unit bug fixed
- Pointer default target is now computed in pixel space from normalized source coordinates.
- This prevents mixed-unit fallback math (`normalized + pixel`) that could place pointers incorrectly.

2. Persisted replay now runs full sequence
- `AnnotationSessionStore.replaySession(...)` now replays intro plus each annotation step in order.
- Replay applies per-operation delay values so board replay behavior matches session timing.

3. Overlay call success is now checked and propagated
- Orchestrator now checks boolean results from `annotationStartSession`, `annotationDraw*`, `annotationClearSession`, and `annotationReplaySession`.
- Failures return explicit transient/failed outcomes instead of unconditional success.
- Service overlay bridge methods now return `false` on real exceptions (with logging) instead of always returning `true`.

4. Replay delay semantics aligned
- Overlay replay delay clamp now matches validated delay range (`0..20000` ms).
- Persisted replay also uses the same max delay bound.

Files updated by this patch:
- `app/src/main/java/com/akimy/genie/agent/AgentOrchestrator.kt`
- `app/src/main/java/com/akimy/genie/tools/AnnotationSessionStore.kt`
- `app/src/main/java/com/akimy/genie/service/AnnotationOverlayController.kt`
- `app/src/main/java/com/akimy/genie/service/GenieAccessibilityService.kt`
