package com.akimy.genie.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSkillMatcherTest {

    @Test
    fun `compare intent maps to table diagram`() {
        val steps = BuiltInSkillMatcher.match("Compare mitosis and meiosis with a visual explanation")

        assertNotNull(steps)
        val first = steps!!.first()
        assertEquals("visualize_concept", first.tool)
        assertEquals("create_scene", first.args["operation"])
        assertEquals("table", first.args["diagram_type"])
    }

    @Test
    fun `timeline intent maps to timeline diagram`() {
        val steps = BuiltInSkillMatcher.match("Create a timeline for the French Revolution")

        assertNotNull(steps)
        val first = steps!!.first()
        assertEquals("timeline", first.args["diagram_type"])
    }

    @Test
    fun `non visual intent does not match`() {
        val steps = BuiltInSkillMatcher.match("Open calculator app")

        assertTrue(steps == null)
    }
}
