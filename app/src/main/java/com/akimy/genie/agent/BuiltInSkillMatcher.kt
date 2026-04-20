package com.akimy.genie.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Deterministic built-in skills for high-confidence intents.
 *
 * These run before querying DB skills so common visualizer intents can be
 * executed predictably with a structured scene payload.
 */
object BuiltInSkillMatcher {
    private val diagramKeywords = mapOf(
        "flowchart" to "flowchart",
        "mindmap" to "mindmap",
        "mind map" to "mindmap",
        "timeline" to "timeline",
        "cycle" to "cycle",
        "table" to "table",
    )

    fun match(goal: String): List<Decision.Act>? {
        val lowered = goal.lowercase()
        val isTeachingGoal = lowered.contains("teach") ||
            lowered.contains("lesson") ||
            lowered.contains("step by step") ||
            lowered.contains("whiteboard") ||
            lowered.contains("walk me through")

        val isVisualGoal = lowered.contains("visual") ||
            lowered.contains("diagram") ||
            lowered.contains("flowchart") ||
            lowered.contains("mindmap") ||
            lowered.contains("timeline") ||
            lowered.contains("cycle") ||
            lowered.contains("compare") ||
            lowered.contains("comparison") ||
            lowered.contains("summarize") ||
            lowered.contains("summary") ||
            lowered.contains("explain")

        if (isTeachingGoal) {
            return buildTeachingBoardSkill(goal)
        }

        if (!isVisualGoal) return null

        val diagramType = resolveDiagramType(lowered)
        val graph = parseGraph(goal)
        val sceneId = "scene_${System.currentTimeMillis()}"
        val title = goal.trim().replace("\n", " ").take(72).ifBlank { "Concept Diagram" }

        val createArgs = mapOf(
            "operation" to "create_scene",
            "scene_id" to sceneId,
            "diagram_type" to diagramType,
            "title" to title,
            "nodes" to graph.nodes.toString(),
            "edges" to graph.edges.toString(),
        )

        val exportArgs = mapOf(
            "operation" to "export_scene",
            "scene_id" to sceneId,
        )

        return listOf(
            Decision.Act(tool = "visualize_concept", args = createArgs),
            Decision.Act(tool = "visualize_concept", args = exportArgs),
        )
    }

    private fun buildTeachingBoardSkill(goal: String): List<Decision.Act> {
        val sceneId = "board_${System.currentTimeMillis()}"
        val cleanedGoal = goal.trim().replace("\n", " ").take(90).ifBlank { "Lesson Board" }
        val keywords = goal
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
            .take(4)
            .ifEmpty { listOf("Core Idea", "Example", "Key Point") }

        val objects = buildJsonArray {
            add(
                buildJsonObject {
                    put("objectId", JsonPrimitive("title"))
                    put("objectType", JsonPrimitive("title"))
                    put("text", JsonPrimitive(cleanedGoal))
                    put(
                        "position",
                        buildJsonObject {
                            put("x", JsonPrimitive(52))
                            put("y", JsonPrimitive(48))
                        }
                    )
                    put(
                        "size",
                        buildJsonObject {
                            put("width", JsonPrimitive(720))
                            put("height", JsonPrimitive(56))
                        }
                    )
                    put("stepId", JsonPrimitive("step_intro"))
                    put(
                        "style",
                        buildJsonObject {
                            put("textColor", JsonPrimitive("#F8FAFC"))
                            put("textSize", JsonPrimitive(30))
                            put("textAlign", JsonPrimitive("start"))
                        }
                    )
                }
            )

            keywords.forEachIndexed { index, keyword ->
                add(
                    buildJsonObject {
                        put("objectId", JsonPrimitive("card_${index + 1}"))
                        put("objectType", JsonPrimitive("card"))
                        put("text", JsonPrimitive(keyword.replaceFirstChar { it.uppercase() }))
                        put(
                            "position",
                            buildJsonObject {
                                put("x", JsonPrimitive(60 + (index * 250)))
                                put("y", JsonPrimitive(170 + ((index % 2) * 180)))
                            }
                        )
                        put(
                            "size",
                            buildJsonObject {
                                put("width", JsonPrimitive(220))
                                put("height", JsonPrimitive(130))
                            }
                        )
                        put("stepId", JsonPrimitive(if (index == 0) "step_intro" else "step_detail_${index}"))
                        put(
                            "style",
                            buildJsonObject {
                                put("strokeColor", JsonPrimitive(if (index % 2 == 0) "#4DA3FF" else "#31E7B6"))
                                put("textColor", JsonPrimitive("#FDE047"))
                                put("fillColor", JsonPrimitive("#16181D"))
                            }
                        )
                    }
                )
            }
        }

        val steps = buildJsonArray {
            add(
                buildJsonObject {
                    put("stepId", JsonPrimitive("step_intro"))
                    put("title", JsonPrimitive("Introduction"))
                    put("narration", JsonPrimitive("Let us start with the core idea and set the lesson board."))
                }
            )
            keywords.drop(1).forEachIndexed { index, keyword ->
                add(
                    buildJsonObject {
                        put("stepId", JsonPrimitive("step_detail_${index + 1}"))
                        put("title", JsonPrimitive(keyword.replaceFirstChar { it.uppercase() }))
                        put("narration", JsonPrimitive("Now we focus on ${keyword.lowercase()} and connect it back to the main idea."))
                    }
                )
            }
        }

        return listOf(
            Decision.Act(
                tool = "teach_with_board",
                args = mapOf(
                    "scene_id" to sceneId,
                    "title" to cleanedGoal,
                    "board_theme" to "dark_classroom",
                    "objects" to objects.toString(),
                    "steps" to steps.toString(),
                    "narration_text" to "Let us walk through this concept step by step on the board.",
                )
            ),
            Decision.Act(
                tool = "board_focus_object",
                args = mapOf(
                    "scene_id" to sceneId,
                    "object_id" to "title",
                )
            ),
        )
    }

