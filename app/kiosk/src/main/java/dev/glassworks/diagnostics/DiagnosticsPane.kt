/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.os.SystemClock
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.EspHomeLog
import sh.edm.esphome.SensorEntity
import sh.edm.esphome.TextSensorEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The panel's health in Home Assistant: CPU, temperature, memory, storage, Wi-Fi, uptime and why
 * the app last restarted. Every entity is a diagnostic one, and most are off until someone turns
 * them on.
 *
 * It samples once every [PERIOD_S] seconds on the [device] thread. A value goes to Home Assistant
 * only when it moved past its metric's band since the last one sent. The entities' getters only read
 * the last values, so the core's own state pushes cost nothing here.
 */
class DiagnosticsPane internal constructor(
    private val ctx: PaneContext,
    hasTemperature: Boolean,
    private val device: ScheduledExecutorService,
    private val probe: Probe,
) : Pane {
    /** [cpuThermalZone] is the frame's ([dev.glassworks.frame.Frame.cpuThermalZone]); null means no temperature entity. */
    constructor(ctx: PaneContext, cpuThermalZone: String?, isContentProcess: (String) -> Boolean, device: ScheduledExecutorService) :
        this(ctx, cpuThermalZone != null, device, AndroidProbe(ctx.context, cpuThermalZone, isContentProcess))

    override val id = "diagnostics"

    /** One sensor: its entity, its band, and how to get its value from the last two samples. */
    private class Metric(
        val objectId: String,
        val name: String,
        val icon: String = "",
        val unit: String = "",
        val accuracy: Int = 0,
        val deviceClass: String = "",
        /** `SensorStateClass`: 1 measurement, 0 none (timestamps). */
        val stateClass: Int = 1,
        val enabled: Boolean = false,
        val band: Float,
        val value: (prev: Sample?, cur: Sample) -> Float?,
    )

    private val metrics = listOfNotNull(
        Metric("cpu_usage", "CPU usage", "mdi:cpu-32-bit", "%", enabled = true, band = 1f) { p, c -> systemCpuPercent(p?.cpu, c.cpu) },
        Metric("app_cpu_usage", "App CPU usage", "mdi:cpu-32-bit", "%", accuracy = 1, band = 0.5f, value = ::appCpuPercent),
        if (hasTemperature) {
            Metric("cpu_temperature", "CPU temperature", unit = "°C", accuracy = 1, deviceClass = "temperature", enabled = true, band = 0.5f) { _, c -> c.cpuTemperature }
        } else null,
        Metric("memory_available", "RAM available", "mdi:memory", "MiB", deviceClass = "data_size", band = 8f) { _, c -> c.memoryAvailableBytes?.let { it / MIB } },
        Metric("app_memory", "App RAM", "mdi:memory", "MiB", deviceClass = "data_size", band = 4f) { _, c -> c.appPssBytes?.let { it / MIB } },
        Metric("storage_free", "Storage free", "mdi:harddisk", "GiB", accuracy = 2, deviceClass = "data_size", band = 0.05f) { _, c -> c.storageFreeBytes?.let { it / GIB } },
        Metric("wifi_signal", "Wi-Fi signal", unit = "dBm", deviceClass = "signal_strength", enabled = true, band = 2f) { _, c -> c.wifiRssi?.toFloat() },
        Metric("wifi_link_speed", "Wi-Fi link speed", "mdi:speedometer", "Mbit/s", deviceClass = "data_rate", band = 1f) { _, c -> c.wifiLinkSpeed?.toFloat() },
        // The timestamps change only on a restart or a clock fix, so they cost Home Assistant nothing.
        Metric("uptime", "Uptime", deviceClass = "timestamp", stateClass = 0, enabled = true, band = CLOCK_BAND_S) { _, c -> ((c.wallMs - c.elapsedMs) / 1000).toFloat() },
        Metric("app_uptime", "App uptime", deviceClass = "timestamp", stateClass = 0, band = CLOCK_BAND_S) { _, c ->
            ((c.wallMs - (c.elapsedMs - c.appStartElapsedMs)) / 1000).toFloat()
        },
        Metric("web_content_processes", "Web content processes", "mdi:tab", band = 1f) { _, c -> c.contentProcesses?.toFloat() },
    )

    /** What Home Assistant has, by object id. Absent: unknown. */
    private val values = ConcurrentHashMap<String, Float>()

    @Volatile private var restartReason: String? = null

    private val entities = metrics.map { m ->
        SensorEntity(
            m.objectId, m.name, m.icon, m.unit, m.accuracy, m.deviceClass, m.stateClass,
            EntityCategory.DIAGNOSTIC, disabledByDefault = !m.enabled, get = { values[m.objectId] },
        )
    } + TextSensorEntity(
        "last_restart_reason", "Last restart reason", "mdi:restart-alert", EntityCategory.DIAGNOSTIC,
        disabledByDefault = true, get = { restartReason },
    )

    override fun entities() = entities

    /** Only touched by [tick], on the device thread. */
    private var prev: Sample? = null
    private var ticks = 0
    private var costMs = 0L
    private var task: ScheduledFuture<*>? = null

    override fun start() {
        restartReason = exitReasonText(probe.lastExitReason())
        synchronized(this) {
            task?.cancel(false)
            task = device.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { EspHomeLog.w("diagnostics: sample failed", it) } }, 0, PERIOD_S, TimeUnit.SECONDS)
        }
    }

    override fun stop() {
        synchronized(this) {
            task?.cancel(false)
            task = null
        }
    }

    /** Takes one sample and publishes what moved past its band. Device thread. */
    internal fun tick() {
        val cpuStart = SystemClock.currentThreadTimeMillis()
        val cur = probe.sample()
        var changed = false
        for (m in metrics) {
            val old = values[m.objectId]
            val new = settle(old, m.value(prev, cur)?.let { roundTo(it, m.accuracy) }, m.band)
            if (new == old) continue
            if (new == null) values.remove(m.objectId) else values[m.objectId] = new
            changed = true
        }
        prev = cur
        if (changed) ctx.publishStates()
        val cost = SystemClock.currentThreadTimeMillis() - cpuStart
        costMs += cost
        ticks++
        // The pane's own cost, for the perf work (#105). Logged for the first three samples, then once an hour.
        if (ticks <= 3 || ticks % 60 == 0) EspHomeLog.i("diagnostics: sample $ticks took $cost ms CPU, $costMs ms in all")
    }

    private companion object {
        const val PERIOD_S = 60L
        const val MIB = 1024f * 1024f
        const val GIB = MIB * 1024f

        /** A Float holds a Unix time to 128 s, so a smaller band would only show rounding. */
        const val CLOCK_BAND_S = 120f
    }
}
