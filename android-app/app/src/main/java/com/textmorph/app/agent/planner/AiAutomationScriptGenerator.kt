package com.textmorph.app.agent.planner

import android.content.Context
import com.textmorph.app.data.model.AutomationActionType
import com.textmorph.app.data.model.AutomationEntity
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LiteRtLmSessionDelegate
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Synthesizes an executable AutomationEntity from plain-text or voice user descriptions
 * using on-device/cloud LLM intelligence with a robust rule-based fallback.
 */
object AiAutomationScriptGenerator {

    private const val TAG = "AiAutoScriptGen"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val SYSTEM_PROMPT = """
        You are the TextMorph Automation Generator. Your role is to convert a user's natural language automation request into a structured JSON automation entity.

        Available Tools:
        - wait(ms)
        - open_app("AppName")
        - open_url("https://...")
        - call("ContactName", "PhoneNumber")
        - send_sms("ContactName", "Message")
        - set_volume(levelPercent)
        - set_brightness(levelPercent)
        - set_alarm(hour, minute, "Label")
        - set_timer(seconds, "Label")
        - click_text("Label")
        - type_text("Content")
        - press_enter()
        - scroll("down" or "up")

        Allowed action types:
        "SCREEN_ASSIST", "PHONE_CALL", "SEND_SMS", "OPEN_APP", "DEVICE_CONTROL", "CODE_SCRIPT", "CUSTOM_TOAST"

        Output STRICTLY a single valid JSON object (no markdown quotes, no commentary, no intro/outro) with this schema:
        {
          "trigger": "voice trigger phrase",
          "title": "Short title",
          "actionType": "PHONE_CALL",
          "targetNumber": "phone number or contact name",
          "targetApp": "app name if applicable",
          "scriptCode": "code lines separated by newlines if multi-step",
          "promptInstruction": "instruction if screen assist",
          "toastMessage": "toast confirmation text",
          "copyToClipboard": true or false
        }
    """.trimIndent()

    suspend fun generateAutomation(context: Context, userInput: String): AutomationEntity = withContext(Dispatchers.IO) {
        val trimmed = userInput.trim()
        AppLogger.i(TAG, "Generating automation from user input: '$trimmed'")

        // 1. Try LLM Generation
        try {
            val settings = runCatching {
                com.textmorph.app.data.repository.SettingsRepository.getInstance(context).settingsState.value
            }.getOrNull()
            val engine: com.textmorph.app.llm.LlmEngine = when (settings?.selectedEngine) {
                com.textmorph.app.data.model.EngineType.CLOUD_OPENAI -> {
                    val cloud = com.textmorph.app.llm.OpenAiCloudEngine()
                    cloud.updateConfig(settings.cloudBaseUrl, settings.cloudApiKey, settings.cloudModelId)
                    cloud
                }
                com.textmorph.app.data.model.EngineType.ON_DEVICE_LLAMA_CPP -> {
                    com.textmorph.app.llm.LlamaCppEngineManager.getInstance(context)
                }
                else -> {
                    LiteRtLmEngineManager.getInstance(context)
                }
            }

            if (engine.isLoaded()) {
                val sb = StringBuilder()
                engine.generateStream(
                    prompt = "Convert this request into an automation JSON:\n$trimmed",
                    systemInstruction = SYSTEM_PROMPT
                ).collect { token ->
                    sb.append(token)
                }

                val raw = sb.toString()
                val clean = LiteRtLmSessionDelegate.sanitizeNoThinking(raw.trim())
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

                val parsed = parseJsonToEntity(clean, trimmed)
                if (parsed != null) {
                    AppLogger.i(TAG, "Successfully synthesized automation via LLM: '${parsed.title}'")
                    return@withContext parsed
                }
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "LLM script generation threw: ${e.message}, invoking heuristic fallback.", e)
        }

        // 2. Deterministic Rule-Based Fallback
        generateFallbackEntity(trimmed)
    }

