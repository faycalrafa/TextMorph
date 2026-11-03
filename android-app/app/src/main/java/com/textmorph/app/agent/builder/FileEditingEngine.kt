package com.textmorph.app.agent.builder

import com.textmorph.app.util.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * Production-ready File Editing & Tool-Execution Engine designed specifically for tiny on-device SLMs (1.5B–4B).
 * Utilizes a Plain-Text Block Protocol (Zero JSON escaping) paired with ProgressiveMatcher,
 * lazy comment rejection, and syntax validation with memory rollback.
 */
class FileEditingEngine(private val workspaceRoot: File) {

    companion object {
        private const val TAG = "FileEditingEngine"

        val TINY_MODEL_SYSTEM_PROMPT = """
            You are an expert coding agent. To inspect and edit files, use ONLY these plain-text blocks. Never output JSON for tools.

            1. READ FILE:
            <read file="path/to/file" start="1" end="50" />

            2. CREATE OR OVERWRITE FILE:
            <<<<<<< WRITE path/to/file
            <!DOCTYPE html>
            <html>
            <body><h1>Hello World</h1></body>
            </html>
            >>>>>>> END

            3. EDIT FILE (SEARCH/REPLACE):
            <<<<<<< EDIT path/to/file
            <<<<<<< SEARCH
                <h1>Hello World</h1>
            =======
                <h1>Hello Aquarium</h1>
            >>>>>>> REPLACE

            Rules:
            - In SEARCH, provide 3-5 exact consecutive lines from the file to guarantee uniqueness.
            - In REPLACE, output the exact new code. Never use lazy placeholders like '// ... existing code ...'.
            - Always think briefly inside <think>...</think> before outputting tool blocks.
        """.trimIndent()

        private val READ_REGEX = Pattern.compile(
            """<read\s+file="(?<file>[^"]+)"(?:\s+start="(?<start>\d+)")?(?:\s+end="(?<end>\d+)")?\s*/>""",
            Pattern.CASE_INSENSITIVE
        )

        private val WRITE_REGEX = Pattern.compile(
            """<<<<<<<\s*WRITE\s+(?<file>[^\r\n]+)\r?\n(?<content>.*?)\r?\n>>>>>>>\s*END""",
            Pattern.DOTALL
        )

        private val EDIT_REGEX = Pattern.compile(
            """<<<<<<<\s*EDIT\s+(?<file>[^\r\n]+)\r?\n<<<<<<<\s*SEARCH\r?\n(?<search>.*?)\r?\n=======\r?\n(?<replace>.*?)\r?\n>>>>>>>\s*REPLACE""",
            Pattern.DOTALL
        )
    }

    data class ToolResult(
        val type: String, // "read", "write", "edit"
        val file: String,
        val output: String,
        val isError: Boolean
    )

    fun resolveFile(relPath: String): File {
        val file = File(workspaceRoot, relPath.trim()).canonicalFile
        val root = workspaceRoot.canonicalFile
        if (!file.path.startsWith(root.path)) {
            throw SecurityException("Access denied: path '$relPath' escapes workspace boundary.")
        }
        return file
    }

    fun readFile(relPath: String, start: Int? = null, end: Int? = null): String {
        return try {
            val file = resolveFile(relPath)
            if (!file.exists()) return "ERROR: File '$relPath' does not exist."
            if (file.isDirectory) return "ERROR: '$relPath' is a directory, not a file."

            val lines = file.readLines(Charsets.UTF_8)
            val total = lines.size
            val s = max(1, start ?: 1)
            val e = min(total, end ?: total)

            if (s > total) return "ERROR: Requested start line $s exceeds total lines ($total)."

            val slice = lines.subList(s - 1, e)
            val formatted = slice.mapIndexed { idx, line ->
                String.format(Locale.US, "%4d | %s", s + idx, line)
            }.joinToString("\n")

            "--- $relPath (lines $s-$e of $total) ---\n$formatted"
        } catch (t: Throwable) {
            AppLogger.e(TAG, "readFile error on '$relPath': ${t.message}", t)
            "ERROR: Failed reading '$relPath': ${t.message}"
        }
    }

    fun writeFile(relPath: String, content: String): String {
        return try {
            val file = resolveFile(relPath)

            // Syntax validation before writing
            val syntaxErr = validateSyntax(relPath, content)
            if (syntaxErr != null) {
                return "ERROR: Syntax validation failed for $relPath: $syntaxErr\nFile was NOT saved."
            }

            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            val lineCount = content.lines().size
            "SUCCESS: Wrote ${content.length} bytes ($lineCount lines) to '$relPath'."
        } catch (t: Throwable) {
            AppLogger.e(TAG, "writeFile error on '$relPath': ${t.message}", t)
            "ERROR: Failed writing '$relPath': ${t.message}"
        }
    }

