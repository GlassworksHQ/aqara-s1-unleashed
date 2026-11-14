/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.SharedPreferences
import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch

/**
 * The camera pane ([CameraPane]) for the whole process. [hasCamera]: the device has one
 * (`PackageManager.FEATURE_CAMERA_ANY`). Without one the pane has no switch and no entities. Its
 * settings page comes with the pane's UI (#117). The stream's sign-in has no extra here: any app can
 * send MainActivity extras, so it is set from adb through the app's own adb-only activity.
 */
class CameraDefinition(file: SharedPreferences, private val hasCamera: Boolean) : PaneDefinition {
    override val id = CameraPane.ID
    val prefs = CameraPrefs(file)

    override val switch: PaneSwitch? = if (!hasCamera) null else PaneSwitch(
        file, CameraPrefs.KEY_ENABLED, default = false, title = "Camera", detail = "Presence, and snapshots",
        objectId = "camera_pane", entityName = "Camera pane", icon = "mdi:cctv",
    )

    override val extras: List<IntentExtra> = listOf(
        IntentExtra.string(CameraPrefs.KEY_USE) { v ->
            prefs.use = if (v == "snapshots" || v == CameraPrefs.USE_SNAPSHOTS) CameraUse.PRESENCE_AND_SNAPSHOTS else CameraUse.PRESENCE
        },
        IntentExtra.bool(CameraPrefs.KEY_PRIVACY, false) { prefs.privacy = it },
        IntentExtra.bool(CameraPrefs.KEY_ONVIF, false) { prefs.onvif = it },
        IntentExtra.float(CameraPrefs.KEY_SENSITIVITY, CameraPresence.DEFAULT_SENSITIVITY.toFloat()) { prefs.sensitivity = Math.round(it) },
    )
}
