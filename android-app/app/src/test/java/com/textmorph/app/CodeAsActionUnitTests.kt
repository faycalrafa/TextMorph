package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Code-as-Action execution paradigm, dual-syntax parsing,
 * parameter extraction, aliases, and AgentToolRegistry catalog.
 */
class CodeAsActionUnitTests {

    @Test
    fun testParsePositionalCallCommand() {
        val input = "call(\"nouh\")"
        val parsed = CodeActionParser.parse(input)
        assertNotNull(parsed)
        assertEquals("make_call", parsed?.action)
        assertEquals("nouh", parsed?.getStringParam("contact_name"))
    }

    @Test
    fun testParsePositionalOpenAppCommand() {
        val input = "open_app(\"YouTube\")"
        val parsed = CodeActionParser.parse(input)
        assertNotNull(parsed)
        assertEquals("open_app", parsed?.action)
        assertEquals("YouTube", parsed?.getStringParam("name"))
    }

    @Test
    fun testParsePositionalOpenUrlCommand() {
        val input = "open_url(\"www.google.com\")"
        val parsed = CodeActionParser.parse(input)
        assertNotNull(parsed)
        assertEquals("open_url", parsed?.action)
        assertEquals("www.google.com", parsed?.getStringParam("url"))
    }

    @Test
    fun testParsePositionalClickAtCoordinates() {
        val input = "click_at(540, 960)"
        val parsed = CodeActionParser.parse(input)
        assertNotNull(parsed)
        assertEquals("click_at", parsed?.action)
        assertEquals(540, parsed?.getIntParam("x"))
        assertEquals(960, parsed?.getIntParam("y"))
    }

    @Test
    fun testParseNumericLevelCommands() {
        val brightness = CodeActionParser.parse("set_brightness(75)")
        assertNotNull(brightness)
        assertEquals("set_brightness", brightness?.action)
        assertEquals(75, brightness?.getIntParam("level"))

        val volume = CodeActionParser.parse("set_volume(80)")
        assertNotNull(volume)
        assertEquals("set_volume", volume?.action)
        assertEquals(80, volume?.getIntParam("level"))
    }

    @Test
    fun testParseNamedParameters() {
        val input = "send_sms(recipient=\"Mom\", message=\"I am on my way!\")"
        val parsed = CodeActionParser.parse(input)
        assertNotNull(parsed)
        assertEquals("send_sms", parsed?.action)
        assertEquals("Mom", parsed?.getStringParam("recipient"))
        assertEquals("I am on my way!", parsed?.getStringParam("message"))
    }

    @Test
    fun testParseMixedWithCommasInsideQuotes() {
        val input = "type_text(\"Hello, world! How are you?\")"
        val parsed = CodeActionParser.parse(input)
        assertNotNull(parsed)
        assertEquals("type_text", parsed?.action)
        assertEquals("Hello, world! How are you?", parsed?.getStringParam("text"))
    }

    @Test
    fun testAliasesResolution() {
        // sms -> send_sms
        val sms = CodeActionParser.parse("sms(\"12345\", \"hello\")")
        assertNotNull(sms)
        assertEquals("send_sms", sms?.action)
        assertEquals("12345", sms?.getStringParam("recipient"))
        assertEquals("hello", sms?.getStringParam("message"))

        // browse -> open_url
        val browse = CodeActionParser.parse("browse(\"https://android.com\")")
        assertNotNull(browse)
        assertEquals("open_url", browse?.action)
        assertEquals("https://android.com", browse?.getStringParam("url"))

        // click -> click_text
        val click = CodeActionParser.parse("click(\"Submit\")")
        assertNotNull(click)
        assertEquals("click_text", click?.action)
        assertEquals("Submit", click?.getStringParam("text"))

        // tap with coords -> click_at
        val tap = CodeActionParser.parse("tap(300, 450)")
        assertNotNull(tap)
        assertEquals("click_at", tap?.action)
        assertEquals(300, tap?.getIntParam("x"))
        assertEquals(450, tap?.getIntParam("y"))

        // no-arg actions
        val enter = CodeActionParser.parse("enter()")
        assertNotNull(enter)
        assertEquals("press_enter", enter?.action)

        val back = CodeActionParser.parse("back()")
        assertNotNull(back)
        assertEquals("press_back", back?.action)

        val home = CodeActionParser.parse("home()")
        assertNotNull(home)
        assertEquals("press_home", home?.action)

        // done -> AgentAction.ACTION_DONE ("done")
        val done = CodeActionParser.parse("done(\"Finished successfully.\")")
        assertNotNull(done)
        assertEquals(AgentAction.ACTION_DONE, done?.action)
        assertEquals("Finished successfully.", done?.getStringParam("summary"))
    }

