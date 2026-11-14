/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import sh.edm.esphome.CameraEntity

/** What the camera may do (#117). The pane's select in Home Assistant and its two cards on the panel. */
enum class CameraUse {
    /** Presence only: frames stay on the panel. */
    PRESENCE,

    /** Presence, and stills for Home Assistant's camera entity when it asks. */
    PRESENCE_AND_SNAPSHOTS,
}

/** The camera's settings, read on every frame request. */
data class CameraPolicy(
    /** Camera privacy: the camera stays closed. */
    val privacy: Boolean,
    val use: CameraUse,
) {
    /** Whether a still may go to Home Assistant now. */
    val stillsMayLeave: Boolean get() = !privacy && use == CameraUse.PRESENCE_AND_SNAPSHOTS
}

/** A JPEG of what the camera sees now, or null when there is none. Blocks for at most a second or so. */
fun interface StillCamera {
    fun jpeg(): ByteArray?
}

/**
 * Whether frames are leaving the panel, for the privacy indicator: on while a stream is open, and for
 * [holdMs] after each still, so a card that refreshes every few seconds keeps it on rather than
 * blinking. [onChange] gets each change, in order, from whichever thread caused it; keep it short
 * (post to the main thread). [after] runs a callback
 * once after a delay (a `Handler.postDelayed`); [clock] is `SystemClock.elapsedRealtime`.
 */
class FrameEgress(
    private val clock: () -> Long,
    private val after: (delayMs: Long, Runnable) -> Unit,
    private val holdMs: Long = HOLD_MS,
    private val onChange: (leaving: Boolean) -> Unit,
) {
    private val lock = Any()
    private var streams = 0
    private var stillUntil = 0L
    private var shown = false

    val leaving: Boolean get() = synchronized(lock) { shown }

    /** A still just left. */
    fun still() {
        synchronized(lock) { stillUntil = clock() + holdMs }
        after(holdMs) { settle() }
        settle()
    }

    /** A stream to someone started. Close it when that stream ends; closing twice counts once. */
    fun stream(): AutoCloseable {
        synchronized(lock) { streams++ }
        settle()
        var open = true
        return AutoCloseable {
            synchronized(lock) {
                if (!open) return@AutoCloseable
                open = false
                streams--
            }
            settle()
        }
    }

    // onChange runs under the lock so two threads can't deliver their changes out of order.
    private fun settle() = synchronized(lock) {
        val v = streams > 0 || clock() < stillUntil
        if (v != shown) {
            shown = v
            onChange(v)
        }
    }

    companion object {
        /** A little longer than Home Assistant's 5 s gap between camera stream requests. */
        const val HOLD_MS = 6_000L
    }
}

/**
 * The panel's camera in Home Assistant (#117, on #120's [CameraEntity]). A still leaves only while
 * [policy] allows it and only to a connection signed in with the panel's encryption key; Home Assistant
 * gets an empty image otherwise. Each still that leaves goes through [egress].
 */
fun snapshotCamera(still: StillCamera, policy: () -> CameraPolicy, egress: FrameEgress): CameraEntity =
    CameraEntity("camera", "Camera", icon = "mdi:webcam", maxFps = SNAPSHOT_FPS, frame = stillGate(still, policy, egress))
        .apply { authOnly = true }

/** What [snapshotCamera] answers Home Assistant with: a still when [policy] allows one, else nothing. */
internal fun stillGate(still: StillCamera, policy: () -> CameraPolicy, egress: FrameEgress): () -> ByteArray? = {
    if (!policy().stillsMayLeave) null else still.jpeg()?.also { egress.still() }
}

/** The more-info dialog's stream: enough to see who is there, not video. */
const val SNAPSHOT_FPS = 2f
