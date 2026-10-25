package com.textmorph.app.service.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.textmorph.app.util.AppLogger

/**
 * Lightweight, transparent trampoline activity that satisfies Android's ActivityResultRegistry
 * contract for overlay services, cleanly picking files or images without UI artifacts.
 */
class OverlayMediaPickerActivity : ComponentActivity() {

    private val TAG = "OverlayMediaPicker"

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        AppLogger.i(TAG, "Selected ${uris.size} media items from picker.")
        if (uris.isNotEmpty()) {
            OverlayMediaPickerBus.emitPicked(uris)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            pickerLauncher.launch("*/*")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch media picker: ${e.message}", e)
            finish()
        }
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, OverlayMediaPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}