    fun editFile(relPath: String, searchBlock: String, replaceBlock: String): String {
        return try {
            val file = resolveFile(relPath)
            if (!file.exists()) return "ERROR: File '$relPath' does not exist. Use WRITE to create it first."

            val original = file.readText(Charsets.UTF_8)
            val editResult = ProgressiveMatcher.applyEdit(relPath, original, searchBlock, replaceBlock)

            if (editResult.error != null) {
                return editResult.error
            }

            val newContent = editResult.newContent ?: original

            // In-memory syntax validation before committing to disk
            val syntaxErr = validateSyntax(relPath, newContent)
            if (syntaxErr != null) {
                return "ERROR: Syntax validation failed for $relPath: $syntaxErr\nEdit was ROLLED BACK. The file on disk was not changed."
            }

            file.writeText(newContent, Charsets.UTF_8)

            // Compact context snippet (+/- 5 lines)
            val lines = newContent.lines()
            val ctxStart = max(1, editResult.startLine - 5)
            val ctxEnd = min(lines.size, editResult.startLine + replaceBlock.lines().size + 5)
            val snippet = (ctxStart..ctxEnd).map { i ->
                String.format(Locale.US, "%4d | %s", i, lines[i - 1])
            }.joinToString("\n")

            "SUCCESS: Applied edit to '$relPath' (${editResult.tierName}) at lines ${editResult.startLine}-${editResult.endLine}.\n--- Context (lines $ctxStart-$ctxEnd) ---\n$snippet"
        } catch (t: Throwable) {
            AppLogger.e(TAG, "editFile error on '$relPath': ${t.message}", t)
            "ERROR: Failed editing '$relPath': ${t.message}"
        }
    }

    fun executeLlmResponse(rawLlmOutput: String): List<ToolResult> {
        val results = mutableListOf<ToolResult>()

        // 1. Parse READ blocks
        val readMatcher = READ_REGEX.matcher(rawLlmOutput)
        while (readMatcher.find()) {
            val file = readMatcher.group("file").orEmpty()
            val start = readMatcher.group("start")?.toIntOrNull()
            val end = readMatcher.group("end")?.toIntOrNull()
            val out = readFile(file, start, end)
            results.add(ToolResult("read", file, out, out.startsWith("ERROR:")))
        }

        // 2. Parse WRITE blocks
        val writeMatcher = WRITE_REGEX.matcher(rawLlmOutput)
        while (writeMatcher.find()) {
            val file = writeMatcher.group("file")?.trim().orEmpty()
            val content = writeMatcher.group("content").orEmpty()
            val out = writeFile(file, content)
            results.add(ToolResult("write", file, out, out.startsWith("ERROR:")))
        }

        // 3. Parse EDIT blocks
        val editMatcher = EDIT_REGEX.matcher(rawLlmOutput)
        while (editMatcher.find()) {
            val file = editMatcher.group("file")?.trim().orEmpty()
            val search = editMatcher.group("search").orEmpty()
            val replace = editMatcher.group("replace").orEmpty()
            val out = editFile(file, search, replace)
            results.add(ToolResult("edit", file, out, out.startsWith("ERROR:")))
        }

        return results
    }

    fun validateSyntax(filePath: String, content: String): String? {
        val lower = filePath.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".json") -> {
                try {
                    JSONObject(content)
                    null
                } catch (e: Exception) {
                    "JSON SyntaxError: ${e.message}"
                }
            }
            else -> {
                val allowHash = lower.endsWith(".py") || lower.endsWith(".sh") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                checkBracketsAndQuotes(content, allowHash)
            }
        }
    }

    private fun checkBracketsAndQuotes(content: String, allowHashComments: Boolean): String? {
        val stack = ArrayDeque<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        val lines = content.split('\n')

        var inSingleQuote = false
        var inDoubleQuote = false
        var inBacktick = false
        var inLineComment = false
        var inBlockComment = false

        var i = 0
        val n = content.length
        var lineNo = 1

        while (i < n) {
            val ch = content[i]
            val nxt = if (i + 1 < n) content[i + 1] else ' '

            if (ch == '\n') {
                lineNo++
                inLineComment = false
                i++
                continue
            }

            if (!inSingleQuote && !inDoubleQuote && !inBacktick) {
                if (inBlockComment) {
                    if (ch == '*' && nxt == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                if (ch == '/' && nxt == '/') {
                    inLineComment = true
                    i += 2
                    continue
                }
                if (ch == '/' && nxt == '*') {
                    inBlockComment = true
                    i += 2
                    continue
                }
                if (allowHashComments && ch == '#' && !inBlockComment) {
                    inLineComment = true
                    i++
                    continue
                }
            }

            if (inLineComment || inBlockComment) {
                i++
                continue
            }

            if (ch == '\\' && (inSingleQuote || inDoubleQuote || inBacktick)) {
                i += 2
                continue
            }

            if (ch == '\'' && !inDoubleQuote && !inBacktick) {
                inSingleQuote = !inSingleQuote
                i++
                continue
            }
            if (ch == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote
                i++
                continue
            }
            if (ch == '`' && !inSingleQuote && !inDoubleQuote) {
                inBacktick = !inBacktick
                i++
                continue
            }

            if (inSingleQuote || inDoubleQuote || inBacktick) {
                i++
                continue
            }

            if (ch == '(' || ch == '[' || ch == '{') {
                stack.addLast(Pair(ch, lineNo))
            } else if (ch == ')' || ch == ']' || ch == '}') {
                val expected = pairs[ch]
                if (stack.isEmpty()) {
                    val snippet = lines.getOrNull(lineNo - 1)?.trim().orEmpty()
                    return "Unmatched closing '$ch' at line $lineNo: $snippet"
                }
                val (topChar, topLine) = stack.removeLast()
                if (topChar != expected) {
                    val snippet = lines.getOrNull(lineNo - 1)?.trim().orEmpty()
                    return "Mismatched bracket: expected '$topChar' closed, got '$ch' at line $lineNo: $snippet"
                }
            }
            i++
        }

        if (stack.isNotEmpty()) {
            val (unclosedChar, unclosedLine) = stack.last()
            val snippet = lines.getOrNull(unclosedLine - 1)?.trim().orEmpty()
            return "Unclosed '$unclosedChar' opened at line $unclosedLine: $snippet"
        }

        return null
    }
}
