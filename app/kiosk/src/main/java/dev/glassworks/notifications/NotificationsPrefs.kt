/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import android.content.SharedPreferences

/** The notifications pane's settings, in the file the frame gives its namespace (`notifications`). */
class NotificationsPrefs(private val sp: SharedPreferences) {
    /** The pane's on/off (#67). Off by default: Principle 7. */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, false)

    /** Do not disturb: holds every notification but critical in the list, silent. Kept across restarts. */
    var doNotDisturb: Boolean
        get() = sp.getBoolean(KEY_DND, false)
        // commit(), not apply(): the app can exit right after (#185).
        set(v) { sp.edit().putBoolean(KEY_DND, v).commit() }

    companion object {
        /** Panels keep these keys: never rename them. */
        const val KEY_ENABLED = "notifications_enabled"
        const val KEY_DND = "notifications_dnd"
    }
}
