package com.textmorph.app

import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.PromptRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMorphOverlayUnitTests {

    @Test
    fun testFloatingToolbarPositionSerializationAndDefaults() {
        val defaultSettings = AppSettings()
        assertEquals(null, defaultSettings.floatingToolbarX)
        assertEquals(null, defaultSettings.floatingToolbarY)

        val customPosSettings = AppSettings(floatingToolbarX = 450, floatingToolbarY = 920)
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(customPosSettings)
        val decoded = json.decodeFromString<AppSettings>(encoded)

        assertEquals(450, decoded.floatingToolbarX)
        assertEquals(920, decoded.floatingToolbarY)
    }

    @Test
    fun testFloatingToolbarViewportClampingLogic() {
        val screenWidth = 1080
        val screenHeight = 2400
        val btnSize = 144 // 48dp on 3.0 density
        val topInset = 120 // 40dp status bar margin
        val bottomInset = 120 // 40dp nav bar margin

        val minX = 0
        val maxX = screenWidth - btnSize
        val minY = topInset
        val maxY = screenHeight - btnSize - bottomInset

        fun clampX(x: Int): Int = x.coerceIn(minX, maxX)
        fun clampY(y: Int): Int = y.coerceIn(minY, maxY)

        // Off-screen left or top clamped
        assertEquals(0, clampX(-50))
        assertEquals(minY, clampY(20))

        // Normal middle untouched
        assertEquals(500, clampX(500))
        assertEquals(1000, clampY(1000))

        // Off-screen right or bottom clamped
        assertEquals(maxX, clampX(1200))
        assertEquals(maxY, clampY(3000))
    }

    @Test
    fun testSmartReplyDelimiterParsing() {
        val rawResponse = "\"Sounds great!\" ||| 'Can we meet at 4 instead?' ||| Sorry, I have a conflict 🙏"
        val parsed = rawResponse.split("|||")
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.isNotBlank() && !it.startsWith("Error") && it.length < 60 }
            .take(3)

        assertEquals(3, parsed.size)
        assertEquals("Sounds great!", parsed[0])
        assertEquals("Can we meet at 4 instead?", parsed[1])
        assertEquals("Sorry, I have a conflict 🙏", parsed[2])
    }

    @Test
    fun testChatMessageIncomingExtraction() {
        val messages = listOf(
            com.textmorph.app.service.overlay.TextMorphAccessibilityService.ChatMessage("Them", "Hey, are you free for lunch?", isIncoming = true),
            com.textmorph.app.service.overlay.TextMorphAccessibilityService.ChatMessage("Me", "Yes I am! Where should we go?", isIncoming = false),
            com.textmorph.app.service.overlay.TextMorphAccessibilityService.ChatMessage("Them", "How about the Italian place at 12:30?", isIncoming = true)
        )

        val latestIncoming = messages.lastOrNull { it.isIncoming }?.text
        assertNotNull(latestIncoming)
        assertEquals("How about the Italian place at 12:30?", latestIncoming)
    }

    @Test
    fun testCompanionMagneticEdgeSnappingLogic() {
        val screenWidth = 1080
        val pillWidth = 108 // 36dp
        val margin = 18 // 6dp
        val boundMinX = margin
        val boundMaxX = screenWidth - pillWidth - margin

        fun snapX(currentX: Int): Int {
            return if (currentX < screenWidth / 2) boundMinX else boundMaxX
        }

        // Dropped on the left half -> snaps to boundMinX
        assertEquals(boundMinX, snapX(100))
        assertEquals(boundMinX, snapX(539))

        // Dropped on the right half -> snaps to boundMaxX
        assertEquals(boundMaxX, snapX(540))
        assertEquals(boundMaxX, snapX(800))
        assertEquals(boundMaxX, snapX(1050))
    }

    @Test
    fun testInstantSwipeGestureDetection() {
        val touchSlop = 16f

        fun processGesture(dx: Float, dy: Float): Boolean {
            val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            return dist > touchSlop
        }

        // Micro-tremors or clean tap under touch slop -> false (treated as tap)
        assertFalse(processGesture(0f, 0f))
        assertFalse(processGesture(5f, 5f))
        assertFalse(processGesture(10f, 10f)) // hypot = 14.14 < 16

        // Swipe movements exceeding touch slop -> true (immediately engages drag mode)
        assertTrue(processGesture(20f, 0f))
        assertTrue(processGesture(0f, 25f))
        assertTrue(processGesture(15f, 15f)) // hypot = 21.21 > 16
        assertTrue(processGesture(100f, 50f))
    }

    @Test
    fun testClipboardContextActionEnumAndSerialization() {
        val action = com.textmorph.app.data.model.ContextAction.CLIPBOARD
        assertEquals("Add Clipboard", action.displayName)
        assertTrue(action.description.contains("clipboard", ignoreCase = true))

        val prompt = PromptEntity(
            id = "test_clip",
            title = "Clipboard Test",
            systemInstruction = "Process clipboard text",
            contextAction = com.textmorph.app.data.model.ContextAction.CLIPBOARD
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(prompt)
        val decoded = json.decodeFromString<PromptEntity>(encoded)
        assertEquals(com.textmorph.app.data.model.ContextAction.CLIPBOARD, decoded.contextAction)
    }

    @Test
    fun testAlwaysOpenBarWithKeyboardSettingSerialization() {
        val defaultSettings = AppSettings()
        assertTrue(defaultSettings.alwaysOpenBarWithKeyboard)

        val customSettings = defaultSettings.copy(alwaysOpenBarWithKeyboard = false)
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(customSettings)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        assertFalse(decoded.alwaysOpenBarWithKeyboard)
    }

    @Test
    fun testSeedClipboardPromptExists() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        val clipSeed = seeds.firstOrNull { it.id == "seed_clipboard_summary" }
        assertNotNull(clipSeed)
        assertEquals(com.textmorph.app.data.model.ContextAction.CLIPBOARD, clipSeed?.contextAction)
    }

    @Test
    fun testAiConfigurationsSerialization() {
        val defaultSettings = AppSettings()
        assertEquals(1024, defaultSettings.maxTokens)
        assertEquals(0.7f, defaultSettings.temperature, 0.001f)
        assertEquals(40, defaultSettings.topK)
        assertEquals(0.9f, defaultSettings.topP, 0.001f)
        assertFalse(defaultSettings.enableThinking)
        assertFalse(defaultSettings.speculativeDecoding)
        assertTrue(defaultSettings.defaultSystemPrompt.contains("concise AI writing assistant"))

        val custom = defaultSettings.copy(
            maxTokens = 2048,
            temperature = 0.5f,
            topK = 50,
            topP = 0.85f,
            enableThinking = true,
            speculativeDecoding = true,
            defaultSystemPrompt = "Custom prompt"
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(custom)
        val decoded = json.decodeFromString<AppSettings>(encoded)

        assertEquals(2048, decoded.maxTokens)
        assertEquals(0.5f, decoded.temperature, 0.001f)
        assertEquals(50, decoded.topK)
        assertEquals(0.85f, decoded.topP, 0.001f)
        assertTrue(decoded.enableThinking)
        assertTrue(decoded.speculativeDecoding)
        assertEquals("Custom prompt", decoded.defaultSystemPrompt)
    }

    @Test
    fun testCompactChipsModeSettingSerialization() {
        val defaultSettings = AppSettings()
        assertFalse(defaultSettings.compactChipsMode)

        val customSettings = defaultSettings.copy(compactChipsMode = true)
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(customSettings)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        assertTrue(decoded.compactChipsMode)
    }

    @Test
    fun testUpperHalfScreenPopupDimensions() {
        val screenHeight = 2400
        val upperHalfHeight = (screenHeight * 0.48f).toInt()
        assertEquals(1152, upperHalfHeight)
        assertTrue(upperHalfHeight < screenHeight / 2 + 10)
    }

    @Test
    fun testModelFileTypeDetectionExtensions() {
        assertEquals(com.textmorph.app.data.model.ModelFileType.GGUF, com.textmorph.app.data.model.ModelFileType.detect("smollm2.gguf"))
        assertEquals(com.textmorph.app.data.model.ModelFileType.GGUF, com.textmorph.app.data.model.ModelFileType.detect("/sdcard/Download/Llama-3.2-3B.GGUF"))
        assertEquals(com.textmorph.app.data.model.ModelFileType.LITERT_LM, com.textmorph.app.data.model.ModelFileType.detect("gemma-4-E2B-it.litertlm"))
        assertEquals(com.textmorph.app.data.model.ModelFileType.LITERT_LM, com.textmorph.app.data.model.ModelFileType.detect("classifier.tflite"))
        assertEquals(com.textmorph.app.data.model.ModelFileType.UNKNOWN, com.textmorph.app.data.model.ModelFileType.detect("weights.bin"))
    }

    @Test
    fun testModelFileTypeDetectionMagicBytes() {
        val ggufBytes = byteArrayOf(0x47.toByte(), 0x47.toByte(), 0x55.toByte(), 0x46.toByte(), 0x03.toByte())
        assertEquals(com.textmorph.app.data.model.ModelFileType.GGUF, com.textmorph.app.data.model.ModelFileType.detect("unknown_file", ggufBytes))

        val tfl3Bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x54.toByte(), 0x46.toByte(), 0x4C.toByte(), 0x33.toByte())
        assertEquals(com.textmorph.app.data.model.ModelFileType.LITERT_LM, com.textmorph.app.data.model.ModelFileType.detect("custom_model", tfl3Bytes))
    }

    @Test
    fun testEngineTypeSelectionAndRouting() {
        assertEquals("On-Device (llama.cpp GGUF)", com.textmorph.app.data.model.EngineType.ON_DEVICE_LLAMA_CPP.displayName)
        assertEquals("On-Device (LiteRT-LM)", com.textmorph.app.data.model.EngineType.ON_DEVICE_LITERT.displayName)
        assertEquals("Cloud OpenAI Compatible", com.textmorph.app.data.model.EngineType.CLOUD_OPENAI.displayName)

        fun resolveEngineForModel(name: String): com.textmorph.app.data.model.EngineType = when {
            name.endsWith(".gguf", ignoreCase = true) -> com.textmorph.app.data.model.EngineType.ON_DEVICE_LLAMA_CPP
            name.endsWith(".litertlm", ignoreCase = true) || name.endsWith(".tflite", ignoreCase = true) -> com.textmorph.app.data.model.EngineType.ON_DEVICE_LITERT
            else -> com.textmorph.app.data.model.EngineType.ON_DEVICE_LITERT
        }

        assertEquals(com.textmorph.app.data.model.EngineType.ON_DEVICE_LLAMA_CPP, resolveEngineForModel("SmolLM2-360M-Instruct-Q4_K_M.gguf"))
        assertEquals(com.textmorph.app.data.model.EngineType.ON_DEVICE_LITERT, resolveEngineForModel("gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun testModelInfoWithMmprojProjectorSerialization() {
        val model = com.textmorph.app.data.model.ModelInfo(
            id = "test_gguf_vision",
            name = "SmolLM2-360M-Instruct-Q4_K_M.gguf",
            path = "/sdcard/Download/SmolLM2.gguf",
            modelFileType = com.textmorph.app.data.model.ModelFileType.GGUF,
            mmprojName = "mmproj-f16.gguf",
            mmprojPath = "/sdcard/Download/mmproj-f16.gguf",
            mmprojUriString = "content://media/external/file/456",
            isVisionCapable = true
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(model)
        val decoded = json.decodeFromString<com.textmorph.app.data.model.ModelInfo>(encoded)

        assertEquals("SmolLM2-360M-Instruct-Q4_K_M.gguf", decoded.name)
        assertEquals(com.textmorph.app.data.model.ModelFileType.GGUF, decoded.modelFileType)
        assertEquals("mmproj-f16.gguf", decoded.mmprojName)
        assertEquals("/sdcard/Download/mmproj-f16.gguf", decoded.mmprojPath)
        assertEquals("content://media/external/file/456", decoded.mmprojUriString)
        assertTrue(decoded.isVisionCapable)
    }

    @Test
    fun testAppSettingsMmprojFieldsSerialization() {
        val settings = AppSettings(
            selectedEngine = com.textmorph.app.data.model.EngineType.ON_DEVICE_LLAMA_CPP,
            activeModelName = "qwen-2.5-coder.gguf",
            activeMmprojName = "mmproj-qwen-f16.gguf",
            activeMmprojPath = "/sdcard/Download/mmproj-qwen-f16.gguf",
            activeMmprojUri = "content://com.android.providers.downloads.documents/document/999"
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(settings)
        val decoded = json.decodeFromString<AppSettings>(encoded)

        assertEquals(com.textmorph.app.data.model.EngineType.ON_DEVICE_LLAMA_CPP, decoded.selectedEngine)
        assertEquals("qwen-2.5-coder.gguf", decoded.activeModelName)
        assertEquals("mmproj-qwen-f16.gguf", decoded.activeMmprojName)
        assertEquals("/sdcard/Download/mmproj-qwen-f16.gguf", decoded.activeMmprojPath)
        assertEquals("content://com.android.providers.downloads.documents/document/999", decoded.activeMmprojUri)
    }
}
