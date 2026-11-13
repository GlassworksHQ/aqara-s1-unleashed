/* SPDX-License-Identifier: Apache-2.0 AND BSD-3-Clause
 *
 * A Kotlin port of TensorFlow Lite Micro's audio microfrontend
 * (tensorflow/lite/experimental/microfrontend/lib: window, fft, filterbank, noise_reduction,
 * pcan_gain_control, log_scale, log_lut; Copyright 2018 The TensorFlow Authors, Apache-2.0) and of
 * the 16-bit fixed-point real FFT it uses (kissfft kiss_fft.c and kiss_fftr.c, Copyright 2003-2010
 * Mark Borgerding, BSD-3-Clause). Ported from the copies in rhasspy/pymicro-features v2.0.2
 * (96bd69c). Changes: rewritten in Kotlin for one fixed configuration (microWakeWord's), with C's
 * unsigned and 16-bit wrapping arithmetic spelled out. The texts are in THIRD-PARTY-NOTICES.md and
 * LICENSES/. */

package sh.edm.s1kiosk.voice

/**
 * microWakeWord's audio features, bit for bit what the TFLite Micro frontend gives with ESPHome's
 * settings: 16 kHz audio, 30 ms Hann windows every 10 ms, a 512-point fixed-point FFT, 40 mel
 * channels from 125 to 7500 Hz, noise reduction, PCAN gain control and a log scale. Each [accept] of
 * 160 samples (10 ms) returns 40 uint16 values once the first full window is in (from the third call
 * on), else null. `MicroFrontendTest` compares it with pymicro-features' output.
 *
 * Tables are computed with [StrictMath], so the panel and the JVM tests get the same ones. Not
 * thread-safe.
 */
class MicroFrontend {
    companion object {
        const val STEP = 160
        const val CHANNELS = 40
        private const val WINDOW = 480
        private const val FFT = 512
        private const val HALF = FFT / 2 // the complex FFT kissfft's real FFT runs
        private const val BINS = HALF + 1

        private const val WINDOW_BITS = 12
        private const val FILTERBANK_BITS = 12
        private const val NOISE_BITS = 14
        private const val SMOOTHING_BITS = 10
        private const val PCAN_SNR_BITS = 12
        private const val PCAN_OUTPUT_BITS = 6
        private const val PCAN_GAIN_BITS = 21
        private const val LOG_SCALE_SHIFT = 6
        private const val LOG_SCALE_LOG2 = 16
        private const val LOG_SEGMENTS_LOG2 = 7
        private const val LOG_COEFF = 45426L
        private const val U32 = 0xFFFFFFFFL

        /** `MostSignificantBit32(log2(512)) - 1 - kFilterbankBits / 2`. */
        private val CORRECTION_BITS = msb32(FFT.toLong()) - 1 - FILTERBANK_BITS / 2

        private val LOG_LUT = intArrayOf(
            0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163,
            2329, 2490, 2646, 2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848,
            3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791, 4864, 4934,
            5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507,
            5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640, 5641, 5638, 5633,
            5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
            5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762,
            4697, 4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848,
            3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659,
            2549, 2437, 2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224,
            1094, 963, 830, 695, 559, 421, 282, 142, 0, 0,
        )

        /** Bits needed for an unsigned 32-bit [n] (0 for 0). */
        private fun msb32(n: Long): Int = 64 - java.lang.Long.numberOfLeadingZeros(n and U32)

        private fun freqToMel(freq: Float): Float = 1127.0f * StrictMath.log1p((freq / 700.0f).toDouble()).toFloat()
    }

    // ------------------------------------------------------------------ window (window_util.c)

    private val coefficients = ShortArray(WINDOW).also { c ->
        val arg = Math.PI.toFloat() * 2.0f / WINDOW.toFloat()
        for (i in c.indices) {
            val v = 0.5f - 0.5f * StrictMath.cos((arg * (i + 0.5f)).toDouble()).toFloat()
            c[i] = kotlin.math.floor(v * (1 shl WINDOW_BITS) + 0.5f).toInt().toShort()
        }
    }
    private val input = ShortArray(WINDOW)
    private var inputUsed = 0
    private val windowed = ShortArray(WINDOW)

    // ------------------------------------------------------------------ FFT (kiss_fft.c, kiss_fftr.c)

    private val twR = ShortArray(HALF)
    private val twI = ShortArray(HALF)
    private val superR = ShortArray(HALF / 2)
    private val superI = ShortArray(HALF / 2)

