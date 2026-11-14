/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import dev.glassworks.pane.SettingsItem

/** What the camera's page reads from the running pane: [CameraPane] on a device. */
fun interface CameraReadout {
    fun reading(): CameraReading
}

/** The running camera pane as its page reads it ([CameraPane.reading]). Times are time of day, in ms. */
data class CameraReading(
    val use: CameraUse,
    val privacy: Boolean,
    /** The stream is on and allowed (Presence + snapshots). */
    val onvif: Boolean,
    val signedIn: Boolean,
    val running: Boolean,
    val problem: String?,
    /** Presence is watching (the camera is open for it). */
    val detecting: Boolean,
    val near: Boolean?,
    val nearSince: Long?,
    /** Images are leaving now (the privacy indicator). */
    val leaving: Boolean,
    val lastStill: Long?,
    /** The stream's ports are open. */
    val streaming: Boolean,
    val watchers: List<String>,
    val address: StreamAddress?,
)

/**
 * The words on the camera's page (#117's approved design), from a [CameraReading]. [time] writes a time
 * of day ("14:31"). No credentials, no tokens: only the panel's own address and ports.
 */
object CameraPage {

    enum class Tone { OK, QUIET, PROBLEM }

    /** The status line under the heading. */
    fun status(r: CameraReading): Pair<String, Tone> = when {
        !r.running -> "The camera pane isn't running yet." to Tone.QUIET
        r.problem == CameraPane.NOT_ALLOWED -> "$NOT_ALLOWED_LINE." to Tone.PROBLEM
        r.problem != null -> r.problem to Tone.PROBLEM
        r.privacy -> "Camera privacy is on · the camera is closed" to Tone.QUIET
        r.watchers.isNotEmpty() -> "Camera in use · streaming to ${r.watchers.size} · images leave the panel" to Tone.OK
        r.leaving -> "Camera in use · sending images to Home Assistant" to Tone.OK
        !r.detecting -> "The camera is closed · the panel's own sensor wakes the screen" to Tone.QUIET
        r.use == CameraUse.PRESENCE -> "Watching for people · no image leaves the panel" to Tone.OK
        else -> "Watching for people · images go out only when Home Assistant asks" to Tone.OK
    }

    /** Presence, the camera and images out. */
    fun tiles(r: CameraReading, time: (Long) -> String): List<SettingsItem.Tile> {
        val closed = !r.running || r.privacy || r.problem == CameraPane.NOT_ALLOWED || !r.detecting
        val presence = when {
            closed -> SettingsItem.Tile("Presence", "—", detail = "Not watching")
            r.near == true -> SettingsItem.Tile("Presence", "Someone", detail = r.nearSince?.let { "Near since ${time(it)}" } ?: "Near")
            r.near == false -> SettingsItem.Tile("Presence", "Nobody", detail = r.nearSince?.let { "Since ${time(it)}" } ?: "")
            else -> SettingsItem.Tile("Presence", "—", detail = "Starting")
        }
        val camera = when {
            r.privacy -> SettingsItem.Tile("Camera", "Closed", detail = "Privacy is on")
            r.problem == CameraPane.NOT_ALLOWED -> SettingsItem.Tile("Camera", "Closed", detail = "Not allowed")
            closed -> SettingsItem.Tile("Camera", "Closed", detail = "Not needed now")
            r.watchers.isNotEmpty() -> SettingsItem.Tile("Camera", "Open", detail = "Presence, stream")
            else -> SettingsItem.Tile("Camera", "Open", detail = "For presence")
        }
        val out = when {
            r.watchers.isNotEmpty() -> SettingsItem.Tile("Images out", "Now", detail = "Stream to ${r.watchers.size}")
            r.leaving -> SettingsItem.Tile("Images out", "Now", detail = "A snapshot")
            r.use == CameraUse.PRESENCE || r.privacy -> SettingsItem.Tile("Images out", "None", detail = if (r.privacy) "Privacy is on" else "Presence only")
            r.lastStill != null -> SettingsItem.Tile("Images out", "None", detail = "Last ${time(r.lastStill)} · snapshot")
            else -> SettingsItem.Tile("Images out", "None", detail = "None yet")
        }
        return listOf(presence, camera, out)
    }

    /** The stream switch's line: what it is for, or why it can't be on. */
    fun streamDetail(use: CameraUse): String =
        if (use == CameraUse.PRESENCE) NEEDS_SNAPSHOTS else "For Frigate, go2rtc, Home Assistant's ONVIF integration and UniFi Protect."

    fun signIn(signedIn: Boolean): String =
        if (signedIn) "Set" else "Not set. The stream stays closed until a username and password are set (with adb for now, see docs/esphome.md)."

    fun address(a: StreamAddress?): String =
        a?.let { "${it.host} · RTSP port ${it.rtspPort}, ONVIF port ${it.onvifPort}" } ?: "The panel has no address yet"

    fun stream(r: CameraReading): String = when {
        r.address == null -> "—"
        else -> "${r.address.stream} · H.264 · ${CameraHub.WIDTH} × ${CameraHub.HEIGHT}" + if (r.streaming) "" else " · closed now"
    }

    fun device(r: CameraReading): String = r.address?.device?.let { it + if (r.streaming) "" else " · closed now" } ?: "—"

    fun watching(r: CameraReading): String = when {
        !r.streaming -> "The stream is off"
        r.watchers.isEmpty() -> "Nobody"
        else -> r.watchers.joinToString(" · ")
    }

    const val NOT_ALLOWED_LINE = "Android hasn't allowed the camera. Allow it in setup, or in Android's app settings"
    const val NEEDS_SNAPSHOTS = "Needs Presence + snapshots."
    const val PRIVACY_DETAIL = "Closes the camera. Presence, snapshots and the stream stop."
}
