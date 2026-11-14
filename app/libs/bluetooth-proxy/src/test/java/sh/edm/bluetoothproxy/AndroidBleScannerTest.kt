package sh.edm.bluetoothproxy

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothLeScanner

/** [AndroidBleScanner] on Android 10, the S1's version: what it asks Android for, and what it reports back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AndroidBleScannerTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter = app.getSystemService(BluetoothManager::class.java).adapter
    private val heard = ArrayList<String>()
    private val listener = object : BleScanner.Listener {
        override fun onAdvertisement(address: Long, rssi: Int, data: ByteArray) { heard += "ad %012X %d %d".format(address, rssi, data.size) }
        override fun onFailed(failure: ScanFailure) { heard += "failed $failure" }
        override fun onReady() { heard += "ready" }
    }
    private lateinit var scanner: AndroidBleScanner

    @Before fun aPanelThatCanScan() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        shadowOf(adapter).setEnabled(true)
        scanner = AndroidBleScanner(app)
    }

    private fun androidScanner(): ShadowBluetoothLeScanner = shadowOf(adapter.bluetoothLeScanner)

    private fun stateReceivers() =
        shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED) }

    private fun broadcastState(state: Int) {
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun oneScanWithOneEmptyFilterSoItKeepsRunningWithTheScreenOff() {
        scanner.start(ScanPower.LOW_LATENCY, listener)
        val scan = androidScanner().activeScans.single()
        assertEquals("one filter, matching everything", listOf(ScanFilter.Builder().build()), scan.scanFilters())
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, scan.scanSettings()!!.scanMode)
        assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scan.scanSettings()!!.callbackType)
        assertEquals(0L, scan.scanSettings()!!.reportDelayMillis)
        scanner.stop()
        assertEquals(emptyList<Any>(), androidScanner().activeScans)
    }

    @Test fun aResultArrivesWithItsAddressAsANumber() {
        scanner.start(ScanPower.BALANCED, listener)
        val record = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .invoke(null, byteArrayOf(2, 1, 6) + ByteArray(59)) as ScanRecord
        val device = adapter.getRemoteDevice("C0:FF:EE:00:00:2A")
        androidScanner().activeScans.single().scanCallback()!!.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ScanResult(device, record, -61, 0))
        assertEquals(listOf("ad C0FFEE00002A -61 62"), heard)
    }

    @Test fun androidsFailureCodesMapToTheirReasons() {
        scanner.start(ScanPower.LOW_LATENCY, listener)
        androidScanner().activeScans.single().scanCallback()!!.onScanFailed(6)
        scanner.start(ScanPower.LOW_LATENCY, listener)
        androidScanner().activeScans.last().scanCallback()!!.onScanFailed(2)
        assertEquals(listOf("failed TOO_FREQUENT", "failed ANDROID_ERROR"), heard)
    }

    @Test fun aStoppedScansLateFailureIsIgnored() {
        scanner.start(ScanPower.LOW_LATENCY, listener)
        val first = androidScanner().activeScans.single().scanCallback()!!
        scanner.stop()
        scanner.start(ScanPower.LOW_LATENCY, listener)
        first.onScanFailed(1)
        assertEquals(emptyList<String>(), heard)
    }

    @Test fun bluetoothOffEndsTheScanAndOnSaysTryAgain() {
        scanner.start(ScanPower.LOW_LATENCY, listener)
        assertEquals("watching the adapter", 1, stateReceivers())
        broadcastState(BluetoothAdapter.STATE_TURNING_OFF)
        broadcastState(BluetoothAdapter.STATE_OFF)
        broadcastState(BluetoothAdapter.STATE_ON)
        assertEquals("once per scan, then ready", listOf("failed BLUETOOTH_OFF", "ready"), heard)
        scanner.release()
        broadcastState(BluetoothAdapter.STATE_ON)
        assertEquals("nothing after release", 2, heard.size)
        assertEquals("and no receiver left behind", 0, stateReceivers())
    }

    @Test fun unavailableSaysWhy() {
        assertNull(scanner.unavailable())
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        assertEquals(ScanFailure.LOCATION_OFF, scanner.unavailable())
        shadowOf(adapter).setEnabled(false)
        assertEquals(ScanFailure.BLUETOOTH_OFF, scanner.unavailable())
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(ScanFailure.NO_PERMISSION, scanner.unavailable())
        scanner.start(ScanPower.LOW_LATENCY, listener)
        assertEquals("a scan that can't run reports why and asks Android for nothing", listOf("failed NO_PERMISSION"), heard)
        assertEquals(emptyList<Any>(), androidScanner().activeScans)
    }
}
