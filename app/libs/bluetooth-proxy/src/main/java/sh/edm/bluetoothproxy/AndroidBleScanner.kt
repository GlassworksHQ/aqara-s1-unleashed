package sh.edm.bluetoothproxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import sh.edm.esphome.EspHomeLog

/**
 * [BleScanner] on Android's `BluetoothLeScanner`. Call it from one thread (the engine's).
 *
 * The scan passes one empty `ScanFilter`. It matches everything, but Android 10's ScanManager then
 * counts the scan as filtered, and a filtered scan keeps running with the screen off and isn't
 * suspended while location is off. Results still need location on (GattService).
 *
 * When Bluetooth turns off, Android 10 drops the scan without calling `onScanFailed`. So from the
 * first [start] until [release], the scanner watches the adapter: off ends the current scan with
 * [ScanFailure.BLUETOOTH_OFF], and on tells the listener it may retry now.
 */
class AndroidBleScanner(context: Context) : BleScanner {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val current = CurrentScan<ScanCallback>()
    @Volatile private var listener: BleScanner.Listener? = null
    private var watching = false

    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                    if (current.endAny() != null) {
                        EspHomeLog.i("Bluetooth proxy: Bluetooth turned off; the scan is gone")
                        listener?.onFailed(ScanFailure.BLUETOOTH_OFF)
                    }
                BluetoothAdapter.STATE_ON -> listener?.onReady()
            }
        }
    }

    /** Why a scan can't run now, or null if it can. */
    fun unavailable(): ScanFailure? = when {
        adapter == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> ScanFailure.NO_BLE
        Compat.scanPermissions.any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } -> ScanFailure.NO_PERMISSION
        !adapter.isEnabled -> ScanFailure.BLUETOOTH_OFF
        !locationEnabled() -> ScanFailure.LOCATION_OFF
        else -> null
    }

    /**
     * Turns Bluetooth on, as a "Turn on Bluetooth" button does. False where Android doesn't let apps
     * (Android 13 and later): open the system's Bluetooth settings there instead.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_ADMIN, install-time below Android 12
    @Suppress("DEPRECATION")
    fun turnBluetoothOn(): Boolean {
        if (!Compat.appsCanEnableBluetooth) return false
        val a = adapter ?: return false
        return a.isEnabled || runCatching { a.enable() }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun locationEnabled(): Boolean =
        if (Compat.hasIsLocationEnabled) {
            app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        } else {
            Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
                Settings.Secure.LOCATION_MODE_OFF
        }

    @SuppressLint("MissingPermission") // checked in unavailable()
    override fun start(power: ScanPower, listener: BleScanner.Listener) {
        this.listener = listener
        if (!watching) {
            app.registerReceiver(adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            watching = true
        }
        unavailable()?.let { listener.onFailed(it); return }
        val scanner = adapter?.bluetoothLeScanner ?: return listener.onFailed(ScanFailure.BLUETOOTH_OFF)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord?.bytes ?: return
                listener.onAdvertisement(parseAddress(result.device.address), result.rssi, record)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!current.end(this)) {
                    EspHomeLog.i("Bluetooth proxy: a stopped scan's failure ($errorCode) came late; ignored")
                    return
                }
                EspHomeLog.w("Bluetooth proxy: scan failed ($errorCode)")
                listener.onFailed(if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) ScanFailure.TOO_FREQUENT else ScanFailure.ANDROID_ERROR)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(
                when (power) {
                    ScanPower.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                    ScanPower.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                    ScanPower.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                },
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        // Current before startScan: Android may deliver its failure before startScan returns here.
        current.begin(cb)
        try {
            scanner.startScan(listOf(ScanFilter.Builder().build()), settings, cb)
            EspHomeLog.i("Bluetooth proxy: scanning ($power)")
        } catch (e: SecurityException) {
            if (current.end(cb)) listener.onFailed(ScanFailure.NO_PERMISSION)
        } catch (e: IllegalStateException) {
            if (current.end(cb)) listener.onFailed(ScanFailure.BLUETOOTH_OFF) // the adapter turned off in between
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        val cb = current.endAny() ?: return
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
            EspHomeLog.i("Bluetooth proxy: scan stopped")
        } catch (e: RuntimeException) {
            EspHomeLog.w("Bluetooth proxy: stopping the scan failed: ${e.message}") // Bluetooth went off: the scan is gone anyway
        }
    }

    override fun release() {
        if (!watching) return
        watching = false
        listener = null
        try { app.unregisterReceiver(adapterState) } catch (_: IllegalArgumentException) {}
    }

    private companion object {
        /** `ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY`: hidden on Android 10, public from 13. */
        const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
