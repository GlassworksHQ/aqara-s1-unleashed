package sh.edm.s1kiosk.setup

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.glassworks.frame.BootRest
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.StackView
import sh.edm.s1kiosk.ui.StackView.Look

/**
 * The connecting screen (#63): the boot animation's last frame, carried on. The stack and the
 * "Glassworks" wordmark stay exactly where the boot left them (branding/boot's layout: a 960x480
 * reference page scaled to the screen), on flat night, and only the line under them changes: the
 * boot's "On Aqara S1 Plus" becomes the state ("Starting up", "Connecting to Wi-Fi…", "Connecting to Home
 * Assistant · host"). The stack tells the state as drawn: light rising while it connects, dimmed plates
 * when there is no network to use, the top plate rose and dashed when Home Assistant doesn't answer.
 *
 * The first frame is the boot's own ([bootRest], drawn at its trim offset) and fades into the live stack,
 * so nothing jumps. A new state shows only once it has held for [SETTLE_MS], and the one shown stays at
 * least [HOLD_MS]: a state that flickers during boot shows once. An update of the same state (the
 * countdown) changes the words only. MainActivity shows it on page start and hides it on first
 * contentful paint.
 */
class LoadingOverlay(context: Context, private val bootRest: BootRest? = null) : FrameLayout(context) {

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

    /** The state on screen. */
    var state: State? = null
        private set

    /** The state asked for last; it shows once it has settled. */
    private var target: State? = null
    private var targetSince = 0L
    private var shownSince = 0L

    private val g = Glass(context)
    /** The boot's last frame, by resource id; null when it can't load (the live screen shows at once). */
    internal var loadRest: (Int) -> android.graphics.drawable.Drawable? = { id -> runCatching { context.getDrawable(id) }.getOrNull() }

    private val rest = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
    private val stack = StackView(context).apply { viewBox = true }
    private val word = TextView(context).apply {
        text = WORDMARK
        setTextColor(Glass.TX)
        includeFontPadding = false
        gravity = Gravity.CENTER
        letterSpacing = -0.03f
    }
    private val line = TextView(context).apply {
        setTextColor(Glass.MU)
        includeFontPadding = false
        gravity = Gravity.CENTER
        setSingleLine()
    }
    private val action = g.ghost("").apply { visibility = View.GONE; setOnClickListener { onAction?.invoke() } }

    /** The boot's device line, in its small spaced capitals ("" without a boot frame). */
    private val deviceLine = bootRest?.line?.uppercase().orEmpty()

    private val settle = Runnable { settle() }
    private val tick = object : Runnable {
        override fun run() {
            val s = state as? State.Unreachable ?: return
            line.text = lineOf(s)
            postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(Glass.BG) // flat night, as the boot ends: nothing to band
        isClickable = true // swallow touches while the page underneath is blank
        addView(stack)
        addView(word)
        addView(line)
        addView(action, LayoutParams(-2, -2))
        addView(rest) // on top: the first frame is exactly the boot's; the live screen shows as it fades
        visibility = View.GONE
    }

    // ------------------------------------------------------------------ the boot's layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth; val h = measuredHeight
        val scale = minOf(w / REF_W, h / REF_H)
        val side = (STACK_CSS * scale).toInt()
        stack.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
        rest.drawable?.let { d -> rest.measure(MeasureSpec.makeMeasureSpec(d.intrinsicWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(d.intrinsicHeight, MeasureSpec.EXACTLY)) }
        measureExact(word, w, (WORD_CSS * WORD_LINE * scale).toInt())
        measureExact(line, w, (LINE_CSS * 1.6f * scale).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        if (w <= 0 || h <= 0) return
        // branding/boot/target.py Layout: a 960x480 reference page, scaled to fit the screen.
        val scale = minOf(w / REF_W, h / REF_H)
        val viewH = h / scale
        val lockTop = (viewH - (STACK_CSS + GAP_CSS + WORD_CSS * WORD_LINE)) / 2
        val sx = w / 2f - STACK_CSS / 2 * scale
        val sy = lockTop * scale
        stack.layout(sx.toInt(), sy.toInt(), sx.toInt() + stack.measuredWidth, sy.toInt() + stack.measuredHeight)
        bootRest?.let { br -> rest.layout(br.left, br.top, br.left + rest.measuredWidth, br.top + rest.measuredHeight) }
        val wordTop = ((lockTop + STACK_CSS + GAP_CSS) * scale).toInt()
        word.layout(0, wordTop, w, wordTop + word.measuredHeight)
        // The status line takes the device line's place, near the bottom: the same centre line.
        val deviceMid = (viewH - DEVICE_BOTTOM - DEVICE_CSS / 2) * scale
        val lineTop = (deviceMid - line.measuredHeight / 2f).toInt()
        line.layout(0, lineTop, w, lineTop + line.measuredHeight)
        // Offline's one way out: between the wordmark and the line.
        action.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        val ay = ((wordTop + word.measuredHeight + lineTop) / 2f - action.measuredHeight / 2f).toInt()
        val ax = (w - action.measuredWidth) / 2
        action.layout(ax, ay, ax + action.measuredWidth, ay + action.measuredHeight)
    }

    private fun measureExact(v: View, w: Int, h: Int) =
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))

