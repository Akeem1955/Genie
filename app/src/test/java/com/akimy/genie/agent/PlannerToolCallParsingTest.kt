package com.akimy.genie.agent

import com.akimy.genie.engine.GenieEngine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerToolCallParsingTest {

    private val planner = Planner(engine = GenieEngine(), skillDao = null)

    @Test
    fun `normal tool call maps to act decision`() {
        val result = planner.parseDecision(
            Message.model(toolCalls = listOf(ToolCall("click", mapOf("target" to "Settings"))))
        )

        assertTrue(result is PlanResult.Success)
        val decision = (result as PlanResult.Success).decision as Decision.Act
        assertEquals("click", decision.tool)
        assertEquals("Settings", decision.args["target"])
    }

    @Test
    fun `tasks maps to finish decision`() {
        val result = planner.parseDecision(
            Message.model(toolCalls = listOf(ToolCall("tasks", mapOf("plan" to "Done"))))
        )

        assertTrue(result is PlanResult.Success)
        val decision = (result as PlanResult.Success).decision as Decision.Finish
        assertEquals("Done", decision.summary)
    }

    @Test
    fun `multiple tool calls fail deterministically`() {
        val result = planner.parseDecision(
            Message.model(
                toolCalls = listOf(
                    ToolCall("read_screen", emptyMap()),
                    ToolCall("click", mapOf("target" to "Settings")),
                )
            )
        )

        assertTrue(result is PlanResult.ParseError)
    }

    @Test
    fun `plain text without tool call fails in planner mode`() {
        val result = planner.parseDecision("I already finished this task.")

        assertTrue(result is PlanResult.ParseError)
    }

}
