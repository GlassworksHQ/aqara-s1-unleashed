package sh.edm.s1kiosk.timers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import sh.edm.s1kiosk.TimerBoard
import sh.edm.s1kiosk.ui.Inter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

/**
 * One voice timer as a small glass orb with an amber ring that runs down (0.4.3, direction B; mock-ups
 * 0.4.3-timers/01-04-B): the 80 dp ring and the name tag under it, with [PAD_DP] of room on the left,
 * top and right for the dark halo, the glow and a wide tag, so nothing relies on a parent not clipping.
 * Soft layers are radial gradients (cheap on the A133, no blur). Only the last 10 seconds animate per
 * frame; otherwise the overlay redraws it once a second. Main thread.
 */
class RingView(context: Context) : View(context) {

    var timer: TimerBoard.Shown? = null
        set(v) { field = v; invalidate() }

    /** A light ring around it while the hide control is showing. */
    var highlighted = false
        set(v) { if (v != field) { field = v; invalidate() } }

    private val dp = resources.displayMetrics.density
    private val ring = RING_DP * dp
    private val c = ring / 2f
    private val tagH = TAG_H_DP * dp
    private val pad = PAD_DP * dp

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val digits = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        setShadowLayer(3 * dp, 0f, 1 * dp, Color.argb(153, 0, 0, 0))
    }
    private val tag = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * dp
        typeface = Inter.typeface(context, Inter.MEDIUM, 9.5f)
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
    }
    private val arcBox = RectF()
    private val tagBox = RectF()

    private val halo = RadialGradient(c, c, 80 * dp,
        intArrayOf(argb(0.75f, BG), argb(0.75f, BG), argb(0.35f, BG), argb(0f, BG)), floatArrayOf(0f, 0.30f, 0.48f, 0.70f), Shader.TileMode.CLAMP)
    private val glow = RadialGradient(c, c, 70 * dp,
        intArrayOf(argb(0.30f, AMBER), argb(0f, AMBER)), floatArrayOf(0f, 0.62f), Shader.TileMode.CLAMP)
    private val sphereR = SPHERE_DP / 2f * dp
    private val coreR = sphereR * 0.75f / 0.72f // the CSS core disc is inset 12.5 %; its gradient ends at 72 %
    private val core = RadialGradient(0f, 0f, coreR,
        intArrayOf(argb(0.95f, AMBER), argb(0.35f, AMBER), argb(0f, AMBER)), floatArrayOf(0f, 0.52f * 0.85f, 1f), Shader.TileMode.CLAMP)
    private val shine = run {
        val box = SPHERE_DP * dp
        val sx = c - box / 2 + 0.32f * box; val sy = c - box / 2 + 0.24f * box
        RadialGradient(sx, sy, 0.36f * hypot(box - 0.32f * box, box - 0.24f * box),
            intArrayOf(Color.argb(66, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
    }

    init {
        // No shadow, no outline; the halo and glow overhang the bounds.
        outlineProvider = null
    }

    override fun hasOverlappingRendering() = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((ring + 2 * pad).toInt(), (pad + ring + TAG_GAP_DP * dp + tagH + dp).toInt())

    override fun onDraw(cv: Canvas) {
        val t = timer ?: return
        cv.translate(pad, pad) // everything below is in ring coordinates
        val now = android.os.SystemClock.elapsedRealtime()
        val hot = t.hot(now)
        val paused = !t.active
        val left = t.remaining(now)
        // 0..1 through the current second while hot: a pulse per tick, as the chime will be.
        val beat = if (hot) 1f - ((t.endsAt - now).mod(1000L)) / 1000f else 0f

        paint.shader = halo; cv.drawCircle(c, c, 80 * dp, paint)
        if (!paused) {
            paint.shader = glow
            paint.alpha = ((if (hot) 1f - 0.35f * beat else 0.45f) * 255).toInt()
            cv.drawCircle(c, c, 70 * dp, paint)
            paint.alpha = 255
        }
        paint.shader = null

        // Track and progress, from 12 o'clock clockwise.
        val r = (RING_DP / 2f - 1.5f) * dp
        arcBox.set(c - r, c - r, c + r, c + r)
        stroke.strokeWidth = 2.5f * dp
        stroke.color = Color.argb(26, 255, 255, 255)
        cv.drawCircle(c, c, r, stroke)
        val arc = if (paused) Color.argb(97, 244, 244, 245) else argb(if (hot) 1f else 0.9f, AMBER)
        if (hot) { // a soft halo for the arc (no blur on API 29: a wider faint stroke)
            stroke.strokeWidth = 6f * dp
            stroke.color = argb(0.22f, AMBER)
            cv.drawArc(arcBox, -90f, 360f * left, false, stroke)
            stroke.strokeWidth = 2.5f * dp
        }
        stroke.color = arc
        if (left > 0f) cv.drawArc(arcBox, -90f, max(360f * left, 0.5f), false, stroke)

        // The glass sphere: dark fill (it sits over a busy page), 1 dp rim, amber core, shine.
        paint.color = Color.argb(245, 14, 15, 20); cv.drawCircle(c, c, sphereR, paint)
        val coreA = when { paused -> 0f; hot -> 0.95f; else -> 0.38f }
        if (coreA > 0f) {
            val s = if (hot) 1.05f + 0.06f * (1f - beat) else 0.85f
            cv.save(); cv.translate(c, c); cv.scale(s, s)
            paint.shader = core; paint.alpha = (coreA * 255).toInt()
            cv.drawCircle(0f, 0f, coreR, paint)
            cv.restore()
            paint.shader = null; paint.alpha = 255
        }
        paint.shader = shine; cv.drawCircle(c, c, sphereR, paint); paint.shader = null
        stroke.strokeWidth = 1 * dp; stroke.color = Color.argb(36, 255, 255, 255)
        cv.drawCircle(c, c, sphereR - 0.5f * dp, stroke)

        if (hot) { // one ripple per second, like the wake ripple
            stroke.color = argb((1f - beat) * 0.6f, AMBER)
            cv.drawCircle(c, c, r * (1f + 0.35f * beat), stroke)
        }
        if (highlighted) {
            stroke.strokeWidth = 1.5f * dp; stroke.color = Color.argb(140, 244, 244, 245)
            cv.drawCircle(c, c, r + 5 * dp, stroke)
        }

        // Digits.
        val text = TimerTexts.digits(t, now)
        val size = when {
            hot -> 30f
            paused -> 17f
            text.length > 5 -> 16f // 1:02:15
            else -> DIGITS_SP
        }
        digits.textSize = size * dp
        digits.typeface = Inter.typeface(context, Inter.LIGHT, size)
        digits.color = if (paused) MU else TX
        digits.fontFeatureSettings = "tnum"
        val fm = digits.fontMetrics
        var baseline = c - (fm.ascent + fm.descent) / 2f
        if (paused) {
            baseline += 6 * dp
            paint.color = Color.argb(204, 244, 244, 245)
            val bw = 3 * dp; val bh = 10 * dp; val gap = 4 * dp; val top = c - 18 * dp
            cv.drawRoundRect(c - gap / 2 - bw, top, c - gap / 2, top + bh, bw / 2, bw / 2, paint)
            cv.drawRoundRect(c + gap / 2, top, c + gap / 2 + bw, top + bh, bw / 2, bw / 2, paint)
        }
        cv.drawText(text, c, baseline, digits)

        // The name tag under the ring.
        val label = TextUtils.ellipsize(TimerTexts.tag(t).uppercase(Locale.getDefault()), tag, TAG_MAX_DP * dp, TextUtils.TruncateAt.END).toString()
        val tw = tag.measureText(label) + 16 * dp
        val top = ring + TAG_GAP_DP * dp
        tagBox.set(c - tw / 2, top, c + tw / 2, top + tagH)
        paint.color = Color.argb(209, 10, 11, 15)
        cv.drawRoundRect(tagBox, tagH / 2, tagH / 2, paint)
        stroke.strokeWidth = 1 * dp
        stroke.color = if (hot) argb(0.35f, AMBER) else Color.argb(18, 255, 255, 255)
        tagBox.inset(0.5f * dp, 0.5f * dp)
        cv.drawRoundRect(tagBox, tagH / 2, tagH / 2, stroke)
        tag.color = if (hot) AMBER_C else MU
        val tfm = tag.fontMetrics
        cv.drawText(label, c, top + tagH / 2 - (tfm.ascent + tfm.descent) / 2, tag)

        if (hot) postInvalidateOnAnimation()
    }

    companion object {
        const val RING_DP = 80f
        const val SPHERE_DP = 62f
        const val TAG_GAP_DP = 7f
        const val TAG_H_DP = 19f
        const val TAG_MAX_DP = 130f
        /** Room around the ring: the halo reaches 56 dp from its centre, a tag up to 146 dp wide. */
        const val PAD_DP = 56f
        /** The ring and its tag, without the room around them. */
        const val BODY_H_DP = RING_DP + TAG_GAP_DP + TAG_H_DP
        const val DIGITS_SP = 21f

        const val AMBER = 0xfbbf24
        const val BG = 0x0a0b0f
        val AMBER_C = Color.rgb(251, 191, 36)
        val TX = Color.parseColor("#f4f4f5")
        val MU = Color.parseColor("#a1a1aa")

        fun argb(a: Float, rgb: Int) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
    }
}
