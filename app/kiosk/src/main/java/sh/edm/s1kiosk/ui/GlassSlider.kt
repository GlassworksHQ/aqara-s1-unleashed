package sh.edm.s1kiosk.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * A slider in the Glassworks look (#63 round 2: the presence range): a hairline track, lit sky to violet
 * up to the value, and a white thumb. [onMove] follows the finger; [onSet] runs once, when it lets go.
 * Snaps to [step]. Main thread.
 */
class GlassSlider(
    context: Context,
    private val min: Float,
    private val max: Float,
    private val step: Float,
) : View(context) {

    var value: Float = min
        set(v) { field = snap(v); invalidate() }

    var onMove: ((Float) -> Unit)? = null
    var onSet: ((Float) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbR = 11 * dp
    private val trackH = 4 * dp

    init { isClickable = true; isFocusable = true; contentDescription = "Slider" }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (36 * dp).toInt())

    private fun snap(v: Float): Float {
        val n = ((v.coerceIn(min, max) - min) / step).roundToInt()
        return (min + n * step).coerceIn(min, max)
    }

    private val left get() = paddingLeft + thumbR
    private val right get() = width - paddingRight - thumbR
    private fun xOf(v: Float) = left + (right - left) * ((v - min) / (max - min))
    private fun valueAt(x: Float) = snap(min + (max - min) * ((x - left) / (right - left)).coerceIn(0f, 1f))

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val x = xOf(value)
        paint.shader = null
        paint.color = Color.argb(30, 255, 255, 255)
        c.drawRoundRect(left, cy - trackH / 2, right, cy + trackH / 2, trackH, trackH, paint)
        paint.color = Color.WHITE // the shader's colours, unmuted
        paint.shader = LinearGradient(left, 0f, right, 0f, Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
        paint.alpha = if (isEnabled) 255 else 90
        c.drawRoundRect(left, cy - trackH / 2, x, cy + trackH / 2, trackH, trackH, paint)
        paint.shader = null
        paint.color = if (isEnabled) Glass.TX else Glass.FA
        c.drawCircle(x, cy, thumbR, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val v = valueAt(e.x)
                if (v != value) { value = v; onMove?.invoke(v) }
            }
            MotionEvent.ACTION_UP -> { value = valueAt(e.x); onMove?.invoke(value); onSet?.invoke(value) }
            MotionEvent.ACTION_CANCEL -> onSet?.invoke(value)
        }
        return true
    }
}
