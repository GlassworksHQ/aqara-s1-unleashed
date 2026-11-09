package io.github.aguilaair.s1kiosk.improv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aguilaair.s1kiosk.ScreenController

/**
 * "Allow Wi-Fi setup over Bluetooth?" card, styled like the user's tablet-home-card (design tokens
 * from tablet-home-card.compact.js: bg #0a0b0f, glassy card rgba(255,255,255,.045) with a
 * rgba(255,255,255,.07) border and 26px radius, letter-spaced uppercase label in #63636b, text
 * #f4f4f5 / muted #a1a1aa, `.btn` and `.btn.pri` buttons, sky #38bdf8 accent).
 *
 * Shown as a TYPE_APPLICATION_OVERLAY window (the app holds the SYSTEM_ALERT_WINDOW app-op on the
 * panel), so it also works while the activity is stopped or the kiosk isn't up yet. Without the
 * app-op it falls back to the current activity's decor view. Main thread only.
 */
@SuppressLint("ViewConstructor")
class ImprovOverlay(private val ctx: Context) {

    data class Model(
        val label: String,
        val title: String,
        val body: String,
        val primary: Pair<String, () -> Unit>? = null,
        val secondary: Pair<String, () -> Unit>? = null,
        val accent: Int = SKY,
        val busy: Boolean = false,
    )

    private val d = ctx.resources.displayMetrics.density
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var attachedTo: ViewGroup? = null
    private var pulse: ObjectAnimator? = null

    private lateinit var icon: BluetoothGlyph
    private lateinit var labelView: TextView
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var card: LinearLayout

    /** Activity to attach to when overlays aren't allowed (set by ImprovController). */
    var fallbackActivity: (() -> Activity?)? = null

    val isShown: Boolean get() = root != null

    fun show(m: Model) {
        if (root == null && !attach()) return
        labelView.text = m.label
        titleView.text = m.title
        bodyView.text = m.body
        icon.color = m.accent
        icon.busy = m.busy
        buttons.removeAllViews()
        m.secondary?.let { (t, a) -> buttons.addView(button(t, primary = false, a)) }
        m.primary?.let { (t, a) -> buttons.addView(button(t, primary = true, a)) }
        buttons.visibility = if (buttons.childCount == 0) View.GONE else View.VISIBLE
    }

    /** Identify: pulse the card a few times. */
    fun flash() {
        val c = if (root != null) card else return
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(c, "scaleX", 1f, 1.05f).apply {
            duration = 220; repeatMode = ValueAnimator.REVERSE; repeatCount = 7
            addUpdateListener { c.scaleY = c.scaleX }
            start()
        }
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        val r = root ?: return
        root = null
        val parent = attachedTo
        attachedTo = null
        runCatching { if (parent != null) parent.removeView(r) else wm.removeView(r) }
    }

