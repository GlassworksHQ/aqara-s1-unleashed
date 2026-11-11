package sh.edm.s1kiosk.timers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import sh.edm.s1kiosk.TimerBoard
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.ui.Bakery
import sh.edm.s1kiosk.ui.Dither
import sh.edm.s1kiosk.ui.FloatImage
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.voice.Fader
import sh.edm.s1kiosk.voice.MainTextFit
import sh.edm.s1kiosk.voice.OrbArt
import java.nio.ByteBuffer
import kotlin.math.sin

/**
 * "Timer done" (0.4.3; mock-ups 0.4.3-timers/05-done*): the voice overlay's stage, the backdrop as a
 * 90 % scrim and the glass orb, here with the amber core and a ripple going out every 1.5 s (the
 * chimes), "TIMER DONE", the name(s), the length, and the timers still running. A tap anywhere
 * dismisses it ([TimerBoard.dismissFinished], which also stops the chimes); it also ends by itself
 * 60 s after the last finish. Same layout numbers as VoiceOverlay (orb centre 480,162 dp; label 252;
 * main text 282). Main thread.
 */
class TimerDoneView(context: Context) : FrameLayout(context) {

    /** After a tap dismissed it (the overlay refreshes the timers). */
    var onDismissed: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val art = OrbArt(dp)
    private fun px(v: Float) = art.px(v)
    private val interp = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    private val ease: (Float) -> Float = { interp.getInterpolation(it.coerceIn(0f, 1f)) }

    private val scrim = object : View(context) { override fun hasOverlappingRendering() = false }.apply { background = Backdrop() }
    private val stage = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val glow = layer()
    private val rings = Array(2) { Ripple(context) }
    private val sphere = layer()
    private val clip = FrameLayout(context).apply {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
    }
    private val core = layer()
    private val shine = layer()
    private val label = text()
    private val main = text()
    private val sub = text()
    private val restHead = text()
    private val rest = text()
    private val restRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val hint = text()

    private val vis = Fader(450f, 550f)
    private var active = false
    private var shownAt = 0L
    private var looping = false
    private var baked = false
    private var lastTexts: TimerTexts.DoneTexts? = null
    private val frame = Choreographer.FrameCallback { looping = false; render(SystemClock.uptimeMillis()) }

    init {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
        outlineProvider = null
        isClickable = false
        setOnClickListener { if (active) dismiss() }

        addView(scrim, LayoutParams(-1, -1))
        addView(stage, LayoutParams(px(OrbArt.GLOW_DP), px(OrbArt.GLOW_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = px(162f - OrbArt.GLOW_DP / 2) })
        fun centred(v: View, sideDp: Float, parent: FrameLayout) = parent.addView(v, LayoutParams(px(sideDp), px(sideDp), Gravity.CENTER))
        centred(glow, OrbArt.GLOW_DP, stage)
        for (r in rings) centred(r, OrbArt.SPHERE_DP, stage)
        centred(sphere, OrbArt.SPHERE_BACK_DP, stage)
        centred(clip, OrbArt.SPHERE_DP - 2, stage)
        centred(core, OrbArt.CORE_DP, clip)
        centred(shine, OrbArt.SPHERE_DP - 2, clip)

        styleLabel(label, AMBER)
        addView(label, LayoutParams(px(840f), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(252f) })

        main.gravity = Gravity.CENTER_HORIZONTAL
        sub.gravity = Gravity.CENTER_HORIZONTAL
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        sub.typeface = Inter.typeface(context, Inter.REGULAR, 17f)
        sub.setTextColor(MU)
        styleLabel(restHead, FA)
        restHead.text = "Still running"
        styleLabel(rest, MU)
        restRow.addView(restHead, LinearLayout.LayoutParams(-2, -2))
        restRow.addView(rest, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(14f) })
        column.addView(main, LinearLayout.LayoutParams(px(820f), -2))
        column.addView(sub, LinearLayout.LayoutParams(px(820f), -2).apply { topMargin = px(11f) })
        column.addView(restRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(28f) })
        addView(column, LayoutParams(px(820f), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(282f) })

        styleLabel(hint, FA)
        hint.gravity = Gravity.CENTER
        hint.text = "Tap anywhere to dismiss"
        addView(hint, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = px(22f) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // After the backdrop and the voice orb (1.5 s): nothing needs this until a timer finishes.
        postDelayed({ bake() }, 4_000)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        looping = false
        super.onDetachedFromWindow()
    }

    val showing: Boolean get() = active

    /** Shows, updates or hides from [TimerBoard.done]; call when timers change and once a second. */
    fun refresh(now: Long = SystemClock.elapsedRealtime()) {
        TimerBoard.expire(now)
        val done = TimerBoard.done
        if (done.isEmpty()) { hide(); return }
        val texts = TimerTexts.done(done, TimerBoard.timers, now)
        if (texts != lastTexts) apply(texts)
        show()
    }

