package sh.edm.s1kiosk.improv

/**
 * Pure helpers for turning Improv credentials into an Android Wi-Fi config (JVM-testable).
 * [capabilities] is Android's `ScanResult.capabilities`, e.g. "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
 * "[RSN-PSK+SAE-CCMP][ESS]" (WPA2/WPA3 transition), "[RSN-SAE-CCMP][ESS]", "[WEP][ESS]", "[ESS]".
 */
enum class WifiSecurity { OPEN, OWE, WEP, PSK, SAE, EAP }

object WifiSecurityRules {

    /** Improv auth type label(s) for the scan result (spec: WEP, WPA, WPA2, WPA2 EAP, WPA3, WAPI, NO). */
    fun improvAuth(capabilities: String): String {
        val c = capabilities.uppercase()
        val out = mutableListOf<String>()
        if ("WEP" in c) out += "WEP"
        if (Regex("\\[WPA-[^\\]]*PSK").containsMatchIn(c)) out += "WPA"
        if (Regex("\\[(WPA2|RSN)-[^\\]]*PSK").containsMatchIn(c)) out += "WPA2"
        if ("EAP" in c) out += "WPA2 EAP"
        if ("SAE" in c) out += "WPA3"
        if ("WAPI" in c) out += "WAPI"
        return if (out.isEmpty()) "NO" else out.distinct().joinToString("/")
    }

    /**
     * Security to configure. [capabilities] null = network not in the scan results (hidden or out of
     * range): PSK when a password was given, else open. WPA2/WPA3 transition networks use PSK
     * (works on every Android 10 driver).
     */
    fun choose(capabilities: String?, password: String): WifiSecurity {
        val c = capabilities?.uppercase() ?: return if (password.isEmpty()) WifiSecurity.OPEN else WifiSecurity.PSK
        return when {
            "PSK" in c -> WifiSecurity.PSK
            "SAE" in c -> WifiSecurity.SAE
            "EAP" in c -> WifiSecurity.EAP
            "WEP" in c -> WifiSecurity.WEP
            "OWE" in c -> WifiSecurity.OWE
            else -> WifiSecurity.OPEN
        }
    }

    /** null if the password is acceptable for [security], else why not. */
    fun validate(security: WifiSecurity, password: String): String? = when (security) {
        WifiSecurity.OPEN, WifiSecurity.OWE -> null // any password is ignored
        WifiSecurity.PSK -> when {
            isHex(password) && password.length == 64 -> null
            password.length in 8..63 && password.all { it.code in 0x20..0x7E } -> null
            else -> "WPA/WPA2 passwords are 8-63 printable ASCII characters (or 64 hex digits)"
        }
        WifiSecurity.SAE -> if (password.isNotEmpty()) null else "WPA3 needs a password"
        WifiSecurity.WEP -> if (wepKey(password) != null) null else "WEP keys are 5/13/16 characters or 10/26/32 hex digits"
        WifiSecurity.EAP -> "WPA2/WPA3 Enterprise (EAP) networks need a username/certificate; not supported over Improv"
    }

    /** WifiConfiguration.preSharedKey value: quoted passphrase, or the raw 64-hex PSK unquoted. */
    fun pskValue(password: String): String =
        if (password.length == 64 && isHex(password)) password else quote(password)

    /** WifiConfiguration.wepKeys[0] value: quoted ASCII key or unquoted hex. */
    fun wepKey(password: String): String? = when {
        password.length in setOf(10, 26, 32) && isHex(password) -> password
        password.length in setOf(5, 13, 16) -> quote(password)
        else -> null
    }

    fun quote(s: String) = "\"" + s + "\""

    /** WifiInfo.getSSID() returns `"name"` for UTF-8 SSIDs, bare hex otherwise, or `<unknown ssid>`. */
    fun unquote(s: String?): String? {
        if (s == null || s == "<unknown ssid>") return null
        return if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
    }

    /** WifiInfo.getIpAddress() is little-endian. 0 = no address. */
    fun ipv4(ip: Int): String? = if (ip == 0) null else
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"

    /** Redirect URL returned after provisioning: the panel's setup page. */
    fun redirectUrl(ip: String?, port: Int = SETUP_PORT): String? = ip?.let { "http://$it:$port/" }

    const val SETUP_PORT = 8099

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
