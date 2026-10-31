package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.planner.PlanMilestone
import com.textmorph.app.agent.planner.TaskPlanDecomposer
import com.textmorph.app.agent.ui.AgentToastNotifier
import com.textmorph.app.agent.ui.AgentVisualCues
import com.textmorph.app.service.overlay.OverlayDisplayState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite validating:
 * 1. Quick Agent Request plan decomposition into multi-step milestones.
 * 2. Visual cue resolution for the living bubble (👀 eyes for screen inspect, 🤔 strictly for thinking,
 *    ⌨️ for typing, 👆 for tapping, 👂 for listening).
 * 3. OverlayDisplayState transition lifecycle.
 * 4. Step milestone completion verification.
 */
class QuickAgentRequestAndLivingBubbleUnitTests {

    @Before
    fun setUp() {
        AgentToastNotifier.reset()
    }

    @Test
    fun testCompoundGoalDecompositionIntoMilestones() = runBlocking {
        val compoundGoal = "open youtube and search for cookies recipe and pick the first video"
        val milestones = TaskPlanDecomposer.decompose(compoundGoal)

        assertEquals("Should decompose into exactly 3 milestones", 3, milestones.size)
        
        assertEquals(1, milestones[0].index)
        assertEquals("Open YouTube", milestones[0].description)
        assertEquals("YouTube", milestones[0].targetApp)

        assertEquals(2, milestones[1].index)
        assertEquals("Search for cookies recipe", milestones[1].description)

        assertEquals(3, milestones[2].index)
        assertEquals("Pick the first video", milestones[2].description)
    }

    @Test
    fun testCompoundGoalWithAlternateConnectors() = runBlocking {
        val goalThen = "open chrome then search for textmorph"
        val milestonesThen = TaskPlanDecomposer.decompose(goalThen)
        assertEquals(2, milestonesThen.size)
        assertEquals("Open Chrome", milestonesThen[0].description)
        assertEquals("Search for textmorph", milestonesThen[1].description)

        val goalCommaAnd = "open settings, and check battery"
        val milestonesComma = TaskPlanDecomposer.decompose(goalCommaAnd)
        assertEquals(2, milestonesComma.size)
        assertEquals("Open Settings", milestonesComma[0].description)
        assertEquals("Check battery", milestonesComma[1].description)
    }

    @Test
    fun testSingleIntentFallbackMilestone() = runBlocking {
        val simpleGoal = "open YouTube"
        val milestones = TaskPlanDecomposer.decompose(simpleGoal)
        assertEquals(1, milestones.size)
        assertEquals(1, milestones[0].index)
        assertEquals("Open YouTube", milestones[0].description)
    }

    @Test
    fun testMilestoneConfirmationOnScreen() {
        val openYoutubeMilestone = PlanMilestone(
            index = 1,
            description = "Open YouTube",
            targetApp = "YouTube"
        )

        // Confirmed when YouTube package or screen dump mentions YouTube
        assertTrue(
            TaskPlanDecomposer.isMilestoneConfirmed(
                milestone = openYoutubeMilestone,
                screenDump = "Search YouTube, Subscriptions, Library",
                currentPackage = "com.google.android.youtube"
            )
        )

        // Not confirmed on unrelated screen
        assertFalse(
            TaskPlanDecomposer.isMilestoneConfirmed(
                milestone = openYoutubeMilestone,
                screenDump = "Home screen with app icons",
                currentPackage = "com.android.launcher3"
            )
        )

        val searchMilestone = PlanMilestone(
            index = 2,
            description = "Search for cookies recipe"
        )

        // Confirmed when cookies results appear
        assertTrue(
            TaskPlanDecomposer.isMilestoneConfirmed(
                milestone = searchMilestone,
                screenDump = "Best Chocolate Chip Cookies Recipe - 10 mins",
                currentPackage = "com.google.android.youtube"
            )
        )

        // Unconfirmed if query results not visible
        assertFalse(
            TaskPlanDecomposer.isMilestoneConfirmed(
                milestone = searchMilestone,
                screenDump = "Trending Music Videos",
                currentPackage = "com.google.android.youtube"
            )
        )
    }

    @Test
    fun testVisualCueLivingBubbleSpecs() {
        // 1. Listening state displays ear 👂
        val listeningCue = AgentVisualCues.resolve("listening", isThinking = false)
        assertEquals("👂", listeningCue.emoji)
        assertEquals("Listening...", listeningCue.label)

        // 2. Typing state displays keyboard ⌨️
        val typingCue = AgentVisualCues.resolve(AgentAction.ACTION_TYPE_TEXT, isThinking = false)
        assertEquals("⌨️", typingCue.emoji)
        assertEquals("Typing Input", typingCue.label)

        // 3. Tapping state displays finger 👆
        val tapCue = AgentVisualCues.resolve(AgentAction.ACTION_CLICK_TEXT, isThinking = false)
        assertEquals("👆", tapCue.emoji)
        assertEquals("Tapping Element", tapCue.label)

        val clickAtCue = AgentVisualCues.resolve(AgentAction.ACTION_CLICK_AT, isThinking = false)
        assertEquals("👆", clickAtCue.emoji)

        // 4. Reading/inspecting screen displays eyes 👀 and NEVER 🤔
        val screenCue = AgentVisualCues.resolve(AgentAction.ACTION_READ_SCREEN, isThinking = false)
        assertEquals("👀", screenCue.emoji)
        assertFalse("Viewing screen must NOT display thinking emoji", screenCue.emoji == "🤔")

        val inspectCue = AgentVisualCues.resolve("inspect_screen", isThinking = false)
        assertEquals("👀", inspectCue.emoji)

        // 5. Thinking emoji 🤔 ONLY displayed when isThinking == true
        val thinkingCue = AgentVisualCues.resolve("planning", isThinking = true)
        assertEquals("🤔", thinkingCue.emoji)
    }

    @Test
    fun testOverlayDisplayStateLifecycle() {
        val states = OverlayDisplayState.values()
        assertEquals(5, states.size)
        assertTrue(states.contains(OverlayDisplayState.COLLAPSED_BUBBLE))
        assertTrue(states.contains(OverlayDisplayState.EXPANDED_TOOLBAR))
        assertTrue(states.contains(OverlayDisplayState.LISTENING_REQUEST))
        assertTrue(states.contains(OverlayDisplayState.AUTOMATING_ACTION))
        assertTrue(states.contains(OverlayDisplayState.VOICE_ASSIST_SESSION))
    }
}
