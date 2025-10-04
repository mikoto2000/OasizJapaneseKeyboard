package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple dictionary-backed converter using assets TSV.
 * Format (UTF-8, tab-separated, one per line):
 * reading_hiragana<TAB>word<TAB>cost(optional)
 */
class DictionaryConverter(
    private val context: Context,
    private val assetPath: String = "dictionary/words.tsv"
) : JapaneseConverter {

    private val loaded = AtomicBoolean(false)
    private val entries: MutableMap<String, MutableList<Pair<String, Int>>> = HashMap()

    override fun query(readingHiragana: String): List<String> {
        if (readingHiragana.isEmpty()) return emptyList()
        ensureLoaded()
        val base = LinkedHashSet<String>()
        // Always include reading itself and katakana as baseline
        base += readingHiragana
        base += hiraganaToKatakana(readingHiragana)
        // Exact match candidates from dictionary
        entries[readingHiragana]?.let { list ->
            val sorted = list.sortedBy { it.second }
            for ((w, _) in sorted) base += w
        }
        // Fallback to SimpleConverter extras for better feel
        if (base.size < 2) {
            val extra = SimpleConverter().query(readingHiragana)
            base.addAll(extra)
        }
        return base.toList()
    }

    private fun ensureLoaded() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            try {
                context.assets.open(assetPath).use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            val t = line.trim()
                            if (t.isEmpty() || t.startsWith("#")) return@forEach
                            val parts = t.split('\t')
                            if (parts.size >= 2) {
                                val reading = parts[0]
                                val word = parts[1]
                                val cost = parts.getOrNull(2)?.toIntOrNull() ?: 1000
                                val list = entries.getOrPut(reading) { ArrayList() }
                                list.add(word to cost)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Asset not found or parse error: keep map empty and rely on fallback
            } finally {
                loaded.set(true)
            }
        }
    }

    private fun hiraganaToKatakana(hira: String): String {
        val sb = StringBuilder(hira.length)
        for (ch in hira) {
            if (ch in '\u3041'..'\u3096') sb.append(ch + 0x60) else sb.append(ch)
        }
        return sb.toString()
    }
}

