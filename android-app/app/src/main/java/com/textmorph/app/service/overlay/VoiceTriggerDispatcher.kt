package com.textmorph.app.service.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.data.model.AutomationActionType
import com.textmorph.app.data.model.AutomationEntity
import com.textmorph.app.data.repository.AutomationRepository
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.ClipboardHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class VoiceDispatchResult {
    data class Handled(val title: String, val details: String, val success: Boolean) : VoiceDispatchResult()
    object Unmatched : VoiceDispatchResult()
}

/**
 * Evaluates spoken voice commands and user inputs to determine if they correspond
 * to an instantaneous direct trigger or custom automation before incurring
 * the overhead of full autonomous agent task decomposition.
 */
class VoiceTriggerDispatcher(
    private val context: Context,
    private val serviceProvider: () -> TextMorphAccessibilityService?,
    private val agentEngineProvider: () -> AgentEngine
) {

    private val TAG = "VoiceTriggerDispatcher"
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun dispatchVoiceInput(
        spokenText: String,
        onProgress: ((String) -> Unit)? = null
    ): VoiceDispatchResult = withContext(Dispatchers.IO) {
        val trimmed = spokenText.trim()
        if (trimmed.isBlank()) return@withContext VoiceDispatchResult.Unmatched

        val autoRepo = AutomationRepository.getInstance(context)
        val matched = autoRepo.findMatchingAutomation(trimmed)

        if (matched != null) {
            AppLogger.i(TAG, "Spoken input '$trimmed' matched automation '${matched.title}' (action=${matched.actionType})")
            onProgress?.invoke("Executing direct trigger: ${matched.title}")
            return@withContext executeAutomation(matched)
        }

        VoiceDispatchResult.Unmatched
    }

    suspend fun executeAutomation(automation: AutomationEntity): VoiceDispatchResult = withContext(Dispatchers.IO) {
        val agentEngine = agentEngineProvider()
        val service = serviceProvider()

        try {
            when (automation.actionType) {
                AutomationActionType.SCREEN_ASSIST -> {
                    val (ok, details) = ScreenAssistExecutor.executeScreenAssist(
                        context = context,
                        service = service,
                        customInstruction = automation.promptInstruction,
                        copyToClipboard = automation.copyToClipboard,
                        customToast = automation.toastMessage
                    )
                    VoiceDispatchResult.Handled(automation.title, details, ok)
                }

                AutomationActionType.PHONE_CALL -> {
                    val target = automation.targetNumber?.trim().orEmpty()
                    val res = agentEngine.communication.makeCall(null, target)
                    showCustomToast(automation.toastMessage ?: "Calling $target…")
                    VoiceDispatchResult.Handled(automation.title, res.getOrElse { it.message ?: "Call initiated" }, res.isSuccess)
                }

                AutomationActionType.SEND_SMS -> {
                    val target = automation.targetNumber?.trim().orEmpty()
                    val msg = automation.promptInstruction ?: "Hello"
                    val res = agentEngine.communication.sendSms(null, target, msg)
                    showCustomToast(automation.toastMessage ?: "Opening SMS to $target")
                    VoiceDispatchResult.Handled(automation.title, res.getOrElse { it.message ?: "SMS prepared" }, res.isSuccess)
                }

                AutomationActionType.OPEN_APP -> {
                    val app = automation.targetApp?.trim().orEmpty()
                    val res = agentEngine.appLauncher.openApp(app)
                    showCustomToast(automation.toastMessage ?: "Opening $app…")
                    VoiceDispatchResult.Handled(automation.title, res.getOrElse { it.message ?: "App opened" }, res.isSuccess)
                }

                AutomationActionType.DEVICE_CONTROL -> {
                    val script = automation.scriptCode?.trim().orEmpty()
                    val action = CodeActionParser.parse(script)
                    val res = if (action != null) {
                        agentEngine.execute(action)
                    } else {
                        com.textmorph.app.agent.model.AgentActionResult("device_control", false, "Invalid device control syntax: $script")
                    }
                    showCustomToast(automation.toastMessage ?: res.details)
                    VoiceDispatchResult.Handled(automation.title, res.details, res.success)
                }

                AutomationActionType.CODE_SCRIPT -> {
                    val lines = automation.scriptCode?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                    var allOk = true
                    val summaries = mutableListOf<String>()

                    for (line in lines) {
                        val action = CodeActionParser.parse(line.trim())
                        if (action != null) {
                            val res = agentEngine.execute(action)
                            summaries.add(res.details)
                            if (!res.success) {
                                allOk = false
                                break
                            }
                        }
                    }

                    showCustomToast(automation.toastMessage ?: if (allOk) "Script completed" else "Script stopped on failure")
                    val finalMsg = summaries.joinToString("; ")
                    VoiceDispatchResult.Handled(automation.title, finalMsg, allOk)
                }

                AutomationActionType.CUSTOM_TOAST -> {
                    val msg = automation.toastMessage ?: automation.title
                    showCustomToast(msg)
                    if (automation.copyToClipboard && !automation.promptInstruction.isNullOrBlank()) {
                        ClipboardHelper.copyText(context, automation.promptInstruction, "Automation", showToast = false)
                    }
                    VoiceDispatchResult.Handled(automation.title, msg, true)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing automation '${automation.title}': ${e.message}", e)
            VoiceDispatchResult.Handled(automation.title, "Execution error: ${e.message}", false)
        }
    }

    private fun showCustomToast(message: String) {
        if (message.isNotBlank()) {
            mainHandler.post {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed showing toast: ${e.message}")
                }
            }
        }
    }
}
