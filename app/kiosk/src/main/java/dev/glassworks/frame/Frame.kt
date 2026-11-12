/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.Context
import android.content.Intent

/**
 * One device the core runs on. The app makes one frame at start and hands it to what needs it.
 * It holds only what the core asks a device today. The rest of the planned shape (presence, light,
 * screen off, installer, panes) comes in later migration steps.
 */
interface Frame {
    /** The `board` TXT record in the device's mDNS advert. Home Assistant shows it. Never change it for a frame. */
    val board: String

    /** The device's own settings screens, best first. [systemSettings] picks the first that opens. */
    fun systemSettingsIntents(): List<Intent>
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
