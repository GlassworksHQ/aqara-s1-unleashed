/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.SharedPreferences
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection

/**
 * The camera pane ([CameraPane]) for the whole process. [hasCamera]: the device has one
 * (`PackageManager.FEATURE_CAMERA_ANY`). Like every pane it has an on/off on every frame (#67); without
 * a camera its page says it can't run, it has no setup card, and the pane itself never runs. Its
 * settings have no MainActivity extras: any app can send those, and on a tablet another app could turn
 * privacy off or open the stream. The app sets them from adb through its own adb-only activity.
 */
class CameraDefinition(file: SharedPreferences, val hasCamera: Boolean) : PaneDefinition {
    override val id = CameraPane.ID
    val prefs = CameraPrefs(file)

    override val switch = PaneSwitch(
        file, CameraPrefs.KEY_ENABLED, default = false, title = TITLE, detail = "Presence, and snapshots",
        objectId = "camera_pane", entityName = "Camera pane", icon = "mdi:cctv",
    )

    override val setupChoice: PaneChoice? get() = null

    /** Only where it can't run: the page that says so. With a camera, the core's page with the on/off (the camera's own comes with its UI). */
    override fun settings(): List<SettingsSection> =
        if (hasCamera) emptyList()
        else listOf(SettingsSection(id, TITLE, listOf(SettingsItem.Note { NO_CAMERA_NOTE }), unavailable = { NO_CAMERA }))

    companion object {
        const val TITLE = "Camera"
        const val NO_CAMERA = "it has no camera"
        const val NO_CAMERA_NOTE = "On a panel with a camera, this pane wakes the screen when someone walks up, and can show the room in Home Assistant."
    }
}
