package com.textmorph.app.agent.builder

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * 4-Tier Progressive Matching Engine for tiny on-device SLMs (1.5B–4B).
 * Tier 1: Exact substring match
 * Tier 2: Trimmed line match (ignoring trailing whitespace)
 * Tier 3: Indentation-insensitive match (re-indents replacement relatively)
 * Tier 4: Fuzzy anchor match (anchored boundaries, similarity >= 0.82)
 */
object ProgressiveMatcher {

    val LAZY_COMMENT_PATTERNS = listOf(
        Pattern.compile("//\\s*\\.\\.\\.\\s*(?:existing|remaining|rest|unchanged|previous)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("#\\s*\\.\\.\\.\\s*(?:existing|remaining|rest|unchanged|previous)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/\\*\\s*\\.\\.\\.\\s*(?:existing|remaining|rest|unchanged|previous).*?\\*/", Pattern.CASE_INSENSITIVE or Pattern.DOTALL),
        Pattern.compile("<!--\\s*\\.\\.\\.\\s*(?:existing|remaining|rest|unchanged|previous).*?-->", Pattern.CASE_INSENSITIVE or Pattern.DOTALL),
        Pattern.compile("(?:#|//|/\\*)\\s*(?:rest of code|code remains unchanged|unchanged)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*\\.\\.\\.\\s*$", Pattern.MULTILINE)
    )

    data class EditMatchResult(
        val newContent: String? = null,
        val tierName: String = "",
        val startLine: Int = 1,
        val endLine: Int = 1,
        val error: String? = null
    )

    fun applyEdit(
        filePath: String,
        original: String,
        searchBlock: String,
        replaceBlock: String
    ): EditMatchResult {
        // Step 0: Check for lazy placeholder comments
        for (pattern in LAZY_COMMENT_PATTERNS) {
            val m = pattern.matcher(replaceBlock)
            if (m.find()) {
                val snippet = m.group(0)?.trim().orEmpty()
                return EditMatchResult(
                    error = "ERROR: Lazy placeholder comment detected in REPLACE block: \"$snippet\". You must provide complete code without placeholders."
                )
            }
        }

        val normFile = original.replace("\r\n", "\n").replace("\r", "\n")
        var normSearch = searchBlock.replace("\r\n", "\n").replace("\r", "\n")
        val normReplace = replaceBlock.replace("\r\n", "\n").replace("\r", "\n")

        val fileLines = normFile.lines()
        var searchLines = normSearch.lines()

        if (searchLines.size > 1 && searchLines.last().isEmpty()) {
            searchLines = searchLines.dropLast(1)
            normSearch = searchLines.joinToString("\n")
        }

        // Tier 1: Exact Substring Match
        val occurrences = normFile.windowed(normSearch.length, 1).count { it == normSearch }
        if (occurrences == 1) {
            val idx = normFile.indexOf(normSearch)
            val startLine = normFile.substring(0, idx).count { it == '\n' } + 1
            val matchedCount = normSearch.count { it == '\n' } + 1
            val endLine = startLine + matchedCount - 1
            val newContent = normFile.substring(0, idx) + normReplace + normFile.substring(idx + normSearch.length)
            return EditMatchResult(newContent, "Tier 1 (Exact Match)", startLine, endLine)
        } else if (occurrences > 1) {
            return EditMatchResult(
                error = "ERROR: SEARCH block matched $occurrences places in $filePath. Include 2-3 more surrounding lines in SEARCH to make it unique."
            )
        }

        // Tier 2: Trimmed Line Match (ignoring trailing whitespace)
        val searchRstrip = searchLines.map { it.trimEnd() }
        val fileRstrip = fileLines.map { it.trimEnd() }
        val m = searchRstrip.size
        val n = fileRstrip.size

        val matchesTier2 = mutableListOf<Int>()
        for (i in 0..(n - m)) {
            if (fileRstrip.subList(i, i + m) == searchRstrip) {
                matchesTier2.add(i)
            }
        }

        if (matchesTier2.size == 1) {
            val startIdx = matchesTier2[0]
            val endIdx = startIdx + m
            val newLines = fileLines.take(startIdx) + normReplace.lines() + fileLines.drop(endIdx)
            return EditMatchResult(newLines.joinToString("\n"), "Tier 2 (Trimmed Line Match)", startIdx + 1, endIdx)
        } else if (matchesTier2.size > 1) {
            val occStr = matchesTier2.joinToString(", ") { "line ${it + 1}" }
            return EditMatchResult(error = "ERROR: SEARCH block matched ${matchesTier2.size} places in $filePath (at $occStr). Include 2-3 more surrounding lines.")
        }

        // Tier 3: Indentation-Insensitive Match
        val searchStrip = searchLines.map { it.trim() }
        val fileStrip = fileLines.map { it.trim() }

        val matchesTier3 = mutableListOf<Int>()
        for (i in 0..(n - m)) {
            if (fileStrip.subList(i, i + m) == searchStrip) {
                matchesTier3.add(i)
            }
        }

        if (matchesTier3.size == 1) {
            val startIdx = matchesTier3[0]
            val endIdx = startIdx + m

            val fileIndent = fileLines.subList(startIdx, endIdx).firstOrNull { it.isNotBlank() }
                ?.let { it.take(it.length - it.trimStart().length) } ?: ""
            val searchIndent = searchLines.firstOrNull { it.isNotBlank() }
                ?.let { it.take(it.length - it.trimStart().length) } ?: ""

            val replaceLines = normReplace.lines()
            val reindented = replaceLines.map { rl ->
                if (rl.isBlank()) ""
                else {
                    val curIndent = rl.take(rl.length - rl.trimStart().length)
                    val rel = if (curIndent.startsWith(searchIndent)) curIndent.substring(searchIndent.length) else curIndent
                    fileIndent + rel + rl.trimStart()
                }
            }
            val newLines = fileLines.take(startIdx) + reindented + fileLines.drop(endIdx)
            return EditMatchResult(newLines.joinToString("\n"), "Tier 3 (Indentation-Insensitive Match)", startIdx + 1, endIdx)
        } else if (matchesTier3.size > 1) {
            val occStr = matchesTier3.joinToString(", ") { "line ${it + 1}" }
            return EditMatchResult(error = "ERROR: SEARCH block matched ${matchesTier3.size} places in $filePath (at $occStr). Include 2-3 more surrounding lines.")
        }

        // Tier 4: Fuzzy Anchor Match
        val searchJoined = searchStrip.joinToString("\n")
        val firstSearchNonEmpty = searchStrip.firstOrNull { it.isNotEmpty() } ?: ""
        val lastSearchNonEmpty = searchStrip.lastOrNull { it.isNotEmpty() } ?: ""

        var bestRatio = 0.0
        var bestStart = -1
        var bestEnd = -1

        for (windowLen in max(1, m - 2)..min(n, m + 2)) {
            for (i in 0..(n - windowLen)) {
                val cand = fileStrip.subList(i, i + windowLen)
                val candFirst = cand.firstOrNull { it.isNotEmpty() } ?: ""
                val candLast = cand.lastOrNull { it.isNotEmpty() } ?: ""

                if (firstSearchNonEmpty.isNotEmpty() && candFirst.isNotEmpty() && stringSimilarity(firstSearchNonEmpty, candFirst) < 0.70) continue
                if (lastSearchNonEmpty.isNotEmpty() && candLast.isNotEmpty() && stringSimilarity(lastSearchNonEmpty, candLast) < 0.70) continue

                val candJoined = cand.joinToString("\n")
                val ratio = stringSimilarity(searchJoined, candJoined)
                if (ratio > bestRatio) {
                    bestRatio = ratio
                    bestStart = i
                    bestEnd = i + windowLen
                }
            }
        }

        if (bestRatio >= 0.88 && bestStart != -1) {
            val newLines = fileLines.take(bestStart) + normReplace.lines() + fileLines.drop(bestEnd)
            return EditMatchResult(newLines.joinToString("\n"), String.format(Locale.US, "Tier 4 (Fuzzy Anchor Match %.2f)", bestRatio), bestStart + 1, bestEnd)
        }

        // 0 Matches: Closest candidate suggestion
        var closestRatio = 0.0
        var closestStart = 0
        var closestEnd = min(n, m)

        for (i in 0..max(0, n - m)) {
            val window = fileStrip.subList(i, min(n, i + m))
            val ratio = stringSimilarity(searchJoined, window.joinToString("\n"))
            if (ratio > closestRatio) {
                closestRatio = ratio
                closestStart = i
                closestEnd = min(n, i + m)
            }
        }

        if (closestRatio >= 0.40 && n > 0) {
            val suggestedSlice = fileLines.subList(closestStart, closestEnd)
            val suggested = suggestedSlice.mapIndexed { idx, line -> "${closestStart + 1 + idx}: $line" }.joinToString("\n")
            return EditMatchResult(
                error = String.format(Locale.US, "ERROR: SEARCH block not found in %s (similarity %.2f). Did you mean this section (lines %d-%d)? Copy it verbatim:\n%s", filePath, closestRatio, closestStart + 1, closestEnd, suggested)
            )
        }

        return EditMatchResult(error = "ERROR: SEARCH block not found in $filePath. The file has $n lines. Use <read file=\"...\" /> to view current file contents.")
    }

    fun stringSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val totalLen = s1.length + s2.length
        val dist = levenshteinDistance(s1, s2)
        return (totalLen - dist).coerceAtLeast(0).toDouble() / totalLen
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        var prev = IntArray(s2.length + 1) { it }
        var curr = IntArray(s2.length + 1)

        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[s2.length]
    }
}
