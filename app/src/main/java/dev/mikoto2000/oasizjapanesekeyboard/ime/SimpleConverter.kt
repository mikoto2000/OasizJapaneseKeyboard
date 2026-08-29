package dev.mikoto2000.oasizjapanesekeyboard.ime

interface JapaneseConverter {
    fun query(readingHiragana: String): List<String>
    /** A bounded query used for the first, latency-sensitive candidate batch. */
    fun query(readingHiragana: String, limit: Int, includePredictions: Boolean): List<String> =
        query(readingHiragana).take(limit)

    /** Lightweight check used while finding segment boundaries. */
    fun hasExactCandidates(readingHiragana: String): Boolean =
        query(readingHiragana, 3, false).size > 2
    // Optional: record selection for learning. Default no-op.
    fun recordSelection(readingHiragana: String, word: String) {}
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

    override fun query(readingHiragana: String, limit: Int, includePredictions: Boolean): List<String> =
        query(readingHiragana).take(limit)

    override fun hasExactCandidates(readingHiragana: String): Boolean =
        dict[readingHiragana].isNullOrEmpty().not()

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
