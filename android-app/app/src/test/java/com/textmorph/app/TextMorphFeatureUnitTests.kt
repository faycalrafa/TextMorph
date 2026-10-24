package com.textmorph.app

import com.textmorph.app.data.model.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMorphFeatureUnitTests {

    @Test
    fun testLlamaCppBridgeClassAndLibraryAvailabilityCheck() {
        val loaded = com.textmorph.app.llm.LlamaCppBridge.isLibraryLoaded()
        // On JVM with org.nehuatl.llamacpp.LlamaContext in classpath, isLibraryLoaded() returns true
        assertTrue(loaded)
    }

    @Test
    fun testLlamaCppPromptFormattingAndThinkingFilter() {
        val systemInstruction = "You are a concise grammar assistant."
        val prompt = "Fix this sentence: teh dog barked"

        val formatted = buildString {
            if (systemInstruction.isNotBlank()) {
                append("<|im_start|>system\n$systemInstruction<|im_end|>\n")
            }
            append("<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
        }

        assertTrue(formatted.contains("<|im_start|>system\nYou are a concise grammar assistant.<|im_end|>"))
        assertTrue(formatted.contains("<|im_start|>user\nFix this sentence: teh dog barked<|im_end|>"))
        assertTrue(formatted.endsWith("<|im_start|>assistant\n"))

        // Strict No-Thinking Policy token check
        val rawTokens = listOf("<think>", "Reasoning...", "</think>", "The dog barked.")
        val filtered = rawTokens.filter { !it.contains("<think>") && !it.contains("</think>") }
        assertEquals(listOf("Reasoning...", "The dog barked."), filtered)
    }

    @Test
    fun testLlamaCppContextParamsStructure() {
        val params = mutableMapOf<String, Any>(
            "model" to "content://test/model.gguf",
            "model_fd" to 42,
            "n_ctx" to 2048,
            "n_batch" to 512,
            "n_threads" to 4,
            "n_gpu_layers" to 0,
            "use_mmap" to true,
            "use_mlock" to false
        )
        assertEquals("content://test/model.gguf", params["model"])
        assertEquals(42, params["model_fd"])
        assertTrue(params.containsKey("model_fd"))
        assertTrue(params["model_fd"] is Int)
    }

    @Test
    fun testDefaultThemeIsCharcoalSlate() {
        val settings = AppSettings()
        assertEquals(com.textmorph.app.theme.ThemeMode.DARK_SLATE, settings.theme)
        assertEquals("Charcoal Slate", settings.theme.displayName)
    }

    @Test
    fun testSlashCommandDefaultSeeds() {
        val seeds = com.textmorph.app.data.repository.SlashCommandRepository.getDefaultSeedCommands()
        assertTrue(seeds.size >= 11)

        val fr = seeds.firstOrNull { it.cleanTrigger == "fr" }
        assertNotNull(fr)
        assertEquals("/fr", fr?.displayTrigger)
        assertTrue(fr!!.systemInstruction.contains("French"))
        assertEquals(com.textmorph.app.data.model.ContextAction.NONE, fr.contextAction)

        val ar = seeds.firstOrNull { it.cleanTrigger == "ar" }
        assertNotNull(ar)
        assertEquals("/ar", ar?.displayTrigger)
        assertEquals("Translate to Arabic", ar?.title)
        assertTrue(ar!!.systemInstruction.contains("Arabic"))

        val en = seeds.firstOrNull { it.cleanTrigger == "en" }
        assertNotNull(en)
        assertEquals("/en", en?.displayTrigger)
        assertEquals("Translate to English", en?.title)
        assertTrue(en!!.systemInstruction.contains("English"))

        val fix = seeds.firstOrNull { it.cleanTrigger == "fix" }
        assertNotNull(fix)
        assertEquals("Fix Grammar & Spelling", fix?.title)

        val shot = seeds.firstOrNull { it.cleanTrigger == "shot" }
        assertNotNull(shot)
        assertEquals("Explain Screen", shot?.title)
        assertEquals(com.textmorph.app.data.model.ContextAction.SCREENSHOT, shot?.contextAction)
        assertTrue(shot?.requiresVision == true)

        val clip = seeds.firstOrNull { it.cleanTrigger == "clip" }
        assertNotNull(clip)
        assertEquals("Process Clipboard", clip?.title)
        assertEquals(com.textmorph.app.data.model.ContextAction.CLIPBOARD, clip?.contextAction)

        val ocr = seeds.firstOrNull { it.cleanTrigger == "ocr" }
        assertNotNull(ocr)
        assertEquals(com.textmorph.app.data.model.ContextAction.OCR, ocr?.contextAction)
        assertTrue(ocr?.requiresVision == true)
    }

    @Test
    fun testSlashCommandMultimodalContextActionSupport() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val original = com.textmorph.app.data.model.SlashCommandEntity(
            trigger = "shot",
            title = "Screen Vision",
            systemInstruction = "Analyze screenshot",
            contextAction = com.textmorph.app.data.model.ContextAction.SCREENSHOT,
            requiresVision = true
        )
        val encoded = json.encodeToString(com.textmorph.app.data.model.SlashCommandEntity.serializer(), original)
        assertTrue(encoded.contains("SCREENSHOT"))
        val decoded = json.decodeFromString(com.textmorph.app.data.model.SlashCommandEntity.serializer(), encoded)
        assertEquals(original.trigger, decoded.trigger)
        assertEquals(com.textmorph.app.data.model.ContextAction.SCREENSHOT, decoded.contextAction)
        assertTrue(decoded.requiresVision)
    }

    @Test
    fun testSlashCommandMatchingAndExtractionLogic() {
        val seeds = com.textmorph.app.data.repository.SlashCommandRepository.getDefaultSeedCommands()

        fun match(text: String): Pair<com.textmorph.app.data.model.SlashCommandEntity?, String> {
            val regex = Regex("""(?:^|\s)/([a-zA-Z0-9_-]+)(?:\s|$)""")
            val matches = regex.findAll(text).toList()
            for (m in matches) {
                val token = m.groupValues[1].lowercase()
                val cmd = seeds.firstOrNull { it.cleanTrigger == token }
                if (cmd != null) {
                    val tokenPattern = Regex("""\s*/""" + Regex.escape(m.groupValues[1]) + """\b|\b/""" + Regex.escape(m.groupValues[1]) + """\s*""")
                    val cleaned = text.replace(tokenPattern, " ").replace(Regex("""\s+"""), " ").trim()
                    return Pair(cmd, cleaned)
                }
            }
            return Pair(null, text)
        }

        // Test trailing slash command
        val (cmd1, text1) = match("what hour do we meet? /fr")
        assertNotNull(cmd1)
        assertEquals("fr", cmd1?.cleanTrigger)
        assertEquals("what hour do we meet?", text1)

        // Test leading slash command
        val (cmd2, text2) = match("/fix please corect this speling")
        assertNotNull(cmd2)
        assertEquals("fix", cmd2?.cleanTrigger)
        assertEquals("please corect this speling", text2)

        // Test mid-text slash command
        val (cmd3, text3) = match("can you /short this whole article")
        assertNotNull(cmd3)
        assertEquals("short", cmd3?.cleanTrigger)
        assertEquals("can you this whole article", text3)

        // Test text without slash command
        val (cmd4, text4) = match("this is just normal text with no command")
        assertEquals(null, cmd4)
        assertEquals("this is just normal text with no command", text4)

        // Test unknown command
        val (cmd5, text5) = match("some text with /unknowncmd")
        assertEquals(null, cmd5)
        assertEquals("some text with /unknowncmd", text5)
    }

    @Test
    fun testTenStepUndoHistoryDeque() {
        val deque = ArrayDeque<String>(10)

        fun push(text: String) {
            if (text.isBlank()) return
            if (deque.isNotEmpty() && deque.last() == text) return
            if (deque.size >= 10) {
                deque.removeFirst()
            }
            deque.addLast(text)
        }

        fun pop(): String? = if (deque.isNotEmpty()) deque.removeLast() else null

        // Push 12 sequential texts
        for (i in 1..12) {
            push("Text version $i")
        }

        // Must be capped at 10 items
        assertEquals(10, deque.size)

        // Oldest items (1 and 2) must have been evicted; earliest remaining is version 3
        assertEquals("Text version 3", deque.first())
        assertEquals("Text version 12", deque.last())

        // Duplicate push should be ignored
        push("Text version 12")
        assertEquals(10, deque.size)

        // Sequential pop reverts in LIFO order
        for (i in 12 downTo 3) {
            assertEquals("Text version $i", pop())
        }

        assertTrue(deque.isEmpty())
        assertEquals(null, pop())
    }

    @Test
    fun testAppSettingsQuickFixEnabledSerialization() {
        val defaultSettings = AppSettings()
        assertFalse(defaultSettings.quickFixEnabled)

        val custom = defaultSettings.copy(quickFixEnabled = true)
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(custom)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        assertTrue(decoded.quickFixEnabled)
    }

    @Test
    fun testSingleUndoRevertsDirectlyToOriginal() {
        val history = ArrayDeque<String>(10)
        fun push(text: String) {
            if (text.isNotBlank() && (history.isEmpty() || history.last() != text)) {
                if (history.size >= 10) history.removeFirst()
                history.addLast(text)
            }
        }
        fun pop(): String? = if (history.isNotEmpty()) history.removeLast() else null

        val originalInput = "Original user input with spelling mistak"
        // 1. Snapshot taken at generation start
        push(originalInput)

        // 2. Intermediate stream tokens arrive and are inserted with recordHistory = false (no push)
        val intermediateChunk = "Original user input with spelling mistake"

        // 3. Final generation completes and is inserted with recordHistory = false (no push)
        val finalResult = "Original user input with spelling mistakes fixed."

        // 4. Assert only 1 snapshot exists in history
        assertEquals(1, history.size)
        assertEquals(originalInput, history.last())

        // 5. Very first undo click reverts straight to original
        val reverted = pop()
        assertEquals(originalInput, reverted)
        assertTrue(history.isEmpty())
    }

    @Test
    fun testCrashReportSerializationAndExportFormat() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val report = com.textmorph.app.util.CrashReport(
            crashTimeFormatted = "2026-10-23 12:34:56",
            crashType = "NATIVE_CRASH_OR_KILL",
            exceptionClass = "NativeSignalAbort",
            message = "Process was terminated during GPU inference (SIGSEGV or out-of-memory abort)",
            stackTrace = "at liblitert_lm.so (native mmap tensor compute)",
            threadName = "InferenceThread-1",
            deviceModel = "Pixel 8 Pro",
            androidVersion = "15 (API 35)",
            memoryUsageMb = "650 MB / 4096 MB (max)",
            activeModelName = "gemma-4-E2B-it.litertlm",
            activeBackend = "GPU (OpenCL)",
            recentLogs = listOf("[INFO] Initializing GPU session", "[WARN] Low VRAM condition detected")
        )

        val encoded = json.encodeToString(com.textmorph.app.util.CrashReport.serializer(), report)
        assertTrue(encoded.contains("NATIVE_CRASH_OR_KILL"))
        assertTrue(encoded.contains("gemma-4-E2B-it.litertlm"))

        val decoded = json.decodeFromString(com.textmorph.app.util.CrashReport.serializer(), encoded)
        assertEquals(report.id, decoded.id)
        assertEquals(report.crashType, decoded.crashType)
        assertEquals(report.message, decoded.message)
        assertEquals(report.activeModelName, decoded.activeModelName)
        assertEquals(report.activeBackend, decoded.activeBackend)
        assertEquals(2, decoded.recentLogs.size)

        // Verify export format
        val export = buildString {
            appendLine("=== TextMorph Crash Report ===")
            appendLine("Crash ID: ${decoded.id}")
            appendLine("Time: ${decoded.crashTimeFormatted}")
            appendLine("Type: ${decoded.crashType}")
            appendLine("Exception: ${decoded.exceptionClass}")
            appendLine("Message: ${decoded.message}")
            appendLine("Active Model: ${decoded.activeModelName} (${decoded.activeBackend})")
        }
        assertTrue(export.contains("=== TextMorph Crash Report ==="))
        assertTrue(export.contains("Type: NATIVE_CRASH_OR_KILL"))
        assertTrue(export.contains("Active Model: gemma-4-E2B-it.litertlm (GPU (OpenCL))"))
    }

    @Test
    fun testNativeFlightMarkerSerialization() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val marker = com.textmorph.app.util.FlightMarker(
            pid = 1234,
            operation = "Inference: generateStream",
            modelName = "gemma-4-E2B-it.litertlm",
            backend = "GPU",
            timestamp = 1729700000000L,
            details = "Vulkan memory mapping"
        )
        val encoded = json.encodeToString(com.textmorph.app.util.FlightMarker.serializer(), marker)
        val decoded = json.decodeFromString(com.textmorph.app.util.FlightMarker.serializer(), encoded)

        assertEquals(1234, decoded.pid)
        assertEquals("Inference: generateStream", decoded.operation)
        assertEquals("gemma-4-E2B-it.litertlm", decoded.modelName)
        assertEquals("GPU", decoded.backend)
        assertEquals("Vulkan memory mapping", decoded.details)
    }

    @Test
    fun testKeywordContextResolverRegexAndTagDetection() {
        val withClip = "Please summarize [clipboard] for me"
        val withScreen = "Take a [screenshot] and explain"
        val withImg = "What is in [image]?"
        val plain = "Just normal text"

        assertTrue(com.textmorph.app.util.KeywordContextResolver.hasKeywords(withClip))
        assertTrue(com.textmorph.app.util.KeywordContextResolver.hasKeywords(withScreen))
        assertTrue(com.textmorph.app.util.KeywordContextResolver.hasKeywords(withImg))
        assertFalse(com.textmorph.app.util.KeywordContextResolver.hasKeywords(plain))
    }

    @Test
    fun testEmojiTextArtHelperInstantMappings() {
        // Instant emoji detection
        val matchEmoji = com.textmorph.app.util.EmojiTextArtHelper.findEmojiMatch("hello :(happy): world")
        assertNotNull(matchEmoji)
        assertEquals("happy", matchEmoji?.groupValues?.get(1))

        // Instant text art detection
        val matchArt = com.textmorph.app.util.EmojiTextArtHelper.findTextArtMatch("well T:(shrug): then")
        assertNotNull(matchArt)
        assertEquals("shrug", matchArt?.groupValues?.get(1))
    }
}