    init {
        for (i in 0 until HALF) {
            val phase = -2 * Math.PI * i / HALF
            twR[i] = kotlin.math.floor(0.5 + 32767 * StrictMath.cos(phase)).toInt().toShort()
            twI[i] = kotlin.math.floor(0.5 + 32767 * StrictMath.sin(phase)).toInt().toShort()
        }
        for (i in 0 until HALF / 2) {
            val phase = -Math.PI * ((i + 1).toDouble() / HALF + .5)
            superR[i] = kotlin.math.floor(0.5 + 32767 * StrictMath.cos(phase)).toInt().toShort()
            superI[i] = kotlin.math.floor(0.5 + 32767 * StrictMath.sin(phase)).toInt().toShort()
        }
        require(HALF == 256) { "the FFT below factors as 4*4*4*4" }
    }

    private val fftIn = ShortArray(FFT) // real samples, read as HALF complex values
    private val tmpR = ShortArray(HALF)
    private val tmpI = ShortArray(HALF)
    private val outR = ShortArray(BINS)
    private val outI = ShortArray(BINS)

    /** `sround(smul(a, b))` for Q15. */
    private fun sround(x: Int): Short = ((x + (1 shl 14)) shr 15).toShort()

    private fun fixdiv(x: Short, k: Int): Short = sround(x * (32767 / k))

    /** kf_work for factors 4,64,4,16,4,4,4,1 (in_stride 1): input is [fftIn] read as complex values. */
    private fun work(out: Int, f: Int, fstride: Int, m: Int) {
        if (m == 1) {
            for (j in 0 until 4) {
                val src = f + j * fstride
                tmpR[out + j] = fftIn[2 * src]
                tmpI[out + j] = fftIn[2 * src + 1]
            }
        } else {
            for (j in 0 until 4) work(out + j * m, f + j * fstride, fstride * 4, m / 4)
        }
        bfly4(out, fstride, m)
    }

    private fun bfly4(base: Int, fstride: Int, m: Int) {
        var tw1 = 0
        var tw2 = 0
        var tw3 = 0
        for (k in 0 until m) {
            val i0 = base + k
            val i1 = i0 + m
            val i2 = i0 + 2 * m
            val i3 = i0 + 3 * m
            val f0r = fixdiv(tmpR[i0], 4).toInt(); val f0i = fixdiv(tmpI[i0], 4).toInt()
            val f1r = fixdiv(tmpR[i1], 4).toInt(); val f1i = fixdiv(tmpI[i1], 4).toInt()
            val f2r = fixdiv(tmpR[i2], 4).toInt(); val f2i = fixdiv(tmpI[i2], 4).toInt()
            val f3r = fixdiv(tmpR[i3], 4).toInt(); val f3i = fixdiv(tmpI[i3], 4).toInt()

            val s0r = sround(f1r * twR[tw1] - f1i * twI[tw1]).toInt()
            val s0i = sround(f1r * twI[tw1] + f1i * twR[tw1]).toInt()
            val s1r = sround(f2r * twR[tw2] - f2i * twI[tw2]).toInt()
            val s1i = sround(f2r * twI[tw2] + f2i * twR[tw2]).toInt()
            val s2r = sround(f3r * twR[tw3] - f3i * twI[tw3]).toInt()
            val s2i = sround(f3r * twI[tw3] + f3i * twR[tw3]).toInt()

            val s5r = (f0r - s1r).toShort().toInt(); val s5i = (f0i - s1i).toShort().toInt()
            var a0r = (f0r + s1r).toShort().toInt(); var a0i = (f0i + s1i).toShort().toInt()
            val s3r = (s0r + s2r).toShort().toInt(); val s3i = (s0i + s2i).toShort().toInt()
            val s4r = (s0r - s2r).toShort().toInt(); val s4i = (s0i - s2i).toShort().toInt()
            tmpR[i2] = (a0r - s3r).toShort(); tmpI[i2] = (a0i - s3i).toShort()
            tw1 += fstride
            tw2 += fstride * 2
            tw3 += fstride * 3
            a0r = (a0r + s3r).toShort().toInt(); a0i = (a0i + s3i).toShort().toInt()
            tmpR[i0] = a0r.toShort(); tmpI[i0] = a0i.toShort()
            tmpR[i1] = (s5r + s4i).toShort(); tmpI[i1] = (s5i - s4r).toShort()
            tmpR[i3] = (s5r - s4i).toShort(); tmpI[i3] = (s5i + s4r).toShort()
        }
    }

