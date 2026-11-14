/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.content.Context

/** Tells [ScreenPolicy] whether someone is near the panel. A frame supplies one when it has a sensor for it. */
interface PresenceSource {
    /** Starts reporting. [onReading] runs on the main thread for every reading, changed or not. [raw] is the sensor's own value. */
    fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit)

    /** Stops reporting. [start] may run again. */
    fun stop(context: Context)

    /**
     * Sets how far away still counts as near. 0 or less leaves the device's own setting alone. Null when
     * it took, else why not, in words for the person at the panel.
     */
    fun setRange(context: Context, metres: Float): String? = null

    /** Whether this device has the sensor. A frame's source can be wrong about a device it wasn't made for. */
    fun available(context: Context): Boolean = true
}

/** Ambient light for [ScreenPolicy]'s brightness curve. */
interface LightSource {
    /** Starts reporting. [onLux] runs on the main thread for every reading. */
    fun start(context: Context, onLux: (Float) -> Unit)

    /** Stops reporting. [start] may run again. */
    fun stop(context: Context)
}

/** What [ScreenPolicy] reads on every decision. The app keeps these in its own settings file under its own keys. */
interface ScreenSettings {
    /** "presence": off after the idle timeout with nobody near. "always": never off. */
    val screenMode: String
    val idleOffSec: Int
    /** "HH:MM-HH:MM" in local time. Anything else means no night. */
    val nightWindow: String
    val nightIdleOffSec: Int
    val wakeOnApproach: Boolean
    /** Passed to [PresenceSource.setRange]. */
    val presenceRangeM: Float
    var autoBrightness: Boolean
    val minBrightness: Float
    val maxBrightness: Float
    val nightMaxBrightness: Float
    /** 0..1 while [autoBrightness] is off. 0 or less means the system brightness. */
    var manualBrightness: Float
}
