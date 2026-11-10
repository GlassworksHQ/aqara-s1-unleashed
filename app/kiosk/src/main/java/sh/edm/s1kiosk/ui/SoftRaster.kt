package sh.edm.s1kiosk.ui

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-JVM helpers for baking soft art (backdrop glows, the voice orb's blurred layers) once into
 * 8-bit bitmaps without banding: compute in float, then round with triangular dither.
 *
 * Why: dark, low-contrast gradients (a glow at alpha 0.13 over #0a0b0f) only span a handful of
 * 8-bit levels, so plain rounding draws visible rings. ±1 LSB triangular (TPDF) noise before
 * rounding trades the rings for fine grain that the eye averages away, and keeps the local mean
 * equal to the float value.
 */
object Soft {
    fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** CSS-style ease-in-out cubic (the voice prototype's `ease`). */
    fun easeInOutCubic(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    }
}

/** Triangular (TPDF) dither noise in (-1, 1) LSB from a deterministic xorshift32 generator. */
class Dither(seed: Int = 0x2545F491) {
    private var s = if (seed == 0) 1 else seed
    private var bits = 0
    private var left = 0

    private fun next(): Int {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return x
    }

    private fun byte(): Int {
        if (left == 0) { bits = next(); left = 4 }
        val b = bits and 0xFF
        bits = bits ushr 8
        left--
        return b
    }

    /** `rand() - rand()`, each uniform on [0, 1) in 1/256 steps: triangular on (-1, 1), mean 0. */
    fun noise(): Float = (byte() - byte()) / 256f

    /** [v] on the 0..255 scale, rounded after adding dither noise, clamped to 0..255. */
    fun quantize(v: Float): Int = (v + noise()).roundToInt().coerceIn(0, 255)
}

/** One colour stop: [pos] in px (radial) or degrees (conic); rgb 0..255 unpremultiplied, [a] 0..1. */
data class Stop(val pos: Float, val r: Int, val g: Int, val b: Int, val a: Float) {
    companion object {
        fun rgb(pos: Float, rgb: Int, a: Float) = Stop(pos, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, a)
    }
}

/**
 * A premultiplied RGBA image in floats (0..1 per channel), all operations "source over".
 * Coordinates are pixel centres (x + 0.5, y + 0.5).
 */
class FloatImage(val w: Int, val h: Int) {
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)
    val a = FloatArray(w * h)

    /** Composites a premultiplied colour over pixel [i]. */
    fun over(i: Int, pr: Float, pg: Float, pb: Float, pa: Float) {
        if (pa <= 0f && pr <= 0f && pg <= 0f && pb <= 0f) return
        val k = 1f - pa
        r[i] = pr + r[i] * k
        g[i] = pg + g[i] * k
        b[i] = pb + b[i] * k
        a[i] = pa + a[i] * k
    }

    /**
     * CSS-like radial gradient centred at ([cx], [cy]); stops in px from the centre, interpolated in
     * premultiplied space (as CSS does). [smooth] eases each segment with smoothstep so the glow
     * ends without a visible edge.
     */
    fun radial(cx: Float, cy: Float, stops: List<Stop>, smooth: Boolean = false) {
        val tmp = FloatArray(4)
        for (y in 0 until h) {
            val dy = y + 0.5f - cy
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                sample(stops, sqrt(dx * dx + dy * dy), smooth, tmp)
                over(y * w + x, tmp[0], tmp[1], tmp[2], tmp[3])
            }
        }
    }

    /**
     * CSS `conic-gradient(from 0deg …)` filling a disc of [radius] (antialiased edge): 0° points
     * up and angles grow clockwise; stops in degrees.
     */
    fun conicDisc(cx: Float, cy: Float, radius: Float, stops: List<Stop>) {
        val tmp = FloatArray(4)
        for (y in 0 until h) {
            val dy = y + 0.5f - cy
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                val cov = discCoverage(sqrt(dx * dx + dy * dy), radius)
                if (cov <= 0f) continue
                var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                if (deg < 0f) deg += 360f
                sample(stops, deg, false, tmp)
                over(y * w + x, tmp[0] * cov, tmp[1] * cov, tmp[2] * cov, tmp[3] * cov)
            }
        }
    }

    /** A flat disc (antialiased edge); rgb 0..255, [alpha] 0..1. */
    fun disc(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float) {
        val cr = ((rgb shr 16) and 0xFF) / 255f
        val cg = ((rgb shr 8) and 0xFF) / 255f
        val cb = (rgb and 0xFF) / 255f
        for (y in 0 until h) {
            val dy = y + 0.5f - cy
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                val pa = alpha * discCoverage(sqrt(dx * dx + dy * dy), radius)
                if (pa > 0f) over(y * w + x, cr * pa, cg * pa, cb * pa, pa)
            }
        }
    }

    /** Multiplies every pixel by [mask] (0..1 coverage, same size). */
    fun mask(mask: FloatArray) {
        for (i in a.indices) {
            val m = mask[i]
            r[i] *= m; g[i] *= m; b[i] *= m; a[i] *= m
        }
    }

    /** Keeps only the part inside a circle (antialiased), like `border-radius: 50%` / `overflow: hidden`. */
    fun clipCircle(cx: Float, cy: Float, radius: Float) {
        for (y in 0 until h) {
            val dy = y + 0.5f - cy
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                val c = discCoverage(sqrt(dx * dx + dy * dy), radius)
                if (c < 1f) {
                    val i = y * w + x
                    r[i] *= c; g[i] *= c; b[i] *= c; a[i] *= c
                }
            }
        }
    }

    /** Approximate Gaussian blur (three box passes), transparent outside the image. */
    fun blur(sigma: Float) {
        if (sigma <= 0.3f) return
        val boxes = boxesForGauss(sigma, 3)
        val tmp = FloatArray(max(w, h))
        for (ch in arrayOf(r, g, b, a)) {
            for (radius in boxes) {
                for (y in 0 until h) boxLine(ch, y * w, 1, w, radius, tmp)
                for (x in 0 until w) boxLine(ch, x, w, h, radius, tmp)
            }
        }
    }

    /** Copies the centred [cw]×[ch] window (crop). */
    fun cropCentre(cw: Int, ch: Int): FloatImage {
        val out = FloatImage(cw, ch)
        val ox = (w - cw) / 2
        val oy = (h - ch) / 2
        for (y in 0 until ch) for (x in 0 until cw) {
            val sx = x + ox; val sy = y + oy
            if (sx !in 0 until w || sy !in 0 until h) continue
            val s = sy * w + sx; val d = y * cw + x
            out.r[d] = r[s]; out.g[d] = g[s]; out.b[d] = b[s]; out.a[d] = a[s]
        }
        return out
    }

    /**
     * Premultiplied RGBA bytes (Android's ARGB_8888 memory order, for `Bitmap.copyPixelsFromBuffer`),
     * dithered. Fully transparent pixels stay exactly 0, and colour never exceeds alpha.
     */
    fun toRgbaBytes(d: Dither): ByteArray {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            if (a[i] <= 0f && r[i] <= 0f && g[i] <= 0f && b[i] <= 0f) continue
            val qa = d.quantize(a[i] * 255f)
            val o = i * 4
            out[o] = min(d.quantize(r[i] * 255f), qa).toByte()
            out[o + 1] = min(d.quantize(g[i] * 255f), qa).toByte()
            out[o + 2] = min(d.quantize(b[i] * 255f), qa).toByte()
            out[o + 3] = qa.toByte()
        }
        return out
    }

    companion object {
        /** Coverage of a disc edge at distance [d] from the centre (1 px antialiasing). */
        fun discCoverage(d: Float, radius: Float): Float = (radius - d + 0.5f).coerceIn(0f, 1f)

        /** Premultiplied colour of [stops] at position [p] into out[r, g, b, a]. */
        fun sample(stops: List<Stop>, p: Float, smooth: Boolean, out: FloatArray) {
            val first = stops.first()
            val last = stops.last()
            val (s0, s1, k) = when {
                p <= first.pos -> Triple(first, first, 0f)
                p >= last.pos -> Triple(last, last, 0f)
                else -> {
                    var i = 1
                    while (stops[i].pos < p) i++
                    val lo = stops[i - 1]; val hi = stops[i]
                    val t = if (hi.pos > lo.pos) (p - lo.pos) / (hi.pos - lo.pos) else 1f
                    Triple(lo, hi, if (smooth) Soft.smoothstep(t) else t)
                }
            }
            val a0 = s0.a; val a1 = s1.a
            out[0] = lerp(s0.r / 255f * a0, s1.r / 255f * a1, k)
            out[1] = lerp(s0.g / 255f * a0, s1.g / 255f * a1, k)
            out[2] = lerp(s0.b / 255f * a0, s1.b / 255f * a1, k)
            out[3] = lerp(a0, a1, k)
        }

        private fun lerp(x: Float, y: Float, k: Float) = x + (y - x) * k

        /** Box radii whose [n] passes approximate a Gaussian of [sigma] (Kovesi / Ivan Kutskir). */
        fun boxesForGauss(sigma: Float, n: Int): IntArray {
            val wIdeal = sqrt(12.0 * sigma * sigma / n + 1)
            var wl = wIdeal.toInt()
            if (wl % 2 == 0) wl--
            val wu = wl + 2
            val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4)
            val m = Math.round(mIdeal).toInt()
            return IntArray(n) { i -> ((if (i < m) wl else wu) - 1) / 2 }
        }

        /** One box pass of [radius] over [len] samples of [ch] starting at [start] with [step]. */
        private fun boxLine(ch: FloatArray, start: Int, step: Int, len: Int, radius: Int, tmp: FloatArray) {
            if (radius <= 0) return
            for (i in 0 until len) tmp[i] = ch[start + i * step]
            val norm = 1f / (2 * radius + 1)
            var acc = 0f
            for (i in 0..min(radius, len - 1)) acc += tmp[i]
            for (i in 0 until len) {
                ch[start + i * step] = acc * norm
                val add = i + radius + 1
                val sub = i - radius
                if (add < len) acc += tmp[add]
                if (sub >= 0) acc -= tmp[sub]
            }
        }
    }
}