    private fun scale(): Float = if (width > 0) minOf(width / REF_W, height / REF_H) else resources.displayMetrics.density

    // ------------------------------------------------------------------ showing

    /**
     * Shows (or updates) the screen. The first show continues the boot's last frame; later states show
     * once settled (see the class), and an update of the same state changes only its words.
     */
    fun show(s: State) {
        val now = SystemClock.uptimeMillis()
        if (!isShowing) {
            isShowing = true
            target = s; targetSince = now
            bind(s, now)
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 1f
            if (!handedOff) { handedOff = true; continueFromBoot() } // only the first: later it's no boot
            return
        }
        if (s == target) return
        if (sameKind(s, state) && sameKind(s, target)) {
            // The same state with new words (the countdown's next retry): no transition.
            target = s
            removeCallbacks(settle)
            state = s
            if (bootLine != true) line.text = lineOf(s) // during the device line, the handoff shows it after
            return
        }
        target = s
        targetSince = now
        removeCallbacks(settle)
        // A fade already running ends on the words it started with, never this newer state's (verifier
        // 4bc205dc): stop it; settle starts a new one. The boot's handoff drives the line itself.
        if (boot?.isRunning != true) { line.animate().cancel(); line.alpha = 1f; state?.let { line.text = lineOf(it) } }
        postDelayed(settle, maxOf(SETTLE_MS, HOLD_MS - (now - shownSince)))
    }

    private fun settle() {
        val t = target ?: return
        if (t == state) return
        val now = SystemClock.uptimeMillis()
        val wait = maxOf(SETTLE_MS - (now - targetSince), HOLD_MS - (now - shownSince))
        val handoff = boot?.takeIf { it.isRunning }?.let { BOOT_END_MS - it.currentPlayTime } ?: 0L
        if (wait > 0 || handoff > 0) { postDelayed(settle, maxOf(wait, handoff, 16L)); return }
        // The line fades to the new words; the stack takes the new state's look.
        line.animate().cancel()
        line.animate().alpha(0f).setStartDelay(0).setDuration(160).withEndAction { fadedOut(t) }.start()
    }

    /** The line has faded out for [asked]: show the newest state asked for, if it changed meanwhile. */
    internal fun fadedOut(asked: State) {
        bind(target ?: asked, SystemClock.uptimeMillis())
        line.animate().alpha(1f).setDuration(260).start()
    }

