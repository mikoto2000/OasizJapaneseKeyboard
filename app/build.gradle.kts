plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.mikoto2000.oasizjapanesekeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mikoto2000.oasizjapanesekeyboard"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// --- Dictionary generation task (Pure Kotlin, no JNI) ---
// Usage:
//   ./gradlew generateDictionary [-PdictSrc=/path/to/src] [-PmaxPerKey=20]
// Input format: UTF-8 TSV, each line "reading(hiragana)\tword\t[cost]".
// Merges all *.tsv under dictSrc, dedup by (reading, word), picks min cost, sorts.
tasks.register("generateDictionary") {
    description = "Generate app dictionary TSV (assets) from TSV sources"
    group = "dictionary"
    doLast {
        fun resolvePath(p: String): java.io.File {
            val f1 = file(p)
            if (f1.exists()) return f1
            val f2 = rootProject.file(p)
            if (f2.exists()) return f2
            return f1
        }
        val srcProp = project.findProperty("dictSrc")?.toString()
        val maxPerKey = (project.findProperty("maxPerKey")?.toString()?.toIntOrNull() ?: 50).coerceAtLeast(1)
        val srcDir = if (srcProp != null) resolvePath(srcProp) else rootProject.file("tools/dict-src")
        val outFile = file("src/main/assets/dictionary/words.tsv")

        if (!srcDir.exists()) {
            logger.lifecycle("[generateDictionary] Source dir not found: ${srcDir} (create it or pass -PdictSrc)")
            logger.lifecycle("[generateDictionary] No changes. Existing asset remains: ${outFile}")
            return@doLast
        }

        val entries = HashMap<String, MutableMap<String, Int>>()

        fun katakanaToHiragana(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                if (ch in '\u30A1'..'\u30F6') sb.append(ch - 0x60) else sb.append(ch)
            }
            return sb.toString()
        }

        srcDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "tsv" }
            .forEach { f ->
                logger.lifecycle("[generateDictionary] Loading ${f}")
                f.useLines(Charsets.UTF_8) { seq ->
                    seq.forEach { line ->
                        val t = line.trim()
                        if (t.isEmpty() || t.startsWith("#")) return@forEach
                        val parts = t.split('\t')
                        if (parts.size < 2) return@forEach
                        val readingRaw = parts[0]
                        val word = parts[1]
                        val cost = parts.getOrNull(2)?.toIntOrNull() ?: 1000
                        val reading = katakanaToHiragana(readingRaw)
                        val byWord = entries.getOrPut(reading) { LinkedHashMap() }
                        val prev = byWord[word]
                        if (prev == null || cost < prev) {
                            byWord[word] = cost
                        }
                    }
                }
            }

        outFile.parentFile.mkdirs()

        val totalKeys = entries.size
        var totalLines = 0
        outFile.printWriter(Charsets.UTF_8).use { pw ->
            val sortedKeys = entries.keys.sorted()
            for (key in sortedKeys) {
                val items = entries[key]!!.toList().sortedBy { it.second }.take(maxPerKey)
                for ((word, cost) in items) {
                    pw.println("${key}\t${word}\t${cost}")
                    totalLines++
                }
            }
        }
        logger.lifecycle("[generateDictionary] Wrote ${totalLines} entries for ${totalKeys} keys -> ${outFile}")
        logger.lifecycle("[generateDictionary] Tip: pass -PdictSrc and -PmaxPerKey to customize.")
    }
}

