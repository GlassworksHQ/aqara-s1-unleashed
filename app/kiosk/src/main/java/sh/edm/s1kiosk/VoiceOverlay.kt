package sh.edm.s1kiosk

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Small pill at the bottom of the screen showing the voice assistant state
 * ("Listening…", "Thinking…", "Speaking…"). Hidden when idle. Main thread only.
 */
class VoiceOverlay(context: Context) : TextView(context) {

    private val density = resources.displayMetrics.density
    private var pulse: ObjectAnimator? = null
    private var shown: String? = null

    init {
        val pad = (14 * density).toInt()
        setPadding(pad * 2, pad, pad * 2, pad)
        textSize = 20f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 40 * density
            setColor(Color.argb(220, 24, 24, 28))
            setStroke((2 * density).toInt(), Color.argb(255, 3, 169, 244))
        }
        elevation = 12 * density
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }

    fun layoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = (28 * density).toInt() }

    /** [state] is KioskService.voiceState ("idle", "wake", "listening", "thinking", "speaking", "error: …", "off"). */
    fun update(state: String, enabled: Boolean) {
        val label = if (!enabled) null else when {
            state == "wake" || state == "listening" -> "🎙  Listening…"
            state == "thinking" -> "…  Thinking"
            state == "speaking" -> "🔊  Speaking"
            else -> null
        }
        if (label == shown) return
        shown = label
        if (label == null) {
            pulse?.cancel(); pulse = null
            animate().alpha(0f).setDuration(200).withEndAction { visibility = View.GONE }.start()
            return
        }
        text = label
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(150).start()
        }
        if (pulse == null && (state == "wake" || state == "listening")) {
            pulse = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.06f).apply {
                duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { scaleY = scaleX }
                start()
            }
        } else if (pulse != null && state != "wake" && state != "listening") {
            pulse?.cancel(); pulse = null; scaleX = 1f; scaleY = 1f
        }
    }
}
