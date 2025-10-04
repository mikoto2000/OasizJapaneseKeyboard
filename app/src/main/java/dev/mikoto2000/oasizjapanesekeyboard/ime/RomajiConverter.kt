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

        // S
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",

        // T
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",

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
        "wa" to "わ", "wo" to "を",

        // G
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",

        // Z/J
        "za" to "ざ", "zi" to "じ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",

        // D
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",

        // B
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",

        // P
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",

        // F (extended)
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",

        // Small vowels (optional)
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ"
    )

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
        // Show produced kana only; do not show raw pending romaji, except lone 'n' can be tentatively seen as ん?
        // To avoid flicker, keep it simple: only produced + tentative 'ん' if buffer is exactly "n".
        return if (buffer.toString() == "n") produced.toString() + "ん" else produced.toString()
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

            // Handle 'nn' -> ん
            if (buffer.startsWith("nn")) {
                produced.append('ん')
                buffer.delete(0, 2)
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

            // Try longest romaji match (3 -> 2 -> 1)
            val consumed = when {
                buffer.length >= 3 && map.containsKey(buffer.substring(0, 3)) -> 3
                buffer.length >= 2 && map.containsKey(buffer.substring(0, 2)) -> 2
                buffer.length >= 1 && map.containsKey(buffer.substring(0, 1)) -> 1
                else -> 0
            }
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
        if (buffer.toString() == "n") {
            produced.append('ん')
            buffer.clear()
        }
    }
}
