package sh.edm.s1kiosk.voice

import sh.edm.s1kiosk.ui.FloatImage
import sh.edm.s1kiosk.ui.Stop
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The voice orb's soft layers (SPEC §3), computed in float from the prototype's CSS (b/index.html,
 * 1 CSS px = 1 dp) at [pxPerDp], then baked once into dithered bitmaps by VoiceOverlay. Android 10
 * has no RenderEffect blur, so blurs happen here; at runtime only alpha, scale and rotation change.
 *
 * Every layer is centred on the orb centre. Radial stop percentages follow CSS `circle` sizing
 * (farthest-corner), which is what the prototype renders. Pure JVM.
 */
class OrbArt(private val pxPerDp: Float) {

    enum class Tint { AMBER, SKY, GREEN, ROSE }

    fun px(dp: Float): Int = (dp * pxPerDp).roundToInt()

    /** 300 dp glow behind the sphere; [Tint] or the two-colour thinking glow ([thinking]). */
    fun glow(tint: Tint?, thinking: Boolean = false): FloatImage {
        val side = px(GLOW_DP)
        val img = FloatImage(side, side)
        val box = GLOW_DP
        if (thinking) {
            // CSS draws the first background on top: violet first, then sky over it.
            radialDp(img, box, 0.60f, 0.58f, VIOLET, listOf(0f to 0.34f, 0.46f to 0f))
            radialDp(img, box, 0.40f, 0.42f, SKY, listOf(0f to 0.30f, 0.46f to 0f))
        } else {
            val (rgb, a, end) = when (tint!!) {
                Tint.AMBER -> Triple(AMBER, 0.34f, 0.62f)
                Tint.SKY -> Triple(SKY, 0.28f, 0.62f)
                Tint.GREEN -> Triple(GREEN, 0.28f, 0.60f)
                Tint.ROSE -> Triple(ROSE, 0.26f, 0.60f)
            }
            radialDp(img, box, 0.5f, 0.5f, rgb, listOf(0f to a, end to 0f))
        }
        img.clipCircle(side / 2f, side / 2f, side / 2f)
        return img
    }

    /** The coloured core inside the sphere: an 82 dp disc, blurred 7 dp, on a [CORE_DP] canvas. */
    fun core(tint: Tint): FloatImage {
        val side = px(CORE_DP)
        val img = FloatImage(side, side)
        val c = side / 2f
        val r = CORE_DISC_DP / 2f
        val fc = r * SQRT2 // farthest corner of the 82 dp box
        val stops = when (tint) {
            Tint.AMBER -> listOf(Stop.rgb(0f, AMBER, 0.95f), Stop.rgb(0.52f * fc, AMBER, 0.35f), Stop.rgb(0.72f * fc, AMBER, 0f))
            Tint.SKY -> listOf(Stop.rgb(0f, SKY_LIGHT, 0.9f), Stop.rgb(0.50f * fc, SKY, 0.45f), Stop.rgb(0.72f * fc, SKY, 0f))
            Tint.GREEN -> listOf(Stop.rgb(0f, GREEN, 0.55f), Stop.rgb(0.55f * fc, GREEN, 0.15f), Stop.rgb(0.72f * fc, GREEN, 0f))
            Tint.ROSE -> listOf(Stop.rgb(0f, ROSE, 0.60f), Stop.rgb(0.55f * fc, ROSE, 0.18f), Stop.rgb(0.72f * fc, ROSE, 0f))
        }
        img.radial(c, c, stops.map { it.copy(pos = it.pos * pxPerDp) })
        img.clipCircle(c, c, r * pxPerDp)
        img.blur(7f * pxPerDp)
        return img
    }

    /**
     * The thinking swirl: a conic gradient on a 158 dp disc, blurred 11 dp, cropped to [SWIRL_DP]
     * (any rotation still covers the sphere's 55 dp radius).
     */
    fun swirl(): FloatImage {
        val sigma = 11f * pxPerDp
        val work = px(2 * (SPHERE_DP / 2 + 3 * 11f))
        val img = FloatImage(work, work)
        val c = work / 2f
        img.conicDisc(
            c, c, SWIRL_DISC_DP / 2f * pxPerDp,
            listOf(
                Stop(0f, 0, 0, 0, 0f), Stop.rgb(70f, SKY, 0.85f), Stop.rgb(150f, VIOLET, 0.9f),
                Stop(220f, 0, 0, 0, 0f), Stop.rgb(290f, SKY, 0.35f), Stop(360f, 0, 0, 0, 0f),
            ),
        )
        img.blur(sigma)
        val side = px(SWIRL_DP)
        return img.cropCentre(side, side)
    }

