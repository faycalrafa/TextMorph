package com.textmorph.app.agent.memory

import android.content.Context
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thread-safe service for persisting and retrieving learned multi-step task routines.
 * Enables zero-LLM instant replay for familiar user requests.
 * 1-to-1 port of PrivateAgent's SkillMemoryService.
 */
class SkillMemoryService(private val storageDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val TAG = "SkillMemoryService"
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val stopWords = setOf(
        "to", "and", "the", "a", "in", "of", "for", "on", "with", "at", "by", "from", "go", "turn", "open"
    )

    private val memoryFile: File
        get() = File(storageDir, "skills_memory.jsonl")

    private var cachedSkills: MutableList<SavedSkill> = mutableListOf()
    private var isLoaded = false

    private suspend fun loadSkillsInternal() {
        if (isLoaded) return
        try {
            val file = memoryFile
            if (!file.exists()) {
                isLoaded = true
                return
            }
            val loaded = mutableListOf<SavedSkill>()
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        loaded.add(json.decodeFromString<SavedSkill>(trimmed))
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed parsing skill line: ${e.message}")
                    }
                }
            }
            cachedSkills = loaded
            isLoaded = true
            AppLogger.d(TAG, "Loaded ${cachedSkills.size} skills from memory.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading skills memory: ${e.message}", e)
            isLoaded = true
        }
    }

    private suspend fun saveAllSkillsInternal() {
        try {
            val file = memoryFile
            val content = buildString {
                for (skill in cachedSkills) {
                    appendLine(json.encodeToString(skill))
                }
            }
            file.writeText(content)
            AppLogger.d(TAG, "Saved ${cachedSkills.size} skills to memory.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed saving skills memory: ${e.message}", e)
        }
    }

    fun extractKeywords(text: String): List<String> {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopWords }
        return words
    }

    fun jaccardSimilarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val setA = a.toSet()
        val setB = b.toSet()
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    suspend fun findSkill(taskGoal: String): SavedSkill? = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadSkillsInternal()
            if (cachedSkills.isEmpty()) return@withLock null

            val queryKeywords = extractKeywords(taskGoal)
            var bestMatch: SavedSkill? = null
            var highestSim = 0.0

            for (skill in cachedSkills) {
                val sim = jaccardSimilarity(queryKeywords, skill.taskKeywords)
                if (sim > highestSim) {
                    highestSim = sim
                    bestMatch = skill
                }
            }

            if (highestSim > 0.6) {
                AppLogger.i(TAG, "Found matching skill '${bestMatch?.task}' with similarity $highestSim")
                return@withLock bestMatch
            }
            return@withLock null
        }
    }

    suspend fun saveSkill(taskGoal: String, steps: List<ActionStep>) = withContext(Dispatchers.IO) {
        if (steps.isEmpty()) return@withContext
        mutex.withLock {
            loadSkillsInternal()
            val queryKeywords = extractKeywords(taskGoal)

            for (skill in cachedSkills) {
                if (jaccardSimilarity(queryKeywords, skill.taskKeywords) > 0.8) {
                    skill.successCount++
                    // Replace steps if the new execution was more efficient (fewer steps)
                    if (steps.size < skill.steps.size) {
                        val index = cachedSkills.indexOf(skill)
                        if (index != -1) {
                            cachedSkills[index] = skill.copy(steps = steps, successCount = skill.successCount)
                        }
                    }
                    saveAllSkillsInternal()
                    AppLogger.i(TAG, "Updated existing skill '${skill.task}' (successCount=${skill.successCount})")
                    return@withLock
                }
            }

            val newSkill = SavedSkill(
                id = System.currentTimeMillis().toString(),
                task = taskGoal,
                taskKeywords = queryKeywords,
                successCount = 1,
                failCount = 0,
                lastUsedTimestamp = System.currentTimeMillis(),
                steps = steps
            )
            cachedSkills.add(newSkill)
            saveAllSkillsInternal()
            AppLogger.i(TAG, "Created and persisted new skill '${newSkill.task}' with ${steps.size} steps.")
        }
    }

    suspend fun recordFailure(skillId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadSkillsInternal()
            val index = cachedSkills.indexOfFirst { it.id == skillId }
            if (index != -1) {
                val existing = cachedSkills[index]
                existing.failCount++
                saveAllSkillsInternal()
                AppLogger.w(TAG, "Recorded replay failure for skill '$skillId' (failCount=${existing.failCount})")
            }
        }
    }
}
