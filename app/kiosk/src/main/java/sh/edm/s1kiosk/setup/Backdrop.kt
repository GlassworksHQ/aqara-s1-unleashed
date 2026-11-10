package sh.edm.s1kiosk.setup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import sh.edm.s1kiosk.ui.Bakery
import sh.edm.s1kiosk.ui.Dither
import sh.edm.s1kiosk.ui.Soft
import kotlin.math.sqrt

/**
 * Pixel math of the onboarding backdrop: #0a0b0f with the dashboard's two soft glows, sky at the
 * top-left and violet at the bottom-right (same centres, radii and strengths as the old two
 * RadialGradients). Computed in float with a smoothstep falloff (no hard edge where a linear ramp
 * reaches zero), then rounded with ±1 LSB triangular dither (no 8-bit rings). Pure JVM.
 */
object BackdropMath {
    const val BG_R = 10f
    const val BG_G = 11f
    const val BG_B = 15f

    /** Centre as a fraction of width/height, radius as a fraction of the width, peak alpha, colour. */
    class Glow(val cx: Float, val cy: Float, val radius: Float, val alpha: Float, val r: Float, val g: Float, val b: Float)

    val GLOWS = listOf(
        Glow(-0.10f, -0.20f, 0.85f, 33 / 255f, 56f, 189f, 248f),  // sky
        Glow(1.10f, 1.20f, 0.65f, 36 / 255f, 167f, 139f, 250f),   // violet
    )

    /** Glow strength at [t] = distance / radius: 1 at the centre, 0 from the radius on, flat at both ends. */
    fun falloff(t: Float): Float = 1f - Soft.smoothstep(t)

    /** Float colour (0..255 per channel) at the centre of pixel ([x], [y]) of a [w]×[h] backdrop. */
    fun colorAt(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        var r = BG_R; var g = BG_G; var b = BG_B
        val px = x + 0.5f; val py = y + 0.5f
        for (gl in GLOWS) {
            val dx = px - gl.cx * w
            val dy = py - gl.cy * h
            val k = gl.alpha * falloff(sqrt(dx * dx + dy * dy) / (gl.radius * w))
            if (k <= 0f) continue
            r += (gl.r - r) * k
            g += (gl.g - g) * k
            b += (gl.b - b) * k
        }
        out[0] = r; out[1] = g; out[2] = b
    }

    /** Row [y] as opaque ARGB ints, dithered. */
    fun row(y: Int, w: Int, h: Int, d: Dither, out: IntArray) {
        val c = FloatArray(3)
        for (x in 0 until w) {
            colorAt(x, y, w, h, c)
            out[x] = (0xFF shl 24) or (d.quantize(c[0]) shl 16) or (d.quantize(c[1]) shl 8) or d.quantize(c[2])
        }
    }
}

/**
 * The backdrop as a drawable for SetupOverlay, LoadingOverlay and the voice overlay's scrim. The
 * dithered bitmap is baked once per size on a background thread (~4 MB at 1440×720) and shared by
 * every instance; until it is ready (the first frames after start-up) the old gradients are drawn,
 * with dithering on.
 */
class Backdrop : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = Cache.get(b.width(), b.height(), this)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, b, paint)
            return
        }
        val w = b.width().toFloat(); val h = b.height().toFloat()
        fallback.shader = null
        fallback.color = Color.rgb(10, 11, 15)
        fallback.alpha = paint.alpha
        canvas.drawRect(b, fallback)
        fallback.color = Color.BLACK
        fallback.alpha = paint.alpha
        for (g in BackdropMath.GLOWS) {
            fallback.shader = RadialGradient(
                b.left + g.cx * w, b.top + g.cy * h, g.radius * w,
                Color.argb((g.alpha * 255).toInt(), g.r.toInt(), g.g.toInt(), g.b.toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, fallback)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = if (paint.alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    /** One shared bitmap for the current size (the panel only ever has one). Main thread. */
    private object Cache {
        private var bitmap: Bitmap? = null
        private var baking: Pair<Int, Int>? = null
        private val waiting = ArrayList<Drawable>()

        fun get(w: Int, h: Int, who: Drawable): Bitmap? {
            bitmap?.let { if (it.width == w && it.height == h) return it }
            if (who !in waiting) waiting += who
            if (baking != w to h) {
                baking = w to h
                Bakery.bake("backdrop ${w}x$h", { bake(w, h) }) { bmp ->
                    if (baking == bmp.width to bmp.height) {
                        baking = null
                        bitmap = bmp
                        waiting.toList().also { waiting.clear() }.forEach { it.invalidateSelf() }
                    }
                }
            }
            return null
        }

        private fun bake(w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val row = IntArray(w)
            val d = Dither()
            for (y in 0 until h) {
                BackdropMath.row(y, w, h, d, row)
                bmp.setPixels(row, 0, w, 0, y, w, 1)
            }
            bmp.setHasAlpha(false) // opaque: the GPU can skip blending
            return bmp
        }
    }
}