    private fun resolveDiagramType(lowered: String): String {
        for ((keyword, type) in diagramKeywords) {
            if (lowered.contains(keyword)) return type
        }
        if (lowered.contains("compare") || lowered.contains("comparison")) return "table"
        if (lowered.contains("timeline") || lowered.contains("history") || lowered.contains("sequence")) return "timeline"
        if (lowered.contains("cycle") || lowered.contains("loop") || lowered.contains("repeat")) return "cycle"
        if (lowered.contains("map") || lowered.contains("brainstorm")) return "mindmap"
        return "flowchart"
    }

    private fun parseGraph(goal: String): GraphPayload {
        val edgeRegex = Regex("([A-Za-z][A-Za-z0-9 _-]{0,24})\\s*->\\s*([A-Za-z][A-Za-z0-9 _-]{0,24})")
        val matches = edgeRegex.findAll(goal).toList()

        if (matches.isNotEmpty()) {
            val labelToId = linkedMapOf<String, String>()
            val edges = mutableListOf<JsonObject>()

            fun idForLabel(label: String): String {
                val normalized = normalizeLabel(label)
                return labelToId.getOrPut(normalized) { "n${labelToId.size + 1}" }
            }

            matches.forEach { m ->
                val fromLabel = normalizeLabel(m.groupValues[1])
                val toLabel = normalizeLabel(m.groupValues[2])
                val fromId = idForLabel(fromLabel)
                val toId = idForLabel(toLabel)

                edges += buildJsonObject {
                    put("from", JsonPrimitive(fromId))
                    put("to", JsonPrimitive(toId))
                }
            }

            val nodes = buildJsonArray {
                labelToId.forEach { (label, id) ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("label", JsonPrimitive(label))
                            put("kind", JsonPrimitive("concept"))
                        }
                    )
                }
            }

            return GraphPayload(nodes = nodes, edges = JsonArray(edges))
        }

        val fallbackLabels = goal
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
            .take(4)
            .ifEmpty { listOf("Input", "Process", "Output") }

        val nodes = buildJsonArray {
            fallbackLabels.forEachIndexed { idx, label ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive("n${idx + 1}"))
                        put("label", JsonPrimitive(normalizeLabel(label)))
                        put("kind", JsonPrimitive("concept"))
                    }
                )
            }
        }

        val edges = buildJsonArray {
            for (i in 0 until fallbackLabels.lastIndex) {
                add(
                    buildJsonObject {
                        put("from", JsonPrimitive("n${i + 1}"))
                        put("to", JsonPrimitive("n${i + 2}"))
                    }
                )
            }
        }

        return GraphPayload(nodes = nodes, edges = edges)
    }

    private fun normalizeLabel(raw: String): String {
        return raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(40)
    }

    private data class GraphPayload(
        val nodes: JsonArray,
        val edges: JsonArray,
    )
}
