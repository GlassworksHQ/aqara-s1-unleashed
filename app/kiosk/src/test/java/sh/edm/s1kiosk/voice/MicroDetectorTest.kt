package sh.edm.s1kiosk.voice

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** The streaming detection around the model: strides, quantization, the sliding window. */
class MicroDetectorTest {
    private val tensors = MicroTensors(stride = 3, inputScale = 0.10196078568696976f, inputZeroPoint = -128, outputScale = 0.00390625f, outputZeroPoint = 0)

    @Test
    fun theModelSeesEachRunOfThreeFeatureRowsQuantizedLikePymicroWakeword() {
        val inputs = ArrayList<ByteArray>()
        val det = MicroDetector(tensors, window = 5) { inputs += it.copyOf(); 0 }
        val clip = MwwFixtures.clip.copyOf(16_000 * 5 / 2)
        det.accept(clip)

        val rows = MwwFixtures.rows(MwwFixtures.reference.getJSONObject("features").getJSONArray("clip"))
        assertEquals("one inference per 30 ms", rows.size / 3, inputs.size)
        for (k in inputs.indices) {
            val expected = ByteArray(120) { j ->
                val v = rows[3 * k + j / 40][j % 40] * 0.0390625
                Math.rint(v / 0.10196078568696976 - 128).coerceIn(-128.0, 127.0).toInt().toByte()
            }
            assertArrayEquals("inference $k", expected, inputs[k])
        }
    }

    @Test
    fun theScoreIsTheMeanOfTheLastFiveOutputsOnceThereAreFive() {
        val outputs = ArrayDeque(listOf(255, 0, 0, 0, 0, 255, 255, 255, 255, 255))
        var runs = 0
        val det = MicroDetector(tensors, window = 5) { runs++; outputs.removeFirst() }
        val scores = ArrayList<Float>()
        while (outputs.isNotEmpty()) {
            val before = runs
            val score = det.accept(ShortArray(160))
            if (runs > before) scores += score else assertEquals("no inference, no score", 0f, score)
        }
        val p = 255 / 256f
        assertEquals(listOf(0f, 0f, 0f, 0f, p / 5, p / 5, p * 2 / 5, p * 3 / 5, p * 4 / 5, p), scores)
    }

    @Test
    fun aWholeFrameReturnsItsBestScore() {
        val outputs = ArrayDeque(listOf(0, 0, 0, 0, 255, 0, 0, 0, 0, 0))
        val det = MicroDetector(tensors, window = 5) { outputs.removeFirst() }
        // 8 feature rows per 1280 samples, from the third 10 ms step: 2, then 2, then 3 inferences.
        val scores = (0 until 3).map { det.accept(ShortArray(1280)) }
        assertEquals(listOf(0f, 0f, 255 / 256f / 5), scores)
    }

    @Test
    fun samplesArriveInAnySize() {
        val whole = ArrayList<ByteArray>()
        MicroDetector(tensors, 5) { whole += it.copyOf(); 0 }.accept(MwwFixtures.clip.copyOf(16_000))
        val pieces = ArrayList<ByteArray>()
        val det = MicroDetector(tensors, 5) { pieces += it.copyOf(); 0 }
        val clip = MwwFixtures.clip.copyOf(16_000)
        var i = 0
        for (n in generateSequence(7) { it * 3 % 1001 + 1 }) {
            if (i >= clip.size) break
            det.accept(clip.copyOfRange(i, minOf(clip.size, i + n)))
            i += n
        }
        assertEquals(whole.size, pieces.size)
        for (k in whole.indices) assertArrayEquals(whole[k], pieces[k])
    }
}

/**
 * Each official model, run by LiteRT, behind [MicroDetector] over mww_clip.wav: it fires in its own
 * word and nowhere else, and its best score in each phrase is within 0.01 of the one
 * pymicro-wakeword reached (mww_reference.json). Not exact: the TFLite C library pymicro-wakeword
 * bundles and LiteRT round a few int8 outputs differently (Alexa in its phrase: 0.9742 and 0.9727
 * from the same features, checked 2026-11-12). The models are fetched by [ModelFetcher.micro] into
 * `~/.cache/s1kiosk/microwakeword-<commit>` (or MWW_MODELS_DIR).
 *
 *     uv venv /tmp/mww-venv && uv pip install -p /tmp/mww-venv/bin/python ai-edge-litert numpy
 *     MWW_PYTHON=/tmp/mww-venv/bin/python ./gradlew :kiosk:testDebugUnitTest --tests '*MicroWakeWordClipTest*'
 *
 * Skipped when MWW_PYTHON is not set.
 */
class MicroWakeWordClipTest {
    private val python = System.getenv("MWW_PYTHON")
    private val dir = File(System.getenv("MWW_MODELS_DIR")
        ?: "${System.getProperty("user.home")}/.cache/s1kiosk/microwakeword-${WakeWords.MODELS_COMMIT}")

    private class PythonModel(python: String, model: File) : AutoCloseable {
        private val script = File(javaClass.classLoader!!.getResource("mww_model.py")!!.toURI())
        private val proc = ProcessBuilder(python, "-I", script.path, model.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val out = proc.outputStream
        private val inp = DataInputStream(proc.inputStream)
        val tensors: MicroTensors = JSONObject(generateSequence { inp.readByte() }.takeWhile { it != '\n'.code.toByte() }.toList().toByteArray().decodeToString()).let {
            MicroTensors(it.getInt("stride"), it.getDouble("in_scale").toFloat(), it.getInt("in_zero"), it.getDouble("out_scale").toFloat(), it.getInt("out_zero"))
        }

        fun invoke(input: ByteArray): Int {
            out.write(input)
            out.flush()
            return inp.readUnsignedByte()
        }

        override fun close() {
            out.close()
            proc.waitFor()
        }
    }

    @Test
    fun eachModelFiresInItsOwnWordOnly() {
        assumeTrue("set MWW_PYTHON to a Python with ai-edge-litert", !python.isNullOrEmpty())
        ModelFetcher.micro().ensure(dir)
        val ref = MwwFixtures.reference
        val segments = ref.getJSONObject("segments").let { s -> s.keys().asSequence().associateWith { s.getJSONArray(it).let { a -> a.getInt(0) until a.getInt(1) + 8000 } } }
        val clip = MwwFixtures.clip
        for (model in WakeWords.MICRO) {
            PythonModel(python!!, File(dir, "${model.id}.tflite")).use { py ->
                val det = MicroDetector(py.tensors, model.window, py::invoke)
                val best = HashMap<String, Float>()
                val fired = HashSet<String>()
                var t = 0
                while (t + 160 <= clip.size) {
                    val score = det.accept(clip.copyOfRange(t, t + 160))
                    t += 160
                    val seg = segments.entries.firstOrNull { t in it.value }?.key ?: "gap"
                    best[seg] = maxOf(best[seg] ?: 0f, score)
                    if (score >= model.cutoff) fired += seg
                }
                assertEquals("${model.id} fired in", setOf(model.id), fired)
                val expected = ref.getJSONObject("max_probability").getJSONObject(model.id)
                for (seg in segments.keys) {
                    assertEquals("${model.id} best score in '$seg'", expected.getDouble(seg), (best[seg] ?: 0f).toDouble(), 0.01)
                }
                assertTrue(best.getValue(model.id) >= model.cutoff)
            }
        }
    }
}
