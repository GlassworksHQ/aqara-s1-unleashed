package sh.edm.s1kiosk

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.app.MissingForegroundServiceTypeException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows

/**
 * #93 on Android 14: the service's real startForeground calls, MainActivity's permission result,
 * and the refusals Android 14 throws as IllegalStateException.
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
