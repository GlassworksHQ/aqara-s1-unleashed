package sh.edm.s1kiosk.discovery

import sh.edm.s1kiosk.auth.OAuth
import org.json.JSONException
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Pure logic for finding Home Assistant (unit-tested in HaUrlsTest).
 *
 * mDNS: HA 2026.11.0 `components/zeroconf/__init__.py` (`_async_get_instance_info`) registers
 * `_home-assistant._tcp.local.` with TXT `location_name`, `uuid`, `version`, `external_url`,
 * `internal_url` and `base_url` (= external_url or internal_url, "for backward compatibility").
 * Empty values are published as empty strings. We prefer `internal_url` (the panel is on the LAN),
 * then `base_url`, then the advertised address + port.
 */
object HaUrls {
    const val SERVICE_TYPE = "_home-assistant._tcp"
    const val DEFAULT_PORT = 8123
    const val HELLO_PREFIX = "Home Assistant"

    /** Picks the URL from a resolved `_home-assistant._tcp` service (first of [candidates]). */
    fun fromTxt(txt: Map<String, ByteArray?>, host: InetAddress?, port: Int): String? =
        candidates(txt, host, port).firstOrNull()

    /**
     * Candidate URLs in order: `internal_url`, `base_url`, then `http://<resolved address>:<port>`.
     * The address comes last but matters: Android 10's resolver can't resolve `.local` names, so an
     * `internal_url` like `http://homeassistant.local:8123` fails its probe on the panel.
     */
    fun candidates(txt: Map<String, ByteArray?>, host: InetAddress?, port: Int): List<String> {
        fun attr(k: String) = txt[k]?.toString(Charsets.UTF_8)?.trim().orEmpty()
        val out = ArrayList<String>(3)
        for (k in listOf("internal_url", "base_url")) normalize(attr(k))?.let { if (it !in out) out += it }
        host?.let { fromAddress(it, if (port > 0) port else DEFAULT_PORT) }?.let { if (it !in out) out += it }
        return out
    }

    /** `http://<ip>:<port>`; IPv6 is bracketed. Link-local / loopback addresses are not useful. */
    fun fromAddress(addr: InetAddress, port: Int = DEFAULT_PORT): String? {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) return null
        val h = if (addr is Inet6Address) "[" + addr.hostAddress.substringBefore('%') + "]" else addr.hostAddress
        return "http://$h:$port"
    }

    /** `http(s)://host[:port]` without path / trailing slash, or null if it isn't one. */
    fun normalize(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        val withScheme = if ("://" in u) u else "http://$u"
        return OAuth.origin(withScheme)
    }

    /** True if an ESPHome API client's HelloRequest.client_info is Home Assistant (`CLIENT_INFO`). */
    fun isHomeAssistantHello(clientInfo: String): Boolean = clientInfo.startsWith(HELLO_PREFIX)

    /**
     * `GET /manifest.json` of an HA instance is the PWA manifest served by the `frontend`
     * component; its `name` is "Home Assistant".
     */
    fun looksLikeHaManifest(status: Int, body: String): Boolean {
        if (status != 200) return false
        return try {
            JSONObject(body).optString("name").contains("Home Assistant")
        } catch (_: JSONException) {
            false
        }
    }

    /** The panel's IPv4 address (wlan0 first), for the QR code; null if offline. */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name == "wlan0" }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }?.hostAddress
    } catch (_: Exception) {
        null
    }
}
