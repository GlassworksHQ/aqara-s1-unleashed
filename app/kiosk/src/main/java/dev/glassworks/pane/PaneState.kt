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
