package sh.edm.s1kiosk

import android.Manifest
import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.app.MissingForegroundServiceTypeException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import dev.glassworks.platform.PlatformApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWifiInfo
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows
import java.time.Duration

/**
 * #93 on Android 14: the service's real startForeground calls, MainActivity's permission result,
 * and the refusals Android 14 throws as IllegalStateException. #102 on Android 10: a location grant
 * from outside the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class KioskForegroundWiringTest {
    private val app = RuntimeEnvironment.getApplication()
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private var service: ServiceController<KioskService>? = null

    @After fun tearDown() { service?.destroy() }

    @Test fun theServiceStartsWithoutTheMicAndTakesItOnTheNextStartAfterTheGrant() {
        val s = Robolectric.buildService(KioskService::class.java).create().also { service = it }
        assertEquals(device, s.get().foregroundServiceType)

        s.startCommand(0, 1)
        assertEquals(device, s.get().foregroundServiceType)

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        s.startCommand(0, 2)
        assertEquals(mic, s.get().foregroundServiceType)
    }

    @Test fun aGrantInTheStartupDialogStartsTheServiceAgain() = GeckoPinHarness().use { h ->
        while (shadowOf(app).nextStartedService != null) Unit
        h.activity.onRequestPermissionsResult(1, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertEquals(ComponentName(app, KioskService::class.java), shadowOf(app).nextStartedService?.component)

        h.activity.onRequestPermissionsResult(1, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(PackageManager.PERMISSION_DENIED))
        assertNull(shadowOf(app).nextStartedService)
    }

    /**
     * #102: location granted after the service started, as a device owner's setPermissionGrantState
     * does. The screen-off scan is refused for the app-op, and the service takes the location type.
     */
    @Config(sdk = [29])
    @Test fun aScanRefusedForTheAppOpAfterALocationGrantAddsTheLocationType() {
        val wifi = app.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") wifi.setWifiEnabled(true)
        shadowOf(wifi).setConnectionInfo(ShadowWifiInfo.newInstance().also { shadowOf(it).setSupplicantState(SupplicantState.DISCONNECTED) })
        shadowOf(wifi).setStartScanSucceeds(false)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)

        val s = Robolectric.buildService(KioskService::class.java).create().also { service = it }
        assertEquals(mic, s.get().foregroundServiceType)

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val scanner = ShadowLooper.getAllLoopers().single { it.thread.name == "s1-wifi-scan" }
        shadowOf(scanner).idleFor(Duration.ofSeconds(10))
        ShadowLooper.idleMainLooper()
        assertEquals(mic or location, s.get().foregroundServiceType)
    }

    /** Android 14 throws these as ServiceStartNotAllowedException, an IllegalStateException. */
    @Test fun android14RefusalsThatAreNotSecurityExceptionsFallBackToo() {
        val refusals = mapOf<Int, RuntimeException>(
            (mic or location) to ForegroundServiceStartNotAllowedException("location and microphone from the background"),
            mic to InvalidForegroundServiceTypeException("microphone not allowed"),
            location to MissingForegroundServiceTypeException("location missing"),
        )
        val logged = mutableListOf<Pair<Int, RuntimeException>>()
        val fg = KioskForeground(PlatformApi(34), { t, e -> logged += t to e }) { t -> refusals[t]?.let { throw it } }
        assertEquals(device, fg.promote(micGranted = true, locationGranted = true))
        assertEquals(listOf(mic or location, mic, location), logged.map { it.first })
        assertSame(refusals[mic or location], logged.first().second)
    }

    @Test fun aFirstStartRefusedForEveryTypeThrowsTheLastRefusal() {
        val last = InvalidForegroundServiceTypeException("connectedDevice not allowed")
        val fg = KioskForeground(PlatformApi(34), { _, _ -> }) { t -> throw if (t == device) last else InvalidForegroundServiceTypeException("no") }
        assertSame(last, assertThrows(InvalidForegroundServiceTypeException::class.java) { fg.promote(micGranted = false, locationGranted = false) })
    }
}
