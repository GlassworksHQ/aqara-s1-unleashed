/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.app.Application
import dev.glassworks.pane.PaneContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.ProtoMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticsPaneTest {
    /** A real executor that also records each repeating task's first delay and period, in ms. */
    private class RecordingExecutor : ScheduledThreadPoolExecutor(1) {
        val repeating = mutableListOf<Pair<Long, Long>>()

        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            synchronized(repeating) { repeating += unit.toMillis(initialDelay) to unit.toMillis(delay) }
            return super.scheduleWithFixedDelay(command, initialDelay, delay, unit)
        }
    }

    private val exec = RecordingExecutor()
    private var published = 0
    private val samples = ArrayDeque<Sample>()
    private var exitReason: Int? = null

    private val probe = object : Probe {
        override fun sample() = samples.removeFirst()
        override fun lastExitReason() = exitReason
    }

    private fun pane(hasTemperature: Boolean = true) = DiagnosticsPane(
        PaneContext(
            RuntimeEnvironment.getApplication(), { error("no prefs") },
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = { published++ }, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        ),
        hasTemperature, exec, probe,
    )

    @After fun shutdown() { exec.shutdownNow() }

    @Test fun everyEntityIsDiagnosticAndOnlyTheCommonOnesAreOn() {
        val entities = pane().entities()
        assertTrue(entities.all { it.category == EntityCategory.DIAGNOSTIC })
        assertEquals(setOf("cpu_usage", "cpu_temperature", "wifi_signal", "uptime"), entities.filterNot { it.disabledByDefault }.map { it.objectId }.toSet())
        assertEquals(12, entities.size)
    }

    @Test fun aFrameWithoutAThermalZoneHasNoTemperatureEntity() {
        val ids = pane(hasTemperature = false).entities().map { it.objectId }
        assertFalse("cpu_temperature" in ids)
        assertEquals(11, ids.size)
    }

    @Test fun cpuNeedsTwoSamplesAndTheRestShowAfterTheFirst() {
        val p = pane()
        samples += sample(0, CpuTimes(1000, 800), ticks = 100)
        p.tick()
        assertNull(value(p, "cpu_usage"))
        assertNull(value(p, "app_cpu_usage"))
        assertEquals(48f, value(p, "cpu_temperature"))
        assertEquals(-55f, value(p, "wifi_signal"))
        assertEquals(72f, value(p, "wifi_link_speed"))
        assertEquals(512f, value(p, "memory_available"))
        assertEquals(300f, value(p, "app_memory"))
        assertEquals(1.5f, value(p, "storage_free"))
        assertEquals(2f, value(p, "web_content_processes"))
        assertEquals(((WALL_MS - 3_600_000) / 1000).toFloat(), value(p, "uptime"))
        assertEquals(((WALL_MS - 600_000) / 1000).toFloat(), value(p, "app_uptime"))
        assertEquals(1, published)

        samples += sample(60_000, CpuTimes(1400, 1100), ticks = 220)
        p.tick()
        assertEquals(25f, value(p, "cpu_usage"))
        // 120 ticks in 60 s at 100 ticks/s on 4 cores.
        assertEquals(0.5f, value(p, "app_cpu_usage"))
        assertEquals(2, published)
    }

    @Test fun changesInsideTheBandsAreNotPublished() {
        val p = pane()
        samples += sample(0, CpuTimes(1000, 800), ticks = 100)
        samples += sample(60_000, CpuTimes(1400, 1100), ticks = 100)
        samples += sample(120_000, CpuTimes(1800, 1399), ticks = 100, temperature = 48.3f, rssi = -56)
        repeat(3) { p.tick() }
        assertEquals(2, published)
        assertEquals(25f, value(p, "cpu_usage"))
        assertEquals(48f, value(p, "cpu_temperature"))
        assertEquals(-55f, value(p, "wifi_signal"))

        samples += sample(180_000, CpuTimes(2200, 1700), ticks = 100, temperature = 49f, rssi = null)
        p.tick()
        assertEquals(3, published)
        assertEquals(49f, value(p, "cpu_temperature"))
        assertNull("Wi-Fi gone is unknown, not the last value", value(p, "wifi_signal"))
    }

    /** #67: turned off, its entities have no state; turned on, CPU starts again from a fresh first sample. */
    @Test fun offClearsTheValuesAndOnStartsAfresh() {
        val p = pane()
        samples += sample(0, CpuTimes(1000, 800), ticks = 100)
        samples += sample(60_000, CpuTimes(1400, 1100), ticks = 220)
        repeat(2) { p.tick() }
        assertEquals(25f, value(p, "cpu_usage"))
        val before = published
        p.stop()
        assertNull(value(p, "cpu_usage"))
        assertNull(value(p, "uptime"))
        assertEquals("Home Assistant hears it", before + 1, published)
        samples += sample(600_000, CpuTimes(9000, 1100), ticks = 220)
        p.start() // its first sample runs on the device thread at once
        val sampled = CountDownLatch(1)
        exec.schedule({ sampled.countDown() }, 0, TimeUnit.MILLISECONDS)
        assertTrue(sampled.await(5, TimeUnit.SECONDS))
        assertNull("no CPU across the time it was off", value(p, "cpu_usage"))
        assertEquals(48f, value(p, "cpu_temperature"))
        p.stop()
    }

    @Test fun startReadsTheRestartReasonAndSamplesRightAway() {
        val p = pane()
        val sampled = CountDownLatch(1)
        samples += sample(0, CpuTimes(1, 1), ticks = 1)
        p.start()
        exec.schedule({ sampled.countDown() }, 0, TimeUnit.MILLISECONDS)
        assertTrue(sampled.await(5, TimeUnit.SECONDS))
        assertEquals("unknown", text(p, "last_restart_reason"))
        assertEquals(48f, value(p, "cpu_temperature"))
        assertEquals("one sample now, then one a minute", listOf(0L to 60_000L), exec.repeating)
        p.stop()
    }

    private fun entity(p: DiagnosticsPane, id: String): ApiEntity = p.entities().first { it.objectId == id }

    private fun value(p: DiagnosticsPane, id: String): Float? {
        val m = ProtoMessage(entity(p, id).state()!!.toByteArray())
        return if (m.bool(3)) null else m.float(2)
    }

    private fun text(p: DiagnosticsPane, id: String): String? {
        val m = ProtoMessage(entity(p, id).state()!!.toByteArray())
        return if (m.bool(3)) null else m.string(2)
    }

    private fun sample(elapsedMs: Long, cpu: CpuTimes, ticks: Long, temperature: Float = 48f, rssi: Int? = -55) = Sample(
        elapsedMs = 3_600_000 + elapsedMs, wallMs = WALL_MS + elapsedMs, cpu = cpu,
        appTicks = mapOf(4242 to ticks), ticksPerSecond = 100, cpus = 4, contentProcesses = 2,
        cpuTemperature = temperature, memoryAvailableBytes = 512L shl 20, appPssBytes = 300L shl 20,
        storageFreeBytes = 1536L shl 20, wifiRssi = rssi, wifiLinkSpeed = 72, appStartElapsedMs = 3_000_000,
    )

    private companion object {
        /** A time whose boot time comes out 128 s off when the milliseconds are divided as a Float. */
        const val WALL_MS = 1_800_000_081_000L
    }
}
