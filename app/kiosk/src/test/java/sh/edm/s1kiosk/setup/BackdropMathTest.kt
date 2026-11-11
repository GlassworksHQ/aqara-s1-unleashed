package sh.edm.s1kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.ui.Dither
import kotlin.math.abs
import kotlin.math.roundToInt

class BackdropMathTest {

    private val w = 1440
    private val h = 720

    @Test
    fun falloffIsMonotoneSmoothAndEndsAtTheRadius() {
        assertEquals(1f, BackdropMath.falloff(0f), 0f)
        assertEquals(0f, BackdropMath.falloff(1f), 0f)
        assertEquals(0f, BackdropMath.falloff(1.7f), 0f)
        var prev = BackdropMath.falloff(0f)
        var maxStep = 0f
        for (i in 1..1500) {
            val v = BackdropMath.falloff(i / 1000f)
            assertTrue("monotone at ${i / 1000f}", v <= prev)
            maxStep = maxOf(maxStep, prev - v)
            prev = v
        }
        // Slope is at most 1.5 (smoothstep), so a 0.001 step never jumps by more than ~0.0015.
        assertTrue("no jumps: $maxStep", maxStep < 0.0016f)
        // Flat at both ends: no visible edge where the glow starts or stops.
        assertTrue(BackdropMath.falloff(0.01f) > 0.999f)
        assertTrue(BackdropMath.falloff(0.99f) < 0.001f)
    }

    @Test
    fun glowCentresHaveTheOldColoursAndStrengths() {
        val c = FloatArray(3)
        // Sky centre (-10 %, -20 %) = (-144, -144): pixel -145 has its centre at -144.5.
        BackdropMath.colorAt(-145, -145, w, h, c)
        val sky = 33 / 255f
        assertEquals(10 + (56 - 10) * sky, c[0], 0.01f)
        assertEquals(11 + (189 - 11) * sky, c[1], 0.01f)
        assertEquals(15 + (248 - 15) * sky, c[2], 0.01f)
        // Violet centre (110 %, 120 %) = (1584, 864); the sky glow (radius 1224) doesn't reach it.
        BackdropMath.colorAt(1583, 863, w, h, c)
        val violet = 36 / 255f
        assertEquals(10 + (167 - 10) * violet, c[0], 0.01f)
        assertEquals(11 + (139 - 11) * violet, c[1], 0.01f)
        assertEquals(15 + (250 - 15) * violet, c[2], 0.01f)
        // Outside both radii: exactly the base #0a0b0f.
        BackdropMath.colorAt(5000, -3000, w, h, c)
        assertEquals(listOf(10f, 11f, 15f), c.toList())
    }

    @Test
    fun ditherNoiseIsTriangularWithZeroMean() {
        val d = Dither(7)
        var sum = 0.0
        var sumSq = 0.0
        val n = 200_000
        repeat(n) {
            val v = d.noise()
            assertTrue(v > -1f && v < 1f)
            sum += v; sumSq += v * v
        }
        assertEquals(0.0, sum / n, 0.01)
        assertEquals(1.0 / 6, sumSq / n, 0.01) // variance of rand() - rand()
    }

    @Test
    fun noiseIsPresentInAFlatArea() {
        val d = Dither(3)
        val seen = (0 until 1000).map { d.quantize(12.5f) }.toSet()
        assertTrue("both neighbours appear: $seen", 12 in seen && 13 in seen)
        assertTrue(seen.all { it in 11..14 }) // at most ±1 LSB of noise
    }

    /**
     * The sky glow's red channel only spans 10..16 across the screen: plain rounding turns that into
     * six flat bands (local error up to 0.5 LSB); with dither the local average follows the float.
     */
    @Test
    fun ditheredRampHasNoBands() {
        val len = 1200
        val rows = 64
        val d = Dither(11)
        val ditheredMean = DoubleArray(len)
        for (y in 0 until rows) for (x in 0 until len) ditheredMean[x] += d.quantize(ramp(x, len)).toDouble() / rows
        var worstDithered = 0.0
        var worstPlain = 0.0
        val win = 8
        for (x0 in 0 until len - win step win) {
            var f = 0.0; var q = 0.0; var p = 0.0
            for (x in x0 until x0 + win) { f += ramp(x, len); q += ditheredMean[x]; p += ramp(x, len).roundToInt() }
            worstDithered = maxOf(worstDithered, abs(q - f) / win)
            worstPlain = maxOf(worstPlain, abs(p - f) / win)
        }
        assertTrue("dithered local error $worstDithered", worstDithered < 0.12)
        assertTrue("plain rounding bands ($worstPlain)", worstPlain > 0.4)
    }

    @Test
    fun bakedRowIsOpaqueAndUnbiased() {
        val d = Dither(5)
        val row = IntArray(w)
        val c = FloatArray(3)
        for (y in intArrayOf(0, 360, 719)) {
            BackdropMath.row(y, w, h, d, row)
            var err = 0.0
            for (x in 0 until w) {
                assertEquals(0xFF, row[x] ushr 24)
                BackdropMath.colorAt(x, y, w, h, c)
                val r = (row[x] shr 16) and 0xFF
                val g = (row[x] shr 8) and 0xFF
                val b = row[x] and 0xFF
                assertTrue(abs(r - c[0]) <= 1.5f && abs(g - c[1]) <= 1.5f && abs(b - c[2]) <= 1.5f)
                err += (r - c[0]) + (g - c[1]) + (b - c[2])
            }
            assertEquals("row $y mean error", 0.0, err / (3 * w), 0.05)
        }
    }

    private fun ramp(x: Int, len: Int) = 10f + 6f * x / (len - 1)

    @Test
    fun bandsCoverEveryRowOnceInOrder() {
        for (n in listOf(1, 2, 3, 4, 7)) {
            val b = BackdropMath.bands(h, n)
            assertEquals(n, b.size)
            assertEquals((0 until h).toList(), b.flatten())
            assertTrue("near-equal: $b", b.maxOf { it.count() } - b.minOf { it.count() } <= 1)
        }
        assertEquals(listOf(0 until 1, 1 until 2), BackdropMath.bands(2, 4)) // never more bands than rows
        assertTrue(BackdropMath.bands(0, 4).isEmpty())
    }

    @Test
    fun fallbackStopsFollowTheFalloff() {
        val s = BackdropMath.FALLBACK_STOPS
        assertEquals(0f, s.first(), 0f)
        assertEquals(1f, s.last(), 0f)
        assertTrue(s.toList().zipWithNext().all { (a, b) -> b > a })
        // Ends flat: no ring where the glow stops.
        assertEquals(0f, BackdropMath.falloff(s.last()), 0f)
        assertTrue(BackdropMath.falloff(s[s.size - 2]) < 0.02f)
    }
}
