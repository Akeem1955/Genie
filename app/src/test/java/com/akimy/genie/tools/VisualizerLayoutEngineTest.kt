package com.akimy.genie.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisualizerLayoutEngineTest {

    @Test
    fun `layout selects timeline strategy`() {
        val scene = sampleScene("timeline")

        val layout = VisualizerLayoutEngine.layout(scene)

        assertEquals("timeline", layout.layoutType)
        assertEquals(scene.nodes.size, layout.nodeLayouts.size)
    }

    @Test
    fun `layout selects cycle strategy`() {
        val scene = sampleScene("cycle")

        val layout = VisualizerLayoutEngine.layout(scene)

        assertEquals("cycle", layout.layoutType)
        assertEquals(scene.nodes.size, layout.nodeLayouts.size)
        assertFalse(layout.edgeLayouts.isEmpty())
    }

    @Test
    fun `layout selects mindmap strategy`() {
        val scene = sampleScene("mindmap")

        val layout = VisualizerLayoutEngine.layout(scene)

        assertEquals("mindmap", layout.layoutType)
        assertEquals(scene.nodes.size, layout.nodeLayouts.size)
    }

    @Test
    fun `layout selects table strategy`() {
        val scene = sampleScene("table")

        val layout = VisualizerLayoutEngine.layout(scene)

        assertEquals("table", layout.layoutType)
        assertEquals(scene.nodes.size, layout.nodeLayouts.size)
    }

    private fun sampleScene(diagramType: String): VisualizerScene {
        val nodes = listOf(
            VisualNode(id = "n1", label = "Input"),
            VisualNode(id = "n2", label = "Process"),
            VisualNode(id = "n3", label = "Output"),
            VisualNode(id = "n4", label = "Review"),
        )
        val edges = listOf(
            VisualEdge(from = "n1", to = "n2"),
            VisualEdge(from = "n2", to = "n3"),
            VisualEdge(from = "n3", to = "n4"),
        )
        return VisualizerScene(
            sceneId = "scene_test",
            diagramType = diagramType,
            title = "Test",
            nodes = nodes,
            edges = edges,
        )
    }
}
