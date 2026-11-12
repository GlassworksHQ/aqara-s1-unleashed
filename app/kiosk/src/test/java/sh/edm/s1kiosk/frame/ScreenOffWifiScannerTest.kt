package sh.edm.s1kiosk.frame

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.shadows.ShadowWifiManager
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

    @After fun noReadHook() { SlowReadWifiManager.onRead = null }

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

    @Test fun aConnectedPanelNeverScans() {
        shadowOf(wifi).setConnectionInfo(ShadowWifiInfo.newInstance().also { shadowOf(it).setSupplicantState(SupplicantState.COMPLETED) })
        scanner.start()
        idle(120)
        assertEquals(0, scans)
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

    /** #102: stop() lands while a step waits on the Wi-Fi service. That step must not scan. */
    @Config(shadows = [SlowReadWifiManager::class])
    @Test fun aStopDuringAReadOfTheWifiStateStartsNoScan() {
        scanner.start()
        idle(5)
        var stoppedInRead = false
        SlowReadWifiManager.onRead = {
            SlowReadWifiManager.onRead = null
            scanner.stop()
            stoppedInRead = true
        }
        idle(60)
        assertEquals("the step due at 10 s read the Wi-Fi state", true, stoppedInRead)
        assertEquals(0, scans)
    }

    /** #102: location granted after the service started. The service can then take the location type. */
    @Test fun theFirstScanRefusedForTheAppOpAsksForTheLocationTypeOncePerRun() {
        var asked = 0
        val s = ScreenOffWifiScanner(app, Looper.getMainLooper(), onForegroundOnly = { asked++ })
        shadowOf(wifi).setStartScanSucceeds(false)
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
        // What a foreground-only op reads as from the background (the shadow reads MODE_FOREGROUND as allowed).
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
        s.start()
        idle(10)
        assertEquals("a scan refused for the permission does not ask", 0, asked)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        idle(40)
        assertEquals("the first refusal for the app-op asks", 1, asked)
        idle(120)
        assertEquals("the next ones do not", 1, asked)
        s.stop()
        s.start()
        idle(120)
        assertEquals("a new run asks again", 2, asked)
        s.stop()
    }
}

/** Runs [onRead] inside the scanner's read of the Wi-Fi state, as a slow binder call would let another thread. */
@Implements(WifiManager::class)
class SlowReadWifiManager : ShadowWifiManager() {
    @Implementation
    override fun getConnectionInfo(): WifiInfo? {
        onRead?.invoke()
        return super.getConnectionInfo()
    }

    companion object {
        var onRead: (() -> Unit)? = null
    }
}