    /** kiss_fftr: [fftIn] (FFT real samples) -> [outR]/[outI] (BINS complex values). */
    private fun realFft() {
        work(0, 0, 1, HALF / 4)
        val dcR = fixdiv(tmpR[0], 2).toInt()
        val dcI = fixdiv(tmpI[0], 2).toInt()
        outR[0] = (dcR + dcI).toShort(); outI[0] = 0
        outR[HALF] = (dcR - dcI).toShort(); outI[HALF] = 0
        for (k in 1..HALF / 2) {
            val pkR = fixdiv(tmpR[k], 2).toInt()
            val pkI = fixdiv(tmpI[k], 2).toInt()
            val pnkR = fixdiv(tmpR[HALF - k], 2).toInt()
            val pnkI = fixdiv((-tmpI[HALF - k]).toShort(), 2).toInt()
            val f1r = (pkR + pnkR).toShort().toInt(); val f1i = (pkI + pnkI).toShort().toInt()
            val f2r = (pkR - pnkR).toShort().toInt(); val f2i = (pkI - pnkI).toShort().toInt()
            val sr = superR[k - 1].toInt(); val si = superI[k - 1].toInt()
            val twr = sround(f2r * sr - f2i * si).toInt()
            val twi = sround(f2r * si + f2i * sr).toInt()
            outR[k] = ((f1r + twr) shr 1).toShort(); outI[k] = ((f1i + twi) shr 1).toShort()
            outR[HALF - k] = ((f1r - twr) shr 1).toShort(); outI[HALF - k] = ((twi - f1i) shr 1).toShort()
        }
    }

    // ------------------------------------------------------------------ filterbank (filterbank_util.c)

    private val chanFreqStart = IntArray(CHANNELS + 1)
    private val chanWeightStart = IntArray(CHANNELS + 1)
    private val chanWidth = IntArray(CHANNELS + 1)
    private val weights: IntArray
    private val unweights: IntArray
    private val startIndex: Int
    private val endIndex: Int

    init {
        val n1 = CHANNELS + 1
        val lower = 125.0f
        val upper = 7500.0f
        val melLow = freqToMel(lower)
        val melSpacing = (freqToMel(upper) - melLow) / n1.toFloat()
        val center = FloatArray(n1) { melLow + melSpacing * (it + 1) }
        val hzPerBin = 0.5f * 16000 / (BINS.toFloat() - 1)
        startIndex = (1.5f + lower / hzPerBin).toInt()
        val actualStart = IntArray(n1)
        val actualWidth = IntArray(n1)
        var chanStart = startIndex
        var weightStart = 0
        var needsZeros = false
        for (chan in 0 until n1) {
            var fi = chanStart
            while (freqToMel(fi * hzPerBin) <= center[chan]) fi++
            val width = fi - chanStart
            actualStart[chan] = chanStart
            actualWidth[chan] = width
            if (width == 0) {
                chanFreqStart[chan] = 0
                chanWeightStart[chan] = 0
                chanWidth[chan] = 4
                if (!needsZeros) {
                    needsZeros = true
                    for (j in 0 until chan) chanWeightStart[j] += 4
                    weightStart += 4
                }
            } else {
                val aligned = (chanStart / 2) * 2
                val alignedWidth = chanStart - aligned + width
                val padded = ((alignedWidth - 1) / 4 + 1) * 4
                chanFreqStart[chan] = aligned
                chanWeightStart[chan] = weightStart
                chanWidth[chan] = padded
                weightStart += padded
            }
            chanStart = fi
        }
        weights = IntArray(weightStart)
        unweights = IntArray(weightStart)
        var end = 0
        for (chan in 0 until n1) {
            var frequency = actualStart[chan]
            val offset = frequency - chanFreqStart[chan]
            val denom = if (chan == 0) melLow else center[chan - 1]
            for (j in 0 until actualWidth[chan]) {
                val w = (center[chan] - freqToMel(frequency * hzPerBin)) / (center[chan] - denom)
                val idx = chanWeightStart[chan] + offset + j
                weights[idx] = kotlin.math.floor(w * (1 shl FILTERBANK_BITS) + 0.5f).toInt().toShort().toInt()
                unweights[idx] = kotlin.math.floor((1.0f - w) * (1 shl FILTERBANK_BITS) + 0.5f).toInt().toShort().toInt()
                frequency++
            }
            if (frequency > end) end = frequency
        }
        endIndex = end
        check(endIndex < BINS) { "filterbank end index $endIndex above the spectrum" }
    }

    private val energy = IntArray(BINS)
    private val work = LongArray(CHANNELS + 1) // uint64 accumulators
    private val signal = LongArray(CHANNELS) // uint32 values

