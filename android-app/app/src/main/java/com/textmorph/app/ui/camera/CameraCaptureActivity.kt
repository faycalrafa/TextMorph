package com.textmorph.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.textmorph.app.util.AppLogger
import java.io.ByteArrayOutputStream

class CameraCaptureActivity : ComponentActivity() {

    private val TAG = "CameraCaptureActivity"

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val bytes = stream.toByteArray()
                AppLogger.i(TAG, "Captured photo successfully: ${bytes.size} bytes")
                onPhotoCapturedCallback?.invoke(bytes)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error compressing camera photo: ${e.message}", e)
                Toast.makeText(this, "Failed processing photo: ${e.message}", Toast.LENGTH_SHORT).show()
                onPhotoCapturedCallback?.invoke(null)
            } finally {
                bitmap.recycle()
            }
        } else {
            AppLogger.d(TAG, "Camera capture cancelled by user.")
            onPhotoCapturedCallback?.invoke(null)
        }
        onPhotoCapturedCallback = null
        finish()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            takePictureLauncher.launch(null)
        } else {
            AppLogger.w(TAG, "Camera permission denied.")
            Toast.makeText(this, "Camera permission is required to capture photos.", Toast.LENGTH_SHORT).show()
            onPhotoCapturedCallback?.invoke(null)
            onPhotoCapturedCallback = null
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePictureLauncher.launch(null)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        onPhotoCapturedCallback = null
    }

    companion object {
        @Volatile
        private var onPhotoCapturedCallback: ((ByteArray?) -> Unit)? = null

        fun launch(context: Context, callback: (ByteArray?) -> Unit) {
            onPhotoCapturedCallback = callback
            val intent = Intent(context, CameraCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            context.startActivity(intent)
        }
    }
}
