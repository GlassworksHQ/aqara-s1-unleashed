/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import android.content.SharedPreferences
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import sh.edm.bluetoothproxy.ProxyStatus
import sh.edm.bluetoothproxy.ScanFailure
import java.util.concurrent.ScheduledExecutorService

/**
 * The Bluetooth proxy pane's definition, for the whole process: its on/off (off by default, #67),
 * its setup card (none without Bluetooth LE) and its settings page. The page itself is the frame's
 * to draw: [page] builds it from this definition. Without one, the page is a line of text.
 */
class BluetoothProxyDefinition(
    file: SharedPreferences,
    private val radio: ProxyRadio,
    private val page: ((SettingsItem.NativeHost, BluetoothProxyModel) -> SettingsItem.NativeView)? = null,
) : PaneDefinition, BluetoothProxyModel {
    override val id = "bluetooth-proxy"

    override val switch = PaneSwitch(
        file, BluetoothProxyPrefs.KEY_ENABLED, default = BluetoothProxyPrefs.DEFAULT_ENABLED, title = TITLE, detail = "For BLE sensors",
        objectId = "bluetooth_proxy", icon = "mdi:bluetooth", inSetup = true,
    )

    override val setupChoice get() = if (radio.unavailable() == ScanFailure.NO_BLE) null else switch.choice()

    /** The running pane, for the page. Set by [pane]; the device builds one per run. */
    @Volatile private var live: BluetoothProxyPane? = null

    /** The pane for the device's run. [device] is the shell's device thread. */
    fun pane(ctx: PaneContext, device: ScheduledExecutorService) = BluetoothProxyPane(ctx, radio, device).also { live = it }

    val on: Boolean get() = switch.on

    override fun phase(): ProxyPhase = ProxyPhase.of(switch.on, radio.unavailable(), live?.status())

    override fun status(): ProxyStatus? = live?.status()

    override fun turnBluetoothOn(): Boolean = radio.turnBluetoothOn()

    override fun turnLocationOn(): Boolean = radio.turnLocationOn()

    override fun settings() = listOf(
        SettingsSection(
            id, TITLE,
            items = listOf(page?.let { p -> SettingsItem.Native(id) { host -> p(host, this) } } ?: SettingsItem.Note { phase().entityText ?: "Off" }),
            unavailable = { if (radio.unavailable() == ScanFailure.NO_BLE) "it has no Bluetooth LE" else null },
        ),
    )

    companion object {
        const val TITLE = "Bluetooth proxy"
    }
}
