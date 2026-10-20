package com.textmorph.app.ui.selection

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.util.AppLogger

class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        val isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        AppLogger.i("ProcessTextActivity", "Triggered with text length: ${selectedText.length}, readOnly: $isReadOnly")

        setContent {
            TextMorphTheme {
                Surface(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        Text("Transforming: ${selectedText.take(50)}...")
                    }
                }
            }
        }
    }
}
