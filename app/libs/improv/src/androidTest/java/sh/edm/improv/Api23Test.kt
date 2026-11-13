package sh.edm.improv

import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** The library on the device it runs on; CI runs it on an API 23 emulator (#151). */
@RunWith(AndroidJUnit4::class)
class Api23Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())

    @Test fun theBleServerBuildsAndReports() {
        val server = ImprovBleServer(ctx, main, object : ImprovBleServer.Listener {
            override fun onRpcWrite(chunk: ByteArray) {}
            override fun onClientConnected(address: String) {}
            override fun onClientDisconnected(address: String) {}
            override fun onAdvertising(ok: Boolean, detail: String) {}
        })
        assertTrue(server.capabilityReport().isNotEmpty())
    }

    @Test fun locationEnabledFollowsTheLocationSetting() {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val wifi = WifiProvisioner(ctx, main)
        val before = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        try {
            for (on in listOf(true, false)) {
                setGps(on)
                waitFor { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) == on }
                assertEquals("location ${if (on) "on" else "off"}", on, wifi.locationEnabled)
            }
        } finally {
            setGps(before)
        }
    }

    private fun setGps(on: Boolean) {
        val cmd = if (Compat.hasIsLocationEnabled) {
            "settings put secure location_mode ${if (on) 3 else 0}"
        } else {
            "settings put secure location_providers_allowed ${if (on) "+" else "-"}gps"
        }
        instrumentation.uiAutomation.executeShellCommand(cmd).use { FileInputStream(it.fileDescriptor).readBytes() }
    }

    private fun waitFor(ok: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (!ok() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("the location setting took effect", ok())
    }
}
