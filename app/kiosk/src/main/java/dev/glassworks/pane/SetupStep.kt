/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Context
import android.view.View
import org.json.JSONObject

/**
 * One step a pane adds to first-boot setup. A step is state ([done], [channelState]) plus the requests
 * that complete it ([ops]), so the installer can finish it over the setup channel without the screen.
 * The screen draws it through [screen]. One instance per process ([PaneDefinition.setupSteps]).
 */
interface SetupStep {
    /** Short and stable: the setup channel's namespace for [ops], and `--es setup_step <id>`. */
    val id: String

    /** Its row on the setup screen. */
    val title: String

    /** Why the setup screen shows while this step isn't done, for the log. */
    val reason: String

    /** Nothing more to ask. Cheap and safe from any thread: the screen asks every couple of seconds. */
    fun done(): Boolean

    /** The line under [title] on the setup screen, as the screen shows the step ([done] or not). */
    fun detail(done: Boolean): String

    /** This step's part of the setup channel's state, under [id]. Null leaves it out. No secrets. */
    fun channelState(): JSONObject? = null

    /**
     * Setup channel requests, by name. On the wire they are `<id>.<name>`. Each runs on the channel's
     * thread, may block until it is done, and returns null when it worked, else why not.
     */
    val ops: Map<String, SetupOp> get() = emptyMap()

    /** Older wire names for [ops] (old name to op name), kept for installers that already shipped. */
    val legacyOps: Map<String, String> get() = emptyMap()

    /** Draws the step on one setup screen. Called once per screen, on the main thread. */
    fun screen(context: Context, host: SetupHost): SetupScreen
}

/** A setup channel request: the request's JSON object in, null when it worked or why not out. */
fun interface SetupOp {
    fun run(request: JSONObject): String?
}

/** What the setup screen offers a step's [SetupScreen]. Main thread. */
interface SetupHost {
    /** The screen is a preview (`--ez show_setup true`): never change anything. */
    val preview: Boolean

    /** Draw the card again: the step's own state changed. */
    fun redraw()

    /** The step was answered on the screen: move on (or close the preview). */
    fun next()
}

/** A step's part of the setup screen. Main thread. */
interface SetupScreen {
    /** Fill [card]. [entered]: the step just became the current one. */
    fun bind(card: SetupCard, entered: Boolean)

    /** Every couple of seconds while the app runs. [current]: the screen is up on this step. */
    fun tick(current: Boolean) {}
}

/**
 * The setup screen's card while a pane's step is current. The screen sets the step's label and clears
 * everything before each [SetupScreen.bind]; the step sets what it needs.
 */
interface SetupCard {
    enum class Tone { MUTED, AMBER, GREEN, ROSE }

    fun title(text: String)

    /** The paragraph under the title; null hides it. */
    fun body(text: String?)

    /** The line under the step's content; null hides it. */
    fun status(text: String?, tone: Tone = Tone.MUTED)

    /** A QR code for [url] beside the card's text; null hides it. */
    fun qr(url: String?)

    /** The step's own view, under the title. */
    fun content(view: View?)

    /** The main button. A disabled one is dimmed. */
    fun primary(text: String, enabled: Boolean = true, onClick: () -> Unit)

    /** The second button; null hides it. */
    fun secondary(text: String?, onClick: () -> Unit = {})

    /** Less room above the buttons, for a step whose content fills the card. */
    fun tightButtons()
}
