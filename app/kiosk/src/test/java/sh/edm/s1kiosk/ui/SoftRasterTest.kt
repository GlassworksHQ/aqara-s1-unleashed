package sh.edm.s1kiosk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftRasterTest {

    @Test
    fun gradientInterpolatesInPremultipliedSpace() {
        val stops = listOf(Stop(0f, 255, 0, 0, 1f), Stop(10f, 0, 0, 255, 0f))
        val out = FloatArray(4)
        FloatImage.sample(stops, 5f, smooth = false, out = out)
        // Halfway to *transparent* blue: no blue tint at all (CSS premultiplied interpolation).
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(0.5f, out[3], 1e-6f)
        FloatImage.sample(stops, -3f, smooth = false, out = out)
        assertEquals(1f, out[3], 0f)
        FloatImage.sample(stops, 30f, smooth = false, out = out)
        assertEquals(0f, out[3], 0f)
        FloatImage.sample(stops, 9.9f, smooth = true, out = out)
        assertTrue("smooth segment end is flat: ${out[3]}", out[3] < 0.001f)
    }

    @Test
    fun conicStartsAtTwelveOClockAndTurnsClockwise() {
        val img = FloatImage(41, 41)
        val stops = listOf(
            Stop(0f, 255, 0, 0, 1f), Stop(90f, 0, 255, 0, 1f), Stop(180f, 0, 0, 255, 1f), Stop(360f, 0, 0, 255, 1f),
        )
        img.conicDisc(20.5f, 20.5f, 20f, stops)
        fun px(x: Int, y: Int) = y * 41 + x
        assertTrue(img.r[px(20, 2)] > 0.9f)   // up: red
        assertTrue(img.g[px(38, 20)] > 0.9f)  // right (90°): green
        assertTrue(img.b[px(20, 38)] > 0.9f)  // down (180°): blue
        assertEquals(0f, img.a[px(0, 0)], 0f) // outside the disc
    }

    @Test
    fun blurSpreadsButKeepsTheMass() {
        val img = FloatImage(120, 120)
        img.disc(60f, 60f, 20f, 0xFFFFFF, 1f)
        val before = img.a.sum()
        img.blur(6f)
        assertEquals(before, img.a.sum(), before * 0.01f)
        assertTrue(img.a[60 * 120 + 60] > 0.95f)          // still solid in the middle
        assertTrue(img.a[60 * 120 + 60 + 20] in 0.35f..0.65f) // the edge is now a soft ramp
        assertTrue(img.a[60 * 120 + 60 + 30] > 0.001f)
    }

    @Test
    fun boxesApproximateTheSigma() {
        // Three boxes of width w have variance 3 * (w² - 1) / 12 ≈ sigma².
        for (sigma in floatArrayOf(4f, 10.5f, 16.5f, 30f)) {
            val v = FloatImage.boxesForGauss(sigma, 3).sumOf { r -> val w = 2.0 * r + 1; (w * w - 1) / 12 }
            assertEquals(sigma * sigma.toDouble(), v, sigma * sigma * 0.15)
        }
    }

    @Test
    fun bytesArePremultipliedDitheredAndKeepTransparencyExact() {
        val img = FloatImage(64, 64)
        // Left half: a faint flat glow (alpha 0.2, colour 0.1 premultiplied); right half: nothing.
        for (y in 0 until 64) for (x in 0 until 32) img.over(y * 64 + x, 0.1f, 0.05f, 0.02f, 0.2f)
        val bytes = img.toRgbaBytes(Dither(9))
        var sumA = 0.0
        val seenA = HashSet<Int>()
        for (y in 0 until 64) for (x in 0 until 64) {
            val o = (y * 64 + x) * 4
            val r = bytes[o].toInt() and 0xFF
            val g = bytes[o + 1].toInt() and 0xFF
            val b = bytes[o + 2].toInt() and 0xFF
            val a = bytes[o + 3].toInt() and 0xFF
            assertTrue(r <= a && g <= a && b <= a)
            if (x >= 32) assertEquals(0, r or g or b or a) else { sumA += a; seenA += a }
        }
        assertEquals(0.2 * 255, sumA / (32 * 64), 0.1)
        assertTrue("noise present: $seenA", seenA.size >= 2)
    }

    @Test
    fun easeInOutCubicIsMonotoneAndSymmetric() {
        assertEquals(0f, Soft.easeInOutCubic(0f), 0f)
        assertEquals(1f, Soft.easeInOutCubic(1f), 0f)
        assertEquals(0.5f, Soft.easeInOutCubic(0.5f), 1e-6f)
        var prev = 0f
        for (i in 1..100) {
            val v = Soft.easeInOutCubic(i / 100f)
            assertTrue(v >= prev && v <= 1f) // never overshoots
            prev = v
        }
    }
}
