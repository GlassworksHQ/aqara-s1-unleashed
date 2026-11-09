package io.github.aguilaair.s1kiosk.voice

import kotlin.random.Random

/**
 * Streaming feature extraction, a line-by-line port of openWakeWord's `AudioFeatures`
 * (`openwakeword/utils.py`, v0.6 / main) for the case where audio arrives in exact
 * 1280-sample (80 ms) frames:
 *
 * - raw audio buffer; each step feeds the last `1280 + 160*3` samples (fewer on the very first
 *   frame, exactly like `list(raw_data_buffer)[-n_samples-160*3:]`) to the melspectrogram model,
 *   as float32 values of the int16 samples (no normalisation);
 * - mel output squeezed to `frames x 32` and transformed with `x/10 + 2`;
 * - melspectrogram buffer starts as `ones(76, 32)`, capped at `10*97` frames;
 * - one embedding per step from the last 76 mel frames (`[1, 76, 32, 1]` -> 96 floats);
 * - feature buffer starts with the embeddings of 4 s of uniform random int16 noise in
 *   [-1000, 1000) (76-frame windows stepped by 8), capped at 120 rows;
 * - the wake model sees the last N feature rows (`N` = its input dim 1, 16 for the
 *   pre-trained models).
 *
 * The neural networks are injected so this class can be unit-tested on the JVM.
 *
 * @param melspec raw float32 samples -> mel frames (`frames x 32`), before the transform.
 * @param embed   one `76 x 32` mel window -> 96-dim embedding.
 * @param noise   the 4 s of start-up noise (tests inject a fixed one); random by default.
 */
class OwwFeatures(
    private val melspec: (FloatArray) -> Array<FloatArray>,
    private val embed: (Array<FloatArray>) -> FloatArray,
    noise: FloatArray? = null,
) {
    companion object {
        const val FRAME = 1280
        const val MEL_CONTEXT = 160 * 3
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val MEL_STEP = 8
        const val MEL_MAX = 10 * 97
        const val EMBEDDING_DIM = 96
        const val FEATURE_MAX = 120
        const val NOISE_SAMPLES = 16_000 * 4

        fun melTransform(x: Float): Float = x / 10f + 2f
    }

    private val raw = FloatArray(FRAME + MEL_CONTEXT)
    private var rawCount = 0
    private val mel = ArrayDeque<FloatArray>(MEL_MAX + 16)
    private val features = ArrayDeque<FloatArray>(FEATURE_MAX + 4)

    init {
        repeat(MEL_WINDOW) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }

        // feature_buffer = _get_embeddings(np.random.randint(-1000, 1000, 16000*4))
        val seed = noise ?: FloatArray(NOISE_SAMPLES) { Random.nextInt(-1000, 1000).toFloat() }
        val spec = melspec(seed).map { row -> FloatArray(row.size) { melTransform(row[it]) } }
        var i = 0
        while (i < spec.size) {
            if (i + MEL_WINDOW <= spec.size) features.addLast(embed(Array(MEL_WINDOW) { spec[i + it] }))
            i += MEL_STEP
        }
        while (features.size > FEATURE_MAX) features.removeFirst()
    }

    /** Number of rows currently in the feature buffer. */
    val featureCount: Int get() = features.size

    /** Number of rows currently in the melspectrogram buffer. */
    val melCount: Int get() = mel.size

    /** Consumes exactly one 80 ms frame of 16 kHz int16 audio and appends one embedding. */
    fun accept(frame: ShortArray) {
        require(frame.size == FRAME) { "frame must be $FRAME samples, got ${frame.size}" }

        // raw_data_buffer: keep only what the mel step needs (the last 1280+480 samples).
        System.arraycopy(raw, FRAME, raw, 0, raw.size - FRAME)
        for (k in 0 until FRAME) raw[raw.size - FRAME + k] = frame[k].toFloat()
        rawCount = minOf(rawCount + FRAME, raw.size)

        // _streaming_melspectrogram(1280)
        val input = raw.copyOfRange(raw.size - rawCount, raw.size)
        for (row in melspec(input)) {
            mel.addLast(FloatArray(MEL_BINS) { melTransform(row[it]) })
        }
        while (mel.size > MEL_MAX) mel.removeFirst()

        // One embedding from the last 76 mel frames.
        val start = mel.size - MEL_WINDOW
        features.addLast(embed(Array(MEL_WINDOW) { mel[start + it] }))
        while (features.size > FEATURE_MAX) features.removeFirst()
    }

    /** The last [n] feature rows, oldest first (`get_features(n)`). */
    fun lastFeatures(n: Int): Array<FloatArray> {
        val start = features.size - n
        require(start >= 0) { "only ${features.size} feature rows" }
        return Array(n) { features[start + it] }
    }
}

/**
 * openWakeWord `Model.predict` for a single binary wake model fed 1280-sample frames:
 * scores are forced to 0 for the first 5 predictions while the buffers fill.
 */
class OwwDetector(
    private val features: OwwFeatures,
    private val wakeInputFrames: Int,
    private val wake: (Array<FloatArray>) -> Float,
) {
    private var predictions = 0L

    /** Feeds one 1280-sample frame; returns the wake word score in 0..1. */
    fun process(frame: ShortArray): Float {
        features.accept(frame)
        val score = wake(features.lastFeatures(wakeInputFrames))
        return if (predictions++ < 5) 0f else score
    }
}
