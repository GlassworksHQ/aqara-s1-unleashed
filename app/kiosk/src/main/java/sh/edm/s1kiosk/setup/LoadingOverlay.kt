package sh.edm.s1kiosk.setup

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.StackView
import sh.edm.s1kiosk.ui.StackView.Look

/**
 * The connecting screen (#63): shown while the dashboard loads, so the panel never sits on a blank
 * white page, and when it can't load. The stack tells the state: light rising through the plates while
 * it connects, dimmed plates when there is no network to use, the top plate rose and dashed when Home
 * Assistant doesn't answer. MainActivity shows it on page start and hides it on first contentful paint.
 */
class LoadingOverlay(context: Context) : FrameLayout(context) {

    /** What the screen says. */
    sealed interface State {
        /** Light rising: on the way. [detail] is the host, or a line of text when [host] is false. */
        data class Connecting(val title: String, val detail: String, val host: Boolean = true) : State

        /** Nothing to wait for (Wi-Fi off, no network): dimmed plates and, when given, one way to fix it. */
        data class Offline(val title: String, val detail: String, val action: String? = null) : State

        /** Home Assistant doesn't answer: the top plate rose and dashed, and when it tries again. */
        data class Unreachable(val host: String, val retryAt: Long) : State
    }

    /** The [State.Offline] button. */
    var onAction: (() -> Unit)? = null

    var isShowing = false
        private set

    var state: State? = null
        private set

    /** The state asked for last: a cross-fade still running shows it when it ends. */
    private var target: State? = null

    private val g = Glass(context)
    private val stack = StackView(context)
    private val title = g.heading(34f).apply { gravity = Gravity.CENTER }
    private val detail = TextView(context)
    private val action = g.ghost("").apply { setOnClickListener { onAction?.invoke() } }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(stack, LinearLayout.LayoutParams(g.px(92f), g.px(88f)))
        addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = g.px(40f) })
        addView(detail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = g.px(16f) })
        addView(action, LinearLayout.LayoutParams(-2, -2).apply { topMargin = g.px(26f) })
    }
    private val tick = object : Runnable {
        override fun run() {
            val s = state as? State.Unreachable ?: return
            detail.text = unreachableLine(s, SystemClock.elapsedRealtime())
            postDelayed(this, 1000)
        }
    }

    init {
        background = Backdrop()
        isClickable = true // swallow touches while the page underneath is blank
        addView(column, LayoutParams(-2, -2, Gravity.CENTER))
        visibility = View.GONE
    }

    /** Shows (or updates) the screen; a change of text cross-fades instead of snapping. */
    fun show(s: State) {
        if (!isShowing) {
            isShowing = true
            target = s
            column.animate().cancel()
            column.alpha = 1f
            bind(s)
            animate().cancel()
            if (visibility != View.VISIBLE) alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(250).setInterpolator(AccelerateDecelerateInterpolator()).start()
            return
        }
        if (s == target) return
        target = s
        column.animate().cancel()
        column.animate().alpha(0f).setDuration(150).withEndAction {
            bind(s)
            column.animate().alpha(1f).setDuration(220).start()
        }.start()
    }

    private fun bind(s: State) {
        state = s
        removeCallbacks(tick)
        action.visibility = View.GONE
        when (s) {
            is State.Connecting -> {
                stack.looks = listOf(Look.LIT, Look.OUTLINE, Look.OUTLINE)
                stack.rising = true
                title.text = s.title
                detail(s.detail, s.host)
            }
            is State.Offline -> {
                stack.looks = listOf(Look.DIM, Look.DIM, Look.DIM)
                stack.rising = false
                title.text = s.title
                detail(s.detail, host = false)
                s.action?.let { action.text = it; action.visibility = View.VISIBLE }
            }
            is State.Unreachable -> {
                stack.looks = listOf(Look.PROBLEM, Look.OUTLINE, Look.OUTLINE)
                stack.rising = false
                title.text = UNREACHABLE
                detail(unreachableLine(s, SystemClock.elapsedRealtime()), host = false)
                post(tick)
            }
        }
    }

    private fun detail(text: String, host: Boolean) {
        val t = if (host) g.mono(15f) else g.text(17f, Glass.MU)
        detail.typeface = t.typeface
        detail.textSize = if (host) 15f else 17f
        detail.setTextColor(Glass.MU)
        detail.gravity = Gravity.CENTER
        detail.text = text
        detail.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Fades out once the dashboard has painted. */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        target = null
        removeCallbacks(tick)
        animate().alpha(0f).setDuration(300).withEndAction {
            if (!isShowing) { visibility = View.GONE; stack.rising = false; state = null }
        }.start()
    }

    /** For the mock-ups: hold the rising light at one frame. */
    internal fun previewPhase(p: Float) { stack.held = p }

    companion object {
        const val CONNECTING = "Connecting to Home Assistant"
        const val UNREACHABLE = "Can't reach Home Assistant"

        /** "Trying again in 4 s · homeassistant.local"; "Trying again…" once the time has come. */
        fun unreachableLine(s: State.Unreachable, now: Long): String {
            val left = ((s.retryAt - now + 999) / 1000).coerceAtLeast(0)
            val when_ = if (left > 0) "Trying again in $left s" else "Trying again…"
            return if (s.host.isEmpty()) when_ else "$when_ · ${s.host}"
        }
    }
}
