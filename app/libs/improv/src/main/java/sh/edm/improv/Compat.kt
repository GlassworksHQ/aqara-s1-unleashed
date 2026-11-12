package sh.edm.improv

import android.os.Build

/**
 * The Android version differences this library handles, in one file. `ImprovBleServer` and
 * `WifiProvisioner` ask for a capability here instead of reading `Build.VERSION.SDK_INT`. The
 * library stays standalone, so this is its own copy and does not use the app's `dev.glassworks.platform`.
 */
internal val Compat = CompatApi(Build.VERSION.SDK_INT)

/** The capabilities for one API [level]; [Compat] is the running device's. Tests pass a level. */
internal class CompatApi(private val level: Int) {
    /** `WifiNetworkSuggestion` exists, and `addNetwork` is restricted for apps (Android 10). */
    val hasNetworkSuggestions: Boolean get() = level >= 29

    /** Exactly Android 10: the last version where an app with the overlay permission may still `addNetwork`. */
    val isAndroid10: Boolean get() = level == 29

    /** `WifiConfiguration.setSecurityParams` exists (Android 11). Below it, `requirePMF` is set by reflection. */
    val hasSecurityParams: Boolean get() = level >= 30

    /** `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` are runtime permissions (Android 12). */
    val hasBluetoothRuntimePermissions: Boolean get() = level >= 31

    /** An app may turn Bluetooth on with `BluetoothAdapter.enable()`. From Android 13 the call does nothing. */
    val appsCanEnableBluetooth: Boolean get() = level < 33

    /** `BluetoothGattServer.notifyCharacteristicChanged` takes the value as an argument (Android 13). */
    val hasValueNotify: Boolean get() = level >= 33
}
