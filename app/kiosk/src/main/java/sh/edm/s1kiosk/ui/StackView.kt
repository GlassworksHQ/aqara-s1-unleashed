package sh.edm.s1kiosk.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Glassworks stack, the mark the panel's screens use to tell their state (#63): three plates, each
 * one [Look]. The geometry is the boot animation's (branding/boot/stack.py): isometric rounded squares
 * of 60 units with a 9-unit radius, 19 units apart, outline weights 0.7, 0.45 and 0.2 from the top.
 * [rising] runs the connecting animation, light rising through the plates. Main thread.
 */
class StackView(context: Context) : View(context) {

    enum class Look {
        /** Not set yet: a faint dashed outline (setup's pending steps). */
        GHOST,
        /** Set: a solid outline, brighter towards the top. */
        OUTLINE,
        /** A problem without a fault (Wi-Fi off): a dim outline. */
        DIM,
        /** Something is wrong here: a rose dashed outline. */
        PROBLEM,
        /** Lit: frosted glass, sky to violet. Only the top plate. */
        LIT,
    }

    /** The plates' looks, top first. */
    var looks: List<Look> = listOf(Look.LIT, Look.OUTLINE, Look.OUTLINE)
        set(v) { require(v.size == 3); field = v; invalidate() }

    /** Light rising through the plates (connecting): loops while the view is attached. */
    var rising = false
        set(v) { if (field != v) { field = v; if (v) startRising() else stopRising() } }

    private var phase = 0f
        set(v) { field = v; invalidate() }

    /** For the mock-ups: draw the rising light at [phase] (0..1) without animating. */
    var held: Float? = null
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plate = Path()
    private val tmp = Path()
    private val m = Matrix()
    private val box = RectF()
    private var animator: ValueAnimator? = null

    private fun startRising() {
        if (animator != null || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float }
            start()
        }
    }

    private fun stopRising() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (rising) startRising()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val u = min(width / CONTENT_W, height / CONTENT_H)
        if (u <= 0f) return
        val ox = (width - CONTENT_W * u) / 2f - X0 * u
        val oy = (height - CONTENT_H * u) / 2f - Y0 * u
        val p = held ?: phase.takeIf { rising }
        val light = FloatArray(3) { i -> if (p != null) light(i, p) else 0f }
        for (i in 2 downTo 0) {
            platePath(i, u, ox, oy)
            when (looks[i]) {
                Look.GHOST -> dashed(c, Color.argb(56, 244, 244, 245), 4f * u, u)
                Look.PROBLEM -> dashed(c, Glass.ROSE, 4.5f * u, u)
                Look.OUTLINE -> outline(c, W[i], u, light[i])
                Look.DIM -> outline(c, W[i] * 0.55f, u, 0f)
                Look.LIT -> lit(c, i, u, ox, oy, if (p != null) 0.55f + 0.45f * light[i] else 1f)
            }
        }
    }

    private fun platePath(i: Int, u: Float, ox: Float, oy: Float) {
        tmp.reset()
        box.set(0f, 0f, SIDE, SIDE)
        tmp.addRoundRect(box, R, R, Path.Direction.CW)
        // x' = cx + cos30·(x − y), y' = top + (x + y)/2, then into the view.
        m.setValues(floatArrayOf(C * u, -C * u, ox + CX * u, 0.5f * u, 0.5f * u, oy + (TOP0 + i * GAP) * u, 0f, 0f, 1f))
        tmp.transform(m, plate)
    }

    private fun stroke(width: Float) {
        paint.shader = null
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
    }

    private fun dashed(c: Canvas, color: Int, width: Float, u: Float) {
        stroke(width)
        paint.color = color
        paint.pathEffect = DashPathEffect(floatArrayOf(7f * u, 6f * u), 0f)
        c.drawPath(plate, paint)
        paint.pathEffect = null
    }

    /** A solid outline at [weight]; [light] (0..1) tints it sky to violet and fills it a little. */
    private fun outline(c: Canvas, weight: Float, u: Float, light: Float) {
        plate.computeBounds(box, true)
        if (light > 0f) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.shader = LinearGradient(box.left, box.top, box.right, box.bottom, Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
            paint.alpha = (light * 0.35f * 255).toInt()
            c.drawPath(plate, paint)
            paint.alpha = 255
        }
        stroke(5f * u)
        paint.color = Color.argb((weight * (1f - light) * 255).toInt(), 244, 244, 245)
        c.drawPath(plate, paint)
        if (light > 0f) {
            paint.shader = LinearGradient(box.left, box.top, box.right, box.bottom, Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
            paint.alpha = (light * 255).toInt()
            c.drawPath(plate, paint)
            paint.shader = null
            paint.alpha = 255
        }
    }

    /** Frosted glass: a solid body, the plates below as a soft glow, the tint, a sheen and a rim. */
    private fun lit(c: Canvas, i: Int, u: Float, ox: Float, oy: Float, level: Float) {
        plate.computeBounds(box, true)
        val save = c.save()
        c.clipPath(plate)
        paint.shader = null; paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = BODY
        c.drawPath(plate, paint)
        // The frost: the plates under this one, blurred into light (a wide, faint stroke, nudged down).
        val body = Path(plate)
        for (j in i + 1..2) {
            platePath(j, u, ox, oy + 2.5f * u)
            stroke(11f * u)
            paint.color = Color.argb((W[j] * 0.35f * 255).toInt(), 244, 244, 245)
            c.drawPath(plate, paint)
        }
        plate.set(body)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE // the shaders below carry their own alpha; the paint's multiplies it
        paint.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            intArrayOf(withAlpha(Glass.SKY, 0.95f * level), withAlpha(Glass.INDIGO, 0.85f * level), withAlpha(Glass.VIOLET, 0.92f * level)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(plate, paint)
        val r = 0.7f * sqrt(box.width() * box.width() + box.height() * box.height()) / 2f
        paint.shader = RadialGradient(box.left + 0.3f * box.width(), box.top + 0.15f * box.height(), max(r, 1f),
            withAlpha(Color.WHITE, 0.3f * level), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(plate, paint)
        c.restoreToCount(save)
        stroke(2.2f * u)
        paint.color = Color.WHITE
        paint.shader = LinearGradient(box.left, box.top, box.left, box.bottom,
            intArrayOf(withAlpha(Color.WHITE, 0.95f * level), withAlpha(Color.parseColor("#e0f2fe"), 0.35f * level), withAlpha(Color.parseColor("#c4b5fd"), 0.55f * level)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(plate, paint)
        paint.shader = null
    }

    companion object {
        private const val SIDE = 60f
        private const val R = 9f
        private const val CX = 60f
        private const val TOP0 = 11f
        private const val GAP = 19f
        private val C = cos(PI / 6).toFloat()
        private val W = floatArrayOf(0.7f, 0.45f, 0.2f)
        private val BODY = Color.parseColor("#0b0c11")
        /** The plates' extent in units (strokes included): x 8..112, y 9..111. */
        private const val X0 = 5f
        private const val Y0 = 8f
        private const val CONTENT_W = 110f
        private const val CONTENT_H = 104f
        const val CYCLE_MS = 2400L

        /** How lit plate [i] (0 = top) is at [phase]: a pulse that starts at the bottom and rises. */
        fun light(i: Int, phase: Float): Float {
            val x = phase * 1.6f - (2 - i) * 0.3f
            return if (x <= 0f || x >= 1f) 0f else sin(PI.toFloat() * x)
        }

        private fun withAlpha(color: Int, a: Float) =
            Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}
