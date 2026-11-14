/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import org.json.JSONObject

/**
 * Setup's Camera step under Panes (#117's approved design): what the camera may do, then Android's
 * permission ("Allow the camera"). Shown while the camera pane's card is on. Done once a use is picked
 * and Android allows the camera. [granted] reads Android's permission; [pickers] draws the cards.
 */
internal class CameraSetupStep(
    private val prefs: CameraPrefs,
    private val on: () -> Boolean,
    private val turnOff: () -> Unit,
    private val granted: () -> Boolean,
    private val pickers: CameraUsePickers?,
) : SetupStep {
    override val id = CameraPane.ID // the pane's own id: setup's rail and `--es setup_step camera`
    override val title = "Camera"
    override val reason = "the camera's use isn't picked, or Android hasn't allowed the camera"

    override fun done(): Boolean = !on() || (prefs.useChosen && granted())

    override fun channelState(): JSONObject = JSONObject()
        .put("use", if (prefs.use == CameraUse.PRESENCE) "presence" else "snapshots")
        .put("chosen", prefs.useChosen)
        .put("allowed", granted())

    override fun screen(context: Context, host: SetupHost): SetupScreen = Screen(context, host)

    private inner class Screen(private val context: Context, private val host: SetupHost) : SetupScreen {
        private var chosen = prefs.use
        /** Android was asked from this screen: a refusal now says what to do. */
        private var asked = false
        private var wasGranted = granted()
        private val picker = pickers?.create(context)?.apply { onPick = { u -> chosen = u; host.redraw() } }

        override fun bind(card: SetupCard, entered: Boolean) {
            if (entered) { chosen = prefs.use; asked = false }
            val allowed = granted()
            wasGranted = allowed
            card.title(TITLE)
            card.body(null)
            picker?.bind(chosen, enabled = true)
            card.content(picker?.view)
            when {
                allowed -> card.status(ALLOWED, SetupCard.Tone.GREEN)
                asked -> card.status(REFUSED, SetupCard.Tone.ROSE)
                else -> card.status(ASKS_ONCE, SetupCard.Tone.MUTED)
            }
            if (allowed) {
                card.primary("Continue") { keep(); host.next() }
            } else {
                card.primary(ALLOW) {
                    keep()
                    // A preview walks on to the refusal without asking Android, so it can be seen too.
                    asked = true
                    if (!host.preview) (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE)
                    host.redraw()
                }
            }
            if (!allowed && asked) {
                card.tightButtons()
                card.secondary("Turn the camera off") { if (!host.preview) turnOff(); host.next() }
            }
        }

        /** The picked use, kept; a preview changes nothing. */
        private fun keep() {
            if (!host.preview) prefs.use = chosen
        }

        /** Android's answer comes back to the activity: draw the card again when it changes. */
        override fun tick(current: Boolean) {
            if (current && granted() != wasGranted) host.redraw()
        }
    }

    companion object {
        const val TITLE = "What may the camera do?"
        const val ALLOW = "Allow the camera"
        const val ASKS_ONCE = "Android asks once to allow the camera."
        const val ALLOWED = "Android allows the camera."
        const val REFUSED = "Android didn't allow the camera. Allow it in Android's settings."
        /** Any code: MainActivity starts the service again on a grant, which adds the camera's foreground type. */
        const val REQUEST_CODE = 117
    }
}
