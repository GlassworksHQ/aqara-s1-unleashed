/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.content.SharedPreferences
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch

/** The diagnostics pane ([DiagnosticsPane]) for the whole process: its on/off. [file] is its settings file. */
class DiagnosticsDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "diagnostics"

    override val switch = PaneSwitch(
        file, DiagnosticsPrefs.KEY_ENABLED, default = true, title = "Diagnostics", detail = "The panel's health in Home Assistant",
        objectId = "diagnostics", icon = "mdi:stethoscope",
    )
}
