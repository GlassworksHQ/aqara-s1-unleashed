/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import android.content.Context
import android.provider.Settings
import sh.edm.bluetoothproxy.AndroidBleScanner
import sh.edm.bluetoothproxy.BleScanner
import sh.edm.bluetoothproxy.ScanFailure

/** The device's Bluetooth as the pane uses it: [AndroidProxyRadio] on a panel, a fake in tests. */
interface ProxyRadio {
    val scanner: BleScanner
    /** Why a scan can't run now, or null. Cheap, any thread. */
    fun unavailable(): ScanFailure?
    /** Turns Bluetooth on. False where Android doesn't let apps. */
    fun turnBluetoothOn(): Boolean
    /** Turns location on. False unless the app may write secure settings. */
    fun turnLocationOn(): Boolean
}

class AndroidProxyRadio(context: Context) : ProxyRadio {
    private val app = context.applicationContext
    private val android = AndroidBleScanner(app)
    override val scanner: BleScanner get() = android
    override fun unavailable() = android.unavailable()
    override fun turnBluetoothOn() = android.turnBluetoothOn()

    /**
     * Location on, as `adb shell settings put secure location_mode 3` does. Needs WRITE_SECURE_SETTINGS,
     * which the installer grants on the S1 (a panel with no Settings app to do it by hand).
     */
    @Suppress("DEPRECATION")
    override fun turnLocationOn(): Boolean = try {
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
    } catch (_: SecurityException) {
        false
    }
}
