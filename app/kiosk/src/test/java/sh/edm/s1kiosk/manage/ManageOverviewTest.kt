package sh.edm.s1kiosk.manage

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Process
import android.os.SystemClock
import dev.glassworks.manage.Tile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.util.ReflectionHelpers
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.time.Duration

/** #263: the Manage overview's uptime is the app's, and its Wi-Fi tile says Connected while Wi-Fi is up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class ManageOverviewTest {
    private val app = RuntimeEnvironment.getApplication()
    private val tokens = HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = 0 to ""
        override fun get(url: String, maxBytes: Int) = 0 to ""
    })
    private val manage = S1Manage(app, GlassworksRuntime(AqaraS1Plus()), SignInServer(0, { null }, tokens, { _, _ -> }), tokens, KioskPrefs(app))

    private fun wifi() = manage.status().tiles.single { it.label == "Wi-Fi" }

    @Test fun theUptimeIsTheAppsNotTheDevices() {
        ShadowSystemClock.advanceBy(Duration.ofHours(9))
        // The app started 28 minutes ago; the device 9 hours ago.
        ReflectionHelpers.setStaticField(Process::class.java, "sStartElapsedRealtime", SystemClock.elapsedRealtime() - Duration.ofMinutes(28).toMillis())
        assertEquals(Duration.ofMinutes(28).toMillis(), manage.status().uptimeMs)
    }

    @Config(sdk = [34])
    @Test fun wifiThatHidesItsNameReadsConnected() {
        val cm = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        cm.clearAllNetworks()
        val n = ShadowNetwork.newInstance(1) // Robolectric's Wi-Fi network id, the one it reports as active
        val ni = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED)
        cm.addNetwork(n, ni)
        cm.setActiveNetworkInfo(ni)
        val info = ShadowWifiInfo.newInstance().also { shadowOf(it).setSSID(WifiManager.UNKNOWN_SSID); shadowOf(it).setRssi(-55) }
        cm.setNetworkCapabilities(n, ShadowNetworkCapabilities.newInstance().also { c ->
            shadowOf(c).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(c).setTransportInfo(info)
        })
        // What getConnectionInfo gives an app without the location permission on newer Android.
        shadowOf(app.getSystemService(WifiManager::class.java)).setConnectionInfo(ShadowWifiInfo.newInstance().also { shadowOf(it).setNetworkId(-1) })
        val t = wifi()
        assertEquals("Connected", t.value)
        assertEquals("−55 dBm", t.detail)
        assertEquals(Tile.Tone.OK, t.tone)

        cm.clearAllNetworks()
        assertEquals("Not connected", wifi().value)
    }
}
