package sh.edm.s1kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Top-right pill showing running voice timers ("⏱ Pasta 4:59") and, when one finishes,
 * "⏰ Pasta done" until tapped or for 60 s. Main thread only.
 */
class TimerOverlay(context: Context) : TextView(context) {

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val bg = GradientDrawable().apply {
        cornerRadius = 40 * density
        setColor(Color.argb(225, 24, 24, 28))
    }
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    init {
        val pad = (12 * density).toInt()
        setPadding(pad * 2, pad, pad * 2, pad)
        textSize = 22f
        setTextColor(Color.WHITE)
        background = bg
        elevation = 12 * density
        visibility = View.GONE
        setOnClickListener {
            TimerBoard.dismissFinished()
            refresh()
        }
    }

    fun layoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { topMargin = (20 * density).toInt(); marginEnd = (20 * density).toInt() }

    /** Call when timers changed; keeps itself ticking while anything is shown. */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val done = TimerBoard.lastFinished?.takeIf { now - it.second < 60_000 }
        if (done == null && TimerBoard.lastFinished != null) TimerBoard.dismissFinished()
        val lines = mutableListOf<String>()
        if (done != null) lines += "⏰  ${done.first} done"
        for (t in TimerBoard.timers) {
            val left = if (t.active) ((t.endsAt - now + 999) / 1000).toInt() else t.pausedLeft
            val label = if (t.label == "Timer") "" else "${t.label}  "
            lines += "⏱  $label${TimerBoard.format(left)}${if (t.active) "" else " (paused)"}"
        }
        handler.removeCallbacks(tick)
        if (lines.isEmpty()) {
            visibility = View.GONE
            return
        }
        text = lines.joinToString("\n")
        bg.setStroke((2 * density).toInt(), if (done != null) Color.rgb(255, 152, 0) else Color.rgb(3, 169, 244))
        visibility = View.VISIBLE
        handler.postDelayed(tick, 1000)
    }
}
