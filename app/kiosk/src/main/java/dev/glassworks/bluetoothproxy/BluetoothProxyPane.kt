/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import sh.edm.bluetoothproxy.BluetoothProxyEngine
import sh.edm.bluetoothproxy.ProxyStatus
import sh.edm.bluetoothproxy.ScanFailure
import sh.edm.bluetoothproxy.ScanPower
import sh.edm.esphome.ApiServer
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.SensorEntity
import sh.edm.esphome.TextSensorEntity
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The Bluetooth proxy pane (#121): while it is on, the panel is an ESPHome Bluetooth proxy. Home
 * Assistant finds it by itself and adds it as a Bluetooth scanner. The work is in the
 * `bluetooth-proxy` library's [BluetoothProxyEngine]; this pane gives it the device's server, and
 * adds two diagnostic entities.
 *
 * Starting and stopping changes what DeviceInfo says, so both ask the shell to rebuild the entity
 * list: Home Assistant reconnects and reads DeviceInfo again. The scan runs full time while Home
 * Assistant listens (#121: no Wi-Fi or Sendspin cost measured on the S1, 2026-11-14).
 */
class BluetoothProxyPane(
    private val ctx: PaneContext,
    private val radio: ProxyRadio,
    private val exec: ScheduledExecutorService,
) : Pane {
    override val id = "bluetooth-proxy"

    private val lock = Any()
    private var server: ApiServer? = null
    private var on = false
    private var engine: BluetoothProxyEngine? = null
    private var publisher: ScheduledFuture<*>? = null

    /** The engine's status, or null while no engine runs. */
    fun status(): ProxyStatus? = synchronized(lock) { engine }?.status

    fun phase(): ProxyPhase = ProxyPhase.of(synchronized(lock) { on }, radio.unavailable(), status())

    private val entities = listOf(
        SensorEntity(
            "bluetooth_advertisements", "Bluetooth advertisements", "mdi:bluetooth-audio", unit = "/min",
            category = EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { status()?.advertisementsPerMinute?.toFloat() },
        ),
        TextSensorEntity(
            "bluetooth_proxy_status", "Bluetooth proxy status", "mdi:bluetooth-settings",
            category = EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { phase().entityText },
        ),
    )

    override fun entities() = entities

    override fun bind(server: ApiServer) {
        synchronized(lock) {
            if (this.server === server) return
            this.server = server
            if (on) {
                engine?.close()
                engine = null
                attach()
            }
        }
    }

    override fun start() {
        synchronized(lock) {
            if (on) return
            on = true
            attach()
            publisher = exec.scheduleAtFixedRate({ ctx.publishStates() }, PUBLISH_S, PUBLISH_S, TimeUnit.SECONDS)
        }
        ctx.entitiesChanged()
    }

    override fun stop() {
        synchronized(lock) {
            if (!on) return
            on = false
            publisher?.cancel(false)
            publisher = null
            engine?.close() // also takes it out of the server: DeviceInfo has no proxy flags from now on
            engine = null
        }
        ctx.entitiesChanged()
    }

    /** With [lock]. Without Bluetooth LE the proxy isn't offered to Home Assistant at all. */
    private fun attach() {
        val s = server ?: return
        if (engine != null || radio.unavailable() == ScanFailure.NO_BLE) return
        engine = BluetoothProxyEngine(s, radio.newScanner(), ScanPower.LOW_LATENCY).also { s.bluetooth = it }
    }

    private companion object {
        /** Sends the entities' states if they changed. The advertisements per minute change once a minute at most. */
        const val PUBLISH_S = 15L
    }
}
