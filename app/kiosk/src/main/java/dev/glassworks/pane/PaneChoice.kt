/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

/**
 * A pane's card on setup's Panes step ("What should it do?"): its name, a line under it and a switch.
 * The step only chooses which panes are on. A pane that is on then shows its own [SetupStep]s under
 * Panes in the setup rail, where it is configured (the radio's mode). Continue on the Panes step applies
 * every card.
 */
class PaneChoice(
    val title: String,
    val detail: String,
    /** On now. Cheap, main thread. */
    val on: () -> Boolean,
    /**
     * Applies the switch. Runs off the main thread and may block. Called on every Continue, also when
     * nothing changed. Turning a pane on only says so: its own steps configure it. Null when it worked,
     * else why not, in words for the person at the panel.
     */
    val apply: (on: Boolean) -> String?,
)