    /**
     * The glass sphere behind the cores: drop shadow 0/10/40 black @ 0.35 (outside the sphere
     * only, as CSS does), fill white @ 0.04, inner shade (inset 0 0 28 white @ 0.05) and a 1 dp
     * white @ 0.13 border. On a [SPHERE_BACK_DP] canvas.
     */
    fun sphere(): FloatImage {
        val side = px(SPHERE_BACK_DP)
        val c = side / 2f
        val outer = SPHERE_DP / 2f * pxPerDp
        val inner = outer - pxPerDp
        val img = FloatImage(side, side)
        val n = side * side

        val shadow = FloatImage(side, side)
        shadow.disc(c, c + 10f * pxPerDp, outer, 0x000000, 1f)
        shadow.blur(20f * pxPerDp)
        val inset = FloatImage(side, side)
        forEachPixel(side) { i, d -> inset.a[i] = 1f - FloatImage.discCoverage(d, inner) }
        inset.blur(14f * pxPerDp)

        val cov = FloatArray(n)
        val covInner = FloatArray(n)
        forEachPixel(side) { i, d ->
            cov[i] = FloatImage.discCoverage(d, outer)
            covInner[i] = FloatImage.discCoverage(d, inner)
        }
        for (i in 0 until n) {
            // 1. outer shadow, not under the sphere
            img.over(i, 0f, 0f, 0f, 0.35f * shadow.a[i] * (1f - cov[i]))
            // 2. background
            val bg = 0.04f * cov[i]
            img.over(i, bg, bg, bg, bg)
            // 3. inset shade, inside the border
            val sh = 0.05f * inset.a[i] * covInner[i]
            img.over(i, sh, sh, sh, sh)
            // 4. border ring
            val bd = 0.13f * (cov[i] - covInner[i]).coerceAtLeast(0f)
            img.over(i, bd, bd, bd, bd)
        }
        return img
    }

    /** The always-on highlight, white @ 0.26 at (32 %, 24 %) of the sphere's 110 dp padding box. */
    fun shine(): FloatImage {
        val box = SPHERE_DP - 2
        val side = px(box)
        val img = FloatImage(side, side)
        val cx = 0.32f * box; val cy = 0.24f * box
        val fc = hypot(box - cx, box - cy)
        img.radial(cx * pxPerDp, cy * pxPerDp, listOf(Stop(0f, 255, 255, 255, 0.26f), Stop(0.36f * fc * pxPerDp, 255, 255, 255, 0f)))
        img.clipCircle(side / 2f, side / 2f, side / 2f)
        return img
    }

    /** A centred CSS `radial-gradient(circle at fx fy, …)` in a [boxDp] box; stops are (fraction, alpha). */
    private fun radialDp(img: FloatImage, boxDp: Float, fx: Float, fy: Float, rgb: Int, stops: List<Pair<Float, Float>>) {
        val cx = fx * boxDp; val cy = fy * boxDp
        val fc = maxOf(hypot(cx, cy), hypot(boxDp - cx, cy), hypot(cx, boxDp - cy), hypot(boxDp - cx, boxDp - cy))
        img.radial(cx * pxPerDp, cy * pxPerDp, stops.map { (f, a) -> Stop.rgb(f * fc * pxPerDp, rgb, a) }, smooth = true)
    }

    private inline fun forEachPixel(side: Int, f: (Int, Float) -> Unit) {
        val c = side / 2f
        for (y in 0 until side) {
            val dy = y + 0.5f - c
            for (x in 0 until side) {
                val dx = x + 0.5f - c
                f(y * side + x, sqrt(dx * dx + dy * dy))
            }
        }
    }

    companion object {
        const val GLOW_DP = 300f
        const val SPHERE_DP = 112f
        const val CORE_DISC_DP = 82f // inset 14 inside the 110 dp padding box
        const val CORE_DP = CORE_DISC_DP + 2 * 3 * 7f // + 3 sigma of blur each side
        const val SWIRL_DISC_DP = 158f // inset -24
        const val SWIRL_DP = SPHERE_DP
        const val SPHERE_BACK_DP = SPHERE_DP + 2 * 70f // room for the 0/10/40 shadow

        const val AMBER = 0xfbbf24
        const val SKY = 0x38bdf8
        const val SKY_LIGHT = 0xe0f2fe
        const val VIOLET = 0xa78bfa
        const val GREEN = 0x34d399
        const val ROSE = 0xfb7185
        private val SQRT2 = sqrt(2f)
    }
}
