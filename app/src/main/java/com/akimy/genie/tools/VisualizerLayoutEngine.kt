package com.akimy.genie.tools

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.sin

data class LayoutPoint(
    val x: Float,
    val y: Float,
)

data class LayoutNode(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class LayoutEdge(
    val from: String,
    val to: String,
    val points: List<LayoutPoint>,
)

data class VisualizerSceneLayout(
    val sceneId: String,
    val nodeLayouts: List<LayoutNode>,
    val edgeLayouts: List<LayoutEdge>,
    val layoutType: String = "flowchart",
)

object VisualizerLayoutEngine {
    private const val PADDING_X = 56f
    private const val PADDING_Y = 56f
    private const val LANE_WIDTH = 320f
    private const val ROW_GAP = 44f
    private const val COLUMN_GAP = 36f
    private const val MIN_NODE_WIDTH = 160f
    private const val MAX_NODE_WIDTH = 280f
    private const val MIN_NODE_HEIGHT = 72f
    private const val NODE_LINE_HEIGHT = 20f
    private const val MAX_LABEL_LINES = 3
    private const val TIMELINE_SPACING = 250f
    private const val CYCLE_RADIUS = 260f

    fun layout(scene: VisualizerScene): VisualizerSceneLayout {
        if (scene.board != null || scene.diagramType.equals("board", ignoreCase = true)) {
            return VisualizerSceneLayout(
                sceneId = scene.sceneId,
                nodeLayouts = emptyList(),
                edgeLayouts = emptyList(),
                layoutType = "board",
            )
        }
        return when (scene.diagramType.lowercase()) {
            "flowchart" -> flowchart(scene)
            "timeline" -> timeline(scene)
            "cycle" -> cycle(scene)
            "mindmap" -> mindmap(scene)
            "table" -> table(scene)
            else -> flowchart(scene)
        }
    }

    private fun flowchart(scene: VisualizerScene): VisualizerSceneLayout {
        val layers = computeLayers(scene.nodes, scene.edges)

        val byLayer = layers.entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap()
            .mapValues { (_, ids) -> ids.sorted() }

        val nodeLayouts = mutableListOf<LayoutNode>()
        val idToNode = scene.nodes.associateBy { it.id }

        byLayer.forEach { (layer, ids) ->
            ids.forEachIndexed { row, id ->
                val node = idToNode[id] ?: return@forEachIndexed
                val width = estimateWidth(node.label)
                val height = estimateHeight(node.label)
                val x = PADDING_X + layer * LANE_WIDTH
                val y = PADDING_Y + row * (height + ROW_GAP)
                nodeLayouts.add(
                    LayoutNode(
                        id = id,
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                    )
                )
            }
        }

        val edgeLayouts = orthogonalEdges(scene.edges, nodeLayouts)

        return VisualizerSceneLayout(
            sceneId = scene.sceneId,
            nodeLayouts = nodeLayouts,
            edgeLayouts = edgeLayouts,
            layoutType = "flowchart",
        )
    }

    private fun timeline(scene: VisualizerScene): VisualizerSceneLayout {
        val sorted = scene.nodes.sortedBy { it.id }
        val nodeLayouts = sorted.mapIndexed { idx, node ->
            val width = estimateWidth(node.label)
            val height = estimateHeight(node.label)
            LayoutNode(
                id = node.id,
                x = PADDING_X + idx * TIMELINE_SPACING,
                y = PADDING_Y + 140f + if (idx % 2 == 0) 0f else 120f,
                width = width,
                height = height,
            )
        }

        val edges = if (scene.edges.isEmpty()) {
            sorted.zipWithNext().map { (a, b) -> VisualEdge(from = a.id, to = b.id) }
        } else {
            scene.edges
        }

        return VisualizerSceneLayout(
            sceneId = scene.sceneId,
            nodeLayouts = nodeLayouts,
            edgeLayouts = straightEdges(edges, nodeLayouts),
            layoutType = "timeline",
        )
    }

    private fun cycle(scene: VisualizerScene): VisualizerSceneLayout {
        val sorted = scene.nodes.sortedBy { it.id }
        val count = sorted.size.coerceAtLeast(1)
        val centerX = PADDING_X + CYCLE_RADIUS + 220f
        val centerY = PADDING_Y + CYCLE_RADIUS + 120f

        val nodeLayouts = sorted.mapIndexed { idx, node ->
            val angle = ((2.0 * PI) / count) * idx - (PI / 2.0)
            val width = estimateWidth(node.label)
            val height = estimateHeight(node.label)
            val cx = centerX + (CYCLE_RADIUS * cos(angle)).toFloat()
            val cy = centerY + (CYCLE_RADIUS * sin(angle)).toFloat()
            LayoutNode(
                id = node.id,
                x = cx - (width / 2f),
                y = cy - (height / 2f),
                width = width,
                height = height,
            )
        }

        val edges = if (scene.edges.isEmpty()) {
            val ids = sorted.map { it.id }
            ids.indices.map { i ->
                VisualEdge(from = ids[i], to = ids[(i + 1) % ids.size])
            }
        } else {
            scene.edges
        }

        return VisualizerSceneLayout(
            sceneId = scene.sceneId,
            nodeLayouts = nodeLayouts,
            edgeLayouts = straightEdges(edges, nodeLayouts),
            layoutType = "cycle",
        )
    }

    private fun mindmap(scene: VisualizerScene): VisualizerSceneLayout {
        val sorted = scene.nodes.sortedBy { it.id }
        if (sorted.isEmpty()) {
            return VisualizerSceneLayout(scene.sceneId, emptyList(), emptyList(), "mindmap")
        }

        val center = sorted.first()
        val others = sorted.drop(1)
        val centerW = estimateWidth(center.label)
        val centerH = estimateHeight(center.label)
        val centerX = PADDING_X + 420f
        val centerY = PADDING_Y + 260f

        val nodeLayouts = mutableListOf(
            LayoutNode(
                id = center.id,
                x = centerX,
                y = centerY,
                width = centerW,
                height = centerH,
            )
        )

        val left = others.filterIndexed { idx, _ -> idx % 2 == 0 }
        val right = others.filterIndexed { idx, _ -> idx % 2 == 1 }

        left.forEachIndexed { idx, node ->
            val width = estimateWidth(node.label)
            val height = estimateHeight(node.label)
            nodeLayouts += LayoutNode(
                id = node.id,
                x = centerX - 360f - width,
                y = centerY - 120f + idx * (height + ROW_GAP),
                width = width,
                height = height,
            )
        }
        right.forEachIndexed { idx, node ->
            val width = estimateWidth(node.label)
            val height = estimateHeight(node.label)
            nodeLayouts += LayoutNode(
                id = node.id,
                x = centerX + centerW + 220f,
                y = centerY - 120f + idx * (height + ROW_GAP),
                width = width,
                height = height,
            )
        }

        val edges = if (scene.edges.isEmpty()) {
            others.map { VisualEdge(from = center.id, to = it.id) }
        } else {
            scene.edges
        }

        return VisualizerSceneLayout(
            sceneId = scene.sceneId,
            nodeLayouts = nodeLayouts,
            edgeLayouts = straightEdges(edges, nodeLayouts),
            layoutType = "mindmap",
        )
    }

    private fun table(scene: VisualizerScene): VisualizerSceneLayout {
        val sorted = scene.nodes.sortedBy { it.id }
        val columns = 3
        val nodeLayouts = sorted.mapIndexed { idx, node ->
            val width = estimateWidth(node.label)
            val height = estimateHeight(node.label)
            val col = idx % columns
            val row = idx / columns
            LayoutNode(
                id = node.id,
                x = PADDING_X + col * (MAX_NODE_WIDTH + COLUMN_GAP),
                y = PADDING_Y + row * (height + ROW_GAP),
                width = width,
                height = height,
            )
        }

        val edges = if (scene.edges.isEmpty()) {
            emptyList()
        } else {
            scene.edges
        }

        return VisualizerSceneLayout(
            sceneId = scene.sceneId,
            nodeLayouts = nodeLayouts,
            edgeLayouts = straightEdges(edges, nodeLayouts),
            layoutType = "table",
        )
    }

    private fun computeLayers(nodes: List<VisualNode>, edges: List<VisualEdge>): Map<String, Int> {
        val ids = nodes.map { it.id }
        val outgoing = ids.associateWith { mutableListOf<String>() }
        val indegree = ids.associateWith { 0 }.toMutableMap()

        edges.forEach { edge ->
            if (edge.from in outgoing && edge.to in indegree) {
                outgoing.getValue(edge.from).add(edge.to)
                indegree[edge.to] = (indegree[edge.to] ?: 0) + 1
            }
        }

        val queue = indegree
            .filter { it.value == 0 }
            .keys
            .sorted()
            .toMutableList()

        val layer = mutableMapOf<String, Int>()
        queue.forEach { layer[it] = 0 }

        var idx = 0
        while (idx < queue.size) {
            val current = queue[idx++]
            val currentLayer = layer[current] ?: 0
            outgoing[current]
                ?.sorted()
                ?.forEach { next ->
                    indegree[next] = (indegree[next] ?: 1) - 1
                    layer[next] = max(layer[next] ?: 0, currentLayer + 1)
                    if (indegree[next] == 0) queue.add(next)
                }
        }

        // Cycle fallback: assign any unresolved node to deterministic trailing layers.
        val unresolved = ids.filter { it !in layer }.sorted()
        var fallbackLayer = (layer.values.maxOrNull() ?: -1) + 1
        unresolved.forEach { id ->
            layer[id] = fallbackLayer++
        }

        return layer
    }

    private fun estimateWidth(label: String): Float {
        val estimated = MIN_NODE_WIDTH + (label.length * 6.5f)
        return estimated.coerceIn(MIN_NODE_WIDTH, MAX_NODE_WIDTH)
    }

    private fun estimateHeight(label: String): Float {
        val lineCount = estimateLineCount(label)
        val estimated = 34f + (lineCount * NODE_LINE_HEIGHT)
        return estimated.coerceAtLeast(MIN_NODE_HEIGHT)
    }

    private fun estimateLineCount(label: String): Int {
        val approxCharsPerLine = 18
        val count = ((label.length + approxCharsPerLine - 1) / approxCharsPerLine)
        return count.coerceIn(1, MAX_LABEL_LINES)
    }

    private fun orthogonalEdges(edges: List<VisualEdge>, nodes: List<LayoutNode>): List<LayoutEdge> {
        val layoutById = nodes.associateBy { it.id }
        return edges.mapNotNull { edge ->
            val from = layoutById[edge.from] ?: return@mapNotNull null
            val to = layoutById[edge.to] ?: return@mapNotNull null
            val start = LayoutPoint(from.x + from.width, from.y + from.height / 2f)
            val end = LayoutPoint(to.x, to.y + to.height / 2f)
            val midX = (start.x + end.x) / 2f
            LayoutEdge(
                from = edge.from,
                to = edge.to,
                points = listOf(
                    start,
                    LayoutPoint(midX, start.y),
                    LayoutPoint(midX, end.y),
                    end,
                ),
            )
        }
    }

    private fun straightEdges(edges: List<VisualEdge>, nodes: List<LayoutNode>): List<LayoutEdge> {
        val layoutById = nodes.associateBy { it.id }
        return edges.mapNotNull { edge ->
            val from = layoutById[edge.from] ?: return@mapNotNull null
            val to = layoutById[edge.to] ?: return@mapNotNull null
            val start = LayoutPoint(from.x + from.width / 2f, from.y + from.height / 2f)
            val end = LayoutPoint(to.x + to.width / 2f, to.y + to.height / 2f)
            LayoutEdge(edge.from, edge.to, listOf(start, end))
        }
    }
}
