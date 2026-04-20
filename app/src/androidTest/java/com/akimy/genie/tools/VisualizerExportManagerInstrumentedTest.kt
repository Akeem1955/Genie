package com.akimy.genie.tools

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualizerExportManagerInstrumentedTest {

    @Test
    fun renderSceneBitmap_returnsMinimumExpectedSize() {
        val snapshot = sampleSnapshot()

        val bitmap = VisualizerExportManager.renderSceneBitmap(snapshot)

        assertTrue(bitmap.width >= 960)
        assertTrue(bitmap.height >= 720)
    }

    @Test
    fun buildSharePngIntent_setsImageTypeAndAction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = VisualizerExportManager.exportSceneAsPng(context, sampleSnapshot())

        assertNotNull(uri)
        val intent = VisualizerExportManager.buildSharePngIntent(uri!!)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun exportSceneAsPng_createsReadableContentUri() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val snapshot = sampleSnapshot()

        val uri = VisualizerExportManager.exportSceneAsPng(context, snapshot)

        assertNotNull(uri)
        context.contentResolver.openInputStream(uri!!).use { input ->
            assertNotNull(input)
            val bytes = input!!.readBytes()
            assertTrue(bytes.isNotEmpty())
        }
    }

    private fun sampleSnapshot(): SceneSnapshot {
        val scene = VisualizerScene(
            sceneId = "scene_test_export",
            diagramType = "flowchart",
            title = "Export Test",
            nodes = listOf(
                VisualNode(id = "n1", label = "Start"),
                VisualNode(id = "n2", label = "Solve projectile equation"),
            ),
            edges = listOf(
                VisualEdge(from = "n1", to = "n2"),
            ),
            focusNodeIds = listOf("n2"),
        )

        val layout = VisualizerSceneLayout(
            sceneId = scene.sceneId,
            nodeLayouts = listOf(
                LayoutNode(id = "n1", x = 80f, y = 120f, width = 190f, height = 88f),
                LayoutNode(id = "n2", x = 420f, y = 120f, width = 260f, height = 108f),
            ),
            edgeLayouts = listOf(
                LayoutEdge(
                    from = "n1",
                    to = "n2",
                    points = listOf(
                        LayoutPoint(270f, 164f),
                        LayoutPoint(345f, 164f),
                        LayoutPoint(345f, 174f),
                        LayoutPoint(420f, 174f),
                    ),
                )
            ),
            layoutType = "flowchart",
        )

        return SceneSnapshot(scene = scene, layout = layout)
    }
}
