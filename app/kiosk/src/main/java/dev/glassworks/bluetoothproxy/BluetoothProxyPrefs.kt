/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import android.content.SharedPreferences

class BluetoothProxyPrefs(private val sp: SharedPreferences) {

    /** The pane is on ([BluetoothProxyDefinition.switch] writes it). Off until someone turns it on (#67, #121). */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    companion object {
        /** Panels keep it, and it is the adb extra: never rename it. */
        const val KEY_ENABLED = "bluetooth_proxy_enabled"
        const val DEFAULT_ENABLED = false
    }
}
