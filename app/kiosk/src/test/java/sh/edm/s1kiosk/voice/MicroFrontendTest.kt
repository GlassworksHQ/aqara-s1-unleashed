package sh.edm.s1kiosk.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The fixtures tools/voice/mww_reference.py makes: speech synthesised with Piper (mww_clip.wav) and
 * the TFLite Micro frontend's output for it, from pymicro-features (mww_reference.json).
 */
object MwwFixtures {
    val reference: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResource("mww_reference.json")!!.readText())
    }

    /** mww_clip.wav: 16 kHz mono 16-bit PCM. */
    val clip: ShortArray by lazy {
        val b = ByteBuffer.wrap(javaClass.classLoader!!.getResource("mww_clip.wav")!!.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (true) {
            val id = String(ByteArray(4) { b.get(p + it) }, Charsets.US_ASCII)
            val len = b.getInt(p + 4)
            if (id == "fmt ") {
                check(b.getShort(p + 8).toInt() == 1 && b.getShort(p + 10).toInt() == 1 && b.getInt(p + 12) == 16_000 && b.getShort(p + 22).toInt() == 16)
            }
            if (id == "data") return@lazy ShortArray(len / 2) { b.getShort(p + 8 + 2 * it) }
            p += 8 + len
        }
        @Suppress("UNREACHABLE_CODE") ShortArray(0)
    }

    /** The generator's synthetic signal: loud, medium, quiet noise, silence, medium noise. */
    fun synthetic(): ShortArray = lcg(4000, 1, 32767) + lcg(3200, 4, 2000) + lcg(3200, 2, 40) + ShortArray(2400) + lcg(4000, 3, 3000)

    private fun lcg(n: Int, seed: Long, span: Int): ShortArray {
        var x = seed
        return ShortArray(n) {
            x = (x * 1103515245L + 12345L) % 2147483648L
            ((x shr 8) % (2L * span) - span).toInt().toShort()
        }
    }

    fun rows(a: JSONArray): List<IntArray> = List(a.length()) { i -> a.getJSONArray(i).let { r -> IntArray(r.length()) { r.getInt(it) } } }
}

/**
 * [MicroFrontend] against pymicro-features (the TFLite Micro frontend's C code).
 *
 * The speech must match exactly. The synthetic signal may differ in at most 1 value in 1000, by at
 * most 8: the reference was made with the macOS arm64 wheel, whose clang fuses
 * `mel_low + mel_spacing * (i + 1)` (filterbank_util.c) into one FMA, which moves filterbank weight
 * 293 of 316 by 1/4096 (1400/2696 here, 1401/2695 there). The same C built with `-ffp-contract=off`
 * equals [MicroFrontend] bit for
 * bit (checked 2026-11-12, docs/research/microwakeword.md).
 */
class MicroFrontendTest {

    private fun run(samples: ShortArray): List<IntArray> {
        val fe = MicroFrontend()
        val out = ArrayList<IntArray>()
        var i = 0
        while (i + MicroFrontend.STEP <= samples.size) {
            fe.accept(samples, i)?.let { out += it.copyOf() }
            i += MicroFrontend.STEP
        }
        return out
    }

    private fun assertSameFeatures(what: String, expected: List<IntArray>, actual: List<IntArray>, allowed: Int = 0, maxAllowedDiff: Int = 0) {
        assertEquals("$what: rows", expected.size, actual.size)
        var differing = 0
        var maxDiff = 0
        var first = ""
        for (r in expected.indices) for (c in 0 until MicroFrontend.CHANNELS) {
            val d = kotlin.math.abs(expected[r][c] - actual[r][c])
            if (d != 0) {
                if (differing++ == 0) first = "row $r channel $c: expected ${expected[r][c]}, got ${actual[r][c]}"
                maxDiff = maxOf(maxDiff, d)
            }
        }
        val msg = "$what: $differing values differ from the reference (max diff $maxDiff; first $first)"
        if (allowed == 0) assertEquals(msg, 0, differing)
        assertTrue(msg, differing <= allowed && maxDiff <= maxAllowedDiff)
    }

    @Test
    fun matchesTheReferenceFrontendOnSpeech() {
        val expected = MwwFixtures.rows(MwwFixtures.reference.getJSONObject("features").getJSONArray("clip"))
        val clip = MwwFixtures.clip
        assertSameFeatures("clip", expected, run(clip.copyOf(16_000 * 5 / 2)))
    }

    @Test
    fun matchesTheReferenceFrontendOnLoudQuietAndSilentAudio() {
        val expected = MwwFixtures.rows(MwwFixtures.reference.getJSONObject("features").getJSONArray("synthetic"))
        assertSameFeatures("synthetic", expected, run(MwwFixtures.synthetic()), allowed = expected.size * MicroFrontend.CHANNELS / 1000, maxAllowedDiff = 8)
    }

    @Test
    fun theFirstFeaturesComeWithTheThirdStep() {
        val fe = MicroFrontend()
        val step = ShortArray(MicroFrontend.STEP) { (it * 37 % 200 - 100).toShort() }
        assertNull(fe.accept(step))
        assertNull(fe.accept(step))
        assertEquals(MicroFrontend.CHANNELS, fe.accept(step)!!.size)
    }
}
