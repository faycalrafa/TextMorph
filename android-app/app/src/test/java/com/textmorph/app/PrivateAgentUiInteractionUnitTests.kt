package com.textmorph.app

import android.graphics.Rect
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.ScreenPerceptionMode
import com.textmorph.app.service.overlay.ScreenLinearizer
import com.textmorph.app.service.overlay.UiElementNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Code-as-Action prompts, Tri-modal perception settings,
 * ScreenLinearizer formatting, and PrivateAgent UI interaction parsing.
 */
class PrivateAgentUiInteractionUnitTests {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun testDefaultPromptsAreCodeAsAction() {
        // Verify agent prompt uses pure Code-as-Action
        val agentPrompt = AgentPrompts.getAgentSystemPrompt()
        assertTrue(agentPrompt.contains("single Code-as-Action command"))
        assertTrue(agentPrompt.contains("execute_task("))
        assertTrue(agentPrompt.contains("open_app("))
        assertFalse(agentPrompt.contains("\"action\":"))
        assertFalse(agentPrompt.contains("\"params\":"))

        // Verify task prompt uses pure Code-as-Action
        val taskPrompt = AgentPrompts.getTaskSystemPrompt()
        assertTrue(taskPrompt.contains("single command on a single line"))
        assertTrue(taskPrompt.contains("click_text("))
        assertTrue(taskPrompt.contains("type_text("))
        assertFalse(taskPrompt.contains("Return ONLY a JSON payload"))
        assertFalse(taskPrompt.contains("JSON object"))
        assertFalse(taskPrompt.contains("\"action\":"))
    }

    @Test
    fun testAppSettingsPerceptionModeSerialization() {
        val initialSettings = AppSettings(
            screenPerceptionMode = ScreenPerceptionMode.ACCESSIBILITY_TREE
        )
        val serialized = json.encodeToString(initialSettings)
        assertTrue(serialized.contains("ACCESSIBILITY_TREE"))

        val modifiedSettings = initialSettings.copy(
            screenPerceptionMode = ScreenPerceptionMode.PRIVATE_AGENT
        )
        val modifiedSerialized = json.encodeToString(modifiedSettings)
        assertTrue(modifiedSerialized.contains("PRIVATE_AGENT"))

        val deserialized = json.decodeFromString<AppSettings>(modifiedSerialized)
        assertEquals(ScreenPerceptionMode.PRIVATE_AGENT, deserialized.screenPerceptionMode)
    }

    @Test
    fun testScreenLinearizerSemanticTreeFormat() {
        val nodes = listOf(
            UiElementNode(
                index = 0,
                text = "Search Settings",
                contentDescription = "",
                className = "EditText",
                bounds = Rect(0, 0, 1080, 120),
                isClickable = true,
                isEditable = true,
                isScrollable = false,
                depth = 0
            ),
            UiElementNode(
                index = 1,
                text = "Network & internet",
                contentDescription = "",
                className = "TextView",
                bounds = Rect(0, 150, 1080, 250),
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                depth = 1
            )
        )

        val dump = ScreenLinearizer.formatSemanticTree(nodes, "com.android.settings")
        assertTrue(dump.contains("Current app: com.android.settings"))
        assertTrue(dump.contains("Search Settings"))
        assertTrue(dump.contains("Network & internet"))
        assertTrue(dump.contains("EditText"))
        assertTrue(dump.contains("{clickable, editable}"))
    }

    @Test
    fun testScreenLinearizerPrivateAgentFormatKeywordMatching() {
        val nodes = listOf(
            UiElementNode(
                index = 0,
                text = "Search songs",
                contentDescription = "",
                className = "EditText",
                bounds = Rect(0, 0, 500, 100),
                isClickable = true,
                isEditable = true,
                isScrollable = false,
                depth = 0
            ),
            UiElementNode(
                index = 1,
                text = "Play Bohemian Rhapsody",
                contentDescription = "",
                className = "Button",
                bounds = Rect(0, 120, 500, 200),
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                depth = 0
            )
        )

        val query = "play bohemian"
        val dump = ScreenLinearizer.formatPrivateAgentWay(nodes, query, "com.google.android.apps.youtube.music")
        assertTrue(dump.contains("APP: com.google.android.apps.youtube.music"))
        assertTrue(dump.contains("Play Bohemian Rhapsody"))
        // The matching node should be marked with '*'
        assertTrue(dump.contains("[1]* btn \"Play Bohemian Rhapsody\""))
    }

    @Test
    fun testCodeActionParserSupportsCodeAsAction() {
        // Code-as-action commands
        val codeAction = CodeActionParser.parseAction("click_text(\"Confirm Order\")")
        assertNotNull(codeAction)
        assertEquals("click_text", codeAction?.action)
        assertEquals("Confirm Order", codeAction?.getStringParamWithAliases("text", "label"))

        val typeAction = CodeActionParser.parseAction("type_text(\"best headphones\", hint=\"Search\")")
        assertNotNull(typeAction)
        assertEquals("type_text", typeAction?.action)
        assertEquals("best headphones", typeAction?.getStringParamWithAliases("text", "content"))

        val pressEnter = CodeActionParser.parseAction("press_enter()")
        assertNotNull(pressEnter)
        assertEquals("press_enter", pressEnter?.action)

        val clickAt = CodeActionParser.parseAction("click_at(540, 1200)")
        assertNotNull(clickAt)
        assertEquals("click_at", clickAt?.action)
        assertEquals(540, clickAt?.getIntParam("x"))
        assertEquals(1200, clickAt?.getIntParam("y"))
    }

    @Test
    fun testDefaultScreenPerceptionModeIsPrivateAgent() {
        val defaultSettings = AppSettings()
        assertEquals(ScreenPerceptionMode.PRIVATE_AGENT, defaultSettings.screenPerceptionMode)
    }

    @Test
    fun testBatteryKeywordRetainedInScreenLinearizer() {
        val nodes = listOf(
            UiElementNode(
                index = 0,
                text = "Battery",
                contentDescription = "",
                className = "TextView",
                bounds = Rect(0, 300, 1080, 450),
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                depth = 1
            )
        )
        val dump = ScreenLinearizer.formatPrivateAgentWay(nodes, "open Settings and check battery", "com.android.settings")
        assertTrue("Battery element should be retained in Private Agent dump", dump.contains("Battery"))
        assertTrue("Battery element should be marked with query match *", dump.contains("*"))
    }

    @Test
    fun testExecuteTaskGoalFallback() {
        // When model emits execute_task() without arguments
        val action = CodeActionParser.parseAction("execute_task()")
        assertNotNull(action)
        assertEquals("execute_task", action?.action)
        val goalParam = action?.getStringParam("goal")?.ifEmpty { action.getStringParam("task") } ?: ""
        // If empty, fallback logic will use the user's prompt
        val userPrompt = "open Settings and check battery"
        val effectiveGoal = if (goalParam.isBlank()) userPrompt else goalParam
        assertEquals("open Settings and check battery", effectiveGoal)
    }
}
