/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsSection

/**
 * One status tile on the Overview: a label, the value with its dot, and a line under it. [href]: the tile
 * opens a page (one of the QR flows, e.g. `/signin` while the panel isn't signed in).
 */
data class Tile(val label: String, val value: String, val detail: String, val tone: Tone?, val href: String? = null) {
    enum class Tone { OK, WORKING, PROBLEM, OFF }
}

/**
 * A pane on the Overview and in the nav: its [state] and the line under its name. [links]: pages its own
 * page points to (label to path), such as the radio's Thread join on the QR pages.
 */
data class PaneRow(val id: String, val title: String, val state: PaneState, val sectionId: String?, val links: List<Pair<String, String>> = emptyList())

/**
 * A pane's row from its settings section, the one the panel's menu draws ([dev.glassworks.pane.paneSections]):
 * the section's title, Can't run while [SettingsSection.unavailable] says why, Off while its on/off (#67,
 * [SettingsSection.master]) is off. Otherwise [running] says what the pane is doing ("Wake word …"); when the
 * frame can't say, the row reads On with the switch's line under it. No section: the id in words, state unknown.
 */
fun paneRow(id: String, section: SettingsSection?, links: List<Pair<String, String>> = emptyList(), running: () -> PaneState? = { null }): PaneRow {
    val title = section?.title ?: id.replace('-', ' ').replaceFirstChar { it.uppercase() }
    val on = section?.master?.let { m -> runCatching { m.value() }.getOrNull() }
    val unavailable = section?.let { runCatching { it.unavailable() }.getOrNull() }
    val state = when {
        unavailable != null -> PaneState(PaneState.Kind.CANT_RUN, unavailable)
        on == false -> PaneState(PaneState.Kind.OFF, "Turned off")
        else -> runCatching(running).getOrNull()
            ?: if (on == true) PaneState(PaneState.Kind.RUNNING, section.master?.detail.orEmpty(), chip = "On") else PaneState.UNKNOWN
    }
    // A pane that is off offers nothing to do until it is on again.
    return PaneRow(id, title, state, section?.id, if (state.kind == PaneState.Kind.OFF) emptyList() else links)
}

/** Everything the Manage page shows about the panel now. Read on each page load, off the main thread. */
data class PanelStatus(
    /** "Glassworks Panel". */
    val name: String,
    /** The frame's name in words ("S1 Plus"). */
    val frame: String,
    val version: String,
    val uptimeMs: Long,
    /** The HA address the panel uses, or null when it has none yet. */
    val haBase: String?,
    val tiles: List<Tile>,
    val panes: List<PaneRow>,
    /** Every settings section in the panel's order (`settingsOrder`). */
    val sections: List<SettingsSection>,
    /** Lines for About, as label to value. */
    val about: List<Pair<String, String>>,
)

/** The actions the Overview offers. Each returns null when it was started, else why not, in words. */
interface PanelActions {
    fun reloadDashboard(): String?
    fun restartApp(): String?
    /** Null when the frame can't reboot from the app; the button is left out then. */
    val canReboot: Boolean
    fun reboot(): String?
    fun checkForUpdates(): String?
}

/** What the Manage server asks the app. */
interface ManageHost {
    /** The panel's name ("Glassworks Panel"), for pages that need nothing else. Cheap. */
    val name: String
    fun status(): PanelStatus
    val actions: PanelActions
    /** The panel's diagnostics, as text, before redaction (the server redacts the whole file). */
    fun diagnostics(): String
}
