/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

/**
 * The device's Wi-Fi link (#263). Whether there is one comes from `ConnectivityManager`, which needs no
 * extra permission. The rest is null where Android doesn't tell this app: newer Android hides the
 * network's name from an app without the location permission, so a link with no [ssid] is still connected.
 */
data class WifiLink(val ssid: String?, val rssi: Int?, val linkSpeedMbps: Int?, val ipv4: String?) {
    companion object {
        /** The link now, or null when the device has no Wi-Fi network. */
        fun of(context: Context, platform: PlatformApi = Platform): WifiLink? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION") // allNetworks: the active network can be another one (Ethernet) while Wi-Fi is up too.
            val network = (listOfNotNull(cm.activeNetwork) + cm.allNetworks)
                .firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } ?: return null
            val info = platform.wifiInfo(cm.getNetworkCapabilities(network), context.applicationContext.getSystemService(WifiManager::class.java))
            return WifiLink(
                ssid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID },
                // -127 is WifiInfo.INVALID_RSSI and -1 an unknown link speed.
                rssi = info?.rssi?.takeIf { it in -126..-1 },
                linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
                ipv4 = cm.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress,
            )
        }
    }
}
