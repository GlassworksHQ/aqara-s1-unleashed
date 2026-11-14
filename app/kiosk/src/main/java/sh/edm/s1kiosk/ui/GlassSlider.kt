package sh.edm.s1kiosk.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import kotlin.math.roundToInt

/**
 * A slider in the Glassworks look (#63 round 2: the presence range): a hairline track, lit sky to violet
 * up to the value, and a white thumb. [onMove] follows the finger; [onSet] runs once, when it lets go.
 * A cancelled drag goes back to where it started and sets nothing. Without touch: DPAD left/right (and
 * minus/plus) step it, and accessibility services see a range with scroll forward/backward and set
 * progress, each setting the value at once. Snaps to [step]. Main thread.
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

    /** A finger is on it: a value read from elsewhere waits until it lets go. */
    var dragging = false
        private set

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbR = 11 * dp
    private val trackH = 4 * dp

    /** The value when the finger went down: a cancelled drag goes back to it. */
    private var downValue = min

    init { isClickable = true; isFocusable = true; contentDescription = "Slider" }

    /** Sets [v] the way a key or an accessibility action does: at once, through [onMove] and [onSet]. */
    private fun setFromAction(v: Float): Boolean {
        val n = snap(v)
        if (n == value) return false
        value = n
        onMove?.invoke(n)
        onSet?.invoke(n)
        announce()
        return true
    }

    /** Tells accessibility services the value changed, so a focused slider is re-read. */
    private fun announce() = sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)

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
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { downValue = value; dragging = true }
                parent?.requestDisallowInterceptTouchEvent(true)
                val v = valueAt(e.x)
                if (v != value) { value = v; onMove?.invoke(v) }
            }
            MotionEvent.ACTION_UP -> { dragging = false; value = valueAt(e.x); onMove?.invoke(value); onSet?.invoke(value) }
            // The gesture was taken away (a scroll, the system): nothing is set, the slider goes back.
            MotionEvent.ACTION_CANCEL -> { dragging = false; value = downValue; onMove?.invoke(value); announce() }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            // At an end the key isn't used, so focus can move on, as a platform SeekBar does.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> setFromAction(value - step)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> setFromAction(value + step)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBar::class.java.name // read as a slider
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, min, max, value)
        if (isEnabled) {
            if (value > min) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (value < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return super.performAccessibilityAction(action, arguments)
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> setFromAction(value + step)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> setFromAction(value - step)
            android.R.id.accessibilityActionSetProgress -> arguments?.takeIf { it.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) }
                ?.let { setFromAction(it.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) } ?: false
            else -> super.performAccessibilityAction(action, arguments)
        }
    }
}
