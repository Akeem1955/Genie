package com.akimy.genie.tools

import com.akimy.genie.agent.ToolOutcome
import com.akimy.genie.tools.impl.VisualizeConceptTool
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizeConceptToolValidationTest {

    private val tool = VisualizeConceptTool()
    private val ctx = FakeToolServiceContext()

    @Test
    fun `rejects invalid scene id`() {
        val outcome = runSuspend {
            tool.execute(
                mapOf(
                    "operation" to "create_scene",
                    "scene_id" to "bad id with spaces",
                    "diagram_type" to "flowchart",
                ),
                ctx,
            )
        }

        assertTrue(outcome is ToolOutcome.LogicErr)
    }

    @Test
    fun `accepts operation alias create`() {
        val outcome = runSuspend {
            tool.execute(
                mapOf(
                    "operation" to "create",
                    "scene_id" to "scene_1",
                    "diagram_type" to "flowchart",
                ),
                ctx,
            )
        }

        assertTrue(outcome is ToolOutcome.Ok)
    }

    @Test
    fun `rejects oversized nodes payload`() {
        val huge = "x".repeat(24_001)
        val outcome = runSuspend {
            tool.execute(
                mapOf(
                    "operation" to "update_scene",
                    "scene_id" to "scene_2",
                    "nodes" to huge,
                ),
                ctx,
            )
        }

        assertTrue(outcome is ToolOutcome.LogicErr)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(resumeResult: Result<T>) {
                result = resumeResult
            }
        })
        return result!!.getOrThrow()
    }

    private class FakeToolServiceContext : ToolServiceContext {
        override suspend fun clickElement(target: String): Boolean = false
        override suspend fun typeText(text: String): Boolean = false
        override suspend fun swipe(direction: String): Boolean = false
        override suspend fun scroll(direction: String): Boolean = false
        override suspend fun readScreenText(): String = ""
        override suspend fun readFocusedNode(): String = ""
        override suspend fun takeScreenshot(): String = ""
        override suspend fun consumeLatestScreenshotPngBytes(): ByteArray? = null
        override suspend fun openApp(name: String): Boolean = false
        override suspend fun goBack(): Boolean = false
        override suspend fun goHome(): Boolean = false
        override suspend fun tap(x: Float, y: Float): Boolean = false
        override suspend fun getScreenContext(): ScreenContext = ScreenContext()
        override suspend fun focusNext(): Boolean = false
        override suspend fun focusPrevious(): Boolean = false
        override suspend fun focusFirst(): Boolean = false
        override suspend fun focusElementByText(target: String): Boolean = false
        override suspend fun focusElementByRole(role: String): Boolean = false
        override suspend fun activateFocused(): Boolean = false
        override suspend fun scrollForward(): Boolean = false
        override suspend fun scrollBackward(): Boolean = false
        override suspend fun readScreenSummary(): String = ""
        override suspend fun readRecentEvents(limit: Int): String = ""
        override suspend fun whereAmI(): String = ""
        override suspend fun readNearbyContext(): String = ""
        override suspend fun whatCanIDoHere(): String = ""
        override suspend fun readScreenChanges(): String = ""
        override suspend fun enableContinuousReader(): String = ""
        override suspend fun disableContinuousReader(): String = ""
        override suspend fun readContinuousReaderStatus(): String = ""
        override suspend fun repeatLastNarration(): String = ""
        override suspend fun readScreenMap(): String = ""
        override suspend fun saveScreenHint(note: String): String = ""
        override suspend fun readDialog(): String = ""
        override suspend fun readNotifications(limit: Int): String = ""
        override suspend fun readFormState(): String = ""
        override suspend fun searchHealthTopics(query: String): List<String> = emptyList()
        override suspend fun getHealthTopic(name: String): HealthRecord? = null
    }
}
