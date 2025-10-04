package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class SqliteDictionaryConverter(private val context: Context) : JapaneseConverter {
    private val dbFile: File by lazy {
        File(context.filesDir, "dictionary/words.db")
    }

    // Ensure DB exists: copy from assets if available; otherwise build from TSV asset.
    private fun ensureDb() {
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            // Try copy prebuilt DB from assets
            if (assetExists("dictionary/words.db")) {
                copyAssetToFile("dictionary/words.db", dbFile)
            } else if (assetExists("dictionary/words.tsv")) {
                // Fallback: build from TSV asset (costly for large files; recommended to prepackage DB)
                buildDbFromTsv("dictionary/words.tsv", dbFile)
            } else {
                // Nothing available; create empty DB
                createSchema(dbFile)
            }
        }
        ensureAuxSchema()
    }


    override fun query(readingHiragana: String): List<String> {
        if (readingHiragana.isEmpty()) return emptyList()
        ensureDb()

        val limit = 50
        val out = LinkedHashSet<String>()
        out += readingHiragana
        out += hiraganaToKatakana(readingHiragana)

        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // Exact with learning priority
            db.rawQuery(
                "SELECT e.word, IFNULL(l.freq,0) AS f, e.cost FROM entries e LEFT JOIN learn l ON l.reading = ? AND l.word = e.word WHERE e.reading = ? ORDER BY f DESC, e.cost ASC LIMIT ?",
                arrayOf(readingHiragana, readingHiragana, limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out += c.getString(0)
                }
            }
            // Prefix (exclude exact reading). Aggregate by word with min cost and learning freq.
            if (out.size < limit) {
                val remain = limit - out.size
                db.rawQuery(
                    "SELECT e.word, MIN(e.cost) as c, MAX(IFNULL(l.freq,0)) as f FROM entries e LEFT JOIN learn l ON l.reading = ? AND l.word = e.word WHERE e.reading LIKE ? || '%' AND e.reading <> ? GROUP BY e.word ORDER BY f DESC, c ASC LIMIT ?",
                    arrayOf(readingHiragana, readingHiragana, readingHiragana, remain.toString())
                ).use { c ->
                    while (c.moveToNext() && out.size < limit) {
                        val w = c.getString(0)
                        if (!out.contains(w)) out += w
                    }
                }
            }
        } finally {
            db.close()
        }
        if (out.size <= 2) out.addAll(SimpleConverter().query(readingHiragana))
        return out.toList()
    }

    private fun assetExists(path: String): Boolean {
        val dir = File(path).parent ?: ""
        val name = File(path).name
        return try {
            context.assets.list(dir)?.contains(name) == true
        } catch (e: Exception) { false }
    }

    private fun copyAssetToFile(assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun createSchema(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS entries (reading TEXT NOT NULL, word TEXT NOT NULL, cost INTEGER NOT NULL, PRIMARY KEY(reading, word))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_reading ON entries(reading)")
        db.close()
    }

    private fun ensureAuxSchema() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS learn (reading TEXT NOT NULL, word TEXT NOT NULL, freq INTEGER NOT NULL DEFAULT 0, last_used INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(reading, word))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_learn_reading ON learn(reading)")
        db.close()
    }

    private fun buildDbFromTsv(assetPath: String, file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("PRAGMA journal_mode = WAL")
        db.execSQL("PRAGMA synchronous = NORMAL")
        db.execSQL("CREATE TABLE IF NOT EXISTS entries (reading TEXT NOT NULL, word TEXT NOT NULL, cost INTEGER NOT NULL, PRIMARY KEY(reading, word))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_reading ON entries(reading)")

        val insertSql = "INSERT OR REPLACE INTO entries(reading, word, cost) VALUES(?, ?, ?)"
        val stmt: SQLiteStatement = db.compileStatement(insertSql)

        var count = 0
        db.beginTransaction()
        try {
            context.assets.open(assetPath).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        val t = line.trim()
                        if (t.isEmpty() || t.startsWith("#")) return@forEach
                        val parts = t.split('\t')
                        if (parts.size < 2) return@forEach
                        val reading = katakanaToHiragana(parts[0])
                        val word = parts[1]
                        val cost = parts.getOrNull(2)?.toIntOrNull() ?: 1000
                        stmt.clearBindings()
                        stmt.bindString(1, reading)
                        stmt.bindString(2, word)
                        stmt.bindLong(3, cost.toLong())
                        stmt.executeInsert()
                        count++
                        if (count % 5000 == 0) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun katakanaToHiragana(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch in '\u30A1'..'\u30F6') sb.append(ch - 0x60) else sb.append(ch)
        }
        return sb.toString()
    }

    private fun hiraganaToKatakana(hira: String): String {
        val sb = StringBuilder(hira.length)
        for (ch in hira) {
            if (ch in '\u3041'..'\u3096') sb.append(ch + 0x60) else sb.append(ch)
        }
        return sb.toString()
    }

    override fun recordSelection(readingHiragana: String, word: String) {
        ensureDb()
        val now = System.currentTimeMillis()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            val updated = db.compileStatement("UPDATE learn SET freq = freq + 1, last_used = ? WHERE reading = ? AND word = ?").apply {
                bindLong(1, now)
                bindString(2, readingHiragana)
                bindString(3, word)
            }.executeUpdateDelete()
            if (updated == 0) {
                db.compileStatement("INSERT OR IGNORE INTO learn(reading, word, freq, last_used) VALUES(?,?,1,?)").apply {
                    bindString(1, readingHiragana)
                    bindString(2, word)
                    bindLong(3, now)
                }.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }
}
