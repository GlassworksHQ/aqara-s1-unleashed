/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import sh.edm.bluetoothproxy.ProxyStatus
import sh.edm.bluetoothproxy.ScanFailure

/**
 * What the Bluetooth proxy is doing, in one place: the settings page's status line and card, and the
 * `bluetooth_proxy_status` entity, all read it. [entityText] is null while the pane is off, so the
 * entity is unavailable then, like every off pane's.
 */
enum class ProxyPhase(val entityText: String?) {
    OFF(null),
    /** The device has no Bluetooth LE. The pane can't run: no setup card, a "can't run" page. */
    NO_BLE("No Bluetooth LE"),
    NO_PERMISSION("No permission"),
    BLUETOOTH_OFF("Bluetooth off"),
    /** Android hands Bluetooth results to apps only while location is on. */
    LOCATION_OFF("Location off"),
    /** On, but Home Assistant isn't listening: the radio stays quiet. */
    WAITING("Waiting for Home Assistant"),
    LISTENING("Listening"),
    /** Android stopped or refused the scan; the engine tries again. */
    RETRYING("Retrying");

    companion object {
        /** [on]: the pane's switch. [unavailable]: why a scan can't run now. [status]: the engine's, null while none runs. */
        fun of(on: Boolean, unavailable: ScanFailure?, status: ProxyStatus?): ProxyPhase = when {
            unavailable == ScanFailure.NO_BLE -> NO_BLE
            !on -> OFF
            unavailable == ScanFailure.NO_PERMISSION -> NO_PERMISSION
            unavailable == ScanFailure.BLUETOOTH_OFF -> BLUETOOTH_OFF
            unavailable == ScanFailure.LOCATION_OFF -> LOCATION_OFF
            status == null || !status.subscribed -> WAITING
            status.failure != null -> RETRYING
            else -> LISTENING
        }
    }
}

/** What the settings page reads and does. [BluetoothProxyDefinition] is the real one; mock-ups pass their own. */
interface BluetoothProxyModel {
    fun phase(): ProxyPhase
    /** The engine's status, or null while none runs. */
    fun status(): sh.edm.bluetoothproxy.ProxyStatus?
    /** Turns Bluetooth on. False where Android doesn't let apps. */
    fun turnBluetoothOn(): Boolean
    /** Turns location on. False where the app may not. */
    fun turnLocationOn(): Boolean
}
