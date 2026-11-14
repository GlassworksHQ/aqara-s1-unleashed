/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.content.SharedPreferences
import android.os.SystemClock
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection

/** The diagnostics pane ([DiagnosticsPane]) for the whole process: its on/off and its page. [file] is its settings file. */
class DiagnosticsDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "diagnostics"

    override val switch = PaneSwitch(
        file, DiagnosticsPrefs.KEY_ENABLED, default = true, title = "Diagnostics", detail = "The panel's health in Home Assistant",
        objectId = "diagnostics", icon = "mdi:stethoscope",
        offNote = "Diagnostics is off. The panel stops measuring itself, and its health is unavailable in Home Assistant until you turn it on.",
    )

    /** Six tiles with their last hour, read again while the page is open, and why the app last restarted (#212). */
    override fun settings(running: Pane?): List<SettingsSection> {
        val p = running as? DiagnosticsPane ?: return emptyList()
        // The tiles and the footer cover every entity: nothing to list again.
        return listOf(SettingsSection(id, "Diagnostics", covers = p.entities().map { it.objectId }.toSet(), items = listOf(
            SettingsItem.Tiles(3) { DiagnosticsPage.tiles(p::current, p::lastHour, p.lastSample, SystemClock.elapsedRealtime(), p.hasTemperature) },
            SettingsItem.Note { DiagnosticsPage.footer(p.lastExitReason, p.lastSample?.contentProcesses) },
        )))
    }
}