    private fun parseJsonToEntity(jsonStr: String, originalPrompt: String): AutomationEntity? {
        return try {
            val element = json.parseToJsonElement(jsonStr).jsonObject

            val trigger = element["trigger"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (trigger.isBlank()) return null

            val title = element["title"]?.jsonPrimitive?.content?.trim() ?: "Custom Automation"
            val actionTypeStr = element["actionType"]?.jsonPrimitive?.content?.trim() ?: "CODE_SCRIPT"
            val actionType = try {
                AutomationActionType.valueOf(actionTypeStr)
            } catch (e: Exception) {
                AutomationActionType.CODE_SCRIPT
            }

            val targetNumber = element["targetNumber"]?.jsonPrimitive?.content?.trim()
            val targetApp = element["targetApp"]?.jsonPrimitive?.content?.trim()
            val scriptCode = element["scriptCode"]?.jsonPrimitive?.content?.trim()
            val promptInstruction = element["promptInstruction"]?.jsonPrimitive?.content?.trim()
            val toastMessage = element["toastMessage"]?.jsonPrimitive?.content?.trim()
            val copyToClipboard = element["copyToClipboard"]?.jsonPrimitive?.booleanOrNull ?: false

            AutomationEntity(
                trigger = trigger,
                title = title,
                description = "AI-generated automation for: $originalPrompt",
                actionType = actionType,
                targetNumber = targetNumber,
                targetApp = targetApp,
                scriptCode = scriptCode,
                promptInstruction = promptInstruction,
                toastMessage = toastMessage,
                copyToClipboard = copyToClipboard
            )
        } catch (e: Exception) {
            AppLogger.d(TAG, "Failed parsing JSON element: ${e.message}")
            null
        }
    }

    fun generateFallbackEntity(input: String): AutomationEntity {
        val lower = input.lowercase()

        // Extract Trigger
        val triggerRegex = Regex("""(?:when i say|trigger|if i say|saying)\s+["']?([^,"'.]+)["']?""", RegexOption.IGNORE_CASE)
        val trigger = triggerRegex.find(input)?.groupValues?.get(1)?.trim()
            ?: if (lower.startsWith("call ")) input.take(30).trim()
            else if (lower.startsWith("open ")) input.take(25).trim()
            else input.split(",").firstOrNull()?.trim() ?: "my automation"

        // Extract Toast
        val toastRegex = Regex("""(?:toast(?: like)?|saying|message|display)\s+["']?([^,"'.!]+)["']?""", RegexOption.IGNORE_CASE)
        val toast = toastRegex.find(input)?.groupValues?.get(1)?.trim()

        // Extract Phone Number / Contact
        val phoneRegex = Regex("""(?:\+?\d{6,15})""")
        val foundPhone = phoneRegex.find(input)?.value

        // 1. Call Scenario
        if (lower.contains("call ") || lower.contains("phone") || lower.contains("dial")) {
            val target = foundPhone ?: run {
                val callMatch = Regex("""call\s+([a-zA-Z0-9 ]+?)(?:\s+(?:at|on|and|with)|$)""", RegexOption.IGNORE_CASE).find(input)
                callMatch?.groupValues?.get(1)?.trim() ?: ""
            }
            return AutomationEntity(
                trigger = trigger,
                title = "Call ${target.ifBlank { "Contact" }}",
                description = input,
                actionType = AutomationActionType.PHONE_CALL,
                targetNumber = target,
                toastMessage = toast ?: if (target.isNotBlank()) "Calling $target" else "Calling…",
                copyToClipboard = false
            )
        }

        // 2. Open App Scenario
        if (lower.contains("open ") || lower.contains("launch ")) {
            val appMatch = Regex("""(?:open|launch)\s+([a-zA-Z0-9 ]+?)(?:\s+(?:and|then|with)|$)""", RegexOption.IGNORE_CASE).find(input)
            val appName = appMatch?.groupValues?.get(1)?.trim() ?: "App"
            return AutomationEntity(
                trigger = trigger,
                title = "Launch $appName",
                description = input,
                actionType = AutomationActionType.OPEN_APP,
                targetApp = appName,
                toastMessage = toast ?: "Opening $appName…",
                copyToClipboard = false
            )
        }

        // 3. Screen Assist Scenario
        if (lower.contains("screen") || lower.contains("assist") || lower.contains("summarize") || lower.contains("clipboard")) {
            return AutomationEntity(
                trigger = trigger,
                title = "Screen Assist: $trigger",
                description = input,
                actionType = AutomationActionType.SCREEN_ASSIST,
                promptInstruction = input,
                toastMessage = toast ?: "📋 Screen Assist copied to clipboard!",
                copyToClipboard = true
            )
        }

        // 4. Volume / Device Setting Scenario
        if (lower.contains("volume") || lower.contains("mute")) {
            val volLevel = Regex("""\b(\d{1,3})\b""").find(input)?.value?.toIntOrNull() ?: if (lower.contains("mute")) 0 else 50
            return AutomationEntity(
                trigger = trigger,
                title = "Set Volume to $volLevel%",
                description = input,
                actionType = AutomationActionType.DEVICE_CONTROL,
                scriptCode = "set_volume($volLevel)",
                toastMessage = toast ?: "Volume set to $volLevel%"
            )
        }

        // Default: Code Script
        return AutomationEntity(
            trigger = trigger,
            title = trigger.replaceFirstChar { it.uppercase() },
            description = input,
            actionType = AutomationActionType.CUSTOM_TOAST,
            toastMessage = toast ?: trigger
        )
    }
}
