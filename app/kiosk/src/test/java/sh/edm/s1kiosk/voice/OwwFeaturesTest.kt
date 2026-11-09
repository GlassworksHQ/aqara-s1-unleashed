package sh.edm.s1kiosk.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Checks OwwFeatures against openWakeWord's own AudioFeatures streaming code, run with the same
 * deterministic fake models by tools/voice/oww_reference.py (fixture: oww_reference.json).
 */
class OwwFeaturesTest {

    private fun lcg(n: Int, seed: Long, span: Int): ShortArray {
        var x = seed
        return ShortArray(n) {
            x = (x * 1103515245L + 12345L) % 2147483648L
            ((x shr 8) % (2L * span) - span).toInt().toShort()
        }
    }

    private fun fakeMel(x: FloatArray): Array<FloatArray> {
        val frames = ceil(x.size / 160.0).toInt() - 3
        return Array(frames) { j ->
            var s = 0.0
            var k = j * 160
            while (k < j * 160 + 400) { s += x[k]; k += 40 }
            FloatArray(32) { b -> (s / (1000.0 * (b + 1)) + b * 0.01).toFloat() }
        }
    }

    private fun fakeEmb(w: Array<FloatArray>): FloatArray = FloatArray(96) { d ->
        var s = 0.0
        for (r in 0 until 76) s += w[r][d % 32].toDouble() * ((r + 1) / 76.0)
        (s + d * 0.001).toFloat()
    }

    private fun fakeWake(f: Array<FloatArray>): Double {
        var s = 0.0
        for (i in f.indices) for (v in f[i]) s += v.toDouble() * (i + 1)
        return s / 1000.0
    }

    private fun close(expected: Double, actual: Double, what: String) {
        val tol = 1e-4 * max(1.0, abs(expected))
        if (abs(expected - actual) > tol) throw AssertionError("$what: expected $expected, got $actual")
    }

    @Test
    fun matchesUpstreamStreaming() {
        val ref = JSONObject(javaClass.classLoader!!.getResource("oww_reference.json")!!.readText())
        val noise = lcg(16_000 * 4, 7, 1000).let { s -> FloatArray(s.size) { s[it].toFloat() } }
        val f = OwwFeatures(::fakeMel, ::fakeEmb, noise)
        assertEquals(ref.getInt("init_feature_rows"), f.featureCount)

        val audio = lcg(1280 * 120, 12345, 10000)
        val scores = ref.getJSONArray("scores")
        for (i in 0 until 120) {
            f.accept(audio.copyOfRange(i * 1280, (i + 1) * 1280))
            close(scores.getDouble(i), fakeWake(f.lastFeatures(16)), "score[$i]")
        }
        assertEquals(ref.getInt("mel_rows"), f.melCount)
        assertEquals(ref.getInt("feature_rows"), f.featureCount)

        val last = ref.getJSONArray("last_features")
        val feats = f.lastFeatures(16)
        for (i in 0 until 16) for (d in 0 until 96) {
            close(last.getDouble(i * 96 + d), feats[i][d].toDouble(), "feature[$i][$d]")
        }
    }

    @Test
    fun detectorZeroesFirstFivePredictions() {
        val f = OwwFeatures(::fakeMel, ::fakeEmb, FloatArray(16_000 * 4))
        val det = OwwDetector(f, 16) { 0.9f }
        val out = (0 until 8).map { det.process(ShortArray(1280)) }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f, 0.9f, 0.9f), out)
    }

    @Test
    fun phraseFromFileName() {
        assertEquals("hey jarvis", TfliteWakeWord.phraseFor("hey_jarvis_v0.1.tflite"))
        assertEquals("alexa", TfliteWakeWord.phraseFor("alexa_v0.1.tflite"))
        assertEquals("hey_mycroft_v0.1", TfliteWakeWord.modelName("hey_mycroft_v0.1.tflite"))
    }
}
