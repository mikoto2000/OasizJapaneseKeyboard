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
        val limit = 50
        val out = LinkedHashSet<String>()
        // Baseline: echo reading and katakana
        out += readingHiragana
        out += hiraganaToKatakana(readingHiragana)
        // Exact matches first
        entries[readingHiragana]?.let { list ->
            list.sortedBy { it.second }.forEach { (w, _) ->
                out += w
            }
        }
        if (out.size < limit) {
            val prefix = readingHiragana
            val grouped = mutableMapOf<String, Int>()
            // Aggregate min cost per word over all keys starting with prefix (excluding exact key)
            for ((k, lst) in entries) {
                if (k == prefix || !k.startsWith(prefix)) continue
                for ((w, c) in lst) {
                    val prev = grouped[w]
                    if (prev == null || c < prev) grouped[w] = c
                }
            }
            grouped.toList().sortedBy { it.second }.forEach { (w, _) ->
                if (!out.contains(w)) out += w
                if (out.size >= limit) return out.toList()
            }
        }
        if (out.size < 2) out.addAll(SimpleConverter().query(readingHiragana))
        return out.toList()
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
