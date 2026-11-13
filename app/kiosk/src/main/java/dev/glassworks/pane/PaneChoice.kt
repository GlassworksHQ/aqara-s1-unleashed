/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

/**
 * A pane's card on setup's Panes step ("What should it do?"): its name, a line under it and a switch,
 * and for a pane that runs in one of several ways (the radio's modes), those ways as chips under the
 * cards. The setup screen draws one card per [PaneDefinition.setupChoice], in definition order, and
 * applies every card when the person taps Continue.
 */
class PaneChoice(
    val title: String,
    val detail: String,
    /** On now. Cheap, main thread. */
    val on: () -> Boolean,
    /** The ways it can run, as chips while it is on. Empty for a plain switch. */
    val options: List<Option> = emptyList(),
    /** The option in effect; while off, the one offered first. Null without [options]. */
    val option: () -> String? = { null },
    /** What [options] are called on the chip row ("Radio"). */
    val optionsLabel: String = title,
    /** A change only takes effect when the app starts again: setup restarts it after Continue. */
    val restartsApp: Boolean = false,
    /**
     * Applies the card as set. Runs off the main thread and may block (the radio waits for its helper).
     * Called on every Continue, also when nothing changed. Null when it worked, else why not, in words
     * for the person at the panel.
     */
    val apply: (on: Boolean, option: String?) -> String?,
) {
    class Option(val id: String, val label: String)
}