// --- Convert Mozc-like dictionaries to our TSV format ---
// Usage:
//   ./gradlew :app:convertMozcDict -PmozcSrc=/path/to/mozc/txt -Pout=tools/dict-src/mozcdict.tsv
// Supports .txt/.tsv/.csv; auto-detects delimiter (tab/comma). Heuristics to choose reading/surface/cost.
tasks.register("convertMozcDict") {
    description = "Convert Mozc-like text dictionaries into reading\tword\tcost TSV"
    group = "dictionary"
    doLast {
        fun resolvePath(p: String): java.io.File {
            val f1 = file(p)
            if (f1.exists()) return f1
            val f2 = rootProject.file(p)
            if (f2.exists()) return f2
            return f1
        }
        val srcProp = project.findProperty("mozcSrc")?.toString()
        require(!srcProp.isNullOrBlank()) { "-PmozcSrc must be provided (file or directory)" }
        val src = resolvePath(srcProp!!)
        val outPath = project.findProperty("out")?.toString() ?: rootProject.projectDir.resolve("tools/dict-src/mozcdict.tsv").absolutePath
        val outFile = resolvePath(outPath)

        fun isKanaOnly(s: String): Boolean {
            if (s.isEmpty()) return false
            for (ch in s) {
                val inHira = ch in '\u3041'..'\u309F'
                val inKata = ch in '\u30A1'..'\u30FF'
                val allowed = ch == 'ー' || ch == '・' || ch == '゛' || ch == '゜'
                if (!(inHira || inKata || allowed)) return false
            }
            return true
        }

        fun kataToHira(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                if (ch in '\u30A1'..'\u30F6') sb.append(ch - 0x60) else sb.append(ch)
            }
            return sb.toString()
        }

        data class Rec(val reading: String, val word: String, val cost: Int)

        val recs = ArrayList<Rec>()

        fun consumeFile(f: java.io.File) {
            logger.lifecycle("[convertMozcDict] reading ${f}")
            f.useLines(Charsets.UTF_8) { seq ->
                seq.forEach { line ->
                    val raw = line.trim()
                    if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) return@forEach
                    val delim = when {
                        '\t' in raw -> '\t'
                        ',' in raw -> ','
                        else -> '\t'
                    }
                    val cols = raw.split(delim)
                    if (cols.size < 2) return@forEach

                    // Heuristics:
                    // - Prefer patterns:
                    //   (A) reading, lid, rid, cost, surface   [Mozc common]
                    //   (B) surface, reading, pos, cost
                    //   (C) reading, surface, cost
                    //   (D) reading, surface
                    // - Detect reading column by kana-only check; last column often surface for (A)
                    var reading: String? = null
                    var surface: String? = null
                    var cost: Int = 1000

                    // (A) reading, lid, rid, cost, surface
                    if (cols.size >= 5 && isKanaOnly(cols[0]) && cols[1].toIntOrNull()!=null && cols[2].toIntOrNull()!=null && cols[3].toIntOrNull()!=null) {
                        reading = cols[0]
                        surface = cols[4]
                        cost = cols[3].toInt()
                    }

                    // Try common orders first
                    if (reading == null && cols.size >= 4) {
                        // surface, reading, pos, cost
                        val c3 = cols[3].toIntOrNull()
                        if (c3 != null && isKanaOnly(cols[1]) && !isKanaOnly(cols[0])) {
                            surface = cols[0]
                            reading = cols[1]
                            cost = c3
                        }
                    }
                    if (reading == null && cols.size >= 3) {
                        // reading, surface, cost
                        val c2 = cols[2].toIntOrNull()
                        if (c2 != null && isKanaOnly(cols[0])) {
                            reading = cols[0]
                            surface = cols[1]
                            cost = c2
                        }
                    }
                    if (reading == null) {
                        // generic detection by kana-only
                        val kanaIdx = cols.indexOfFirst { isKanaOnly(it) }
                        if (kanaIdx >= 0) {
                            reading = cols[kanaIdx]
                            // Prefer last column as surface if not same as reading
                            val lastIdx = cols.lastIndex
                            surface = if (lastIdx != kanaIdx) cols[lastIdx] else cols.getOrNull((0 until cols.size).firstOrNull { it != kanaIdx } ?: -1)
                            // try to find numeric cost
                            val costIdx = (0 until cols.size).firstOrNull { it != kanaIdx && cols[it].toIntOrNull() != null }
                            if (costIdx != null) cost = cols[costIdx].toInt()
                        }
                    }

                    if (reading == null || surface == null) return@forEach
                    val r = kataToHira(reading!!)
                    val w = surface!!
                    recs += Rec(r, w, cost)
                }
            }
        }

        if (src.isDirectory) {
            src.walkTopDown().filter { it.isFile && (it.extension.lowercase() in listOf("txt", "tsv", "csv")) }.forEach { consumeFile(it) }
        } else if (src.isFile) {
            consumeFile(src)
        } else {
            error("mozcSrc not found: ${src.absolutePath}")
        }

        // Dedup by (reading, word) keeping min cost
        val map = HashMap<String, MutableMap<String, Int>>()
        for (r in recs) {
            val byW = map.getOrPut(r.reading) { LinkedHashMap() }
            val prev = byW[r.word]
            if (prev == null || r.cost < prev) byW[r.word] = r.cost
        }

        val out = outFile
        out.parentFile.mkdirs()
        var lines = 0
        out.printWriter(Charsets.UTF_8).use { pw ->
            val keys = map.keys.sorted()
            for (key in keys) {
                val items = map[key]!!.toList().sortedBy { it.second }
                for ((w, c) in items) {
                    pw.println("${key}\t${w}\t${c}")
                    lines++
                }
            }
        }
        logger.lifecycle("[convertMozcDict] Wrote ${lines} lines -> ${out}")
        logger.lifecycle("[convertMozcDict] Next: ./gradlew :app:generateDictionary -PdictSrc=${out.parentFile}")
    }
}
