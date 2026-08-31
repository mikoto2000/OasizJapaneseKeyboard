package dev.mikoto2000.oasizjapanesekeyboard.ime

/**
 * Simple streaming Romaji -> Kana converter for IME composing.
 * - Greedy consume with longest-match mapping (3, 2, 1 chars)
 * - Handles sokuon (double consonant except 'n') -> っ
 * - Handles 'nn' -> ん, and single 'n' before non-vowel/y or at flush -> ん
 * - Keeps produced Kana separate from pending romaji buffer
 */
class RomajiConverter {
    private val produced = StringBuilder()
    private val buffer = StringBuilder()

    private val vowels = setOf('a','i','u','e','o')

    // Base romaji->kana table (not exhaustive but practical)
    private val map = mapOf(
        // Vowels
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",

        // K
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "kwa" to "くぁ", "kwi" to "くぃ", "kwu" to "くぅ", "kwe" to "くぇ", "kwo" to "くぉ",

        // S
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "sha" to "しゃ", "shu" to "しゅ", "she" to "しぇ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",

        // T
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",
        "cha" to "ちゃ", "chu" to "ちゅ", "che" to "ちぇ", "cho" to "ちょ",
        "cya" to "ちゃ", "cyu" to "ちゅ", "cyo" to "ちょ",
        "tya" to "ちゃ", "tyi" to "ちぃ", "tyu" to "ちゅ", "tye" to "ちぇ", "tyo" to "ちょ",
        "tha" to "てゃ", "thi" to "てぃ", "thu" to "てゅ", "the" to "てぇ", "tho" to "てょ",
        "tsa" to "つぁ", "tsi" to "つぃ", "tse" to "つぇ", "tso" to "つぉ",

        // N
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",

        // H
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",

        // M
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",

        // Y
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",

        // R
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",

        // W
        "wa" to "わ", "wi" to "うぃ", "we" to "うぇ", "wo" to "を",

        // G
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "gwa" to "ぐぁ", "gwi" to "ぐぃ", "gwu" to "ぐぅ", "gwe" to "ぐぇ", "gwo" to "ぐぉ",

        // Z/J
        "za" to "ざ", "zi" to "じ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ja" to "じゃ", "ju" to "じゅ", "je" to "じぇ", "jo" to "じょ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",

        // D
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
        "dha" to "でゃ", "dhi" to "でぃ", "dhu" to "でゅ", "dhe" to "でぇ", "dho" to "でょ",

        // B
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",

        // P
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",

        // F (extended)
        "fa" to "ふぁ", "fi" to "ふぃ", "fyu" to "ふゅ", "fe" to "ふぇ", "fo" to "ふぉ",

        // V (extended)
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
        "vya" to "ゔゃ", "vyu" to "ゔゅ", "vyo" to "ゔょ",

        // Small vowels (optional)
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
        "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
        "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
        "xtu" to "っ", "ltu" to "っ", "ltsu" to "っ"
    )
    private val maxMapKeyLength = map.keys.maxOf { it.length }

    fun clear() {
        produced.clear()
        buffer.clear()
    }

    fun hasComposing(): Boolean = produced.isNotEmpty() || buffer.isNotEmpty()

    fun pushChar(c: Char) {
        val ch = c.lowercaseChar()
        if (ch !in 'a'..'z') return // ignore non-letters here
        buffer.append(ch)
        consume()
    }

    fun backspace() {
        if (buffer.isNotEmpty()) {
            buffer.deleteCharAt(buffer.lastIndex)
            return
        }
        if (produced.isNotEmpty()) {
            produced.deleteCharAt(produced.lastIndex)
        }
    }

    fun getComposing(): String {
        // Show produced kana and any pending raw romaji so consonants are visible while composing.
        return produced.toString() + buffer.toString()
    }

    fun flush(): String {
        // Finalize pending buffer (resolve 'n' to ん, and emit any leftover romaji literally)
        finalizeN()
        val out = produced.toString() + buffer.toString()
        clear()
        return out
    }

    fun restoreFromKana(kana: String) {
        produced.setLength(0)
        produced.append(kana)
        buffer.setLength(0)
    }

    /** Adds a kana symbol without committing the current composition. */
    fun appendKana(text: String) {
        finalizeN()
        if (buffer.isNotEmpty()) {
            produced.append(buffer)
            buffer.clear()
        }
        produced.append(text)
    }

    private fun consume() {
        // Handle sokuon for double consonants (except 'n') at buffer head
        while (true) {
            if (buffer.length >= 2) {
                val c1 = buffer[0]
                val c2 = buffer[1]
                if (c1 == c2 && c1 !in vowels && c1 != 'n') {
                    // っ then drop one leading consonant
                    produced.append('っ')
                    buffer.deleteCharAt(0)
                    continue
                }
            }

            // Keep the second n so `nna` becomes `んな`. With exactly `nn`, wait
            // for the next key; flush() resolves it to a single ん.
            if (buffer.startsWith("nn")) {
                if (buffer.length == 2) break
                produced.append('ん')
                buffer.deleteCharAt(0)
                continue
            }

            // If buffer starts with single 'n' followed by non-vowel and not 'y', commit ん
            if (buffer.length >= 2 && buffer[0] == 'n') {
                val nxt = buffer[1]
                if (nxt !in vowels && nxt != 'y') {
                    produced.append('ん')
                    buffer.deleteCharAt(0)
                    continue
                }
            }

            // Try the longest registered romaji sequence first.
            val maxTry = minOf(buffer.length, maxMapKeyLength)
            val consumed = (maxTry downTo 1).firstOrNull {
                map.containsKey(buffer.substring(0, it))
            } ?: 0
            if (consumed > 0) {
                val key = buffer.substring(0, consumed)
                val kana = map[key]!!
                produced.append(kana)
                buffer.delete(0, consumed)
                continue
            }
            break
        }
    }

    private fun finalizeN() {
        if (buffer.toString() == "n" || buffer.toString() == "nn") {
            produced.append('ん')
            buffer.clear()
        }
    }
}
