package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class SqliteDictionaryConverter(private val context: Context) : JapaneseConverter {
    private companion object {
        // Prediction quality remains broad enough for a candidate bar while avoiding
        // unbounded aggregation for one-character readings.
        const val PREFIX_SCAN_LIMIT = 384
        const val ASSET_COPY_BUFFER_SIZE = 1024 * 1024
    }

    private val dbFile: File by lazy {
        File(context.filesDir, "dictionary/words.db")
    }
    private val initLock = Any()
    private data class QueryKey(val reading: String, val limit: Int, val predictions: Boolean)
    private val candidateCache = object : LinkedHashMap<QueryKey, List<String>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<QueryKey, List<String>>): Boolean =
            size > 128
    }
    private val segmentCache = object : LinkedHashMap<String, Int>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > 128
    }
    @Volatile private var dbReady = false
    private val readDbs = ConcurrentHashMap<Thread, SQLiteDatabase>()

    // Ensure DB exists: copy from assets if available; otherwise build from TSV asset.
    private fun ensureDb() {
        if (dbReady && dbFile.exists()) return
        synchronized(initLock) {
            if (dbReady && dbFile.exists()) return
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
            dbReady = true
        }
    }

    fun preload() {
        ensureDb()
        openReadDb()
    }

    fun close() {
        synchronized(initLock) {
            readDbs.values.forEach { db -> if (db.isOpen) db.close() }
            readDbs.clear()
        }
    }


    override fun query(readingHiragana: String): List<String> {
        return query(readingHiragana, 50, true)
    }

    override fun query(readingHiragana: String, limit: Int, includePredictions: Boolean): List<String> {
        if (readingHiragana.isEmpty()) return emptyList()
        if (limit <= 0) return emptyList()
        val key = QueryKey(readingHiragana, limit, includePredictions)
        synchronized(candidateCache) {
            candidateCache[key]?.let { return it }
            // A completed full query contains the same exact candidates at its head.
            if (!includePredictions) {
                candidateCache[QueryKey(readingHiragana, 50, true)]?.let { return it.take(limit) }
            }
        }
        ensureDb()

        val out = LinkedHashSet<String>()
        out += readingHiragana
        if (out.size < limit) out += hiraganaToKatakana(readingHiragana)

        val db = openReadDb()
        try {
            // Exact with learning priority
            db.rawQuery(
                "SELECT e.word, IFNULL(l.freq,0) AS f, e.cost FROM entries e LEFT JOIN learn l ON l.reading = ? AND l.word = e.word WHERE e.reading = ? ORDER BY f DESC, e.cost ASC LIMIT ?",
                arrayOf(readingHiragana, readingHiragana, (limit - out.size).coerceAtLeast(0).toString())
            ).use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += c.getString(0)
                }
            }
            // Prefix (exclude exact reading). Aggregate by word with min cost and learning freq.
            if (includePredictions && out.size < limit) {
                val remain = limit - out.size
                // A parameterized `LIKE ? || '%'` is not reliably converted into an
                // index range by Android SQLite. Explicit bounds keep this on
                // idx_entries_reading, especially important for the 1M+ entry dictionary.
                val prefixEnd = readingHiragana + '\uFFFF'
                db.rawQuery(
                    "SELECT p.word, MIN(p.cost) as c, MAX(IFNULL(l.freq,0)) as f " +
                        "FROM (SELECT word, cost FROM entries INDEXED BY idx_entries_reading " +
                        "WHERE reading > ? AND reading < ? LIMIT ?) p " +
                        "LEFT JOIN learn l ON l.reading = ? AND l.word = p.word " +
                        "GROUP BY p.word ORDER BY f DESC, c ASC LIMIT ?",
                    arrayOf(
                        readingHiragana,
                        prefixEnd,
                        PREFIX_SCAN_LIMIT.toString(),
                        readingHiragana,
                        remain.toString()
                    )
                ).use { c ->
                    while (c.moveToNext() && out.size < limit) {
                        val w = c.getString(0)
                        if (!out.contains(w)) out += w
                    }
                }
            }
        } finally {
            // keep db open for reuse
        }
        if (out.size <= 2) out.addAll(SimpleConverter().query(readingHiragana))
        return out.toList().also { result ->
            synchronized(candidateCache) { candidateCache[key] = result }
        }
    }

    override fun hasExactCandidates(readingHiragana: String): Boolean {
        if (readingHiragana.isEmpty()) return false
        ensureDb()
        openReadDb().rawQuery(
            "SELECT 1 FROM entries WHERE reading = ? LIMIT 1",
            arrayOf(readingHiragana)
        ).use { cursor -> return cursor.moveToFirst() }
    }

    override fun longestExactPrefix(text: String, start: Int, maxLength: Int): Int {
        if (maxLength <= 0) return 1
        val cacheKey = text.substring(start, start + maxLength)
        synchronized(segmentCache) { segmentCache[cacheKey]?.let { return it } }
        ensureDb()
        val readings = (maxLength downTo 1).map { text.substring(start, start + it) }
        val placeholders = readings.joinToString(",") { "?" }
        openReadDb().rawQuery(
            "SELECT reading FROM entries WHERE reading IN ($placeholders) ORDER BY LENGTH(reading) DESC LIMIT 1",
            readings.toTypedArray()
        ).use { cursor ->
            val result = if (cursor.moveToFirst()) cursor.getString(0).length else 1
            synchronized(segmentCache) { segmentCache[cacheKey] = result }
            return result
        }
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
                input.copyTo(output, ASSET_COPY_BUFFER_SIZE)
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
        synchronized(candidateCache) {
            candidateCache.keys.removeAll { it.reading == readingHiragana }
        }
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

    private fun openReadDb(): SQLiteDatabase {
        val thread = Thread.currentThread()
        val current = readDbs[thread]
        if (current != null && current.isOpen) return current
        synchronized(initLock) {
            val cached = readDbs[thread]
            if (cached != null && cached.isOpen) return cached
            val db = SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            // Keep hot index pages in memory; both pragmas are safe for this read-only handle.
            db.rawQuery("PRAGMA mmap_size=67108864", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA cache_size=-8192", null).use { it.moveToFirst() }
            readDbs[thread] = db
            return db
        }
    }
}
