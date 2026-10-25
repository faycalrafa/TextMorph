package com.textmorph.app.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.textmorph.app.util.AppLogger

/**
 * Tool for performing phone calls, text messaging (SMS), and email composition.
 */
class CommunicationTool(
    private val context: Context,
    private val contactsTool: ContactsTool
) {

    private val TAG = "CommunicationTool"

    fun makeCall(contactName: String?, phoneNumber: String?): Result<String> {
        return try {
            var targetNumber = phoneNumber?.trim()
            var displayName = contactName?.trim()

            if (targetNumber.isNullOrEmpty() && !displayName.isNullOrEmpty()) {
                val found = contactsTool.findPhoneNumber(displayName)
                if (found != null) {
                    targetNumber = found.number
                    displayName = found.name
                }
            }

            if (targetNumber.isNullOrEmpty()) {
                return Result.failure(Exception("No phone number provided or contact not found."))
            }

            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$targetNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val label = if (!displayName.isNullOrEmpty()) "$displayName ($targetNumber)" else targetNumber
            AppLogger.i(TAG, "Initiated call to $label")
            Result.success("Calling $label…")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error initiating call: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun sendSms(contactName: String?, phoneNumber: String?, message: String): Result<String> {
        return try {
            var targetNumber = phoneNumber?.trim()
            var displayName = contactName?.trim()

            if (targetNumber.isNullOrEmpty() && !displayName.isNullOrEmpty()) {
                val found = contactsTool.findPhoneNumber(displayName)
                if (found != null) {
                    targetNumber = found.number
                    displayName = found.name
                }
            }

            if (targetNumber.isNullOrEmpty()) {
                return Result.failure(Exception("No phone number provided or contact not found."))
            }

            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$targetNumber")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val label = if (!displayName.isNullOrEmpty()) "$displayName ($targetNumber)" else targetNumber
            AppLogger.i(TAG, "Prepared SMS to $label with length ${message.length}")
            Result.success("Opening SMS to $label with message: \"$message\"")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error preparing SMS: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun sendEmail(to: String, subject: String?, body: String?): Result<String> {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to.trim()))
                if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.i(TAG, "Prepared email to $to")
            Result.success("Opening email client to $to")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error preparing email: ${e.message}", e)
            Result.failure(e)
        }
    }
}
