/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.view.Surface

/** An H.264 encoder as the stream drives it: [H264Encoder] on a device. */
interface StreamEncoder {
    /** Where the camera draws for it. */
    val surface: Surface?
    fun requestKeyFrame()
    fun release()
}

/** Makes an encoder. [onFailure] gets the encoder that failed, so a late report from a replaced one is told apart. */
fun interface EncoderFactory {
    fun create(onAccessUnit: (annexB: ByteArray, key: Boolean) -> Unit, onFailure: (StreamEncoder) -> Unit): StreamEncoder
}

/** Where the encoder's input comes from: [CameraHub]. Detaching (null) returns once the camera no longer draws into the old one. */
fun interface EncoderInput {
    fun setEncoder(encoder: StreamEncoder?)
}

/**
 * The stream's encoder for one RTSP server's life: started when someone watches, stopped when nobody
 * does, replaced when it fails while someone still watches, at most once every [RESTART_MS] (a later
 * attempt is posted, not dropped). Each running encoder holds the privacy indicator on through
 * [egress]. After [close], nothing more happens, so a stopped server's late reports change nothing.
 *
 * [watched] says whether the server still has viewers; [after] runs a callback later (a Handler).
 */
internal class ViewerEncoder(
    private val input: EncoderInput,
    private val encoders: EncoderFactory,
    private val egress: FrameEgress,
    private val onAccessUnit: (ByteArray, Boolean) -> Unit,
    private val watched: () -> Boolean,
    private val clock: () -> Long,
    private val after: (delayMs: Long, Runnable) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var current: StreamEncoder? = null
    private var viewing: AutoCloseable? = null
    private var closed = false
    private var lastRestartMs = Long.MIN_VALUE / 2

    /** RTSP's count of viewers, waiting ones included. */
    fun viewers(count: Int) {
        synchronized(lock) {
            if (closed) return
            when {
                count > 0 && current == null -> start()
                count == 0 -> stop()
                else -> current?.requestKeyFrame()
            }
        }
    }

    /** The server stopped: the encoder goes, and later reports do nothing. */
    fun close() {
        synchronized(lock) {
            closed = true
            stop()
        }
    }

    private fun start() {
        val e = try {
            encoders.create(onAccessUnit, ::failed)
        } catch (ex: Exception) {
            log("H.264 encoder failed: ${ex.javaClass.simpleName}")
            return
        }
        current = e
        input.setEncoder(e)
        viewing = egress.stream()
    }

    /** The camera stops drawing into it first, then it is released. */
    private fun stop() {
        val e = current ?: return
        current = null
        input.setEncoder(null)
        e.release()
        viewing?.close(); viewing = null
    }

    private fun failed(e: StreamEncoder) {
        synchronized(lock) {
            // Only the encoder in use: a replaced one's late report must not stop its replacement.
            if (closed || e !== current) return
            stop()
            val wait = lastRestartMs + RESTART_MS - clock()
            val go = Runnable {
                synchronized(lock) {
                    if (closed || current != null || !watched()) return@synchronized
                    lastRestartMs = clock()
                    start()
                }
            }
            if (wait <= 0) go.run() else after(wait, go)
        }
    }

    companion object {
        const val RESTART_MS = 5_000L
    }
}