    private fun attach(): Boolean {
        val r = build()
        if (Settings.canDrawOverlays(ctx)) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { title = "S1 Kiosk Wi-Fi setup" }
            if (runCatching { wm.addView(r, lp) }.onFailure { Log.w(TAG, "overlay addView failed", it) }.isSuccess) {
                root = r
                return true
            }
        }
        val act = fallbackActivity?.invoke()
        val decor = act?.window?.decorView as? ViewGroup
        if (decor == null) {
            Log.w(TAG, "can't show the Improv overlay: no SYSTEM_ALERT_WINDOW and no resumed activity")
            return false
        }
        decor.addView(r, ViewGroup.LayoutParams(-1, -1))
        attachedTo = decor
        root = r
        return true
    }

    @Suppress("DEPRECATION")
    private fun build(): FrameLayout {
        val scrim = object : FrameLayout(ctx) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) ScreenController.userActivity()
                return super.dispatchTouchEvent(ev)
            }
        }
        scrim.setBackgroundColor(Color.argb(158, 0, 0, 0)) // .ov rgba(0,0,0,.62)
        scrim.isClickable = true // swallow touches meant for the dashboard underneath
        scrim.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, dp(20), p, dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                // .card on the dashboard: rgba(255,255,255,.045) over #0a0b0f, plus a sky glow.
                colors = intArrayOf(Color.rgb(0x17, 0x1a, 0x22), Color.rgb(0x10, 0x11, 0x16))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(1), Color.argb(26, 255, 255, 255))
            }
            elevation = dp(16).toFloat()
        }

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        icon = BluetoothGlyph(ctx)
        head.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        labelView = TextView(ctx).apply {
            textSize = 11f; letterSpacing = 0.14f; isAllCaps = true
            setTextColor(FAINT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        titleView = TextView(ctx).apply {
            textSize = 22f; letterSpacing = -0.02f; setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(labelView)
        texts.addView(titleView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        head.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)

        bodyView = TextView(ctx).apply {
            textSize = 14.5f; setTextColor(MUTED); setLineSpacing(0f, 1.2f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        card.addView(bodyView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        scrim.addView(card, FrameLayout.LayoutParams(dp(460).coerceAtMost(ctx.resources.displayMetrics.widthPixels - dp(32)), -2, Gravity.CENTER))
        return scrim
    }

    private fun button(text: String, primary: Boolean, action: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(12), dp(20), dp(12))
        minWidth = dp(110)
        typeface = Typeface.create(if (primary) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setTextColor(if (primary) BG else TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (primary) TEXT else Color.argb(20, 255, 255, 255)) // .btn.pri / .btn (--s2)
        }
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.96f).scaleY(.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            false
        }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) }
    }

    private fun dp(v: Int) = (v * d).toInt()

    /** The Bluetooth rune in a rounded tile (like the dashboard's `.room .ic`). */
    private class BluetoothGlyph(ctx: Context) : View(ctx) {
        var color: Int = SKY
            set(v) { field = v; invalidate() }
        var busy = false
            set(v) {
                field = v
                if (v) startSpin() else { spin?.cancel(); spin = null; phase = 0f; invalidate() }
            }
        private var spin: ValueAnimator? = null
        private var phase = 0f
        private val d = ctx.resources.displayMetrics.density
        private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        private fun startSpin() {
            if (spin != null) return
            spin = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDetachedFromWindow() { spin?.cancel(); spin = null; super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val glow = if (busy) (0.16f + 0.14f * kotlin.math.sin(phase * 2 * Math.PI).toFloat()) else 0.16f
            tile.color = Color.argb((glow * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            c.drawRoundRect(0f, 0f, w, h, 14 * d, 14 * d, tile)
            stroke.color = color
            stroke.strokeWidth = 2.2f * d
            val cx = w / 2; val top = h * 0.24f; val bot = h * 0.76f; val r = w * 0.15f
            path.reset()
            path.moveTo(cx - r, h * 0.36f)
            path.lineTo(cx + r, h * 0.62f)
            path.lineTo(cx, bot)
            path.lineTo(cx, top)
            path.lineTo(cx + r, h * 0.38f)
            path.lineTo(cx - r, h * 0.64f)
            c.drawPath(path, stroke)
        }
    }

    companion object {
        private const val TAG = "S1Kiosk.Improv"
        val BG = Color.rgb(0x0a, 0x0b, 0x0f)
        val TEXT = Color.rgb(0xf4, 0xf4, 0xf5)
        val MUTED = Color.rgb(0xa1, 0xa1, 0xaa)
        val FAINT = Color.rgb(0x63, 0x63, 0x6b)
        val SKY = Color.rgb(0x38, 0xbd, 0xf8)
        val GREEN = Color.rgb(0x34, 0xd3, 0x99)
        val ROSE = Color.rgb(0xfb, 0x71, 0x85)
        val AMBER = Color.rgb(0xfb, 0xbf, 0x24)
    }
}
