package com.textmorph.app.ui.tools.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full documentation and copyable LLM prompt guide for authoring TextMorph JavaScript tools.
 */
@Composable
fun JsToolDocumentationDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val documentationMarkdown = """
# TextMorph JavaScript Tool Plugin Specification

You can extend TextMorph's on-device AI actions by writing custom JavaScript tools.
TextMorph runs these scripts in a sandboxed, headless Android WebView environment with asynchronous coroutine support.

## 1. File Structure & Embedded Metadata
Include metadata comments at the top of your `.js` file so TextMorph automatically discovers its name, parameters, and invocation signature:

```javascript
/**
 * @tool calculate_discount
 * @name Calculate Discount & Tax
 * @description Computes discounted price, sales tax, and final total.
 * @signature calculate_discount(price=100, discount_percent=20, tax_percent=8)
 * @param {number} price Original item price
 * @param {number} discount_percent Discount percentage (0-100)
 * @param {number} tax_percent Sales tax percentage (0-100)
 */
function run(params) {
  const p = parseFloat(params.price) || 0;
  const d = parseFloat(params.discount_percent) || 0;
  const t = parseFloat(params.tax_percent) || 0;

  const discounted = p * (1 - d / 100);
  const tax = discounted * (t / 100);
  const total = discounted + tax;

  return `Original: ${'$'}${'$'}{p.toFixed(2)} | Discounted: ${'$'}${'$'}{discounted.toFixed(2)} | Tax: ${'$'}${'$'}{tax.toFixed(2)} | Total: ${'$'}${'$'}{total.toFixed(2)}`;
}
```

## 2. Requirements & Execution Rules
1. **Entry Point**: The script must define a `run(params)` function (or `async function run(params)`).
2. **Parameters**: `params` is a JavaScript object with string or numerical properties matching your `@param` definitions.
3. **Return Value**: Return a string or JSON object describing the outcome. This text is injected directly back into the LLM conversation context.
4. **Async & Fetch**: You may use `async/await` and standard JavaScript built-ins (`Math`, `Date`, `JSON`, `RegExp`).
5. **No Infinite Loops**: Execution is capped with a 10-second timeout safeguard.

## 3. Prompt for Other LLMs (Copy & Paste to ChatGPT/Claude)
> "Write a custom JavaScript tool plugin for TextMorph on Android. The tool must have JSDoc metadata tags (@tool, @name, @description, @signature, @param) and export a \`function run(params)\` that handles errors cleanly and returns a formatted result string. My tool goal is: [INSERT YOUR GOAL HERE]."
    """.trimIndent()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111827), // Midnight Charcoal
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "JS Tool Documentation",
                                color = Color(0xFFF9FAFB),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Plugin specification & LLM prompt guide",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.5.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF9CA3AF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Doc Content
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0B1020),
                    border = BorderStroke(1.dp, Color(0xFF1F2937)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = documentationMarkdown,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFE5E7EB)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("TextMorph JS Tool Spec", documentationMarkdown)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Documentation copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy Guide (Markdown)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, Color(0xFF4B5563)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = "Close", color = Color(0xFFD1D5DB), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
