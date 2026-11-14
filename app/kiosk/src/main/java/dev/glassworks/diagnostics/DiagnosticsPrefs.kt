/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.content.SharedPreferences

/** The diagnostics pane's settings, in the file `PaneContext.prefs("diagnostics")` gives it. */
class DiagnosticsPrefs(private val sp: SharedPreferences) {

    /** The pane is on ([DiagnosticsDefinition.switch] writes it). */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, true)

    companion object {
        const val KEY_ENABLED = "diagnostics_enabled"
    }
}
