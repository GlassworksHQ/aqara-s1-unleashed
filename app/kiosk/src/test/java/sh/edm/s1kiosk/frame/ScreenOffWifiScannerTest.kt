package sh.edm.s1kiosk.frame

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWifiInfo
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ScreenOffWifiScannerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val scanner = ScreenOffWifiScanner(app, Looper.getMainLooper())
    private var scans = 0

    @Before fun panelAsleepWithWifiDown() {
        @Suppress("DEPRECATION") wifi.setWifiEnabled(true)
        shadowOf(wifi).setConnectionInfo(ShadowWifiInfo.newInstance().also { shadowOf(it).setSupplicantState(SupplicantState.DISCONNECTED) })
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        shadowOf(wifi).setScanResults(listOf(ScanResult()))
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { scans++ }
        }, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun scannerFilters() = shadowOf(app).registeredReceivers
        .map { it.intentFilter }
        .filter { it.hasAction(Intent.ACTION_SCREEN_OFF) }

    @Test fun startRegistersForScreenAndWifiChangesOnceAndStopUnregisters() {
        scanner.start()
        scanner.start()
        val f = scannerFilters().single()
        for (a in listOf(Intent.ACTION_SCREEN_ON, WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            assertEquals(a, true, f.hasAction(a))
        }
        scanner.stop()
        assertEquals(0, scannerFilters().size)
    }

    /** A service restart while asleep and offline gets no broadcast; the scanner still has to start. */
    @Test fun aPanelAlreadyAsleepAndOfflineAtStartScansAfterTheDelayOnly() {
        scanner.start()
        idle(9)
        assertEquals(0, scans)
        idle(1)
        assertEquals(1, scans)
        scanner.stop()
    }

    @Test fun stopCancelsThePendingScanAndStartAgainWorks() {
        scanner.start()
        idle(5)
        scanner.stop()
        idle(60)
        assertEquals(0, scans)
        scanner.start()
        idle(10)
        assertEquals(1, scans)
        scanner.stop()
    }
}
