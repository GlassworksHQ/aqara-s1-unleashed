/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import dev.glassworks.platform.Platform
import java.io.File

/** Where the pane gets its readings. */
interface Probe {
    fun sample(): Sample

    /** Why the app's main process last ended, as an `ApplicationExitInfo.REASON_*`. Null when the platform doesn't say. */
    fun lastExitReason(): Int?
}

/**
 * Reads the running Android: files in `/proc` and `/sys`, and a few system services. No shell
 * commands and no root. `/proc/stat` needs SELinux to let the app read it: the S1's is permissive.
 * Where a read fails, that field is null.
 */
class AndroidProbe(
    private val context: Context,
    private val cpuThermalZone: String?,
    private val isContentProcess: (String) -> Boolean,
    private val proc: File = File("/proc"),
    private val thermal: File = File("/sys/class/thermal"),
) : Probe {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK)
    private val cpus = Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toInt()
    private val temperature by lazy { cpuThermalZone?.let { findThermalZone(thermal, it) } }

    override fun sample(): Sample {
        // Android 5.1 and later list only this app's own processes.
        val processes = runCatching { am?.runningAppProcesses }.getOrNull().orEmpty()
        val pids = processes.map { it.pid }
        @Suppress("DEPRECATION") // Its replacement, NetworkCapabilities.transportInfo, is Android 12.
        val wifiInfo = runCatching { wifi?.connectionInfo }.getOrNull()
        return Sample(
            elapsedMs = SystemClock.elapsedRealtime(),
            wallMs = System.currentTimeMillis(),
            cpu = read(File(proc, "stat"))?.let(::parseCpuLine),
            appTicks = pids.mapNotNull { pid -> read(File(proc, "$pid/stat"))?.let(::parseProcessTicks)?.let { pid to it } }.toMap(),
            ticksPerSecond = ticksPerSecond,
            cpus = cpus,
            contentProcesses = processes.takeIf { it.isNotEmpty() }?.count { isContentProcess(it.processName) },
            cpuTemperature = temperature?.let(::read)?.trim()?.toIntOrNull()?.let { it / 1000f },
            memoryAvailableBytes = runCatching { am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).availMem } }.getOrNull(),
            appPssBytes = totalOrNull(pids.map { read(File(proc, "$it/smaps_rollup"))?.let(::parsePss) }),
            storageFreeBytes = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull(),
            // -127 is WifiInfo.INVALID_RSSI and -1 an unknown link speed. Both mean Wi-Fi is not connected.
            wifiRssi = wifiInfo?.rssi?.takeIf { it in -126..-1 },
            wifiLinkSpeed = wifiInfo?.linkSpeed?.takeIf { it > 0 },
            appStartElapsedMs = Process.getStartElapsedRealtime(),
            storageTotalBytes = runCatching { StatFs(Environment.getDataDirectory().path).totalBytes }.getOrNull(),
        )
    }

    override fun lastExitReason(): Int? = runCatching { Platform.lastExitReason(context, context.packageName) }.getOrNull()

    private fun read(f: File): String? = runCatching { f.readText() }.getOrNull()
}
