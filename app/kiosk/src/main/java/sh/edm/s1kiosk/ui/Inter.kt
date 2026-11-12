package sh.edm.s1kiosk.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.util.Log
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.R
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Inter (SIL OFL 1.1), bundled as res/font/inter.ttf: the variable font from google/fonts, with weight
 * and optical-size axes. [typeface] picks the weight and, like CSS `font-optical-sizing: auto`, the
 * optical size from the text size (14 to 32), so the views match the HTML prototypes. Characters Inter
 * lacks fall back to the system's sans-serif. Main thread.
 */
object Inter {
    const val LIGHT = 300
    const val REGULAR = 400
    const val MEDIUM = 500

    /** Inter's optical size for text of [sizeSp] (1 sp = 1 CSS px at font scale 1), whole steps. */
    fun opticalSize(sizeSp: Float): Int = sizeSp.roundToInt().coerceIn(14, 32)

    /** The `font-variation-settings` string for [weight] at [sizeSp]. */
    fun variation(weight: Int, sizeSp: Float): String =
        String.format(Locale.ROOT, "'wght' %d, 'opsz' %d", weight, opticalSize(sizeSp))

    /** Reads the font file; tests point it at the source tree (Robolectric runs without app resources). */
    internal var load: (Context) -> ByteArray = { c -> c.resources.openRawResource(R.font.inter).use { it.readBytes() } }
        set(v) { field = v; cache.clear(); data = null; failed = false }

    private val cache = HashMap<String, Typeface>()
    private var data: ByteBuffer? = null
    private var failed = false

    fun typeface(context: Context, weight: Int, sizeSp: Float): Typeface {
        val key = variation(weight, sizeSp)
        cache[key]?.let { return it }
        val tf = build(context, weight, key) ?: fallback(weight)
        cache[key] = tf
        return tf
    }

    private fun build(context: Context, weight: Int, settings: String): Typeface? {
        if (failed || !Platform.hasFontBuilder) return null
        return try {
            // One copy of the file for every instance (Font.Builder(Resources, id) would read it each time).
            val buf = data ?: load(context).let { bytes ->
                ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
            }.also { data = it }
            val font = Font.Builder(buf).setWeight(weight).setSlant(FontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(settings).build()
            Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                .setSystemFallback("sans-serif")
                .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
                .build()
        } catch (t: Throwable) {
            Log.w("S1Kiosk", "Inter unavailable, using sans-serif", t)
            failed = true
            null
        }
    }

    private fun fallback(weight: Int): Typeface = Typeface.create(
        when {
            weight <= LIGHT -> "sans-serif-light"
            weight >= MEDIUM -> "sans-serif-medium"
            else -> "sans-serif"
        },
        Typeface.NORMAL,
    )
}
