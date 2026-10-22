package com.textmorph.app

import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.llm.ModelLoadingProgress
import com.textmorph.app.llm.ModelSessionState
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.LogLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMorphUnitTests {

    @Test
    fun testDefaultSeedPromptsContainsCoreCategories() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        assertNotNull(seeds)
        assertTrue(seeds.size >= 10)

        val hasTextTransform = seeds.any { it.category == PromptCategory.TEXT_TRANSFORM }
        val hasSmartReply = seeds.any { it.category == PromptCategory.SMART_REPLY }
        val hasVision = seeds.any { it.category == PromptCategory.VISION_ACTION }

        assertTrue("Should contain TEXT_TRANSFORM seeds", hasTextTransform)
        assertTrue("Should contain SMART_REPLY seeds", hasSmartReply)
        assertTrue("Should contain VISION_ACTION seeds", hasVision)
    }

    @Test
    fun testSeedPromptFixGrammar() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        val fixGrammar = seeds.firstOrNull { it.id == "seed_fix_grammar" }
        assertNotNull(fixGrammar)
        assertEquals("Fix Grammar", fixGrammar?.title)
        assertFalse(fixGrammar!!.requiresVision)
    }

    @Test
    fun testVersionComparison() {
        fun isVersionNewer(remote: String, current: String): Boolean {
            val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        assertTrue(isVersionNewer("1.0.1", "1.0.0"))
        assertTrue(isVersionNewer("1.1.0", "1.0.9"))
        assertTrue(isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(isVersionNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun testAppSettingsActiveModelUriSerialization() {
        val settings = AppSettings(
            activeModelName = "gemma-4-E2B-it.litertlm",
            activeModelPath = "/sdcard/Download/gemma-4-E2B-it.litertlm",
            activeModelUri = "content://com.android.providers.downloads.documents/document/123"
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(settings)
        val decoded = json.decodeFromString<AppSettings>(encoded)

        assertEquals("gemma-4-E2B-it.litertlm", decoded.activeModelName)
        assertEquals("/sdcard/Download/gemma-4-E2B-it.litertlm", decoded.activeModelPath)
        assertEquals("content://com.android.providers.downloads.documents/document/123", decoded.activeModelUri)
    }

    @Test
    fun testModelSessionStateAndLoadingProgressDefaults() {
        val defaultSession = ModelSessionState()
        assertFalse(defaultSession.isLoaded)
        assertEquals("No Model Loaded", defaultSession.modelName)
        assertEquals("Standby (Simulated)", defaultSession.backend)

        val defaultProgress = ModelLoadingProgress()
        assertFalse(defaultProgress.isLoading)
        assertEquals(0f, defaultProgress.progress, 0.001f)
        assertEquals("Idle", defaultProgress.statusText)
        assertTrue(defaultProgress.logLines.isEmpty())
    }

    @Test
    fun testAppLoggerFormatAndClear() {
        AppLogger.clear()
        assertEquals(0, AppLogger.logsState.value.size)

        AppLogger.i("TestTag", "Test message info")
        AppLogger.w("TestTag", "Test message warn")
        AppLogger.e("TestTag", "Test message error")

        val logs = AppLogger.logsState.value
        assertEquals(3, logs.size)

        val exported = AppLogger.formatForExport("1.4.0")
        assertTrue(exported.contains("=== TextMorph Diagnostic Logs ==="))
        assertTrue(exported.contains("App Version: 1.4.0"))
        assertTrue(exported.contains("Test message info"))
        assertTrue(exported.contains("Test message error"))

        AppLogger.clear()
        assertEquals(0, AppLogger.logsState.value.size)
    }

    @Test
    fun testTokenSanitizationPreservesSpacesAndRemovesControlTokens() {
        fun sanitize(text: String): String {
            var clean = text
            clean = clean.replace(Regex("(?s)<think>.*?</think>"), "")
            clean = clean.replace("<think>", "").replace("</think>", "")
            clean = clean.replace("<end_of_turn>", "")
            clean = clean.replace("<start_of_turn>", "")
            clean = clean.replace("<eos>", "")
            clean = clean.replace("<bos>", "")
            return clean
        }

        val chunks = listOf("Hello.", " How", " can", " I", " assist", " you", "?", "<end_of_turn>")
        val result = chunks.map { sanitize(it) }.joinToString("")
        assertEquals("Hello. How can I assist you?", result)
    }

    @Test
    fun testTokenSanitizationRemovesThinkingTags() {
        fun sanitize(text: String): String {
            var clean = text
            clean = clean.replace(Regex("(?s)<think>.*?</think>"), "")
            clean = clean.replace("<think>", "").replace("</think>", "")
            clean = clean.replace("<end_of_turn>", "")
            clean = clean.replace("<start_of_turn>", "")
            clean = clean.replace("<eos>", "")
            clean = clean.replace("<bos>", "")
            return clean
        }

        val chunk1 = "<think>Let me reason</think>Direct answer"
        assertEquals("Direct answer", sanitize(chunk1))

        val chunk2 = "<start_of_turn>model\nHello world<end_of_turn>"
        assertEquals("model\nHello world", sanitize(chunk2))
    }

    @Test
    fun testCatalogModelsHaveValidDownloadUrlsAndMetadata() {
        val e2b = com.textmorph.app.data.repository.ModelStorageRepository.CATALOG_E2B
        val e4b = com.textmorph.app.data.repository.ModelStorageRepository.CATALOG_E4B

        assertNotNull(e2b.downloadUrl)
        assertTrue(e2b.downloadUrl?.startsWith("https://huggingface.co/") == true)
        assertTrue(e2b.downloadUrl?.endsWith("gemma-4-E2B-it.litertlm") == true)
        assertTrue(e2b.sizeBytes > 2_000_000_000L)
        assertTrue(e2b.isVisionCapable)

        assertNotNull(e4b.downloadUrl)
        assertTrue(e4b.downloadUrl?.startsWith("https://huggingface.co/") == true)
        assertTrue(e4b.downloadUrl?.endsWith("gemma-4-E4B-it.litertlm") == true)
        assertTrue(e4b.sizeBytes > 4_000_000_000L)
        assertTrue(e4b.isVisionCapable)
    }

    @Test
    fun testDownloadWorkerKeysAndConstants() {
        assertEquals("model_id", com.textmorph.app.worker.DownloadWorker.KEY_MODEL_ID)
        assertEquals("model_name", com.textmorph.app.worker.DownloadWorker.KEY_MODEL_NAME)
        assertEquals("download_url", com.textmorph.app.worker.DownloadWorker.KEY_DOWNLOAD_URL)
        assertEquals("progress_percent", com.textmorph.app.worker.DownloadWorker.KEY_PROGRESS_PERCENT)
        assertEquals("received_bytes", com.textmorph.app.worker.DownloadWorker.KEY_RECEIVED_BYTES)
        assertEquals("download_rate", com.textmorph.app.worker.DownloadWorker.KEY_DOWNLOAD_RATE)
        assertEquals("remaining_ms", com.textmorph.app.worker.DownloadWorker.KEY_REMAINING_MS)
    }

    @Test
    fun testSmartReplyCleaningAndLineExtraction() {
        val rawLlmOutput = """
            1. Sounds great, see you there!
            - Let me double-check my schedule first.
            • Sorry, I won't be able to make it today.
        """.trimIndent()

        val lines = rawLlmOutput.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").removePrefix("1.").removePrefix("2.").removePrefix("3.").trim().trim('"', '\'') }
            .filter { it.length in 2..120 }
            .take(3)

        assertEquals(3, lines.size)
        assertEquals("Sounds great, see you there!", lines[0])
        assertEquals("Let me double-check my schedule first.", lines[1])
        assertEquals("Sorry, I won't be able to make it today.", lines[2])
    }

    @Test
    fun testPromptEntityContextActionSerialization() {
        val prompt = PromptEntity(
            id = "test_ocr_action",
            title = "Extract Text",
            systemInstruction = "Extract OCR",
            contextAction = com.textmorph.app.data.model.ContextAction.OCR,
            requiresVision = true
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(prompt)
        val decoded = json.decodeFromString<PromptEntity>(encoded)

        assertEquals("test_ocr_action", decoded.id)
        assertEquals(com.textmorph.app.data.model.ContextAction.OCR, decoded.contextAction)
        assertTrue(decoded.requiresVision)

        // Test backward compatibility: JSON missing contextAction defaults to NONE
        val legacyJson = """
            {
               "id": "legacy_prompt",
               "title": "Legacy Fix",
               "systemInstruction": "Fix text",
               "requiresVision": false,
               "sortOrder": 0
            }
        """.trimIndent()
        val legacyDecoded = json.decodeFromString<PromptEntity>(legacyJson)
        assertEquals(com.textmorph.app.data.model.ContextAction.NONE, legacyDecoded.contextAction)
    }

    @Test
    fun testSeedPromptsContextActionAssignments() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        val fixGrammar = seeds.first { it.id == "seed_fix_grammar" }
        assertEquals(com.textmorph.app.data.model.ContextAction.NONE, fixGrammar.contextAction)

        val ocrPrompt = seeds.first { it.id == "seed_vision_ocr" }
        assertEquals(com.textmorph.app.data.model.ContextAction.OCR, ocrPrompt.contextAction)

        val describePrompt = seeds.first { it.id == "seed_vision_describe" }
        assertEquals(com.textmorph.app.data.model.ContextAction.SCREENSHOT, describePrompt.contextAction)

        val smartReply = seeds.first { it.id == "seed_reply_agree" }
        assertEquals(com.textmorph.app.data.model.ContextAction.ACCESSIBILITY_TEXT, smartReply.contextAction)
    }

    @Test
    fun testRadialArcAnglesLeftVsRight() {
        val total = 6
        val centerX = 200f
        val centerY = 200f
        val radius = 135f

        // When button is on the Right half: arc curves to the LEFT
        for (i in 0 until total) {
            val t = i.toFloat() / (total - 1)
            val angleDeg = 255f - t * 150f
            val rad = Math.toRadians(angleDeg.toDouble())
            val x = centerX + (radius * kotlin.math.cos(rad)).toFloat()
            val y = centerY + (radius * kotlin.math.sin(rad)).toFloat()

            // All points must be to the left of the button (x <= centerX)
            assertTrue("x should be <= centerX on left-opening arc", x <= centerX + 0.01f)
            if (i == 0) {
                // Top item must have y < centerY
                assertTrue("Top item should have y < centerY", y < centerY)
            }
            if (i == total - 1) {
                // Bottom item (Undo) must have y > centerY
                assertTrue("Bottom item should have y > centerY", y > centerY)
            }
        }

        // When button is on the Left half: arc curves to the RIGHT
        for (i in 0 until total) {
            val t = i.toFloat() / (total - 1)
            val angleDeg = -75f + t * 150f
            val rad = Math.toRadians(angleDeg.toDouble())
            val x = centerX + (radius * kotlin.math.cos(rad)).toFloat()
            val y = centerY + (radius * kotlin.math.sin(rad)).toFloat()

            // All points must be to the right of the button (x >= centerX)
            assertTrue("x should be >= centerX on right-opening arc", x >= centerX - 0.01f)
            if (i == 0) {
                // Top item must have y < centerY
                assertTrue("Top item should have y < centerY", y < centerY)
            }
            if (i == total - 1) {
                // Bottom item (Undo) must have y > centerY
                assertTrue("Bottom item should have y > centerY", y > centerY)
            }
        }
    }

    @Test
    fun testAppSettingsAlwaysShowFloatingToolbarSerialization() {
        val settings = AppSettings(
            alwaysShowFloatingToolbar = true,
            activeModelName = "gemma-4-E2B-it.litertlm"
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(settings)
        val decoded = json.decodeFromString<AppSettings>(encoded)

        assertTrue(decoded.alwaysShowFloatingToolbar)
        assertEquals("gemma-4-E2B-it.litertlm", decoded.activeModelName)
    }

    @Test
    fun testTextOnlyModelHeaderDetectionLogic() {
        val headerWithVision = "LITETFL3...tf_lite_vision_encoder...weights".toByteArray(Charsets.ISO_8859_1)
        val headerTextOnly = "LITETFL3...text_decoder_only...weights".toByteArray(Charsets.ISO_8859_1)

        fun hasVision(buffer: ByteArray): Boolean {
            val text = String(buffer, Charsets.ISO_8859_1)
            return text.contains("tf_lite_vision_encoder")
        }

        assertTrue(hasVision(headerWithVision))
        assertFalse(hasVision(headerTextOnly))
    }

    @Test
    fun testThrottledStreamTokenCollectorLogic() {
        var lastEmitTime = 0L
        val emittedChunks = mutableListOf<String>()
        val sb = StringBuilder()

        fun onToken(token: String, currentTime: Long) {
            sb.append(token)
            if (currentTime - lastEmitTime >= 80L || token.contains("\n") || token.contains(".")) {
                lastEmitTime = currentTime
                emittedChunks.add(sb.toString())
            }
        }

        // Simulate fast tokens arriving every 20ms starting at t=1000ms
        onToken("Hello", 1000L)   // 1000ms -> emitted immediately since 1000 - 0 >= 80
        onToken(" ", 1020L)       // 1020ms -> buffered (20ms delta)
        onToken("world", 1040L)   // 1040ms -> buffered (40ms delta)
        onToken("!", 1060L)       // 1060ms -> buffered (60ms delta)
        onToken("\n", 1070L)      // 1070ms with newline -> emitted immediately
        onToken("How", 1090L)     // 1090ms -> buffered (20ms delta from 1070ms)
        onToken(" are", 1160L)    // 1160ms (90ms delta >= 80ms) -> emitted

        assertTrue(emittedChunks.isNotEmpty())
        assertTrue(emittedChunks.contains("Hello"))
        assertTrue(emittedChunks.any { it.contains("Hello world!\n") })
    }

    @Test
    fun testRadialSlotsTop5PlusMorePromptsAndUndo() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        val topPrompts = seeds.take(5)
        assertEquals(5, topPrompts.size)

        val extendedPrompts = seeds.drop(5)
        assertTrue(extendedPrompts.isNotEmpty())
    }

    @Test
    fun testRadialActionItemsExecuteCommandIsSlotZero() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        val topPrompts = seeds.take(5).map { com.textmorph.app.service.overlay.RadialActionItem.PromptAction(it) }
        val radialItems = listOf(com.textmorph.app.service.overlay.RadialActionItem.DirectExecuteAction) + topPrompts + listOf(
            com.textmorph.app.service.overlay.RadialActionItem.MorePromptsAction,
            com.textmorph.app.service.overlay.RadialActionItem.UndoAction
        )

        assertEquals(8, radialItems.size)
        assertTrue(radialItems.first() is com.textmorph.app.service.overlay.RadialActionItem.DirectExecuteAction)
        assertTrue(radialItems[1] is com.textmorph.app.service.overlay.RadialActionItem.PromptAction)
        assertTrue(radialItems[6] is com.textmorph.app.service.overlay.RadialActionItem.MorePromptsAction)
        assertTrue(radialItems[7] is com.textmorph.app.service.overlay.RadialActionItem.UndoAction)
    }

    @Test
    fun testCloudEngineModelsEndpointUrlResolution() {
        fun resolveModelsEndpoint(rawBaseUrl: String): String {
            val cleanBase = rawBaseUrl.trim().trimEnd('/')
            return if (cleanBase.endsWith("/models")) cleanBase else "$cleanBase/models"
        }

        assertEquals("https://api.openai.com/v1/models", resolveModelsEndpoint("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/models", resolveModelsEndpoint("https://api.openai.com/v1/"))
        assertEquals("https://openrouter.ai/api/v1/models", resolveModelsEndpoint("https://openrouter.ai/api/v1"))
        assertEquals("https://openrouter.ai/api/v1/models", resolveModelsEndpoint("https://openrouter.ai/api/v1/models"))
        assertEquals("http://127.0.0.1:8080/models", resolveModelsEndpoint("http://127.0.0.1:8080"))
    }

    @Test
    fun testUpdateDownloadProgressThrottlingLogic() {
        var lastPercent = -1
        var lastEmitTime = 0L
        val emittedProgress = mutableListOf<Int>()

        fun onProgressUpdate(currentPercent: Int, currentTime: Long) {
            if ((currentPercent >= lastPercent + 2 && currentTime - lastEmitTime >= 150L) || currentPercent == 100 || (lastPercent == -1 && currentPercent > 0)) {
                lastPercent = currentPercent
                lastEmitTime = currentTime
                emittedProgress.add(currentPercent)
            }
        }

        onProgressUpdate(1, 1000L)   // First emission
        onProgressUpdate(2, 1050L)   // Throttled (< 150ms delta)
        onProgressUpdate(3, 1100L)   // Throttled (< 150ms delta)
        onProgressUpdate(4, 1180L)   // Emitted (>= 150ms and >= 2% delta from 1%)
        onProgressUpdate(5, 1200L)   // Throttled
        onProgressUpdate(100, 1250L) // 100% always emitted

        assertTrue(emittedProgress.contains(1))
        assertTrue(emittedProgress.contains(4))
        assertTrue(emittedProgress.contains(100))
        assertFalse(emittedProgress.contains(2))
        assertFalse(emittedProgress.contains(3))
    }
}
