package sh.edm.s1kiosk.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import sh.edm.s1kiosk.ui.Bakery
import sh.edm.s1kiosk.ui.Dither
import sh.edm.s1kiosk.ui.FloatImage
import sh.edm.s1kiosk.voice.OrbArt
import java.nio.ByteBuffer
import kotlin.math.sin

/**
 * The critical alert's orb (#118, the approved design): the timer-done stage's glass orb
 * ([sh.edm.s1kiosk.timers.TimerDoneView]) with a rose core. A ripple goes out every [RIPPLE_MS] and
 * the core and glow pulse in step with them, while it [run]s. The same layers ([OrbArt]), sizes and
 * motion as the timer's, in rose because a critical alert is a status.
 *
 * Its own frame loop (Choreographer), as TimerDoneView's: [start] when the alert shows, [stop] when it
 * goes. While the screen is off no frames come, and the loop picks up when it turns on. Main thread.
 */
class CriticalOrb(context: Context) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private val art = OrbArt(dp)
    private fun px(v: Float) = art.px(v)
    private val interp = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    private val ease: (Float) -> Float = { interp.getInterpolation(it.coerceIn(0f, 1f)) }

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

    /** The loop runs: started and not stopped. */
    var running = false
        private set

    /** Frames drawn since [start], for tests. */
    var frames = 0
        private set

    private var startedAt = 0L
    private var looping = false
    private var baked = false
    private val frame = Choreographer.FrameCallback {
        looping = false
        if (running) { render(SystemClock.uptimeMillis()); loop() }
    }

    init {
        clipChildren = false
        clipToPadding = false
        fun centred(v: View, sideDp: Float, parent: FrameLayout) = parent.addView(v, LayoutParams(px(sideDp), px(sideDp), Gravity.CENTER))
        centred(glow, OrbArt.GLOW_DP, this)
        for (r in rings) centred(r, OrbArt.SPHERE_DP, this)
        centred(sphere, OrbArt.SPHERE_BACK_DP, this)
        centred(clip, OrbArt.SPHERE_DP - 2, this)
        centred(core, OrbArt.CORE_DP, clip)
        centred(shine, OrbArt.SPHERE_DP - 2, clip)
    }

    fun start() {
        if (running) return
        running = true
        frames = 0
        startedAt = SystemClock.uptimeMillis()
        bake()
        render(startedAt)
        loop()
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        looping = false
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun loop() {
        if (looping || !running) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** As TimerDoneView.render, at full visibility: the pulse and the two ripples, half a period apart. */
    private fun render(now: Long) {
        frames++
        val t = (now - startedAt).toFloat()
        val pulse = 0.5f + 0.5f * sin(t / RIPPLE_MS * 2f * Math.PI.toFloat())
        glow.alpha = 0.8f + 0.2f * pulse; glow.scaleX = 1.08f + 0.06f * pulse; glow.scaleY = glow.scaleX
        core.scaleX = 1.02f + 0.08f * pulse; core.scaleY = core.scaleX
        for ((i, r) in rings.withIndex()) r.ripple(((t / RIPPLE_MS + i / 2f) % 1f), 0.6f)
    }

    /** The core's scale now, for tests: it pulses while the loop runs. */
    val coreScale: Float get() = core.scaleX

    private fun bake() {
        if (baked) return
        baked = true
        Bakery.bake("critical orb", {
            val d = Dither(0x51ED270B)
            fun FloatImage.bmp(): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .apply { copyPixelsFromBuffer(ByteBuffer.wrap(toRgbaBytes(d))) }
            listOf(art.glow(OrbArt.Tint.ROSE).bmp(), art.sphere().bmp(), art.core(OrbArt.Tint.ROSE).bmp(), art.shine().bmp())
        }) { b -> listOf(glow, sphere, core, shine).zip(b).forEach { (view, bmp) -> view.setImageBitmap(bmp) } }
    }

    private fun layer() = object : ImageView(context) { override fun hasOverlappingRendering() = false }
        .apply { scaleType = ImageView.ScaleType.FIT_XY }

    /** A 1 dp rose ring around the sphere: scale 1 → 1.85 while fading out. */
    private inner class Ripple(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; color = ROSE }
        override fun hasOverlappingRendering() = false
        override fun onDraw(c: Canvas) = c.drawCircle(width / 2f, height / 2f, width / 2f - dp / 2, paint)
        fun ripple(p: Float, strength: Float) {
            val k = ease(p)
            alpha = (1f - k) * strength
            scaleX = 1f + 0.85f * k; scaleY = scaleX
        }
    }

    companion object {
        /** As the timer-done stage: a ripple every 1.5 s. */
        const val RIPPLE_MS = 1500f
        val ROSE = Color.parseColor("#fb7185")
    }
}