    private fun bind(s: State, now: Long) {
        state = s
        shownSince = now
        removeCallbacks(tick)
        action.visibility = View.GONE
        line.text = lineOf(s)
        when (s) {
            is State.Connecting -> { stack.looks = listOf(Look.LIT, Look.OUTLINE, Look.OUTLINE); stack.rising = true }
            is State.Offline -> {
                stack.looks = listOf(Look.DIM, Look.DIM, Look.DIM)
                stack.rising = false
                s.action?.let { action.text = it; action.visibility = View.VISIBLE }
            }
            is State.Unreachable -> {
                stack.looks = listOf(Look.PROBLEM, Look.OUTLINE, Look.OUTLINE)
                stack.rising = false
                postDelayed(tick, 1000)
            }
        }
        styleLine(boot = false)
        requestLayout()
    }

    /**
     * The first frame: the boot's own, with its device line; then the live stack settles from the boot's
     * float into place, the rising light fades in, and the line turns into the state. One clock drives it
     * all ([bootAt]), so a mock-up can draw any moment of it.
     */
    private fun continueFromBoot() {
        boot?.cancel()
        bootLine = null
        restShown = bootRest?.let { br -> loadRest(br.drawable) }?.let { d ->
            rest.setImageDrawable(d)
            rest.visibility = View.VISIBLE
            true
        } ?: false
        bootAt(0)
        boot = ValueAnimator.ofInt(0, BOOT_END_MS.toInt()).apply {
            duration = BOOT_END_MS
            interpolator = LinearInterpolator()
            addUpdateListener { bootAt((it.animatedValue as Int).toLong()) }
            start()
        }
    }

    private var handedOff = false
    private var boot: ValueAnimator? = null
    private var restShown = false
    private var bootLine: Boolean? = null

    /** The handoff [t] ms after the first frame. */
    private fun bootAt(t: Long) {
        val after = t - BOOT_HOLD_MS
        // The boot's last frame on top, then dissolved into the live screen.
        if (restShown) {
            rest.alpha = 1f - (after / REST_FADE_MS.toFloat()).coerceIn(0f, 1f)
            rest.visibility = if (rest.alpha > 0f) View.VISIBLE else View.GONE
        }
        // The stack, from the boot's rest pose (its loop's first frame, branding/boot/stack.py float_ at 0.8 s).
        val x = (after / SETTLE_STACK_MS.toFloat()).coerceIn(0f, 1f)
        val k = 1f - (0.5f - 0.5f * kotlin.math.cos(Math.PI.toFloat() * x))
        stack.lifts = FloatArray(3) { BOOT_LIFTS[it] * k }
        stack.glow = 1f - (1f - BOOT_GLOW) * k
        stack.risingMix = 1f - k
        // The line: the device line as the boot drew it (in the boot's frame while that shows), then the state.
        // With the boot's frame on top, its own device line fades with it: the state comes in once it's gone.
        val out = if (restShown) REST_FADE_MS else LINE_OUT_MS
        val device = after < out
        if (bootLine != device) {
            bootLine = device
            line.text = if (device) deviceLine else state?.let(::lineOf).orEmpty()
            styleLine(boot = device)
        }
        line.alpha = when {
            device && restShown -> 0f
            device -> 1f - (after / LINE_OUT_MS.toFloat()).coerceIn(0f, 1f)
            else -> ((after - out) / LINE_IN_MS.toFloat()).coerceIn(0f, 1f)
        }
    }

    /** The boot's last frame is on screen (tests). */
    internal val showsBootFrame get() = rest.visibility == View.VISIBLE && rest.alpha > 0f

    /** What the status line says (tests). */
    internal val lineText get() = line.text.toString()

    /** For the mock-ups: the handoff stopped at [t] ms, the rising light where it would be then. */
    internal fun previewBoot(t: Long) {
        boot?.cancel()
        boot = null
        stack.held = (t % StackView.CYCLE_MS) / StackView.CYCLE_MS.toFloat()
        bootAt(t)
    }

