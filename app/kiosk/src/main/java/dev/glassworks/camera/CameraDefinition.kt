/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.SharedPreferences
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch

/**
 * The camera pane ([CameraPane]) for the whole process. [hasCamera]: the device has one
 * (`PackageManager.FEATURE_CAMERA_ANY`). Without one the pane has no switch and no entities. Its
 * settings page comes with the pane's UI (#117). Its settings have no MainActivity extras: any app can
 * send those, and on a tablet another app could turn privacy off or open the stream. The app sets them
 * from adb through its own adb-only activity.
 */
class CameraDefinition(file: SharedPreferences, private val hasCamera: Boolean) : PaneDefinition {
    override val id = CameraPane.ID
    val prefs = CameraPrefs(file)

    override val switch: PaneSwitch? = if (!hasCamera) null else PaneSwitch(
        file, CameraPrefs.KEY_ENABLED, default = false, title = "Camera", detail = "Presence, and snapshots",
        objectId = "camera_pane", entityName = "Camera pane", icon = "mdi:cctv",
    )
}
