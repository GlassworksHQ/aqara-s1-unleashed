/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.net.wifi.WifiManager
import android.os.DeadSystemException
import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.ShadowWifiInfo
import java.io.File

/** The probe on Android 10, the S1's, with `/proc` and `/sys/class/thermal` in a temp tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AndroidProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()

    private fun probe(): AndroidProbe {
        val thermal = tmp.newFolder("thermal")
        File(thermal, "thermal_zone0").apply { mkdirs(); File(this, "type").writeText("cpu_thermal_zone\n"); File(this, "temp").writeText("47999\n") }
        return AndroidProbe(app, "cpu_thermal_zone", { false }, proc = tmp.newFolder("proc"), thermal = thermal)
    }

    private fun wifi(rssi: Int, linkSpeed: Int) {
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setRssi(rssi)
        shadowOf(info).setLinkSpeed(linkSpeed)
        shadowOf(app.getSystemService(WifiManager::class.java)).setConnectionInfo(info)
    }

    @Test fun temperatureIsInDegrees() {
        // The S1's zone reads millidegrees (47999 on 2026-11-12).
        assertEquals(47.999f, probe().sample().cpuTemperature!!, 0.0005f)
    }

    @Test fun storageIsWhatTheAppMayUseOnData() {
        ShadowStatFs.registerStats(Environment.getDataDirectory(), 1000, 600, 500)
        assertEquals(500L * ShadowStatFs.BLOCK_SIZE, probe().sample().storageFreeBytes)
    }

    @Test fun wifiOnlyCountsWhenConnected() {
        val p = probe()
        wifi(rssi = -55, linkSpeed = 72)
        p.sample().let { assertEquals(-55, it.wifiRssi); assertEquals(72, it.wifiLinkSpeed) }
        wifi(rssi = -126, linkSpeed = 1)
        p.sample().let { assertEquals(-126, it.wifiRssi); assertEquals(1, it.wifiLinkSpeed) }
        // WifiInfo.INVALID_RSSI and an unknown link speed: Android's values while Wi-Fi is down.
        wifi(rssi = -127, linkSpeed = -1)
        p.sample().let { assertNull(it.wifiRssi); assertNull(it.wifiLinkSpeed) }
    }

    @Test fun availableMemoryComesFromActivityManager() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = 512L shl 20 })
        assertEquals(512L shl 20, probe().sample().memoryAvailableBytes)
    }

    /** A framework restart (docs/research/framework-restart.md): the binder call throws, as getMemoryInfo rethrows it. */
    @Implements(ActivityManager::class)
    class DeadSystemActivityManager : ShadowActivityManager() {
        @Implementation
        override fun getMemoryInfo(outInfo: ActivityManager.MemoryInfo?) {
            throw RuntimeException(DeadSystemException())
        }
    }

    @Test @Config(shadows = [DeadSystemActivityManager::class])
    fun aDeadSystemServerLosesOnlyTheMemoryReading() {
        val s = probe().sample()
        assertNull(s.memoryAvailableBytes)
        assertEquals(47.999f, s.cpuTemperature!!, 0.0005f)
    }
}
