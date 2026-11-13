/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cpuLineCountsIowaitAsIdleAndLeavesOutGuest() {
        // The S1's first line, 2026-11-12.
        val t = parseCpuLine("cpu  904646 24618 337114 2894570 1021 0 4302 0 0 0\ncpu0 1 2 3 4 5 6 7 8 9 10\n")
        assertEquals(CpuTimes(total = 904646L + 24618 + 337114 + 2894570 + 1021 + 0 + 4302 + 0, idle = 2894570L + 1021), t)
        assertNull(parseCpuLine("intr 1 2 3"))
        assertNull(parseCpuLine(""))
    }

    @Test fun processTicksCountFromTheLastParenthesis() {
        // The S1's main process names itself "AndroidUI"; a name may hold spaces and ")".
        val rest = "S 10662 10662 0 0 -1 1077952832 162365 99509 0 0 12578 592 299 317 20 0 91 0 1018723"
        assertEquals(12578L + 592, parseProcessTicks("18632 (AndroidUI) $rest"))
        assertEquals(12578L + 592, parseProcessTicks("18632 (a b) c)) $rest"))
        assertNull(parseProcessTicks("18632 (x) S 1 2"))
    }

    @Test fun pssIsInBytes() {
        assertEquals(51234L * 1024, parsePss("00000000-ffffffff ---p 00000000 00:00 0 [rollup]\nRss:  80000 kB\nPss:  51234 kB\n"))
        assertNull(parsePss("Rss: 1 kB\n"))
    }

    @Test fun thermalZoneIsFoundByType() {
        for ((i, type) in listOf("cpu_thermal_zone", "gpu_thermal_zone", "ddr_thermal_zone").withIndex()) {
            File(tmp.root, "thermal_zone$i").apply { mkdirs(); File(this, "type").writeText("$type\n") }
        }
        File(tmp.root, "cooling_device0").mkdirs()
        assertEquals(File(tmp.root, "thermal_zone1/temp"), findThermalZone(tmp.root, "gpu_thermal_zone"))
        assertNull(findThermalZone(tmp.root, "soc"))
        assertNull(findThermalZone(File(tmp.root, "missing"), "cpu_thermal_zone"))
    }

    @Test fun systemCpuIsTheBusyShareOfTheDelta() {
        assertEquals(25f, systemCpuPercent(CpuTimes(1000, 800), CpuTimes(1400, 1100))!!, 0.001f)
        assertNull(systemCpuPercent(null, CpuTimes(1, 1)))
        assertNull(systemCpuPercent(CpuTimes(5, 5), CpuTimes(5, 5)))
    }

    @Test fun appCpuIsAShareOfAllCores() {
        val a = sample(elapsedMs = 0, ticks = mapOf(1 to 100L, 2 to 50L, 3 to 10L))
        // 60 s at 100 ticks/s on 4 cores is 24000 ticks. Pid 1 used 240, pid 4 is new with 120, pids 2 and 3 ended.
        val b = sample(elapsedMs = 60_000, ticks = mapOf(1 to 340L, 4 to 120L))
        assertEquals(1.5f, appCpuPercent(a, b)!!, 0.001f)
        // Pid 2 now belongs to a new process with 20 ticks, below the old 50: all 20 count (240 + 20 + 700).
        assertEquals(4f, appCpuPercent(a, sample(elapsedMs = 60_000, ticks = mapOf(1 to 340L, 2 to 20L, 4 to 700L)))!!, 0.001f)
        assertNull(appCpuPercent(null, b))
        assertNull(appCpuPercent(a, sample(elapsedMs = 60_000, ticks = emptyMap())))
    }

    @Test fun settleKeepsTheOldValueInsideTheBand() {
        assertEquals(40f, settle(40f, 41.9f, 2f))
        assertEquals(42f, settle(40f, 42f, 2f))
        assertEquals(38f, settle(40f, 38f, 2f))
        assertEquals(7f, settle(null, 7f, 2f))
        assertNull(settle(40f, null, 2f))
    }

    @Test fun exitReasonsReadAsWords() {
        assertEquals("unknown", exitReasonText(null))
        assertEquals("crash", exitReasonText(ApplicationExitInfo.REASON_CRASH))
        assertEquals("low memory", exitReasonText(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("unknown", exitReasonText(ApplicationExitInfo.REASON_UNKNOWN))
    }

    private fun sample(elapsedMs: Long, ticks: Map<Int, Long>) = Sample(
        elapsedMs = elapsedMs, wallMs = 0, cpu = null, appTicks = ticks, ticksPerSecond = 100, cpus = 4,
        contentProcesses = null, cpuTemperature = null, memoryAvailableBytes = null, appPssBytes = null,
        storageFreeBytes = null, wifiRssi = null, wifiLinkSpeed = null, appStartElapsedMs = 0,
    )
}