    private fun filterbank(inputShift: Int) {
        for (i in startIndex until endIndex) {
            val r = outR[i].toInt()
            val im = outI[i].toInt()
            energy[i] = r * r + im * im
        }
        var weightAcc = 0L
        var unweightAcc = 0L
        for (chan in 0..CHANNELS) {
            val mag = chanFreqStart[chan]
            val ws = chanWeightStart[chan]
            for (j in 0 until chanWidth[chan]) {
                val e = energy[mag + j].toLong() // int32 -> uint64 sign-extends, as in C
                weightAcc += weights[ws + j] * e
                unweightAcc += unweights[ws + j] * e
            }
            work[chan] = weightAcc
            weightAcc = unweightAcc
            unweightAcc = 0
        }
        for (i in 0 until CHANNELS) signal[i] = (sqrt64(work[i + 1]) shr inputShift) and U32
    }

    private fun sqrt32(n: Long): Long {
        var num = n and U32
        if (num == 0L) return 0
        var res = 0L
        var maxBit = 32 - msb32(num)
        maxBit = maxBit or 1
        var bit = 1L shl (31 - maxBit)
        var iterations = (31 - maxBit) / 2 + 1
        while (iterations-- > 0) {
            if (num >= res + bit) {
                num -= res + bit
                res = (res shr 1) + bit
            } else {
                res = res shr 1
            }
            bit = bit shr 2
        }
        if (num > res && res != 0xFFFFL) res++
        return res and 0xFFFF
    }

    private fun sqrt64(n: Long): Long {
        if (n ushr 32 == 0L) return sqrt32(n)
        var num = n.toULong()
        var res = 0UL
        var maxBit = java.lang.Long.numberOfLeadingZeros(n) // 64 - MostSignificantBit64
        maxBit = maxBit or 1
        var bit = 1UL shl (63 - maxBit)
        var iterations = (63 - maxBit) / 2 + 1
        while (iterations-- > 0) {
            if (num >= res + bit) {
                num -= res + bit
                res = (res shr 1) + bit
            } else {
                res = res shr 1
            }
            bit = bit shr 2
        }
        if (num > res && res != 0xFFFFFFFFUL) res++
        return res.toLong() and U32
    }

    // ------------------------------------------------------------------ noise reduction (noise_reduction.c)

    private val evenSmoothing = (0.025f * (1 shl NOISE_BITS)).toLong()
    private val oddSmoothing = (0.06f * (1 shl NOISE_BITS)).toLong()
    private val minSignalRemaining = (0.05f * (1 shl NOISE_BITS)).toLong()
    private val estimate = LongArray(CHANNELS)

    private fun noiseReduction() {
        for (i in 0 until CHANNELS) {
            val smoothing = if (i and 1 == 0) evenSmoothing else oddSmoothing
            val oneMinus = (1L shl NOISE_BITS) - smoothing
            val scaledUp = (signal[i] shl SMOOTHING_BITS) and U32
            var est = ((scaledUp * smoothing + estimate[i] * oneMinus) ushr NOISE_BITS) and U32
            estimate[i] = est
            if (est > scaledUp) est = scaledUp
            val floor = ((signal[i] * minSignalRemaining) ushr NOISE_BITS) and U32
            val subtracted = (scaledUp - est) ushr SMOOTHING_BITS
            signal[i] = if (subtracted > floor) subtracted else floor
        }
    }

    // ------------------------------------------------------------------ PCAN (pcan_gain_control*.c)

    private val snrShift = PCAN_GAIN_BITS - CORRECTION_BITS - PCAN_SNR_BITS

    /** `gain_lut` after its `+= 6`: [0], [1], then 3 entries per interval at `4 * interval - 6`. */
    private val gainLut = IntArray(4 * 32 - 3).also { lut ->
        val inputBits = SMOOTHING_BITS - CORRECTION_BITS
        fun lookup(x: Long): Int {
            val xf = (x and U32).toFloat() / (1L shl inputBits).toFloat()
            val gain = (1L shl PCAN_GAIN_BITS).toFloat() * StrictMath.pow((xf + 80.0f).toDouble(), -0.95f.toDouble()).toFloat()
            return if (gain > 0x7FFF) 0x7FFF else (gain + 0.5f).toInt().toShort().toInt()
        }
        lut[0] = lookup(0)
        lut[1] = lookup(1)
        for (interval in 2..32) {
            val x0 = 1L shl (interval - 1)
            val x1 = x0 + (x0 shr 1)
            val x2 = if (interval == 32) x0 + (x0 - 1) else 2 * x0
            val y0 = lookup(x0)
            val y1 = lookup(x1)
            val y2 = lookup(x2)
            val a1 = 4 * (y1 - y0) - (y2 - y0)
            val a2 = (y2 - y0) - a1
            val at = 4 * interval - 6
            lut[at] = y0
            lut[at + 1] = a1.toShort().toInt()
            lut[at + 2] = a2.toShort().toInt()
        }
    }