    @Test
    fun testMarkdownCodeFencesAndSurroundingConversationalText() {
        val fenced = """
            I am going to open YouTube for you right now.
            ```cmd
            open_app("YouTube")
            ```
            Hope this helps!
        """.trimIndent()

        val parsed = CodeActionParser.parse(fenced)
        assertNotNull(parsed)
        assertEquals("open_app", parsed?.action)
        assertEquals("YouTube", parsed?.getStringParam("name"))
    }

    @Test
    fun testCodeActionParsing() {
        val codeAction = "open_app(\"Camera\")"
        val parsedCode = CodeActionParser.parse(codeAction)
        assertNotNull(parsedCode)
        assertEquals("open_app", parsedCode?.action)
        assertEquals("Camera", parsedCode?.getStringParam("name"))
    }

    @Test
    fun testStripActionPayloadRemovesCodeAction() {
        val codeText = "I will dial Nouh now.\ncall(\"nouh\")"
        val strippedCode = CodeActionParser.stripActionPayload(codeText)
        assertEquals("I will dial Nouh now.", strippedCode)
        assertFalse(strippedCode.contains("call(\"nouh\")"))
    }

    @Test
    fun testAgentToolRegistryCatalog() {
        val tools = AgentToolRegistry.getAllTools()
        assertTrue("Registry should register at least 20 tools", tools.size >= 20)

        // Test Category filtering
        val commTools = AgentToolRegistry.getToolsByCategory(AgentToolCategory.COMMUNICATION)
        assertTrue(commTools.any { it.id == "make_call" })
        assertTrue(commTools.any { it.id == "send_sms" })

        val screenTools = AgentToolRegistry.getToolsByCategory(AgentToolCategory.SCREEN_AUTOMATION)
        assertTrue(screenTools.any { it.id == "click_text" })
        assertTrue(screenTools.any { it.id == "click_at" })
        assertTrue(screenTools.any { it.id == "type_text" })

        // Find by name
        val openApp = AgentToolRegistry.findTool("open_app")
        assertNotNull(openApp)
        assertEquals("open_app", openApp?.id)
        assertEquals("open_app(\"YouTube\")", openApp?.codeSignature)

        // Find by alias
        val callAlias = AgentToolRegistry.findTool("call")
        assertNotNull(callAlias)
        assertEquals("make_call", callAlias?.id)
    }

    @Test
    fun testParseArgumentTokensMalformedParenthesesDoesNotHangOrOOM() {
        // Cases that previously could trigger infinite loops:
        val tokens1 = com.textmorph.app.agent.parser.CodeActionMapper.parseArgumentTokens(")")
        assertTrue(tokens1.isEmpty())

        val tokens2 = com.textmorph.app.agent.parser.CodeActionMapper.parseArgumentTokens("foo, bar)")
        assertEquals(2, tokens2.size)

        val tokens3 = com.textmorph.app.agent.parser.CodeActionMapper.parseArgumentTokens("   ) ) ) ,,, ")
        assertTrue(tokens3.isEmpty())

        val parsedAction = CodeActionParser.parse("click_text(\"Submit\"))")
        assertNotNull(parsedAction)
        assertEquals("click_text", parsedAction?.action)
        assertEquals("Submit", parsedAction?.getStringParam("text"))
    }

    @Test
    fun testHtmlAndProgrammingCodeBlocksNeverTreatedAsToolCalls() {
        val htmlOutput = """
            Here is the swimming fish animation:
            ```html
            <!DOCTYPE html>
            <html>
            <head>
            <style>
            .fish {
                transform: rotate(45deg);
                background: rgb(255, 0, 0);
            }
            </style>
            </head>
            <body>
            <script>
            function animateFish() {
                window.requestAnimationFrame(animateFish);
            }
            </script>
            </body>
            </html>
            ```
            Enjoy the animation!
        """.trimIndent()

        val parsed = CodeActionParser.parse(htmlOutput)
        assertNull("Code inside ```html block should never be parsed as tool call", parsed)
        assertFalse(CodeActionParser.hasCompleteAction(htmlOutput))

        // Also test rust, php, go
        val phpOutput = "Here is php:\n```php\n<?php rotate(45); ?>\n```"
        assertNull(CodeActionParser.parse(phpOutput))

        val goOutput = "Here is go:\n```go\nfunc rotate(deg int) {}\n```"
        assertNull(CodeActionParser.parse(goOutput))

        val rustOutput = "Here is rust:\n```rust\nfn rotate(deg: f32) {}\n```"
        assertNull(CodeActionParser.parse(rustOutput))

        // Direct CSS transform should be blacklisted even outside code fences
        assertNull(CodeActionParser.parse("transform: rotate(45deg);"))
        assertNull(CodeActionParser.parse("rotate(45deg)"))
    }
}
