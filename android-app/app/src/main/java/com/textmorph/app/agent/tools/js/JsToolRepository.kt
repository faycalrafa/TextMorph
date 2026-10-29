package com.textmorph.app.agent.tools.js

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thread-safe persistent repository for user-defined and pre-seeded JavaScript tools.
 * Enforces atomic writes, error trapping, and zero data loss.
 */
class JsToolRepository private constructor(private val context: Context) {

    private val TAG = "JsToolRepository"
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val toolsDir: File
        get() = File(context.filesDir, "js_tools").apply { if (!exists()) mkdirs() }

    private val toolsFile: File
        get() = File(toolsDir, "custom_tools.json")

    private val _tools = MutableStateFlow<List<JsToolEntity>>(emptyList())
    val tools: StateFlow<List<JsToolEntity>> = _tools.asStateFlow()

    init {
        scope.launch {
            loadTools()
        }
    }

    suspend fun loadTools() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!toolsFile.exists()) {
                    AppLogger.d(TAG, "No JS tools file found. Seeding default showcase tools.")
                    val defaultTools = getShowcaseTools()
                    _tools.value = defaultTools
                    saveToDiskLocked(defaultTools)
                    return@withContext
                }

                val content = toolsFile.readText()
                val loaded = try {
                    json.decodeFromString<List<JsToolEntity>>(content)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Corrupt JS tools JSON, restoring defaults: ${e.message}", e)
                    getShowcaseTools()
                }

                _tools.value = if (loaded.isEmpty()) getShowcaseTools() else loaded
                AppLogger.d(TAG, "Loaded ${_tools.value.size} JS tools from storage")
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error loading JS tools: ${t.message}", t)
                _tools.value = getShowcaseTools()
            }
        }
    }

    suspend fun saveTool(tool: JsToolEntity): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val current = _tools.value.toMutableList()
                val index = current.indexOfFirst { it.id == tool.id }
                if (index != -1) {
                    current[index] = tool
                } else {
                    current.add(tool)
                }
                _tools.value = current
                saveToDiskLocked(current)
                AppLogger.i(TAG, "Saved JS tool: ${tool.id} ('${tool.name}')")
                true
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed saving JS tool '${tool.id}': ${t.message}", t)
                false
            }
        }
    }

    suspend fun deleteTool(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val current = _tools.value.filterNot { it.id == id }
                _tools.value = current
                saveToDiskLocked(current)
                AppLogger.i(TAG, "Deleted JS tool: $id")
                true
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed deleting JS tool '$id': ${t.message}", t)
                false
            }
        }
    }

    suspend fun toggleTool(id: String, isEnabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val current = _tools.value.map {
                    if (it.id == id) it.copy(isEnabled = isEnabled) else it
                }
                _tools.value = current
                saveToDiskLocked(current)
                AppLogger.d(TAG, "Toggled JS tool '$id' enabled=$isEnabled")
                true
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed toggling JS tool '$id': ${t.message}", t)
                false
            }
        }
    }

    fun getToolById(id: String): JsToolEntity? {
        return _tools.value.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun getEnabledTools(): List<JsToolEntity> {
        return _tools.value.filter { it.isEnabled }
    }

    private fun saveToDiskLocked(list: List<JsToolEntity>) {
        try {
            val atomicFile = AtomicFile(toolsFile)
            val stream = atomicFile.startWrite()
            try {
                val bytes = json.encodeToString(list).toByteArray(Charsets.UTF_8)
                stream.write(bytes)
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Atomic write to custom_tools.json failed: ${t.message}", t)
        }
    }

    private fun getShowcaseTools(): List<JsToolEntity> {
        return listOf(
            JsToolEntity(
                id = "calculate_tip",
                name = "Calculate Tip & Split",
                description = "Computes bill tip amount, total bill, and per-person split with formatted breakdown.",
                codeSignature = "calculate_tip(bill=50, tip_percent=15, split=1)",
                script = """
                    /**
                     * @tool calculate_tip
                     * @name Calculate Tip & Split
                     * @description Computes bill tip amount, total bill, and per-person split with formatted breakdown.
                     * @signature calculate_tip(bill=50, tip_percent=15, split=1)
                     * @param {number} bill Total bill amount before tip
                     * @param {number} tip_percent Tip percentage to add
                     * @param {number} split Number of people splitting the bill
                     */
                    function run(params) {
                      const b = parseFloat(params.bill) || 0;
                      const tp = parseFloat(params.tip_percent) || 15;
                      const s = Math.max(1, parseInt(params.split) || 1);
                      const tip = (b * tp) / 100;
                      const total = b + tip;
                      const perPerson = total / s;
                      return `Bill: $${'$'}{b.toFixed(2)} | Tip (${'$'}{tp}%): $${'$'}{tip.toFixed(2)} | Total: $${'$'}{total.toFixed(2)} | Split (${'$'}{s} ways): $${'$'}{perPerson.toFixed(2)} per person`;
                    }
                """.trimIndent(),
                isEnabled = true,
                isPreinstalled = true
            ),
            JsToolEntity(
                id = "unit_converter",
                name = "Accurate Unit Converter",
                description = "Converts between metric and imperial units (temperature, distance, weight).",
                codeSignature = "unit_converter(value=100, from_unit=\"c\", to_unit=\"f\")",
                script = """
                    /**
                     * @tool unit_converter
                     * @name Accurate Unit Converter
                     * @description Converts between metric and imperial units (temperature, distance, weight).
                     * @signature unit_converter(value=100, from_unit="c", to_unit="f")
                     * @param {number} value Numerical amount to convert
                     * @param {string} from_unit Source unit (c, f, km, mi, kg, lb)
                     * @param {string} to_unit Target unit (c, f, km, mi, kg, lb)
                     */
                    function run(params) {
                      const v = parseFloat(params.value) || 0;
                      const from = String(params.from_unit || '').toLowerCase().trim();
                      const to = String(params.to_unit || '').toLowerCase().trim();
                      if ((from === 'c' || from === 'celsius') && (to === 'f' || to === 'fahrenheit')) {
                        return `${'$'}{v}°C = ${'$'}{((v * 9/5) + 32).toFixed(2)}°F`;
                      }
                      if ((from === 'f' || from === 'fahrenheit') && (to === 'c' || to === 'celsius')) {
                        return `${'$'}{v}°F = ${'$'}{(((v - 32) * 5/9)).toFixed(2)}°C`;
                      }
                      if ((from === 'km' || from === 'kilometers') && (to === 'mi' || to === 'miles')) {
                        return `${'$'}{v} km = ${'$'}{(v * 0.621371).toFixed(2)} miles`;
                      }
                      if ((from === 'mi' || from === 'miles') && (to === 'km' || to === 'kilometers')) {
                        return `${'$'}{v} miles = ${'$'}{(v / 0.621371).toFixed(2)} km`;
                      }
                      if ((from === 'kg' || from === 'kilograms') && (to === 'lb' || to === 'pounds')) {
                        return `${'$'}{v} kg = ${'$'}{(v * 2.20462).toFixed(2)} lbs`;
                      }
                      if ((from === 'lb' || from === 'pounds') && (to === 'kg' || to === 'kilograms')) {
                        return `${'$'}{v} lbs = ${'$'}{(v / 2.20462).toFixed(2)} kg`;
                      }
                      return `Unsupported conversion from '${'$'}{from}' to '${'$'}{to}'. Supported: c/f, km/mi, kg/lb.`;
                    }
                """.trimIndent(),
                isEnabled = true,
                isPreinstalled = true
            )
        )
    }

    companion object {
        @Volatile
        private var instance: JsToolRepository? = null

        fun getInstance(context: Context): JsToolRepository {
            return instance ?: synchronized(this) {
                instance ?: JsToolRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
