package sh.edm.s1kiosk.sendspin

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [FlacFrameDecoder] on MediaCodec `audio/flac`, synchronous mode (driven by [AudioPipeline]'s
 * thread). On the panel (Android 10) this is the platform software decoder (`c2.android.flac.decoder`,
 * libFLAC). It is configured with `csd-0` = `fLaC` + STREAMINFO, which the decoder parses as codec
 * config (`C2SoftFlacDec` → `FLACDecoder::parseMetadata`), asks for 16-bit output, and gets one
 * output buffer per input frame carrying the input's `presentationTimeUs`, used here as the tag.
 */
class MediaCodecFlacDecoder : FlacFrameDecoder {
    private var codec: MediaCodec? = null
    private val bi = MediaCodec.BufferInfo()
    private var channels = 2
    override var name: String = "audio/flac"; private set

    override fun open(info: FlacStreamInfo, csd0: ByteArray) {
        val fmt = MediaFormat.createAudioFormat(MIME, info.sampleRate, info.channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, info.maxInputSize())
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val c = MediaCodec.createDecoderByType(MIME)
        try {
            c.configure(fmt, null, null, 0)
            c.start()
        } catch (t: Throwable) {
            c.release()
            throw t
        }
        channels = info.channels
        name = c.name
        codec = c
    }

    override fun queue(data: ByteArray, offset: Int, length: Int, tag: Long, timeoutUs: Long): Boolean {
        val c = codec ?: throw IllegalStateException("not open")
        val idx = c.dequeueInputBuffer(timeoutUs)
        if (idx < 0) return false
        val buf = c.getInputBuffer(idx) ?: throw IllegalStateException("no input buffer $idx")
        buf.clear()
        if (buf.remaining() < length) throw IllegalStateException("frame of $length bytes > input buffer ${buf.remaining()}")
        buf.put(data, offset, length)
        c.queueInputBuffer(idx, 0, length, tag, 0)
        return true
    }

    override fun dequeue(timeoutUs: Long): FlacFrameDecoder.Decoded? {
        val c = codec ?: throw IllegalStateException("not open")
        var wait = timeoutUs
        while (true) {
            val idx = c.dequeueOutputBuffer(bi, wait)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    if (enc != AudioFormat.ENCODING_PCM_16BIT) throw IllegalStateException("decoder output encoding $enc, not 16-bit")
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (ch != channels) throw IllegalStateException("decoder output has $ch channels, expected $channels")
                    SsLog.d("FLAC decoder output format: $f")
                    wait = 0
                }
                idx < 0 -> wait = 0 // INFO_OUTPUT_BUFFERS_CHANGED (deprecated): nothing to do
                else -> {
                    val size = bi.size
                    val pts = bi.presentationTimeUs
                    val out = if (size > 0) {
                        val b = c.getOutputBuffer(idx) ?: throw IllegalStateException("no output buffer $idx")
                        b.position(bi.offset).limit(bi.offset + size)
                        ShortArray(size / 2).also { b.order(ByteOrder.nativeOrder()).asShortBuffer().get(it) }
                    } else null
                    c.releaseOutputBuffer(idx, false)
                    if (out == null) { wait = 0; continue }
                    return FlacFrameDecoder.Decoded(pts, out, out.size / channels)
                }
            }
        }
    }

    override fun close() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Throwable) {}
        c.release()
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_FLAC

        /** The name of a decoder for 48 kHz stereo FLAC, or null if the device has none. */
        fun available(sampleRate: Int = 48_000, channels: Int = 2): String? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(MediaFormat.createAudioFormat(MIME, sampleRate, channels))
        } catch (_: Throwable) { null }
    }
}
