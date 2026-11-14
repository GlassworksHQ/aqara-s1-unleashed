package sh.edm.s1kiosk.notifications

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import sh.edm.s1kiosk.ui.Bakery
import sh.edm.s1kiosk.ui.Dither
import sh.edm.s1kiosk.voice.OrbArt
import sh.edm.s1kiosk.voice.OrbBakes
import sh.edm.s1kiosk.voice.OrbBakes.Companion.bitmap
import kotlin.math.sin

/**
 * The critical alert's orb (#118, the approved design): the timer-done stage's glass orb
 * ([sh.edm.s1kiosk.timers.TimerDoneView]) with a rose core. A ripple goes out every [RIPPLE_MS] and
 * the core and glow pulse in step with them, while it [run]s. The same layers ([OrbArt]), sizes and
 * motion as the timer's, in rose because a critical alert is a status.
 *
 * Its own loop, a main-thread tick every [FRAME_MS] (about 60 fps). It ticks only while it is wanted
 * ([start] when the alert shows, [stop] when it goes), attached, its window visible and the [screenOn]:
 * an alert nobody answers mustn't wake the main looper 60 times a second all night with the screen
 * dark. Not Choreographer as TimerDoneView: a Choreographer loop's next frame is always due at once
 * under Robolectric's paused looper, so every test that showed the alert hung.
 *
 * Its layers bake once it is attached, as TimerDoneView's, so the first alert has its orb; the sphere
 * and shine are the timer's own bitmaps ([OrbBakes]). Main thread.
 */
class CriticalOrb(
    context: Context,
    private val orbs: OrbBakes = OrbBakes(),
) : FrameLayout(context) {
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

    /** The alert is up: [start] and not [stop]ped. */
    private var wanted = false
    /** Between onAttachedToWindow and onDetachedFromWindow (isAttachedToWindow is still true in the latter). */
    private var attached = false
    /**
     * The window was last reported visible. Changed only by onWindowVisibilityChanged: on the panel the
     * window goes GONE when the activity stops (the screen off) and VISIBLE again on resume.
     * (getWindowVisibility() isn't used: it stays GONE in Robolectric even in a resumed activity.)
     */
    private var windowShown = true
    /** Whether the screen is on; the overlay sets it on every change. Off: no ticks. */
    var screenOn = true
        set(v) { field = v; sync() }

    /** Frames are being drawn now: wanted, attached, its window visible and the screen on. */
    var running = false
        private set

    /** Frames drawn since [start], for tests. */
    var frames = 0
        private set

    private var startedAt = 0L
    private var baked = false
    private val main = Handler(Looper.getMainLooper())
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            render(SystemClock.uptimeMillis())
            main.postDelayed(this, FRAME_MS)
        }
    }
    private val bakeLater = Runnable { bake() }

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
        if (wanted) return
        wanted = true
        frames = 0
        startedAt = SystemClock.uptimeMillis()
        bake()
        render(startedAt)
        sync()
    }

    fun stop() {
        wanted = false
        sync()
    }

    /** Ticks or not, as it should now. One chain at most: any pending tick goes before a new one. */
    private fun sync() {
        val should = wanted && screenOn && attached && windowShown
        if (should == running) return
        running = should
        main.removeCallbacks(frame)
        if (should) main.postDelayed(frame, FRAME_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        // After the backdrop and the voice orb, as TimerDoneView: ready before the first alert.
        main.postDelayed(bakeLater, BAKE_AFTER_MS)
        sync()
    }

    override fun onDetachedFromWindow() {
        attached = false
        main.removeCallbacks(bakeLater)
        sync()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == View.VISIBLE
        sync()
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

    /** Its layers are in: the orb isn't empty. For tests. */
    internal val layersIn: Boolean get() = core.drawable != null && sphere.drawable != null

    /** The core's scale now, for tests: it pulses while the loop runs. */
    val coreScale: Float get() = core.scaleX

    private fun bake() {
        if (baked) return
        baked = true
        main.removeCallbacks(bakeLater)
        // The rose layers are its own; the sphere and shine are the timer's (OrbBakes).
        Bakery.bake("critical orb", {
            val d = Dither(0x51ED270B)
            listOf(art.glow(OrbArt.Tint.ROSE).bitmap(d), art.core(OrbArt.Tint.ROSE).bitmap(d))
        }) { b -> listOf(glow, core).zip(b).forEach { (view, bmp) -> view.setImageBitmap(bmp) } }
        orbs.layers(art) { l -> sphere.setImageBitmap(l.sphere); shine.setImageBitmap(l.shine) }
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
        const val FRAME_MS = 16L
        const val BAKE_AFTER_MS = 4_000L
        val ROSE = Color.parseColor("#fb7185")
    }
}