    private fun wideDynamic(x: Long): Int {
        if (x <= 2) return gainLut[x.toInt()]
        val interval = msb32(x)
        val at = 4 * interval - 6
        val frac = ((if (interval < 11) (x shl (11 - interval)) else (x ushr (interval - 11))) and 0x3FF).toInt()
        var result = (gainLut[at + 2] * frac) shr 5
        result += gainLut[at + 1] shl 5
        result *= frac
        result = (result + (1 shl 14)) shr 15
        result += gainLut[at]
        return result.toShort().toInt()
    }

    private fun pcan() {
        for (i in 0 until CHANNELS) {
            val gain = wideDynamic(estimate[i]).toLong() and U32
            val snr = ((signal[i] * gain) ushr snrShift) and U32
            signal[i] = if (snr < (2L shl PCAN_SNR_BITS)) {
                (snr * snr) ushr (2 + 2 * PCAN_SNR_BITS - PCAN_OUTPUT_BITS)
            } else {
                (snr ushr (PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) - (1L shl PCAN_OUTPUT_BITS)
            } and U32
        }
    }

    // ------------------------------------------------------------------ log scale (log_scale.c)

    private fun log2FractionPart(x: Long, log2x: Int): Long {
        var frac = (x - (1L shl log2x)).toInt()
        frac = if (log2x < LOG_SCALE_LOG2) frac shl (LOG_SCALE_LOG2 - log2x) else frac shr (log2x - LOG_SCALE_LOG2)
        val baseSeg = frac ushr (LOG_SCALE_LOG2 - LOG_SEGMENTS_LOG2)
        val segUnit = (1 shl LOG_SCALE_LOG2) shr LOG_SEGMENTS_LOG2
        val c0 = LOG_LUT[baseSeg]
        val c1 = LOG_LUT[baseSeg + 1]
        val relPos = ((c1 - c0) * (frac - segUnit * baseSeg)) shr LOG_SCALE_LOG2
        return (frac + c0 + relPos).toLong() and U32
    }

    private fun log(x: Long): Long {
        val integer = msb32(x) - 1
        val log2 = ((integer.toLong() shl LOG_SCALE_LOG2) + log2FractionPart(x, integer)) and U32
        val round = 1L shl (LOG_SCALE_LOG2 - 1)
        val loge = ((LOG_COEFF * log2 + round) ushr LOG_SCALE_LOG2) and U32
        return ((((loge shl LOG_SCALE_SHIFT) + round) and U32) ushr LOG_SCALE_LOG2)
    }

    // ------------------------------------------------------------------ frontend.c

    /**
     * Feeds the next [STEP] samples of [samples] from [offset]. Returns the 40 features of the
     * window that ends with them (uint16 values), or null while the first window is still filling.
     * The returned array is reused by the next call.
     */
    fun accept(samples: ShortArray, offset: Int = 0): IntArray? {
        require(offset + STEP <= samples.size) { "need $STEP samples" }
        System.arraycopy(samples, offset, input, inputUsed, STEP)
        inputUsed += STEP
        if (inputUsed < WINDOW) return null

        var maxAbs = 0
        for (i in 0 until WINDOW) {
            val v = ((input[i] * coefficients[i]) shr WINDOW_BITS).toShort()
            windowed[i] = v
            val a = (if (v < 0) -v else v.toInt()).toShort().toInt()
            if (a > maxAbs) maxAbs = a
        }
        System.arraycopy(input, STEP, input, 0, WINDOW - STEP)
        inputUsed -= STEP

        val inputShift = 15 - msb32(maxAbs.toLong())
        for (i in 0 until WINDOW) fftIn[i] = ((windowed[i].toInt() and 0xFFFF) shl inputShift).toShort()
        java.util.Arrays.fill(fftIn, WINDOW, FFT, 0)
        realFft()
        filterbank(inputShift)
        noiseReduction()
        pcan()

        for (i in 0 until CHANNELS) {
            var value = signal[i]
            value = if (CORRECTION_BITS < 0) value ushr -CORRECTION_BITS else (value shl CORRECTION_BITS) and U32
            value = if (value > 1) log(value) else 0
            features[i] = if (value < 0xFFFF) value.toInt() else 0xFFFF
        }
        return features
    }

    private val features = IntArray(CHANNELS)
}