    private fun apply(t: TimerTexts.DoneTexts) {
        lastTexts = t
        label.text = t.label
        styleMain(main, t.main)
        main.text = t.main
        sub.text = t.sub
        sub.visibility = if (t.sub.isEmpty()) View.GONE else View.VISIBLE
        rest.text = t.rest
        restRow.visibility = if (t.rest.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dismiss() {
        TimerBoard.dismissFinished()
        hide()
        onDismissed?.invoke()
    }

    private fun show() {
        if (active) return
        active = true
        isClickable = true
        val now = SystemClock.uptimeMillis()
        if (visibility != View.VISIBLE) {
            vis.snap(0f)
            shownAt = now
            visibility = View.VISIBLE
            if (!baked) bake()
        }
        vis.set(1f, now)
        startLoop()
    }

    private fun hide() {
        if (!active) return
        active = false
        isClickable = false
        vis.set(0f, SystemClock.uptimeMillis())
        startLoop()
    }

    private fun startLoop() {
        if (looping || visibility != View.VISIBLE) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun render(now: Long) {
        val v = vis.update(now, ease)
        val t = (now - shownAt).toFloat()
        scrim.alpha = 0.9f * v
        val s = 0.82f + 0.18f * v
        stage.scaleX = s; stage.scaleY = s
        stage.translationY = (1f - v) * 10f * dp
        val pulse = 0.5f + 0.5f * sin(t / 1500f * 2f * Math.PI.toFloat()) // in step with the ripples
        glow.alpha = v * (0.8f + 0.2f * pulse); glow.scaleX = 1.08f + 0.06f * pulse; glow.scaleY = glow.scaleX
        core.alpha = v; core.scaleX = 1.02f + 0.08f * pulse; core.scaleY = core.scaleX
        sphere.alpha = v; shine.alpha = v
        for ((i, r) in rings.withIndex()) r.ripple(((t / RIPPLE_MS + i / 2f) % 1f), v * 0.6f)
        label.alpha = v; column.alpha = v; hint.alpha = v * 0.9f
        if (!active && v <= 0f) {
            visibility = View.GONE
            lastTexts = null
            return
        }
        startLoop()
    }

    private fun bake() {
        if (baked) return
        baked = true
        Bakery.bake("timer orb", {
            val d = Dither(0x51ED270B)
            fun FloatImage.bmp(): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .apply { copyPixelsFromBuffer(ByteBuffer.wrap(toRgbaBytes(d))) }
            listOf(art.glow(OrbArt.Tint.AMBER).bmp(), art.sphere().bmp(), art.core(OrbArt.Tint.AMBER).bmp(), art.shine().bmp())
        }) { b -> listOf(glow, sphere, core, shine).zip(b).forEach { (view, bmp) -> view.setImageBitmap(bmp) } }
    }

    // ---------------------------------------------------------------- pieces

    private fun layer() = object : ImageView(context) { override fun hasOverlappingRendering() = false }
        .apply { scaleType = ImageView.ScaleType.FIT_XY }

    private fun text() = TextView(context).apply { includeFontPadding = false }

    private fun styleLabel(tv: TextView, color: Int) {
        tv.typeface = Inter.typeface(context, Inter.MEDIUM, 11.5f)
        tv.letterSpacing = 0.14f
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        tv.setTextColor(color)
        tv.gravity = Gravity.CENTER_HORIZONTAL
        tv.setSingleLine(true)
        tv.ellipsize = TextUtils.TruncateAt.END
        // Last: setSingleLine() replaces the transformation method, which is what all-caps is.
        tv.isAllCaps = true
    }

    /** As VoiceOverlay's main text: 36 sp light on up to 2 lines, then 30, then 26 on 3. */
    private fun styleMain(tv: TextView, text: String) {
        tv.letterSpacing = -0.02f
        tv.setTextColor(TX)
        val width = px(820f)
        val paint = TextPaint(tv.paint)
        fun sizePx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        fun extra(p: TextPaint) = p.fontMetricsInt.let { 1.2f * p.textSize - (it.descent - it.ascent) }
        val fit = MainTextFit.choose { sp ->
            paint.textSize = sizePx(sp)
            paint.typeface = Inter.typeface(context, Inter.LIGHT, sp)
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(extra(paint), 1f)
                .build().lineCount
        }
        tv.typeface = Inter.typeface(context, Inter.LIGHT, fit.sizeSp)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fit.sizeSp)
        tv.setLineSpacing(extra(tv.paint), 1f)
        tv.maxLines = fit.maxLines
        tv.ellipsize = TextUtils.TruncateAt.END
    }

    /** A 1 dp amber ring around the sphere: scale 1 → 1.85 while fading out. */
    private inner class Ripple(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; color = AMBER }
        override fun hasOverlappingRendering() = false
        override fun onDraw(c: Canvas) = c.drawCircle(width / 2f, height / 2f, width / 2f - dp / 2, paint)
        fun ripple(p: Float, strength: Float) {
            val k = ease(p)
            alpha = (1f - k) * strength
            scaleX = 1f + 0.85f * k; scaleY = scaleX
        }
    }

    private companion object {
        const val RIPPLE_MS = 1500f
        val TX = Color.parseColor("#f4f4f5")
        val MU = Color.parseColor("#a1a1aa")
        val FA = Color.parseColor("#63636b")
        val AMBER = Color.parseColor("#fbbf24")
    }
}
