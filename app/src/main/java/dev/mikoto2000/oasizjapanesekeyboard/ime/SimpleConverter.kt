package dev.mikoto2000.oasizjapanesekeyboard.ime

interface JapaneseConverter {
    fun query(readingHiragana: String): List<String>
}

/**
 * Placeholder converter before Mozc integration.
 * Returns: [ひらがな, カタカナ, 一部ハードコード変換候補...]
 */
class SimpleConverter : JapaneseConverter {
    private val dict = mapOf(
        "わたし" to listOf("私"),
        "にほん" to listOf("日本"),
        "にっぽん" to listOf("日本"),
        "がっこう" to listOf("学校"),
        "きょう" to listOf("今日", "京都"),
        "とうきょう" to listOf("東京"),
        "ありがとうございます" to listOf("有難うございます", "ありがとうございます"),
    )

    override fun query(readingHiragana: String): List<String> {
        if (readingHiragana.isEmpty()) return emptyList()
        val base = mutableListOf<String>()
        base += readingHiragana
        base += hiraganaToKatakana(readingHiragana)
        dict[readingHiragana]?.let { base.addAll(it) }
        return base.distinct()
    }

    private fun hiraganaToKatakana(hira: String): String {
        val sb = StringBuilder()
        for (ch in hira) {
            if (ch in '\u3041'..'\u3096') {
                sb.append(ch + 0x60)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}

