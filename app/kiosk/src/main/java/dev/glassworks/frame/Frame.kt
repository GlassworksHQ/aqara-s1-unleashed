/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.Context
import android.content.Intent
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import java.util.concurrent.ScheduledExecutorService

/**
 * One device the core runs on. The app makes one frame at start and hands it to what needs it.
 * It holds only what the core asks a device today. The rest of the planned shape (screen off,
 * installer, panes the user can turn off) comes in later migration steps.
 */
interface Frame {
    /** The `board` TXT record in the device's mDNS advert. Home Assistant shows it. Never change it for a frame. */
    val board: String

    /** Whether someone is near, for the screen policy. Null when the device has no sensor for it. */
    val presence: PresenceSource?

    /** Ambient light, for the screen policy's brightness curve. Null when the device has no light sensor. */
    val light: LightSource?

    /** The device's own settings screens, best first. [systemSettings] picks the first that opens. */
    fun systemSettingsIntents(): List<Intent>

    /**
     * The panes only this device has (its wall base, its own entities). The shell builds them once per
     * device run, next to its own, and runs them like any other. [device] is the shell's device thread.
     */
    fun panes(ctx: PaneContext, device: ScheduledExecutorService): List<Pane>
}

/** The first of [Frame.systemSettingsIntents] that resolves on this device, ready to start from any context. Null when none does. */
fun Frame.systemSettings(context: Context): Intent? {
    val pm = context.packageManager
    return systemSettingsIntents().firstOrNull { it.resolveActivity(pm) != null }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens [systemSettings]. False when the device has no settings screen or it did not start. */
fun Frame.openSystemSettings(context: Context): Boolean {
    val i = systemSettings(context) ?: return false
    return runCatching { context.startActivity(i) }.isSuccess
}
