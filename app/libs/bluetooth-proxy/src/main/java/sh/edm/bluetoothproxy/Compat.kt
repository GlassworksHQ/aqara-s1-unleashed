package sh.edm.bluetoothproxy

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The Android version differences this library handles, in one file. The library stays standalone,
 * so this is its own copy and does not use the app's `dev.glassworks.platform`.
 */
internal val Compat = CompatApi(Build.VERSION.SDK_INT)

/** The capabilities for one API [level]; [Compat] is the running device's. Tests pass a level. */
internal class CompatApi(private val level: Int) {
    /** `LocationManager.isLocationEnabled` exists (Android 9). Below it, the location mode setting says. */
    @get:ChecksSdkIntAtLeast(api = 28)
    val hasIsLocationEnabled: Boolean get() = level >= 28

    /**
     * The runtime permissions a scan needs to get results: COARSE location up to Android 9, FINE from
     * Android 10 (GattService `checkCallerHasFineLocation`), plus `BLUETOOTH_SCAN` from Android 12.
     */
    @get:SuppressLint("InlinedApi") // a string constant, asked for only on Android 12+
    val scanPermissions: List<String> get() = when {
        level >= 31 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        level >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
