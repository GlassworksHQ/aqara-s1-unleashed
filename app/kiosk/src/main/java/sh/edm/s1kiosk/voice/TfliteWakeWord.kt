package sh.edm.s1kiosk.voice

import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Thrown when model files are missing or unreadable. */
class ModelsMissingException(message: String) : Exception(message)

/**
 * openWakeWord with TFLite: melspectrogram.tflite -> embedding_model.tflite -> wake model. The legacy
 * engine, kept for one release behind [WakeWord.Legacy] (#175). Takes 1280-sample (80 ms) frames.
 * Not thread-safe: use from the microphone thread only.
 */
class TfliteWakeWord private constructor(
    /** Model id, e.g. `hey_jarvis_v0.1` (file name without `.tflite`). */
    val name: String,
    private val melModel: Interpreter,
    private val embModel: Interpreter,
    private val wakeModel: Interpreter,
) : WakeDetector {

    companion object {
        const val MELSPEC_FILE = "melspectrogram.tflite"
        const val EMBEDDING_FILE = "embedding_model.tflite"

        /** Human phrase for a model file, e.g. `hey_jarvis_v0.1.tflite` -> `hey jarvis`. */
        fun phraseFor(modelFile: String): String =
            modelFile.removeSuffix(".tflite").replace(Regex("_v\\d+(\\.\\d+)*$"), "").replace('_', ' ').trim()

        fun modelName(modelFile: String): String = modelFile.removeSuffix(".tflite")

        @Throws(ModelsMissingException::class)
        fun load(dir: File, wakeModelFile: String): TfliteWakeWord {
            val files = listOf(MELSPEC_FILE, EMBEDDING_FILE, wakeModelFile).map { File(dir, it) }
            val missing = files.filter { !it.isFile || it.length() < 8 }
            if (missing.isNotEmpty()) {
                throw ModelsMissingException(
                    "Wake word models missing in $dir: ${missing.joinToString { it.name }} " +
                        "(run tools/voice/fetch-models.sh)"
                )
            }
            val opts = { Interpreter.Options().setNumThreads(1) }
            val loaded = ArrayList<Interpreter>()
            try {
                // The Interpreter constructor allocates at the stored input shape; the mel model
                // stores [1, 1], which can't be prepared. Store a real frame size instead.
                val mel = readDirect(files[0])
                val old = TfliteShapePatch.patchInputShape(mel, intArrayOf(1, OwwFeatures.FRAME + OwwFeatures.MEL_CONTEXT))
                VoiceLog.d("melspectrogram input shape ${old.contentToString()} -> [1, ${OwwFeatures.FRAME + OwwFeatures.MEL_CONTEXT}]")
                loaded += Interpreter(mel, opts())
                loaded += Interpreter(map(files[1]), opts())
                loaded += Interpreter(map(files[2]), opts())
            } catch (e: Throwable) {
                loaded.forEach { it.close() }
                throw ModelsMissingException("Failed to load TFLite models from $dir: ${e.message}")
            }
            return TfliteWakeWord(modelName(wakeModelFile), loaded[0], loaded[1], loaded[2])
        }

        /** Whole file in a writable direct buffer (needed to patch it). */
        private fun readDirect(f: File): ByteBuffer {
            val bytes = f.readBytes()
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }

        private fun map(f: File): MappedByteBuffer =
            FileInputStream(f).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    // --- melspectrogram: input [1, n] float32 (dynamic n), output [1, 1, frames, 32]
    private var melInputLen = -1
    private var melIn: ByteBuffer = direct(4)
    private var melOut: ByteBuffer = direct(4)
    private var melFrames = 0
    private var melBins = OwwFeatures.MEL_BINS

    private fun runMel(samples: FloatArray): Array<FloatArray> {
        if (samples.size != melInputLen) {
            // Same as openWakeWord: resize_tensor_input(0, [1, n], strict=True); allocate_tensors()
            melModel.resizeInput(0, intArrayOf(1, samples.size), true)
            melModel.allocateTensors()
            melInputLen = samples.size
            val shape = melModel.getOutputTensor(0).shape()
            melBins = shape.last()
            melFrames = shape.fold(1) { a, b -> a * b } / melBins
            melIn = direct(samples.size * 4)
            melOut = direct(melFrames * melBins * 4)
        }
        melIn.clear()
        melIn.asFloatBuffer().put(samples)
        melOut.clear()
        melModel.run(melIn, melOut)
        melOut.rewind()
        val fb = melOut.asFloatBuffer()
        return Array(melFrames) { FloatArray(melBins).also { row -> fb.get(row) } }
    }

    // --- embedding: input [1, 76, 32, 1], output [1, 1, 1, 96]
    private val embIn = direct(OwwFeatures.MEL_WINDOW * OwwFeatures.MEL_BINS * 4)
    private val embOut = direct(OwwFeatures.EMBEDDING_DIM * 4)

    private fun runEmbedding(window: Array<FloatArray>): FloatArray {
        embIn.clear()
        val fb = embIn.asFloatBuffer()
        for (row in window) fb.put(row)
        embOut.clear()
        embModel.run(embIn, embOut)
        embOut.rewind()
        return FloatArray(OwwFeatures.EMBEDDING_DIM).also { embOut.asFloatBuffer().get(it) }
    }

    // --- wake model: input [1, N, 96] (N = 16 for the pre-trained models), output [1, 1]
    private val wakeFrames = wakeModel.getInputTensor(0).shape()[1]
    private val wakeIn = direct(wakeFrames * OwwFeatures.EMBEDDING_DIM * 4)
    private val wakeOut = direct(wakeModel.getOutputTensor(0).numElements() * 4)

    private fun runWake(feats: Array<FloatArray>): Float {
        wakeIn.clear()
        val fb = wakeIn.asFloatBuffer()
        for (row in feats) fb.put(row)
        wakeOut.clear()
        wakeModel.run(wakeIn, wakeOut)
        wakeOut.rewind()
        return wakeOut.asFloatBuffer().get(0)
    }

    private val detector = OwwDetector(OwwFeatures(::runMel, ::runEmbedding), wakeFrames, ::runWake)

    /** Feeds one 1280-sample (80 ms) frame of 16 kHz audio; returns the wake score 0..1. */
    override fun process(frame: ShortArray): Float = detector.process(frame)

    override fun close() {
        melModel.close()
        embModel.close()
        wakeModel.close()
    }
}
