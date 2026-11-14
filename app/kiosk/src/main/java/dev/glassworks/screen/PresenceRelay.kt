/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.content.Context

/**
 * The presence source [ScreenPolicy] reads: the frame's own sensor, and readings a pane offers for a
 * device that has none (the camera pane, #117). With no offers it is the frame's source unchanged.
 * Main thread.
 */
class PresenceRelay(private val own: PresenceSource?) : PresenceSource {
    private var listener: ((Boolean, Float) -> Unit)? = null

    /** Whether the frame's own sensor is on this device. A pane offers readings only when it isn't. */
    fun ownSensor(context: Context): Boolean = own?.available(context) == true

    /** A reading from a pane, for a device without the frame's sensor. Dropped while the policy isn't started. */
    fun offer(near: Boolean, raw: Float) {
        listener?.invoke(near, raw)
    }

    override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) {
        listener = onReading
        own?.start(context, onReading)
    }

    override fun stop(context: Context) {
        listener = null
        own?.stop(context)
    }

    override fun setRange(context: Context, metres: Float): String? = own?.setRange(context, metres)

    override fun available(context: Context): Boolean = true
}
