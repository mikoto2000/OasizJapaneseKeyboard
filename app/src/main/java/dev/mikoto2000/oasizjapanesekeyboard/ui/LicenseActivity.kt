package dev.mikoto2000.oasizjapanesekeyboard.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.mikoto2000.oasizjapanesekeyboard.R
import android.widget.TextView

class LicenseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license)
        supportActionBar?.title = getString(R.string.license_title)

        val tv = findViewById<TextView>(R.id.license_text)
        tv.text = buildText()
    }

    private fun buildText(): CharSequence {
        val parts = listOf(
            "licenses/LICENSE_mozc.txt" to "Mozc License (BSD 3-Clause)",
            "licenses/NOTICE_mozc.txt" to "Mozc Third-Party Notices"
        )
        val sb = StringBuilder()
        for ((path, header) in parts) {
            sb.appendLine("==== $header ====")
            sb.appendLine(readAssetOrPlaceholder(path))
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun readAssetOrPlaceholder(path: String): String {
        return try {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            "(missing) $path"
        }
    }
}

