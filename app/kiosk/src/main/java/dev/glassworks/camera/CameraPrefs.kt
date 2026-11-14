/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.SharedPreferences

/**
 * The camera pane's settings, in the file `PaneContext.prefs("camera")` gives it. Panels keep these
 * keys: never rename them. The stream's password is never logged.
 */
class CameraPrefs(private val sp: SharedPreferences) {

    /** The pane is on ([CameraDefinition.switch] writes it). Off until someone turns it on. */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, false)

    var use: CameraUse
        get() = when (sp.getString(KEY_USE, null)) { USE_SNAPSHOTS -> CameraUse.PRESENCE_AND_SNAPSHOTS; else -> CameraUse.PRESENCE }
        set(v) { sp.edit().putString(KEY_USE, if (v == CameraUse.PRESENCE_AND_SNAPSHOTS) USE_SNAPSHOTS else USE_PRESENCE).apply() }

    var privacy: Boolean
        get() = sp.getBoolean(KEY_PRIVACY, false)
        set(v) { sp.edit().putBoolean(KEY_PRIVACY, v).apply() }

    var onvif: Boolean
        get() = sp.getBoolean(KEY_ONVIF, false)
        set(v) { sp.edit().putBoolean(KEY_ONVIF, v).apply() }

    var sensitivity: Int
        get() = sp.getInt(KEY_SENSITIVITY, CameraPresence.DEFAULT_SENSITIVITY).coerceIn(1, 10)
        set(v) { sp.edit().putInt(KEY_SENSITIVITY, v.coerceIn(1, 10)).apply() }

    /** The stream's sign-in, or null until both parts are set. */
    var signIn: Pair<String, String>?
        get() {
            val user = sp.getString(KEY_STREAM_USER, null)?.takeIf { it.isNotBlank() } ?: return null
            val pass = sp.getString(KEY_STREAM_PASSWORD, null)?.takeIf { it.isNotEmpty() } ?: return null
            return user to pass
        }
        set(v) { sp.edit().putString(KEY_STREAM_USER, v?.first).putString(KEY_STREAM_PASSWORD, v?.second).apply() }

    val policy: CameraPolicy get() = CameraPolicy(privacy, use, onvif)

    companion object {
        const val KEY_ENABLED = "camera_enabled"
        const val KEY_USE = "camera_use"
        const val KEY_PRIVACY = "camera_privacy"
        const val KEY_ONVIF = "onvif_stream"
        const val KEY_SENSITIVITY = "camera_sensitivity"
        const val KEY_STREAM_USER = "camera_stream_user"
        const val KEY_STREAM_PASSWORD = "camera_stream_password"

        /** The keys the running pane reacts to. */
        val KEYS = setOf(KEY_USE, KEY_PRIVACY, KEY_ONVIF, KEY_SENSITIVITY, KEY_STREAM_USER, KEY_STREAM_PASSWORD)

        /** The `camera_use` select's options, as Home Assistant shows them. */
        const val USE_PRESENCE = "Presence only"
        const val USE_SNAPSHOTS = "Presence + snapshots"
    }
}
