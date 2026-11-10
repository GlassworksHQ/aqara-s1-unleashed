package sh.edm.s1kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
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
import android.widget.TextView
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.ui.Bakery
import sh.edm.s1kiosk.ui.Dither
import sh.edm.s1kiosk.ui.FloatImage
import sh.edm.s1kiosk.voice.AudioLevel
import sh.edm.s1kiosk.voice.Fader
import sh.edm.s1kiosk.voice.LevelSmoother
import sh.edm.s1kiosk.voice.MainTextFit
import sh.edm.s1kiosk.voice.OrbArt
import sh.edm.s1kiosk.voice.TextFade
import sh.edm.s1kiosk.voice.VoiceLevels
import sh.edm.s1kiosk.voice.VoiceTexts
import sh.edm.s1kiosk.voice.VoiceUi
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen voice assistant overlay, "Ambient Stage" (voice-design SPEC, direction B): the
 * onboarding backdrop as a 90 % scrim over the dashboard, a glass orb whose glowing layers follow
 * the state and the audio level, what was heard, and the reply.
 *
 * Cheap on the A133: every soft layer is baked once into a dithered bitmap ([OrbArt]) and only
 * alpha, scale and rotation change per frame (RenderNode properties, no re-recording). A
 * Choreographer loop runs only while the overlay is on screen; idle, it is GONE. Main thread only.
 */
class VoiceOverlay(context: Context) : FrameLayout(context) {

    /** Stops the current run (streaming and playback); also enables "Tap anywhere to cancel". */
    var onCancel: (() -> Unit)? = null
        set(v) { field = v; hint.visibility = if (v != null) View.VISIBLE else View.GONE }

    private enum class Phase { WAKE, LISTENING, THINKING, SPEAKING, DONE, ERROR }

    private enum class Style { LABEL_AMBER, LABEL_FAINT, LABEL_GREEN, LABEL_ROSE, QUOTE, MAIN_TEXT, MAIN_MUTED }

    private data class Spec(val text: String, val style: Style)

    private val dp = resources.displayMetrics.density
    private val art = OrbArt(dp)
    private fun px(v: Float) = art.px(v)
    private val interp = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    private val ease: (Float) -> Float = { interp.getInterpolation(it.coerceIn(0f, 1f)) }

    // ---------------------------------------------------------------- views

