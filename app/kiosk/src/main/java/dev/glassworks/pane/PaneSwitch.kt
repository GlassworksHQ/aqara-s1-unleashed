/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.SharedPreferences
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.SwitchEntity

/**
 * A pane's on/off (#67), kept by the core: a flag under [key] in the pane's own settings [file]. The
 * shell starts the pane only while it is on, and starts or stops it when the flag changes, without
 * restarting the app. From the same switch the core makes the switch at the top of the pane's settings
 * page, its card on setup's Panes step ([inSetup]), a Home Assistant switch ([entity]) and the adb extra
 * `--ez <key> true|false`.
 */
class PaneSwitch(
    private val file: SharedPreferences,
    /** The flag's key, and the adb extra's name. Panels keep it: never rename it. */
    val key: String,
    val default: Boolean,
    /** The pane's name in the settings and on its setup card (`Voice`). */
    val title: String,
    /** The line under it (`Assist and timers`). */
    val detail: String,
    /** The Home Assistant switch's object id. Home Assistant keys entities by it: never change it. */
    val objectId: String,
    /** The Home Assistant switch's name. */
    val entityName: String = title,
    val icon: String = "",
    /** The pane has a card on setup's Panes step. */
    val inSetup: Boolean = false,
    /**
     * The pane is the device's voice assistant: while it is off, the device info offers Home Assistant
     * no voice features, so the switch changes what Home Assistant reads at connect.
     */
    val voiceAssistant: Boolean = false,
) {
    /** Cheap, any thread. */
    var on: Boolean
        get() = file.getBoolean(key, default)
        // commit(), not apply(): the app can exit right after (a restart from the menu or Home Assistant),
        // and an async write dies with the process (#185).
        set(v) { file.edit().putBoolean(key, v).commit() }

    /** Its card on setup's Panes step, and the switch at the top of its settings page. */
    fun choice() = PaneChoice(title, detail, on = { on }, apply = { on = it; null })

    fun extra() = IntentExtra.bool(key, default) { on = it }

    /**
     * A config switch, off in Home Assistant until someone turns it on there: people set it once (#132,
     * #208). Only an authenticated connection may change it: turned on, a pane can open the microphone
     * or a port, which nobody on the LAN should do to a panel without a key.
     */
    fun entity() = SwitchEntity(
        objectId, entityName, icon, EntityCategory.CONFIG, disabledByDefault = true,
        get = { on }, set = { on = it },
    ).apply { authOnly = true }
}
