package sh.edm.s1kiosk.setup

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen "connecting" state shown while the dashboard loads, so the panel never sits on a blank
 * white page. Same look as the onboarding screen: #0a0b0f with the sky/violet glows, thin type, an
 * amber pulse. MainActivity shows it on page start and hides it on first contentful paint.
 */
class LoadingOverlay(context: Context) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density

    private val dot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AMBER) }
    }
    private val label = text(11.5f, FA, MEDIUM).apply { letterSpacing = 0.14f; isAllCaps = true; text = "S1 Unleashed" }
    private val title = text(34f, TX, LIGHT).apply { letterSpacing = -0.02f; text = "Connecting to Home Assistant" }
    private val detail = text(16f, MU, REGULAR).apply { setLineSpacing(0f, 1.25f) }
    private val pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
        duration = 900
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    }
    var isShowing = false
        private set

    init {
        background = SetupOverlay.Backdrop()
        isClickable = true // swallow touches while the page underneath is blank
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(px(10f), px(10f)).apply { marginEnd = px(10f) })
                addView(label)
            }
            addView(row)
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(18f) })
            addView(detail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10f) })
        }
        title.gravity = Gravity.CENTER
        detail.gravity = Gravity.CENTER
        addView(column, LayoutParams(-2, -2, Gravity.CENTER))
        visibility = View.GONE
    }

    /** Shows (or updates) the loading state; state changes cross-fade instead of snapping. */
    fun show(heading: String, sub: String) {
        if (!isShowing) {
            isShowing = true
            setTexts(heading, sub)
            animate().cancel()
            if (visibility != View.VISIBLE) alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(250).setInterpolator(AccelerateDecelerateInterpolator()).start()
            pulse.start()
            return
        }
        if (title.text.toString() == heading && detail.text.toString() == sub) return
        // Old text fades and drifts up a touch, then the new text fades in from slightly below.
        val texts = listOf(title, detail)
        texts.forEach { it.animate().cancel() }
        title.animate().alpha(0f).translationY(-px(6f).toFloat()).setDuration(150)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                setTexts(heading, sub)
                texts.forEach { v ->
                    v.translationY = px(6f).toFloat()
                    v.animate().alpha(1f).translationY(0f).setDuration(220)
                        .setInterpolator(AccelerateDecelerateInterpolator()).start()
                }
            }.start()
        detail.animate().alpha(0f).translationY(-px(6f).toFloat()).setDuration(150).start()
    }

    private fun setTexts(heading: String, sub: String) {
        title.text = heading
        detail.text = sub
        detail.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Fades out once the dashboard has painted. */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        animate().alpha(0f).setDuration(300).withEndAction {
            if (!isShowing) { visibility = View.GONE; pulse.cancel() }
        }.start()
    }

    private fun px(v: Float) = (v * dp).toInt()

    private fun text(sp: Float, color: Int, tf: Typeface) = TextView(context).apply {
        textSize = sp; setTextColor(color); typeface = tf
    }

    private companion object {
        // Same tokens as SetupOverlay / tablet-home-card.
        val TX = Color.parseColor("#f4f4f5")
        val MU = Color.parseColor("#a1a1aa")
        val FA = Color.parseColor("#63636b")
        val AMBER = Color.parseColor("#fbbf24")
        val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
