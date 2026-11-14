package dev.glassworks.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/** The diagnostics page's tiles (#212), as drawn: illustrative values only. */
class DiagnosticsPageTest {
    private val hour = mapOf(
        "cpu_usage" to listOf(18f, 23f), "app_cpu_usage" to listOf(9f), "cpu_temperature" to listOf(50.8f, 51.5f),
        "memory_available" to listOf(640f, 612f), "app_memory" to listOf(214f), "wifi_signal" to listOf(-57f, -58f),
        "wifi_link_speed" to listOf(72f),
    )
    private val sample = Sample(
        elapsedMs = 0, wallMs = 0, cpu = null, appTicks = emptyMap(), ticksPerSecond = 100, cpus = 4, contentProcesses = 3,
        cpuTemperature = null, memoryAvailableBytes = null, appPssBytes = null, storageFreeBytes = 4_424_000_000L,
        wifiRssi = null, wifiLinkSpeed = null, appStartElapsedMs = 270_000_000L - (2 * 60 + 10) * 60_000L,
        storageTotalBytes = 6_013_000_000L,
    )
    private val now = (3L * 24 + 4) * 3_600_000L + 120_000L

    @Test fun sixTilesWithTheirLastHour() {
        val t = DiagnosticsPage.tiles({ hour[it]?.lastOrNull() }, { hour[it].orEmpty() }, sample.copy(appStartElapsedMs = now - (2 * 60 + 10) * 60_000L), now, hasTemperature = true)
        assertEquals(listOf("CPU", "Temperature", "RAM", "Wi-Fi", "Storage", "Uptime"), t.map { it.name })
        assertEquals(listOf("23", "51.5", "612", "−58", "4.12", "3 d 4 h"), t.map { it.value })
        assertEquals(listOf("App 9 %", "CPU", "App 214 MiB", "Good · 72 Mbit/s", "of 5.6 GiB", "App 2 h 10 min"), t.map { it.detail })
        assertEquals(listOf(18f, 23f), t[0].history)
        assertEquals("storage is a meter, uptime has neither", 0.264f, t[4].meter!!, 0.001f)
        assertEquals(null, t[5].history)
    }

    @Test fun beforeTheFirstSampleTheTilesSayNothingYet() {
        val t = DiagnosticsPage.tiles({ null }, { emptyList() }, null, now, hasTemperature = false)
        assertEquals(listOf("CPU", "RAM", "Wi-Fi", "Storage", "Uptime"), t.map { it.name })
        assertEquals(listOf("—", "—", "—", "—"), t.take(4).map { it.value })
        assertEquals(null, t[3].meter)
    }

    @Test fun aReadingThatWentAwayShowsNothingButKeepsItsLine() {
        // CodeRabbit on #235: Wi-Fi dropped, yet the tile kept the hour's last -58 and "Good".
        val t = DiagnosticsPage.tiles({ id -> if (id == "wifi_signal") null else hour[id]?.lastOrNull() }, { hour[it].orEmpty() }, sample, now, hasTemperature = true)
        val wifi = t.first { it.name == "Wi-Fi" }
        assertEquals("—", wifi.value)
        assertEquals("72 Mbit/s", wifi.detail)
        assertEquals(listOf(-57f, -58f), wifi.history)
    }

    @Test fun theFooterAndTheWords() {
        assertEquals("Lines show the last hour. Last app restart: app updated. Web content processes: 3.",
            DiagnosticsPage.footer(android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED, 3))
        assertEquals("Android 10 can't say why: left out", "Lines show the last hour.", DiagnosticsPage.footer(null, null))
        assertEquals("a reason the words don't know still shows", "Lines show the last hour. Last app restart: unknown.", DiagnosticsPage.footer(999, null))
        assertEquals(listOf("Excellent", "Good", "Fair", "Weak"), listOf(-45, -58, -65, -80).map(DiagnosticsPage::wifiQuality))
        assertEquals(listOf("5 min", "2 h 10 min", "1 d 0 h"), listOf(5L, 130L, 24 * 60L).map { DiagnosticsPage.duration(it * 60_000) })
    }
}
