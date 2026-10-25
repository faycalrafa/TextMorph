package com.textmorph.app.agent.tools

import android.content.Context
import android.provider.ContactsContract
import com.textmorph.app.util.AppLogger

/**
 * Tool for looking up contacts from the device's address book.
 */
class ContactsTool(private val context: Context) {

    private val TAG = "ContactsTool"

    data class ContactEntry(val name: String, val number: String)

    fun findPhoneNumber(query: String): ContactEntry? {
        val matches = searchContacts(query)
        return matches.firstOrNull()
    }

    fun searchContacts(query: String): List<ContactEntry> {
        val cleanQuery = query.trim()
        val results = mutableListOf<ContactEntry>()
        if (cleanQuery.isBlank()) return results

        try {
            val resolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$cleanQuery%")

            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext() && results.size < 10) {
                    if (nameIdx != -1 && numIdx != -1) {
                        val name = cursor.getString(nameIdx) ?: continue
                        val number = cursor.getString(numIdx) ?: continue
                        results.add(ContactEntry(name, number))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Contacts permission not granted or query failed: ${e.message}")
        }
        return results
    }

    fun searchAndFormat(query: String): Result<String> {
        val contacts = searchContacts(query)
        return if (contacts.isEmpty()) {
            Result.success("No contacts found matching '$query'.")
        } else {
            val formatted = contacts.joinToString("\n") { "• ${it.name}: ${it.number}" }
            Result.success("Found contacts:\n$formatted")
        }
    }
}
