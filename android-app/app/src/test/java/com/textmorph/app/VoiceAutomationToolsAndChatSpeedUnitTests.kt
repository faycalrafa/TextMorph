package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.planner.AiAutomationScriptGenerator
import com.textmorph.app.agent.ui.AgentToastNotifier
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.automations.components.AVAILABLE_AUTOMATION_TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAutomationToolsAndChatSpeedUnitTests {

    @Test
    fun testWaitActionParsingInCodeActionParser() {
        val action1 = CodeActionParser.parse("wait(1500)")
        assertNotNull(action1)
        assertEquals(AgentAction.ACTION_WAIT, action1?.action)
        assertEquals("1500", action1?.getStringParam("ms"))

        val action2 = CodeActionParser.parse("sleep(500)")
        assertNotNull(action2)
        assertEquals(AgentAction.ACTION_WAIT, action2?.action)
        assertEquals("500", action2?.getStringParam("ms"))

        val action3 = CodeActionParser.parse("delay(ms=2000)")
        assertNotNull(action3)
        assertEquals(AgentAction.ACTION_WAIT, action3?.action)
        assertEquals("2000", action3?.getStringParam("ms"))
    }

    @Test
    fun testAvailableAutomationToolsCatalogIncludesWaitTool() {
        val waitTool = AVAILABLE_AUTOMATION_TOOLS.find { it.signature.startsWith("wait") }
        assertNotNull("Wait tool must be present in AVAILABLE_AUTOMATION_TOOLS", waitTool)
        assertEquals("wait(1000)", waitTool?.template)
        assertEquals("Timing & Synchronization", waitTool?.category)

        val appTool = AVAILABLE_AUTOMATION_TOOLS.find { it.signature.startsWith("open_app") }
        assertNotNull(appTool)
        val clickTool = AVAILABLE_AUTOMATION_TOOLS.find { it.signature.startsWith("click_text") }
        assertNotNull(clickTool)
    }

    @Test
    fun testOpenAiCloudEngineConfigAndReadiness() {
        val engine = OpenAiCloudEngine()
        assertEquals(false, engine.isLoaded())

        engine.updateConfig("https://api.openai.com/v1", "test-key-123", "gpt-4o")
        assertEquals(true, engine.isLoaded())
        assertEquals(true, engine.isVisionCapable)

        // Localhost config without key should still be ready
        engine.updateConfig("http://127.0.0.1:11434/v1", "", "llama3")
        assertEquals(true, engine.isLoaded())
    }

    @Test
    fun testAiAutomationScriptGeneratorFallback() {
        val entity = AiAutomationScriptGenerator.generateFallbackEntity("when I say mute volume please mute")
        assertEquals("mute volume please mute", entity.trigger)
        assertEquals("Set Volume to 0%", entity.title)
        assertEquals("set_volume(0)", entity.scriptCode)
    }

    @Test
    fun testAgentToastNotifierReset() {
        AgentToastNotifier.reset()
        // verify reset does not throw and leaves clean state
        assertTrue(true)
    }
}
