package sh.edm.s1kiosk

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Open source licences" in the hidden settings: THIRD-PARTY-NOTICES.md, the project's LICENSE and the
 * licence texts of LICENSES/, which app/kiosk/build.gradle.kts copies into assets/licenses/ at build time.
 * Shown as plain text, like the rest of the settings dialog.
 */
object Licences {
    const val DIR = "licenses"
    const val NOTICES = "THIRD-PARTY-NOTICES.md"
    const val LICENSE = "LICENSE"

    data class Doc(val label: String, val path: String)

    /** The list shown: the notices, the project's own licence, then each text in [texts] (file names) by SPDX id. */
    fun documents(texts: List<String>): List<Doc> =
        listOf(
            Doc("Notices: what ships, versions, sources", "$DIR/$NOTICES"),
            Doc("S1 Unleashed licence (MIT)", "$DIR/$LICENSE"),
        ) + texts.filter { it.endsWith(".txt") }.sorted().map { Doc(it.removeSuffix(".txt"), "$DIR/texts/$it") }

    fun show(activity: Activity) {
        val texts = try { activity.assets.list("$DIR/texts")?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        val docs = documents(texts)
        AlertDialog.Builder(activity)
            .setTitle("Open source licences")
            .setItems(docs.map { it.label }.toTypedArray()) { _, i -> showText(activity, docs[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showText(activity: Activity, doc: Doc) {
        val text = try {
            activity.assets.open(doc.path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Couldn't read ${doc.path}: $e"
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val view = TextView(activity).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(activity)
            .setTitle(doc.label)
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton("Close", null)
            .show()
    }
}
