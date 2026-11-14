/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

/**
 * Whether a pane runs now, for the Manage page: its [kind], the line under its name ([detail], "Wake word
 * “Okay Nabu” · Assist", or the reason it can't run), and the chip's word when not the kind's own
 * ([chip], "Playing").
 */
data class PaneState(val kind: Kind, val detail: String, val chip: String? = null) {
    enum class Kind { RUNNING, OFF, CANT_RUN, PROBLEM, UNKNOWN }

    companion object {
        val UNKNOWN = PaneState(Kind.UNKNOWN, "")
    }
}

/**
 * A pane's state from its settings section, the same on the panel's rail and on the Manage page (#243):
 * Off while its on/off ([SettingsSection.master]) is off, else Can't run while [SettingsSection.unavailable]
 * says why. Off wins: a pane that is off offers nothing, also where it couldn't run anyway (the radio
 * without the root helper). Otherwise [running] says what the pane is doing, by default the section's own
 * [SettingsSection.status]; when it can't say, the pane reads On with its switch's line. No section: unknown.
 */
fun paneState(section: SettingsSection?, running: () -> PaneState? = { section?.status?.invoke() }): PaneState {
    val on = section?.master?.let { m -> runCatching { m.value() }.getOrNull() }
    val unavailable = section?.let { runCatching { it.unavailable() }.getOrNull() }
    return when {
        on == false -> PaneState(PaneState.Kind.OFF, "Turned off")
        unavailable != null -> PaneState(PaneState.Kind.CANT_RUN, unavailable)
        else -> runCatching(running).getOrNull()
            ?: if (on == true) PaneState(PaneState.Kind.RUNNING, section.master.detail, chip = "On") else PaneState.UNKNOWN
    }
}

/** The chip's word: the pane's own ([PaneState.chip]) else its kind's (Running, Off, Can't run, Problem); null for none. */
val PaneState.chipWord: String?
    get() = chip ?: when (kind) {
        PaneState.Kind.RUNNING -> "Running"
        PaneState.Kind.OFF -> "Off"
        PaneState.Kind.CANT_RUN -> "Can't run"
        PaneState.Kind.PROBLEM -> "Problem"
        PaneState.Kind.UNKNOWN -> null
    }
