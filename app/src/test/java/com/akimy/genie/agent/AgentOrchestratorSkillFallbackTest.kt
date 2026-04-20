package com.akimy.genie.agent

import android.test.mock.MockContext
import com.akimy.genie.data.FactDao
import com.akimy.genie.data.Skill
import com.akimy.genie.data.SkillDao
import com.akimy.genie.data.UserFact
import com.akimy.genie.engine.GenieEngine
import com.akimy.genie.tools.ScreenContext
import com.akimy.genie.tools.ToolRegistry
import com.akimy.genie.tools.ToolServiceContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentOrchestratorSkillFallbackTest {

    @Test
    fun `stored skill success increments success count`() = runBlocking {
        val skillDao = FakeSkillDao()
        val planner = object : GeniePlanner {
            override suspend fun findSkillMatch(goal: String): SkillMatch {
                return SkillMatch.Stored(
                    skillId = 7,
                    goalPattern = goal,
                    steps = listOf(Decision.Act("go_home")),
                )
            }

            override suspend fun plan(prompt: String, imagePngBytes: List<ByteArray>): PlanResult {
                throw AssertionError("Live planning should not run when cached skill succeeds")
            }
        }

        val orchestrator = AgentOrchestrator(
            engine = GenieEngine(),
            toolRegistry = ToolRegistry(),
            promptBuilder = PromptBuilder(),
            planner = planner,
            factDao = FakeFactDao(),
            skillDao = skillDao,
            appContext = MockContext(),
        )

        val result = orchestrator.executeGoal(
            goal = "go home",
            serviceContext = object : FakeToolServiceContext() {
                override suspend fun goHome(): Boolean = true
            },
        )

        assertEquals("Completed from cached skill", result)
        assertEquals(7, skillDao.incrementedSkillId)
    }

    @Test
    fun `cached skill failure falls back to live planning without recursive skill lookup`() = runBlocking {
        val skillDao = FakeSkillDao()
        val planner = FallbackPlanner()
        val orchestrator = AgentOrchestrator(
            engine = GenieEngine(),
            toolRegistry = ToolRegistry(),
            promptBuilder = PromptBuilder(),
            planner = planner,
            factDao = FakeFactDao(),
            skillDao = skillDao,
            appContext = MockContext(),
        )

        val result = orchestrator.executeGoal(
            goal = "open settings",
            serviceContext = object : FakeToolServiceContext() {
                override suspend fun clickElement(target: String): Boolean = false
            },
        )

        assertEquals("Recovered via live planning", result)
        assertEquals(1, planner.findSkillMatchCalls)
        assertEquals(1, planner.planCalls)
        assertNull(skillDao.incrementedSkillId)
    }

    private class FallbackPlanner : GeniePlanner {
        var findSkillMatchCalls = 0
        var planCalls = 0

        override suspend fun findSkillMatch(goal: String): SkillMatch {
            findSkillMatchCalls++
            return SkillMatch.Stored(
                skillId = 11,
                goalPattern = goal,
                steps = listOf(Decision.Act("click", mapOf("target" to "Settings"))),
            )
        }

        override suspend fun plan(prompt: String, imagePngBytes: List<ByteArray>): PlanResult {
            planCalls++
            return PlanResult.Success(Decision.Finish("Recovered via live planning"))
        }
    }

    private open class FakeToolServiceContext : ToolServiceContext {
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
    }

    private class FakeFactDao : FactDao {
        override suspend fun insert(fact: UserFact): Long = 0L
        override suspend fun update(fact: UserFact) = Unit
        override suspend fun delete(fact: UserFact) = Unit
        override suspend fun getFactByKey(key: String): UserFact? = null
        override suspend fun searchFacts(query: String): List<UserFact> = emptyList()
        override fun getAllFacts(): Flow<List<UserFact>> = flowOf(emptyList())
        override suspend fun getAllFactsSnapshot(): List<UserFact> = emptyList()
    }

    private class FakeSkillDao : SkillDao {
        var incrementedSkillId: Int? = null

        override suspend fun insert(skill: Skill): Long = 0L
        override suspend fun update(skill: Skill) = Unit
        override suspend fun delete(skill: Skill) = Unit
        override suspend fun findMatchingSkills(query: String): List<Skill> = emptyList()
        override suspend fun getAllSkills(): List<Skill> = emptyList()
        override suspend fun incrementSuccessCount(skillId: Int) {
            incrementedSkillId = skillId
        }
    }
}
