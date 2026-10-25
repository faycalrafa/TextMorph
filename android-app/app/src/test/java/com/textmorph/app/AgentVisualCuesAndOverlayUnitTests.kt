package com.textmorph.app

import androidx.compose.ui.graphics.Color
import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentStepStatus
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.ui.AgentVisualCues
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.theme.DangerRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentVisualCuesAndOverlayUnitTests {

    @Before
    fun setUp() {
        TaskExecutionCoordinator.reset()
    }

    @Test
    fun testActionVisualCueMappingForAllSteps() {
        val actionsToTest = listOf(
            AgentAction.ACTION_OPEN_APP,
            AgentAction.ACTION_LAUNCH_PACKAGE,
            AgentAction.ACTION_OPEN_URL,
            AgentAction.ACTION_READ_SCREEN,
            AgentAction.ACTION_CLICK_TEXT,
            AgentAction.ACTION_CLICK_AT,
            AgentAction.ACTION_TYPE_TEXT,
            AgentAction.ACTION_PRESS_ENTER,
            AgentAction.ACTION_SCROLL,
            AgentAction.ACTION_SWIPE,
            AgentAction.ACTION_PRESS_BACK,
            AgentAction.ACTION_PRESS_HOME,
            AgentAction.ACTION_OPEN_NOTIFICATIONS,
            AgentAction.ACTION_MAKE_CALL,
            AgentAction.ACTION_SEND_SMS,
            AgentAction.ACTION_SEND_EMAIL,
            AgentAction.ACTION_SEARCH_CONTACT,
            AgentAction.ACTION_SET_ALARM,
            AgentAction.ACTION_SET_TIMER,
            AgentAction.ACTION_SET_VOLUME,
            AgentAction.ACTION_SET_BRIGHTNESS,
            "wait",
            AgentAction.ACTION_DONE,
            "interrupted",
            "custom_device_tool"
        )

        for (action in actionsToTest) {
            val cue = AgentVisualCues.resolve(action)
            assertNotNull("Visual cue for $action should not be null", cue)
            assertTrue("Visual cue label for $action should not be blank", cue.label.isNotBlank())
            assertNotNull("Visual cue color for $action should not be null", cue.color)
            assertTrue("Visual cue must have either an icon or emoji", cue.icon != null || cue.emoji != null)
        }
    }

    @Test
    fun testThinkingVisualCueEmoji() {
        val thinkingCue = AgentVisualCues.resolve("thinking", isThinking = true)
        assertEquals("🤔", thinkingCue.emoji)
        assertTrue(thinkingCue.label.contains("Thinking") || thinkingCue.label.contains("Analyzing"))

        val planCue = AgentVisualCues.resolve("plan", isThinking = false)
        assertEquals("🤔", planCue.emoji)
    }

    @Test
    fun testAgentTaskStepSerialization() {
        val step1 = AgentTaskStep(
            stepIndex = 1,
            actionType = AgentAction.ACTION_OPEN_APP,
            title = "Opening YouTube",
            detail = "Package launch",
            status = AgentStepStatus.COMPLETED
        )
        val step2 = AgentTaskStep(
            stepIndex = 2,
            actionType = AgentAction.ACTION_CLICK_TEXT,
            title = "Tapping Search",
            detail = "Target element",
            status = AgentStepStatus.RUNNING
        )

        val serialized = AgentTaskStep.serializeList(listOf(step1, step2))
        assertTrue(serialized.contains("Opening YouTube"))
        assertTrue(serialized.contains("Tapping Search"))

        val deserialized = AgentTaskStep.deserializeList(serialized)
        assertEquals(2, deserialized.size)
        assertEquals("Opening YouTube", deserialized[0].title)
        assertEquals(AgentStepStatus.COMPLETED, deserialized[0].status)
        assertEquals("Tapping Search", deserialized[1].title)
        assertEquals(AgentStepStatus.RUNNING, deserialized[1].status)
    }

    @Test
    fun testTaskExecutionCoordinatorStepTransitions() {
        TaskExecutionCoordinator.startTask("Open YouTube and search cats")
        val initialState = TaskExecutionCoordinator.state.value
        assertTrue(initialState.isRunning)
        assertTrue(initialState.isThinking)
        assertEquals("Open YouTube and search cats", initialState.goal)

        TaskExecutionCoordinator.beginStep(1, AgentAction.ACTION_OPEN_APP, "Opening YouTube")
        val step1State = TaskExecutionCoordinator.state.value
        assertFalse(step1State.isThinking)
        assertEquals(1, step1State.stepsHistory.size)
        assertEquals("Opening YouTube", step1State.currentStep?.title)
        assertEquals(AgentStepStatus.RUNNING, step1State.currentStep?.status)

        TaskExecutionCoordinator.completeCurrentStep(success = true)
        val completedStep1State = TaskExecutionCoordinator.state.value
        assertEquals(AgentStepStatus.COMPLETED, completedStep1State.stepsHistory[0].status)

        TaskExecutionCoordinator.beginStep(2, AgentAction.ACTION_CLICK_TEXT, "Tapping Search")
        val step2State = TaskExecutionCoordinator.state.value
        assertEquals(2, step2State.stepsHistory.size)
        assertEquals(AgentStepStatus.COMPLETED, step2State.stepsHistory[0].status)
        assertEquals(AgentStepStatus.RUNNING, step2State.stepsHistory[1].status)

        TaskExecutionCoordinator.finishTask("Task completed successfully.", success = true)
        val finishedState = TaskExecutionCoordinator.state.value
        assertFalse(finishedState.isRunning)
        assertEquals(AgentStepStatus.COMPLETED, finishedState.stepsHistory[1].status)
        assertEquals("Task completed successfully.", finishedState.summary)
    }

    @Test
    fun testTaskExecutionCoordinatorInterruption() {
        var listenerCalled = false
        val listener = { listenerCalled = true }
        TaskExecutionCoordinator.addInterruptListener(listener)

        TaskExecutionCoordinator.startTask("Automate navigation")
        TaskExecutionCoordinator.beginStep(1, AgentAction.ACTION_READ_SCREEN, "Viewing screen")

        TaskExecutionCoordinator.interrupt()

        val interruptedState = TaskExecutionCoordinator.state.value
        assertFalse(interruptedState.isRunning)
        assertTrue(interruptedState.isInterrupted)
        assertEquals(AgentStepStatus.INTERRUPTED, interruptedState.stepsHistory[0].status)
        assertTrue(listenerCalled)

        TaskExecutionCoordinator.removeInterruptListener(listener)
    }

    @Test
    fun testChatMessageEntityWithActionSteps() {
        val step = AgentTaskStep(
            stepIndex = 1,
            actionType = AgentAction.ACTION_OPEN_APP,
            title = "Opening Settings",
            status = AgentStepStatus.COMPLETED
        )
        val serializedSteps = AgentTaskStep.serializeList(listOf(step))

        val entity = ChatMessageEntity(
            sessionId = "s1",
            role = "assistant",
            content = "Completed opening settings",
            actionPayloadJson = "open_app",
            actionStatus = "success",
            actionStepsJson = serializedSteps
        )

        val loadedSteps = entity.getActionSteps()
        assertEquals(1, loadedSteps.size)
        assertEquals("Opening Settings", loadedSteps[0].title)
        assertEquals(AgentStepStatus.COMPLETED, loadedSteps[0].status)
    }

    @Test
    fun testProperDangerRedThemeColors() {
        assertEquals(Color(0xFFDC2626), DangerRed)
    }
}
