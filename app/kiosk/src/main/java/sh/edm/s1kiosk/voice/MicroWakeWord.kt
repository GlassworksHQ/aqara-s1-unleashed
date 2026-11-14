package sh.edm.s1kiosk.voice

import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** A loaded wake word model. Not thread-safe: the microphone thread feeds it. */
interface WakeDetector : Closeable {
    /** Feeds one microphone frame of 16 kHz mono audio; returns the wake score, 0..1. */
    fun process(frame: ShortArray): Float
}

/**
 * The input and output quantization of a microWakeWord streaming model: int8 input of [stride]
 * feature rows of 40, uint8 output of one probability.
 */
data class MicroTensors(
    val stride: Int,
    val inputScale: Float,
    val inputZeroPoint: Int,
    val outputScale: Float,
    val outputZeroPoint: Int,
)

/**
 * microWakeWord's streaming detection, as pymicro-wakeword and ESPHome run it: the audio goes
 * through [MicroFrontend] in 10 ms steps, every [MicroTensors.stride] feature rows are quantized and
 * fed to the model ([invoke]: int8 input -> raw uint8 output), and the score is the mean of the last
 * `window` probabilities (0 until the window is full).
 *
 * Features are quantized like pymicro-wakeword, `round(value * 0.0390625 / scale + zero_point)`
 * (half to even), and clamped to int8 like ESPHome. The model is injected so a JVM test can drive it.
 */
class MicroDetector(
    private val tensors: MicroTensors,
    window: Int,
    private val invoke: (ByteArray) -> Int,
) {
    private val frontend = MicroFrontend()
    private val pending = ShortArray(MicroFrontend.STEP)
    private var pendingCount = 0
    private val input = ByteArray(tensors.stride * MicroFrontend.CHANNELS)
    private var rows = 0
    private val probabilities = FloatArray(window)
    private var seen = 0

    /** Feeds any number of samples; returns the highest score reached among them, 0 if none. */
    fun accept(samples: ShortArray): Float {
        var best = 0f
        var i = 0
        while (i < samples.size) {
            val n = minOf(MicroFrontend.STEP - pendingCount, samples.size - i)
            System.arraycopy(samples, i, pending, pendingCount, n)
            pendingCount += n
            i += n
            if (pendingCount < MicroFrontend.STEP) break
            pendingCount = 0
            val score = step(frontend.accept(pending) ?: continue)
            if (score > best) best = score
        }
        return best
    }

    private fun step(features: IntArray): Float {
        val base = rows * MicroFrontend.CHANNELS
        for (c in 0 until MicroFrontend.CHANNELS) input[base + c] = quantize(features[c])
        if (++rows < tensors.stride) return 0f
        rows = 0
        val raw = invoke(input)
        probabilities[seen % probabilities.size] = (raw - tensors.outputZeroPoint) * tensors.outputScale
        seen++
        if (seen < probabilities.size) return 0f
        return probabilities.sum() / probabilities.size
    }

    private fun quantize(value: Int): Byte {
        val q = Math.rint(value * 0.0390625 / tensors.inputScale.toDouble() + tensors.inputZeroPoint)
        return q.coerceIn(-128.0, 127.0).toInt().toByte()
    }
}

/** microWakeWord on TFLite: one streaming model, fed by [MicroDetector]. */
class MicroWakeWord private constructor(
    private val interpreter: Interpreter,
    model: MicroModel,
) : WakeDetector {

    companion object {
        @Throws(ModelsMissingException::class)
        fun load(file: File, model: MicroModel): MicroWakeWord {
            if (!file.isFile) throw ModelsMissingException("Wake word model missing: $file")
            val interpreter = try {
                val buf = FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
                Interpreter(buf, Interpreter.Options().setNumThreads(1))
            } catch (e: Throwable) {
                throw ModelsMissingException("Failed to load $file: ${e.message}")
            }
            return try {
                MicroWakeWord(interpreter, model)
            } catch (e: Throwable) {
                interpreter.close()
                throw ModelsMissingException("Unexpected microWakeWord model $file: ${e.message}")
            }
        }
    }

    private val inTensor = interpreter.getInputTensor(0)
    private val outTensor = interpreter.getOutputTensor(0)
    private val tensors = MicroTensors(
        stride = inTensor.shape()[1],
        inputScale = inTensor.quantizationParams().scale,
        inputZeroPoint = inTensor.quantizationParams().zeroPoint,
        outputScale = outTensor.quantizationParams().scale,
        outputZeroPoint = outTensor.quantizationParams().zeroPoint,
    )

    init {
        require(inTensor.numElements() == tensors.stride * MicroFrontend.CHANNELS) { "input ${inTensor.shape().contentToString()}" }
        require(outTensor.numElements() == 1) { "output ${outTensor.shape().contentToString()}" }
    }

    private val inBuf = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())

    private val detector = MicroDetector(tensors, model.window) { input ->
        inBuf.clear()
        inBuf.put(input)
        inBuf.rewind()
        outBuf.clear()
        interpreter.run(inBuf, outBuf)
        outBuf.get(0).toInt() and 0xFF
    }

    override fun process(frame: ShortArray): Float = detector.accept(frame)

    override fun close() = interpreter.close()
}
