/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress

/**
 * #263: Wi-Fi is connected when `ConnectivityManager` says so, whatever `WifiManager` hides. On newer
 * Android (34 here, as on the Pixel Tablet) its connection info has no network for an app without the
 * location permission; the S1's Android 10 (29) still gives it.
 */
@RunWith(RobolectricTestRunner::class)
class WifiLinkTest {
    private val app = RuntimeEnvironment.getApplication()
    private val cm = shadowOf(app.getSystemService(ConnectivityManager::class.java))
    private val wifiManager = shadowOf(app.getSystemService(WifiManager::class.java))

    @Before fun noNetworks() = cm.clearAllNetworks()

    private fun wifiInfo(ssid: String, rssi: Int, speed: Int, networkId: Int = 1): WifiInfo = ShadowWifiInfo.newInstance().also {
        shadowOf(it).setSSID(ssid)
        shadowOf(it).setRssi(rssi)
        shadowOf(it).setLinkSpeed(speed)
        shadowOf(it).setNetworkId(networkId)
    }

    /** A connected network of [transport], 192.0.2.40 on it, and the active one. */
    private fun network(transport: Int, info: WifiInfo? = null): Network {
        val type = if (transport == NetworkCapabilities.TRANSPORT_WIFI) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE
        // Robolectric's own ids for its Wi-Fi and mobile networks: the one it reports as active.
        val n = ShadowNetwork.newInstance(if (type == ConnectivityManager.TYPE_WIFI) 1 else 2)
        val ni = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)
        cm.addNetwork(n, ni)
        cm.setActiveNetworkInfo(ni)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        if (info != null) shadowOf(caps).setTransportInfo(info)
        cm.setNetworkCapabilities(n, caps)
        val address = ReflectionHelpers.callConstructor(
            LinkAddress::class.java, ClassParameter.from(InetAddress::class.java, InetAddress.getByName("192.0.2.40")), ClassParameter.from(Int::class.java, 24),
        )
        val lp = LinkProperties().also { ReflectionHelpers.callInstanceMethod<Any>(it, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address)) }
        cm.setLinkProperties(n, lp)
        return n
    }

    @Config(sdk = [34])
    @Test fun newerAndroidConnectedWithTheNameHiddenIsConnected() {
        network(NetworkCapabilities.TRANSPORT_WIFI, wifiInfo(WifiManager.UNKNOWN_SSID, -55, 300))
        wifiManager.setConnectionInfo(wifiInfo(WifiManager.UNKNOWN_SSID, -127, -1, networkId = -1))
        assertEquals(WifiLink(ssid = null, rssi = -55, linkSpeedMbps = 300, ipv4 = "192.0.2.40"), WifiLink.of(app))
    }

    @Config(sdk = [34])
    @Test fun newerAndroidShowsTheNameWhenItGivesIt() {
        network(NetworkCapabilities.TRANSPORT_WIFI, wifiInfo("Example-WiFi", -62, 144))
        assertEquals("Example-WiFi", WifiLink.of(app)?.ssid)
    }

    /** Some devices leave the capabilities without the details: WifiManager's, and still connected. */
    @Config(sdk = [34])
    @Test fun newerAndroidWithoutDetailsInTheCapabilitiesFallsBack() {
        network(NetworkCapabilities.TRANSPORT_WIFI)
        wifiManager.setConnectionInfo(wifiInfo("Example-WiFi", -66, 54))
        assertEquals(WifiLink(ssid = "Example-WiFi", rssi = -66, linkSpeedMbps = 54, ipv4 = "192.0.2.40"), WifiLink.of(app))
        wifiManager.setConnectionInfo(wifiInfo(WifiManager.UNKNOWN_SSID, -127, -1, networkId = -1))
        assertEquals("nothing known, still connected", WifiLink(null, null, null, "192.0.2.40"), WifiLink.of(app))
    }

    @Config(sdk = [29])
    @Test fun androidTenReadsTheConnectionInfo() {
        network(NetworkCapabilities.TRANSPORT_WIFI)
        wifiManager.setConnectionInfo(wifiInfo("Example-WiFi", -60, 72))
        assertEquals(WifiLink(ssid = "Example-WiFi", rssi = -60, linkSpeedMbps = 72, ipv4 = "192.0.2.40"), WifiLink.of(app))
    }

    /** Home Assistant's Wi-Fi signal and link speed sensors read the same link. */
    @Config(sdk = [34])
    @Test fun theHomeAssistantSensorsReadTheSameLink() {
        network(NetworkCapabilities.TRANSPORT_WIFI, wifiInfo(WifiManager.UNKNOWN_SSID, -55, 300))
        wifiManager.setConnectionInfo(wifiInfo(WifiManager.UNKNOWN_SSID, -127, -1, networkId = -1))
        val sample = dev.glassworks.diagnostics.AndroidProbe(app, null, { false }).sample()
        assertEquals(-55, sample.wifiRssi)
        assertEquals(300, sample.wifiLinkSpeed)
    }

    /** The active network is a VPN over Wi-Fi (both transports, the tunnel's address): the link is the physical one. */
    @Config(sdk = [34])
    @Test fun aVpnOverWifiIsNotTheLink() {
        fun address(ip: String, prefix: Int): LinkProperties = LinkProperties().also { lp ->
            val a = ReflectionHelpers.callConstructor(LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java, InetAddress.getByName(ip)), ClassParameter.from(Int::class.java, prefix))
            ReflectionHelpers.callInstanceMethod<Any>(lp, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, a))
        }
        val wifiType = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED)
        // Robolectric reports netId 1 as active for a Wi-Fi-type active network: the VPN takes that slot.
        val vpn = ShadowNetwork.newInstance(1)
        cm.addNetwork(vpn, wifiType)
        cm.setActiveNetworkInfo(wifiType)
        cm.setNetworkCapabilities(vpn, ShadowNetworkCapabilities.newInstance().also { c ->
            shadowOf(c).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            shadowOf(c).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        })
        cm.setLinkProperties(vpn, address("198.51.100.2", 32))
        val physical = ShadowNetwork.newInstance(3)
        cm.addNetwork(physical, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
        cm.setNetworkCapabilities(physical, ShadowNetworkCapabilities.newInstance().also { c ->
            shadowOf(c).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(c).setTransportInfo(wifiInfo("Example-WiFi", -58, 200))
        })
        cm.setLinkProperties(physical, address("192.0.2.40", 24))
        assertEquals("the VPN is the active network", vpn, app.getSystemService(ConnectivityManager::class.java).activeNetwork)
        assertEquals(WifiLink(ssid = "Example-WiFi", rssi = -58, linkSpeedMbps = 200, ipv4 = "192.0.2.40"), WifiLink.of(app))
    }

    @Config(sdk = [34])
    @Test fun noWifiNetworkIsNotConnected() {
        network(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertNull(WifiLink.of(app))
    }
}
