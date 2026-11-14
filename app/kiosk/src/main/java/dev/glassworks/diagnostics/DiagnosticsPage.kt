/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import dev.glassworks.pane.SettingsItem
import java.util.Locale

/**
 * The diagnostics pane's settings page (#212): six tiles, three to a row, with each one's last hour. Pure,
 * for tests: [current] is [DiagnosticsPane.current], [hour] is [DiagnosticsPane.lastHour], [sample] its
 * last sample, [nowElapsedMs] the clock now. A value is the last sample's, so a reading that went away
 * shows "—" while its line keeps the hour.
 */
object DiagnosticsPage {
    /** CPU, temperature (when the frame has it), RAM, Wi-Fi, storage and uptime. */
    fun tiles(current: (String) -> Float?, hour: (String) -> List<Float>, sample: Sample?, nowElapsedMs: Long, hasTemperature: Boolean): List<SettingsItem.Tile> {
        fun last(id: String) = current(id)
        val rssi = last("wifi_signal")
        val free = sample?.storageFreeBytes
        val total = sample?.storageTotalBytes?.takeIf { it > 0 }
        return listOfNotNull(
            SettingsItem.Tile("CPU", number(last("cpu_usage"), 0), "%", last("app_cpu_usage")?.let { "App ${number(it, 0)} %" }.orEmpty(), hour("cpu_usage")),
            if (hasTemperature) SettingsItem.Tile("Temperature", number(last("cpu_temperature"), 1), "°C", "CPU", hour("cpu_temperature")) else null,
            SettingsItem.Tile("RAM", number(last("memory_available"), 0), "MiB free", last("app_memory")?.let { "App ${number(it, 0)} MiB" }.orEmpty(), hour("memory_available")),
            SettingsItem.Tile(
                "Wi-Fi", rssi?.let { number(it, 0).replace('-', '−') } ?: NONE, "dBm",
                listOfNotNull(rssi?.let { wifiQuality(it.toInt()) }, last("wifi_link_speed")?.let { "${number(it, 0)} Mbit/s" }).joinToString(" · "),
                hour("wifi_signal"),
            ),
            SettingsItem.Tile(
                "Storage", number(free?.let { it / GIB }, 2), "GiB free", total?.let { "of ${number(it / GIB, 1)} GiB" }.orEmpty(),
                meter = if (free != null && total != null) 1f - free.toFloat() / total else null,
            ),
            SettingsItem.Tile(
                "Uptime", duration(nowElapsedMs), "",
                sample?.let { "App ${duration(nowElapsedMs - it.appStartElapsedMs)}" }.orEmpty(),
            ),
        )
    }

    /**
     * The line under the tiles: what the lines are, why the app last restarted ([exitReason], an
     * `ApplicationExitInfo.REASON_*`) and the web content processes. No reason (Android 10 can't say,
     * ApplicationExitInfo is Android 11) leaves it out; a reason the words don't know still shows.
     */
    fun footer(exitReason: Int?, contentProcesses: Int?): String = listOfNotNull(
        "Lines show the last hour.",
        exitReason?.let { "Last app restart: ${exitReasonText(it)}." },
        contentProcesses?.let { "Web content processes: $it." },
    ).joinToString(" ")

    /** A Wi-Fi signal in words, by fixed bands of its RSSI in dBm. */
    fun wifiQuality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Good"
        rssi >= -70 -> "Fair"
        else -> "Weak"
    }

    /** A length of time the way the page says it: `3 d 4 h`, `2 h 10 min`, `5 min`. */
    fun duration(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        val d = min / (24 * 60); val h = min / 60 % 24; val m = min % 60
        return when {
            d > 0 -> "$d d $h h"
            h > 0 -> "$h h $m min"
            else -> "$m min"
        }
    }

    private fun number(v: Float?, decimals: Int) = v?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: NONE

    private const val NONE = "—"
    private const val GIB = 1024f * 1024f * 1024f
}
