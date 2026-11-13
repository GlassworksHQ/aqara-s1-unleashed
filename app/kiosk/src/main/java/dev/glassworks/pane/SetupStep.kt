/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import org.json.JSONObject

/**
 * Something first-boot setup waits for from a pane. A step is state ([done], [channelState]) plus the
 * requests that complete it ([ops]), so the installer can finish it over the setup channel without the
 * screen. On the panel the person answers it on the Panes step, through the pane's [PaneChoice]. One
 * instance per process ([PaneDefinition.setupSteps]).
 */
interface SetupStep {
    /** Short and stable: the setup channel's namespace for [ops]. */
    val id: String

    /** Why the setup screen shows while this step isn't done, for the log. */
    val reason: String

    /** Nothing more to ask. Cheap and safe from any thread: the screen asks every couple of seconds. */
    fun done(): Boolean

    /** This step's part of the setup channel's state, under [id]. Null leaves it out. No secrets. */
    fun channelState(): JSONObject? = null

    /**
     * Setup channel requests, by name. On the wire they are `<id>.<name>`. Each runs on the channel's
     * thread, may block until it is done, and returns null when it worked, else why not.
     */
    val ops: Map<String, SetupOp> get() = emptyMap()

    /** Older wire names for [ops] (old name to op name), kept for installers that already shipped. */
    val legacyOps: Map<String, String> get() = emptyMap()
}

/** A setup channel request: the request's JSON object in, null when it worked or why not out. */
fun interface SetupOp {
    fun run(request: JSONObject): String?
}