    private val scrim = Plain(context).apply { background = Backdrop() }
    private val stage = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val glowAmber = layer(); private val glowThink = layer(); private val glowSky = layer()
    private val glowGreen = layer(); private val glowRose = layer()
    private val rings = Array(3) { i -> Ring(context, if (i == 0) AMBER else SKY) }
    private val sphere = layer()
    private val clip = FrameLayout(context).apply {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true // the sphere's overflow: hidden (a circle outline clips on API 29)
    }
    private val coreAmber = layer(); private val swirl = layer(); private val coreSky = layer()
    private val coreGreen = layer(); private val coreRose = layer(); private val shine = layer()
    private val check = Check(context)
    private val label = Slot(dy = 6f, durMs = 450f)
    private val main = Slot(dy = 8f, durMs = 500f)
    private val hint = TextView(context).apply {
        styleLabel(this, FA)
        text = "Tap anywhere to cancel"
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    // ---------------------------------------------------------------- state

    private var ui = VoiceUi()
    private var phase = Phase.LISTENING
    private var errorText = ""
    /** Shown (or appearing); false while hidden or fading out. */
    private var active = false
    private var snapTexts = false
    private var shownAt = 0L
    private var rippleAt = Long.MIN_VALUE / 2
    private var doneAt = 0L
    private var lastFrame = 0L
    private var looping = false
    private var baked = false

    private val vis = Fader(450f, 550f)
    private val oListen = Fader(350f, 350f)
    private val oThink = Fader(400f, 400f)
    private val oSpeak = Fader(400f, 400f)
    private val oDone = Fader(350f, 550f)
    private val oError = Fader(350f, 550f)
    private val faders = listOf(oListen, oThink, oSpeak, oDone, oError)
    private val level = LevelSmoother()

    private val holdEnd = Runnable { hide() }
    private val frame = Choreographer.FrameCallback { nanos ->
        looping = false
        render(nanos / 1_000_000L)
    }

    init {
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
        // Between TimerOverlay (12 dp) and SetupOverlay (20 dp); no outline, so no shadow.
        outlineProvider = null
        elevation = 16 * dp
        isFocusable = false
        setOnClickListener { onTap() }
        isClickable = false

        addView(scrim, LayoutParams(-1, -1))
        addView(stage, LayoutParams(px(OrbArt.GLOW_DP), px(OrbArt.GLOW_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = px(162f - OrbArt.GLOW_DP / 2) })
        fun centred(v: View, sideDp: Float, parent: FrameLayout) =
            parent.addView(v, LayoutParams(px(sideDp), px(sideDp), Gravity.CENTER))
        for (g in listOf(glowAmber, glowThink, glowSky, glowGreen, glowRose)) centred(g, OrbArt.GLOW_DP, stage)
        for (r in rings) centred(r, OrbArt.SPHERE_DP, stage)
        centred(sphere, OrbArt.SPHERE_BACK_DP, stage)
        centred(clip, OrbArt.SPHERE_DP - 2, stage)
        for (c in listOf(coreAmber, coreSky, coreGreen, coreRose)) centred(c, OrbArt.CORE_DP, clip)
        clip.addView(swirl, clip.indexOfChild(coreSky), LayoutParams(px(OrbArt.SWIRL_DP), px(OrbArt.SWIRL_DP), Gravity.CENTER))
        centred(shine, OrbArt.SPHERE_DP - 2, clip)
        centred(check, OrbArt.SPHERE_DP, stage)

        for (v in label.views) addView(v, LayoutParams(px(840f), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(252f) })
        for (v in main.views) addView(v, LayoutParams(px(820f), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(282f) })
        addView(hint, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = px(22f) })
        resetMotion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Let the backdrop bake first (it is on screen at start-up); the orb isn't needed yet.
        postDelayed({ bake() }, 1_500)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        looping = false
        removeCallbacks(holdEnd)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- state changes

    /** Applies the latest voice snapshot (KioskService.voiceUi); [enabled] is prefs.voiceOverlay. */
    fun update(next: VoiceUi, enabled: Boolean) {
        val prev = ui
        ui = next
        if (!enabled) { hide(); return }
        val now = SystemClock.uptimeMillis()
        val st = next.state
        val fresh = next.seq != prev.seq
        when {
            st == "wake" || st == "listening" || st == "thinking" || st == "speaking" -> {
                val p = when (st) { "wake" -> Phase.WAKE; "listening" -> Phase.LISTENING; "thinking" -> Phase.THINKING; else -> Phase.SPEAKING }
                val listening = p == Phase.WAKE || p == Phase.LISTENING
                if (listening && (!active || (phase != Phase.WAKE && phase != Phase.LISTENING))) rippleAt = now
                removeCallbacks(holdEnd) // a new wake during done/error cancels the hold
                enter(p, now)
            }
            st.startsWith("error") -> if (fresh) {
                // A panel-side problem (mic, models) outside a run doesn't pop the overlay up.
                if (next.errorLocal && !active) return
                errorText = VoiceTexts.friendlyError(st, next.errorLocal)
                enter(Phase.ERROR, now)
                hold(ERROR_HOLD_MS)
            }
            else -> if (fresh && active) when (phase) { // idle / off
                // The reply has been spoken: a short "done" (not for announcements).
                Phase.SPEAKING -> if (next.announcement) hide() else { enter(Phase.DONE, now); hold(DONE_HOLD_MS) }
                Phase.DONE, Phase.ERROR -> Unit // the hold ends it
                else -> hide() // e.g. thinking -> idle: HA ended the run without a reply
            }
        }
        if (active) refreshTexts(now)
    }

    private fun enter(p: Phase, now: Long) {
        show(now)
        if (p == Phase.DONE && phase != Phase.DONE) doneAt = now
        phase = p
        oListen.set(if (p == Phase.WAKE || p == Phase.LISTENING) 1f else 0f, now)
        oThink.set(if (p == Phase.THINKING) 1f else 0f, now)
        oSpeak.set(if (p == Phase.SPEAKING) 1f else 0f, now)
        oDone.set(if (p == Phase.DONE) 1f else 0f, now)
        oError.set(if (p == Phase.ERROR) 1f else 0f, now)
        startLoop()
    }

    private fun show(now: Long) {
        if (active) return
        active = true
        isClickable = true
        if (visibility != View.VISIBLE) {
            resetMotion()
            shownAt = now
            lastFrame = now
            snapTexts = true
            visibility = View.VISIBLE
            if (!baked) bake()
        }
        vis.set(1f, now)
        startLoop()
    }

    private fun hide() {
        removeCallbacks(holdEnd)
        if (!active) return
        active = false
        isClickable = false // touches go to the dashboard while it fades
        val now = SystemClock.uptimeMillis()
        vis.set(0f, now)
        for (f in faders) f.set(0f, now)
        startLoop()
    }

    private fun hold(ms: Long) {
        removeCallbacks(holdEnd)
        postDelayed(holdEnd, ms)
    }

    private fun onTap() {
        if (!active) return
        // Listening, thinking or speaking: stop the run (HA pipeline, mic stream, playback).
        if (phase != Phase.DONE && phase != Phase.ERROR) onCancel?.invoke()
        hide()
    }

    private fun resetMotion() {
        vis.snap(0f)
        for (f in faders) f.snap(0f)
        level.reset()
        label.clear()
        main.clear()
        check.progress = 0f
        render(SystemClock.uptimeMillis(), schedule = false)
    }

    // ---------------------------------------------------------------- texts

    private fun refreshTexts(now: Long) {
        val animate = !snapTexts
        snapTexts = false
        label.set(labelSpec(), now, animate)
        main.set(mainSpec(), now, animate)
        startLoop()
    }

    private fun labelSpec(): Spec = when (phase) {
        Phase.WAKE, Phase.LISTENING -> Spec("Listening", Style.LABEL_AMBER)
        Phase.THINKING -> Spec("Thinking", Style.LABEL_FAINT)
        Phase.SPEAKING -> Spec(if (ui.announcement) "" else VoiceTexts.quote(ui.heard), Style.QUOTE)
        Phase.DONE -> Spec("Done", Style.LABEL_GREEN)
        Phase.ERROR -> Spec("Something went wrong", Style.LABEL_ROSE)
    }

    private fun mainSpec(): Spec = when (phase) {
        Phase.WAKE, Phase.LISTENING -> Spec(VoiceTexts.PROMPT, Style.MAIN_MUTED)
        Phase.THINKING ->
            if (ui.heard.isBlank()) Spec(VoiceTexts.PROMPT, Style.MAIN_MUTED) else Spec(VoiceTexts.quote(ui.heard), Style.MAIN_TEXT)
        // A streamed response can start playing before its text arrives (TTS_START): blank until then.
        Phase.SPEAKING, Phase.DONE ->
            if (ui.announcement && ui.reply.isBlank()) Spec(VoiceTexts.ANNOUNCEMENT, Style.MAIN_MUTED) else Spec(ui.reply, Style.MAIN_TEXT)
        Phase.ERROR -> Spec(errorText, Style.MAIN_MUTED)
    }

    // ---------------------------------------------------------------- frame loop

    private fun startLoop() {
        if (looping || visibility != View.VISIBLE) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun render(now: Long, schedule: Boolean = true) {
        val dt = (now - lastFrame).coerceIn(0L, 100L).toFloat()
        lastFrame = now
        val t = (now - shownAt).toDouble()
        val v = vis.update(now, ease)
        val oL = oListen.update(now, ease)
        val oT = oThink.update(now, ease)
        val oS = oSpeak.update(now, ease)
        val oD = oDone.update(now, ease)
        val oE = oError.update(now, ease)

        val target = when (phase) {
            Phase.LISTENING -> VoiceLevels.mic
            Phase.SPEAKING -> if (VoiceLevels.ttsPlaying) AudioLevel.ttsPulse(t) else 0f
            else -> 0f
        }
        var l = level.step(target, dt)
        if (phase == Phase.LISTENING && active) l = max(l, AudioLevel.LISTENING_FLOOR)

        // The backdrop bitmap is opaque; drawn at 0.9 it is the 90 % scrim (SPEC §2).
        scrim.alpha = 0.9f * v
        val breathe = 1f + 0.025f * sin(t / 520.0).toFloat() * oT
        val s = (0.82f + 0.18f * v) * breathe * (1f + 0.07f * l * oL)
        stage.scaleX = s; stage.scaleY = s
        stage.translationY = (1f - v) * 10f * dp

        // Per-layer alpha × v instead of fading the whole stage, which would need an offscreen layer.
        glowAmber.set(v * oL * (0.35f + 0.65f * l), 0.75f + 0.45f * l)
        glowThink.set(v * oT); glowThink.rotation = ((now - shownAt) % 4000L) * 0.09f
        glowSky.set(v * oS * (0.4f + 0.6f * l), 0.8f + 0.35f * l)
        glowGreen.set(v * oD)
        glowRose.set(v * oE)
        coreAmber.set(v * oL, 0.62f + 0.5f * l)
        swirl.set(v * oT * 0.95f); swirl.rotation = ((now - shownAt) % 1500L) * 0.24f
        coreSky.set(v * oS, 0.72f + 0.38f * l)
        coreGreen.set(v * oD)
        coreRose.set(v * oE)
        sphere.alpha = v
        shine.alpha = v
        check.alpha = v * oD
        check.progress = if (phase == Phase.DONE) ease(((now - doneAt) / 500f)) else check.progress

        // Wake: one amber ripple; speaking: two slow sky ripples following the voice.
        val sinceListen = now - rippleAt
        val wakeRipple = (phase == Phase.WAKE || phase == Phase.LISTENING) && sinceListen in 0 until 1000
        rings[0].ripple(if (wakeRipple) sinceListen / 1000f else -1f, v * 0.6f)
        for (i in 1..2) {
            val p = if (oS > 0f) ((t / 1800.0 + i / 2.0) % 1.0).toFloat() else -1f
            rings[i].ripple(p, v * 0.38f * oS * (0.35f + 0.65f * l))
        }

        label.frame(now, v)
        main.frame(now, v)
        hint.alpha = v * (1f - max(oD, oE)) * 0.9f

        if (!active && vis.value <= 0f) {
            visibility = View.GONE // costs nothing until the next run
            label.clear(); main.clear()
            return
        }
        if (schedule) startLoop()
    }

    // ---------------------------------------------------------------- baking

    private fun bake() {
        if (baked) return
        baked = true
        Bakery.bake("voice orb", {
            val d = Dither()
            fun FloatImage.bmp(): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .apply { copyPixelsFromBuffer(ByteBuffer.wrap(toRgbaBytes(d))) }
            listOf(
                art.glow(OrbArt.Tint.AMBER).bmp(), art.glow(null, thinking = true).bmp(), art.glow(OrbArt.Tint.SKY).bmp(),
                art.glow(OrbArt.Tint.GREEN).bmp(), art.glow(OrbArt.Tint.ROSE).bmp(),
                art.sphere().bmp(), art.core(OrbArt.Tint.AMBER).bmp(), art.swirl().bmp(), art.core(OrbArt.Tint.SKY).bmp(),
                art.core(OrbArt.Tint.GREEN).bmp(), art.core(OrbArt.Tint.ROSE).bmp(), art.shine().bmp(),
            )
        }) { bitmaps ->
            listOf(glowAmber, glowThink, glowSky, glowGreen, glowRose, sphere, coreAmber, swirl, coreSky, coreGreen, coreRose, shine)
                .zip(bitmaps).forEach { (view, bmp) -> view.setImageBitmap(bmp) }
        }
    }

    // ---------------------------------------------------------------- pieces

    private fun layer() = Layer(context)

    /** A baked bitmap layer; alpha applies straight to the draw (no offscreen layer). */
    private class Layer(context: Context) : ImageView(context) {
        init { scaleType = ScaleType.FIT_XY }
        override fun hasOverlappingRendering() = false
        fun set(a: Float, scale: Float = 1f) {
            alpha = a
            scaleX = scale; scaleY = scale
        }
    }

    private class Plain(context: Context) : View(context) {
        override fun hasOverlappingRendering() = false
    }

    /** A 1 dp ripple ring around the sphere: scale 1 → 1.85 while fading out. */
    private inner class Ring(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; this.color = color }
        override fun hasOverlappingRendering() = false
        override fun onDraw(c: Canvas) = c.drawCircle(width / 2f, height / 2f, width / 2f - dp / 2, paint)

        /** [p] 0..1 progress, or < 0 for hidden; [strength] is the alpha at p = 0. */
        fun ripple(p: Float, strength: Float) {
            if (p < 0f || strength <= 0f) { alpha = 0f; return }
            val k = ease(p)
            alpha = (1f - k) * strength
            scaleX = 1f + 0.85f * k; scaleY = scaleX
        }
    }

    /** The done check, drawn in along its length (SVG M40 57.5 l11 11 l22 -24 in the 112 dp box). */
    private inner class Check(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = TX
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val full = Path().apply {
            val u = px(OrbArt.SPHERE_DP) / OrbArt.SPHERE_DP
            moveTo(40f * u, 57.5f * u); rLineTo(11f * u, 11f * u); rLineTo(22f * u, -24f * u)
        }
        private val measure = PathMeasure(full, false)
        private val part = Path()
        var progress = 0f
            set(v) { if (v != field) { field = v; invalidate() } }

        override fun hasOverlappingRendering() = false
        override fun onDraw(c: Canvas) {
            if (progress <= 0f) return
            // CSS dasharray 52 over a ~48 dp path: fully drawn slightly before the end.
            val len = measure.length
            part.reset()
            measure.getSegment(0f, min(len, len * progress * 52f / 48.1f), part, true)
            c.drawPath(part, paint)
        }
    }

    /** One text slot: two stacked TextViews that cross-fade (outgoing drifts up, incoming rises). */
    private inner class Slot(private val dy: Float, private val durMs: Float) {
        val views = arrayOf(make(), make())
        private val specs = arrayOfNulls<Spec>(2)
        private val base = FloatArray(2)
        private var front = 0
        private var start = 0L
        private var fading = false
        private var outFrom = 1f
        private var frontAlpha = 1f

        private fun make() = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            alpha = 0f
        }

        fun set(spec: Spec, now: Long, animate: Boolean) {
            if (specs[front] == spec) return
            if (!animate) {
                apply(front, spec)
                fading = false
                frontAlpha = 1f
                views[1 - front].alpha = 0f
                return
            }
            // The current text (possibly still fading in) becomes the outgoing one.
            outFrom = if (fading) frontAlpha else 1f
            front = 1 - front
            apply(front, spec)
            start = now
            fading = true
            frontAlpha = 0f
        }

        fun clear() {
            for (i in 0..1) { specs[i] = null; views[i].text = ""; views[i].alpha = 0f }
            fading = false
        }

        fun frame(now: Long, v: Float) {
            val inV = views[front]; val outV = views[1 - front]
            if (!fading) {
                inV.alpha = v * frontAlpha
                inV.translationY = base[front]
                outV.alpha = 0f
                return
            }
            val k = ease((now - start) / durMs)
            val inA = TextFade.incomingAlpha(k, ease)
            outV.alpha = v * outFrom * TextFade.outgoingAlpha(k, ease)
            outV.translationY = base[1 - front] - dy * dp * k
            inV.alpha = v * inA
            inV.translationY = base[front] + dy * dp * (1f - inA)
            frontAlpha = inA
            if (now - start >= durMs) { fading = false; frontAlpha = 1f; outV.alpha = 0f }
        }

        private fun apply(i: Int, spec: Spec) {
            specs[i] = spec
            val tv = views[i]
            base[i] = 0f
            when (spec.style) {
                Style.LABEL_AMBER -> styleLabel(tv, AMBER)
                Style.LABEL_FAINT -> styleLabel(tv, FA)
                Style.LABEL_GREEN -> styleLabel(tv, GREEN)
                Style.LABEL_ROSE -> styleLabel(tv, ROSE)
                Style.QUOTE -> {
                    tv.isAllCaps = false
                    tv.typeface = REGULAR
                    tv.letterSpacing = 0f
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    tv.setTextColor(MU)
                    tv.setSingleLine(true)
                    tv.ellipsize = TextUtils.TruncateAt.END
                    base[i] = -3f * dp
                }
                Style.MAIN_TEXT, Style.MAIN_MUTED -> styleMain(tv, spec.text, if (spec.style == Style.MAIN_TEXT) TX else MU)
            }
            tv.text = spec.text
        }
    }

    private fun styleLabel(tv: TextView, color: Int) {
        tv.isAllCaps = true
        tv.typeface = MEDIUM
        tv.letterSpacing = 0.14f
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        tv.setTextColor(color)
        tv.setSingleLine(true)
        tv.ellipsize = TextUtils.TruncateAt.END
    }

    /** 36 sp light, −0.02 em, line height 1.2; steps down to 30 / 26 sp to fit (SPEC §2). */
    private fun styleMain(tv: TextView, text: String, color: Int) {
        tv.isAllCaps = false
        tv.typeface = LIGHT
        tv.letterSpacing = -0.02f
        tv.setSingleLine(false)
        tv.setTextColor(color)
        val width = px(820f)
        val paint = TextPaint(tv.paint)
        fun sizePx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        fun extra(p: TextPaint) = p.fontMetricsInt.let { 1.2f * p.textSize - (it.descent - it.ascent) }
        val fit = MainTextFit.choose { sp ->
            paint.textSize = sizePx(sp)
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(extra(paint), 1f)
                .setBreakStrategy(tv.breakStrategy)
                .setHyphenationFrequency(tv.hyphenationFrequency)
                .build().lineCount
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fit.sizeSp)
        tv.setLineSpacing(extra(tv.paint), 1f)
        tv.maxLines = fit.maxLines
        tv.ellipsize = TextUtils.TruncateAt.END
    }

    private companion object {
        const val DONE_HOLD_MS = 1_000L
        const val ERROR_HOLD_MS = 2_800L

        // tablet-home-card / SetupOverlay tokens
        val TX = Color.parseColor("#f4f4f5")
        val MU = Color.parseColor("#a1a1aa")
        val FA = Color.parseColor("#63636b")
        val AMBER = Color.parseColor("#fbbf24")
        val GREEN = Color.parseColor("#34d399")
        val ROSE = Color.parseColor("#fb7185")
        val SKY = Color.parseColor("#38bdf8")
        val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
