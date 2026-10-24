package com.textmorph.app.util

import com.textmorph.app.llm.LlmEngine

/**
 * Handles text-based emoji triggers " :(emotion): " and ASCII/Kaomoji
 * text-art triggers " T:(requested emotion): ".
 */
object EmojiTextArtHelper {

    private const val TAG = "EmojiTextArtHelper"

    // Matches :(emotion): or :emotion:
    val EMOJI_TRIGGER_REGEX = Regex("""(?<!\S):\(?([a-zA-Z0-9_\-\s]+)\)?:\s?""")

    // Matches T:(requested emotion): or T:requested emotion:
    val TEXT_ART_TRIGGER_REGEX = Regex("""(?<!\S)T:\(?([a-zA-Z0-9_\-\s]+)\)?:\s?""")

    private val STANDARD_EMOJIS = mapOf(
        "happy" to "😊",
        "smile" to "😄",
        "laugh" to "😂",
        "crying laughing" to "😂🤣",
        "love" to "❤️",
        "heart" to "❤️",
        "fire" to "🔥",
        "lit" to "🔥",
        "sad" to "😢",
        "cry" to "😭",
        "party" to "🎉🥳",
        "celebrate" to "🎉✨",
        "celebration" to "🎉🥳",
        "cool" to "😎",
        "sunglasses" to "😎",
        "wink" to "😉",
        "thinking" to "🤔",
        "think" to "🤔",
        "angry" to "😡",
        "rage" to "🤬",
        "thumbs up" to "👍",
        "thumbsup" to "👍",
        "ok" to "👌",
        "clap" to "👏",
        "rocket" to "🚀",
        "sparkles" to "✨",
        "magic" to "✨🪄",
        "pray" to "🙏",
        "please" to "🙏",
        "thanks" to "🙏",
        "mind blown" to "🤯",
        "shocked" to "😱",
        "sleepy" to "😴",
        "tired" to "🥱",
        "kiss" to "😘",
        "eyes" to "👀",
        "100" to "💯",
        "star" to "⭐",
        "warning" to "⚠️",
        "check" to "✅",
        "cross" to "❌"
    )

    private val STANDARD_TEXT_ART = mapOf(
        "shrug" to """¯\_(ツ)_/¯""",
        "table flip" to """(╯°□°)╯︵ ┻━┻""",
        "flip" to """(╯°□°)╯︵ ┻━┻""",
        "table put back" to """┬─┬ノ( º _ ºノ)""",
        "unflip" to """┬─┬ノ( º _ ºノ)""",
        "happy" to """(づ｡◕‿‿◕｡)づ""",
        "joy" to """(＾▽＾)""",
        "cat" to """(=^･ω･^=)""",
        "kitty" to """(=^･ω･^=)""",
        "bear" to """ʕ•ᴥ•ʔ""",
        "dog" to """(U・ᴥ・U)""",
        "dance" to """♪┏(・o･)┛♪""",
        "love" to """(♥_♥)""",
        "heart" to """(｡♥‿♥｡)""",
        "disapproval" to """ಠ_ಠ""",
        "lenny" to """( ͡° ͜ʖ ͡°)""",
        "cry" to """(╥﹏╥)""",
        "sad" to """(ಥ﹏ಥ)""",
        "confused" to """(⊙_☉)""",
        "salute" to """(｀-´)ゞ""",
        "peace" to """✌(-‿-)✌""",
        "sword" to """o()xxxx[{:::::::::::::::::::>"""
    )

    fun findEmojiMatch(text: String): MatchResult? = EMOJI_TRIGGER_REGEX.find(text)

    fun findTextArtMatch(text: String): MatchResult? = TEXT_ART_TRIGGER_REGEX.find(text)

    /**
     * Resolves an emotion string to emoji(s). Returns instant match if available,
     * or invokes the LLM engine for uncommon or complex emotions.
     */
    suspend fun resolveEmoji(emotionRaw: String, engine: LlmEngine? = null): String {
        val cleanEmotion = emotionRaw.trim().lowercase()
        val instant = STANDARD_EMOJIS[cleanEmotion]
        if (instant != null) return instant

        if (engine != null && engine.isLoaded()) {
            return try {
                val instruction = "Convert the emotion into 1-3 appropriate emojis. Output ONLY the emojis directly without words or punctuation."
                val output = engine.generate(cleanEmotion, instruction).trim()
                if (output.isNotBlank() && !output.startsWith("Error")) {
                    output
                } else "😊"
            } catch (e: Exception) {
                AppLogger.w(TAG, "LLM emoji conversion failed: ${e.message}")
                "😊"
            }
        }
        return "😊"
    }

    /**
     * Resolves a text art request. Returns instant kaomoji/ASCII art if available,
     * or invokes the LLM engine for custom drawings.
     */
    suspend fun resolveTextArt(requestRaw: String, engine: LlmEngine? = null): String {
        val cleanRequest = requestRaw.trim().lowercase()
        val instant = STANDARD_TEXT_ART[cleanRequest]
        if (instant != null) return instant

        if (engine != null && engine.isLoaded()) {
            return try {
                val instruction = "Create concise text art or kaomoji representing the user's request. Output ONLY the raw text art directly without explanations or markdown formatting."
                val output = engine.generate(cleanRequest, instruction).trim()
                if (output.isNotBlank() && !output.startsWith("Error")) {
                    output
                } else "¯\\_(ツ)_/¯"
            } catch (e: Exception) {
                AppLogger.w(TAG, "LLM text art generation failed: ${e.message}")
                "¯\\_(ツ)_/¯"
            }
        }
        return "¯\\_(ツ)_/¯"
    }
}