    private fun styleLine(boot: Boolean) {
        val scale = scale()
        if (boot) {
            line.setTextSize(TypedValue.COMPLEX_UNIT_PX, DEVICE_CSS * scale)
            line.typeface = Inter.typeface(context, Inter.MEDIUM, DEVICE_CSS, opticalSize = 14)
            line.letterSpacing = 0.14f
            line.setTextColor(Color.argb((0.8f * 255).toInt(), Color.red(Glass.FA), Color.green(Glass.FA), Color.blue(Glass.FA)))
        } else {
            line.setTextSize(TypedValue.COMPLEX_UNIT_PX, LINE_CSS * scale)
            line.typeface = Inter.typeface(context, Inter.REGULAR, LINE_CSS)
            line.letterSpacing = 0f
            line.setTextColor(Glass.MU)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        word.setTextSize(TypedValue.COMPLEX_UNIT_PX, WORD_CSS * scale())
        word.typeface = Inter.typeface(context, 200, WORD_CSS, opticalSize = 14)
        styleLine(boot = line.text.isNotEmpty() && line.text == deviceLine)
    }

    /** Fades out once the dashboard has painted. */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        target = null
        removeCallbacks(tick)
        removeCallbacks(settle)
        animate().alpha(0f).setDuration(300).withEndAction {
            if (!isShowing) { visibility = View.GONE; stack.rising = false; state = null }
        }.start()
    }

    /** For the mock-ups: hold the rising light at one frame. */
    internal fun previewPhase(p: Float) { stack.held = p }

    companion object {
        const val CONNECTING = "Connecting to Home Assistant"
        const val UNREACHABLE = "Can't reach Home Assistant"
        const val WORDMARK = "Glassworks"

        /** A new state shows once it has held this long (the boot's Wi-Fi state flickers). */
        const val SETTLE_MS = 600L
        /** The state on screen stays at least this long. */
        const val HOLD_MS = 1_000L
        /** The boot's frame stays this long before the live screen takes over. */
        const val BOOT_HOLD_MS = 350L
        private const val REST_FADE_MS = 420L
        private const val SETTLE_STACK_MS = 700L
        private const val LINE_OUT_MS = 200L
        private const val LINE_IN_MS = 300L
        /** When the handoff is over: the stack settled, the line in. */
        val BOOT_END_MS = BOOT_HOLD_MS + maxOf(SETTLE_STACK_MS, REST_FADE_MS + LINE_IN_MS)

        // branding/boot/target.py: the reference page, in CSS px.
        private const val REF_W = 960f
        private const val REF_H = 480f
        private const val STACK_CSS = 150f
        private const val GAP_CSS = 22f
        private const val WORD_CSS = 46f
        private const val WORD_LINE = 1.5f
        private const val DEVICE_CSS = 11f
        private const val DEVICE_BOTTOM = 30f
        /** The status line, a little larger than the device line it replaces. */
        private const val LINE_CSS = 14f

        /** The boot's loop at its first frame (stack.py float_ at 0.8 s): units lifted, top plate first. */
        val BOOT_LIFTS = floatArrayOf(7.16f, 4.46f, 3.86f)
        /** And its top plate's breathe there. */
        const val BOOT_GLOW = 0.90f

        private fun sameKind(a: State?, b: State?): Boolean = when {
            a is State.Unreachable && b is State.Unreachable -> a.host == b.host
            else -> false
        }

        /** The one line under the wordmark. */
        fun lineOf(s: State, now: Long = SystemClock.elapsedRealtime()): String = when (s) {
            is State.Connecting -> if (s.detail.isEmpty()) s.title else "${s.title} · ${s.detail}"
            is State.Offline -> if (s.detail.isEmpty()) s.title else "${s.title} · ${s.detail}"
            is State.Unreachable -> "$UNREACHABLE · ${unreachableLine(s, now)}"
        }

        /** "Trying again in 4 s · homeassistant.local"; "Trying again…" once the time has come. */
        fun unreachableLine(s: State.Unreachable, now: Long): String {
            val left = ((s.retryAt - now + 999) / 1000).coerceAtLeast(0)
            val when_ = if (left > 0) "Trying again in $left s" else "Trying again…"
            return if (s.host.isEmpty()) when_ else "$when_ · ${s.host}"
        }
    }
}
