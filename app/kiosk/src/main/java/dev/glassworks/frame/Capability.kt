/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.pm.PackageManager

/**
 * Hardware a pane needs and a frame has: the words of a pane.json's `requires` and a frame.json's
 * `provides` ([id]). [noun] says it as packages/frame-card's CAPABILITIES do ("a Silicon Labs radio");
 * [missing] is why a pane that needs it can't run, as the settings page says it.
 */
enum class Capability(val id: String, val noun: String, val missing: String = "it has no ${noun.removePrefix("a ")}") {
    MICROPHONE("microphone", "a microphone"),
    SPEAKER("speaker", "a speaker"),
    SILABS_RADIO("silabs-radio", "a Silicon Labs radio"),
    S1_WALL_BASE("s1-wall-base", "the S1's wall base", "it isn't an Aqara S1"),
    BLUETOOTH_LE("bluetooth-le", "Bluetooth LE"),
    CAMERA("camera", "a camera");

    companion object {
        /** The ones a device says it has, by its `PackageManager` features ([hasFeature]). No feature names a Silicon Labs radio or an S1 wall base. */
        fun ofDevice(hasFeature: (String) -> Boolean): Set<Capability> = buildSet {
            if (hasFeature(PackageManager.FEATURE_MICROPHONE)) add(MICROPHONE)
            if (hasFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) add(SPEAKER)
            if (hasFeature(PackageManager.FEATURE_BLUETOOTH_LE)) add(BLUETOOTH_LE)
            if (hasFeature(PackageManager.FEATURE_CAMERA_ANY)) add(CAMERA)
        }
    }
}
