package sh.edm.s1kiosk.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * What the `*Mockups` classes share: the real views drawn by Robolectric's native graphics at the
 * panel's 1440x720 px, written as PNGs to `-Ps1MockupDir=<dir>`.
 */
object Mockup {
    const val W = 1440
    const val H = 720

    val dir: String? get() = System.getProperty("s1.mockupDir")

    /** Robolectric runs without the app's resources: read Inter from the source tree, as the app does from res/font. */
    fun interFromSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        Inter.load = { File(root, "app/kiosk/src/main/res/font/inter.ttf").readBytes() }
    }

    fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    fun <V : View> show(v: V): V {
        activity().setContentView(v, ViewGroup.LayoutParams(-1, -1))
        return v
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }

    fun save(name: String, v: View) {
        layout(v)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val out = File(dir!!).apply { mkdirs() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The smallest text view under [root] that says exactly [text]: a section's label rather than its nav row. */
    fun label(root: View, text: String): TextView =
        views(root).filterIsInstance<TextView>().filter { it.text.toString() == text }.minBy { it.textSize }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }
}
