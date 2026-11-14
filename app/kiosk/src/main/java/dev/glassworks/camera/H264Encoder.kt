/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import dev.glassworks.camera.onvif.H264AccessUnitAssembler
import dev.glassworks.camera.onvif.H264ParameterSetCache

/**
 * The stream's H.264 encoder: the camera draws into [surface], and each access unit goes to
 * [onAccessUnit] in Annex-B with its parameter sets before every keyframe (the ported
 * [H264ParameterSetCache]), on the encoder's own thread. A keyframe every second, so a new viewer
 * waits at most that long, and [requestKeyFrame] for one now.
 */
class H264Encoder(
    width: Int,
    height: Int,
    fps: Int = FPS,
    bitrate: Int = bitrateFor(width, height),
    private val onAccessUnit: (annexB: ByteArray, key: Boolean) -> Unit,
    /** The encoder stopped by itself (a codec error), not through [release]. On the encoder's thread. */
    private val onFailure: (StreamEncoder) -> Unit = {},
) : StreamEncoder {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    override val surface: Surface
    @Volatile private var running = true
    private val drain: Thread

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        var made: Surface? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            made = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            made?.release()
            codec.release()
            throw e
        }
        surface = made
        drain = Thread(::drainLoop, "glassworks-h264").apply { isDaemon = true; start() }
    }

    override fun requestKeyFrame() {
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    /** Stops the encoder and frees it. Take [surface] out of the camera session first. */
    override fun release() {
        running = false
        if (Thread.currentThread() !== drain) drain.join(1_000) // from onFailure, it is this thread
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        surface.release()
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        val cache = H264ParameterSetCache()
        val assembler = H264AccessUnitAssembler()
        while (running) {
            val i = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                if (running) {
                    Log.w(TAG, "H.264 encoder stopped: ${e.javaClass.simpleName}")
                    running = false
                    onFailure(this)
                }
                break
            }
            if (i < 0) continue
            try {
                val buf = codec.getOutputBuffer(i) ?: continue
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset); buf.get(bytes)
                    val whole = assembler.append(bytes, info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0)
                    val au = whole?.let { cache.prepare(it, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) }
                    if (au != null) onAccessUnit(au.bytes, au.isKeyframe)
                }
            } catch (e: Exception) {
                Log.w(TAG, "H.264 output dropped: ${e.javaClass.simpleName}")
            } finally {
                try { codec.releaseOutputBuffer(i, false) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val TAG = "S1Kiosk.Camera"
        const val FPS = 15

        /** About 0.1 bit a pixel at [FPS]: 1.4 Mbit/s at 1280x720. */
        fun bitrateFor(width: Int, height: Int): Int = (width * height * FPS / 10).coerceIn(500_000, 6_000_000)
    }
}
