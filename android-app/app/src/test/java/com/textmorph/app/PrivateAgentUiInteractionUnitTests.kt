package com.textmorph.app

import android.graphics.Rect
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.model.ActionCommandSyntax
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
 * Unit tests verifying Code-as-Action prompts, JSON toggle, Tri-modal perception settings,
 * ScreenLinearizer formatting, and PrivateAgent UI interaction parsing.
 */
class PrivateAgentUiInteractionUnitTests {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun testDefaultPromptsAreCodeAsAction() {
        // Verify default agent prompt uses Code-as-Action
        val defaultAgentPrompt = AgentPrompts.getAgentSystemPrompt(ActionCommandSyntax.CODE_AS_ACTION)
        assertTrue(defaultAgentPrompt.contains("single Code-as-Action command"))
        assertTrue(defaultAgentPrompt.contains("execute_task("))
        assertTrue(defaultAgentPrompt.contains("open_app("))
        assertFalse(defaultAgentPrompt.contains("\"action\":"))

        // Verify JSON agent prompt includes JSON format
        val jsonAgentPrompt = AgentPrompts.getAgentSystemPrompt(ActionCommandSyntax.JSON_BASED)
        assertTrue(jsonAgentPrompt.contains("\"action\":"))
        assertTrue(jsonAgentPrompt.contains("\"params\":"))

        // Verify task prompts
        val defaultTaskPrompt = AgentPrompts.getTaskSystemPrompt(ActionCommandSyntax.CODE_AS_ACTION)
        assertTrue(defaultTaskPrompt.contains("single command on a single line"))
        assertTrue(defaultTaskPrompt.contains("click_text("))
        assertTrue(defaultTaskPrompt.contains("type_text("))
        assertFalse(defaultTaskPrompt.contains("Return ONLY a JSON payload"))
        assertFalse(defaultTaskPrompt.contains("\"action\":"))

        val jsonTaskPrompt = AgentPrompts.getTaskSystemPrompt(ActionCommandSyntax.JSON_BASED)
        assertTrue(jsonTaskPrompt.contains("JSON object"))
        assertTrue(jsonTaskPrompt.contains("\"action\":"))
    }

    @Test
    fun testAppSettingsPerceptionModeAndSyntaxSerialization() {
        val initialSettings = AppSettings(
            actionCommandSyntax = ActionCommandSyntax.CODE_AS_ACTION,
            screenPerceptionMode = ScreenPerceptionMode.ACCESSIBILITY_TREE
        )
        val serialized = json.encodeToString(initialSettings)
        assertTrue(serialized.contains("CODE_AS_ACTION"))
        assertTrue(serialized.contains("ACCESSIBILITY_TREE"))

        val modifiedSettings = initialSettings.copy(
            actionCommandSyntax = ActionCommandSyntax.JSON_BASED,
            screenPerceptionMode = ScreenPerceptionMode.PRIVATE_AGENT
        )
        val modifiedSerialized = json.encodeToString(modifiedSettings)
        assertTrue(modifiedSerialized.contains("JSON_BASED"))
        assertTrue(modifiedSerialized.contains("PRIVATE_AGENT"))

        val deserialized = json.decodeFromString<AppSettings>(modifiedSerialized)
        assertEquals(ActionCommandSyntax.JSON_BASED, deserialized.actionCommandSyntax)
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
    fun testCodeActionParserSupportsCodeAsActionAndJson() {
        // 1. Code-as-action
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

        // 2. JSON
        val jsonAction = CodeActionParser.parseAction("""{"action": "click_text", "params": {"text": "Submit"}}""")
        assertNotNull(jsonAction)
        assertEquals("click_text", jsonAction?.action)
        assertEquals("Submit", jsonAction?.getStringParam("text"))
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
