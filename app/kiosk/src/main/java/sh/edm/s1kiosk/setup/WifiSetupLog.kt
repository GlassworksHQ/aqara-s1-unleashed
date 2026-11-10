package sh.edm.s1kiosk.setup

/**
 * Result lines for Wi-Fi sent from the computer (the web installer's `--es wifi_ssid`, see
 * MainActivity.applyIntent). The installer polls them over adb without root:
 *
 * ```
 * adb shell logcat -d -s S1Kiosk.WifiSetup
 * ```
 *
 * Tag: [TAG] (`S1Kiosk.WifiSetup`), priority INFO. One line per event, the message being exactly
 * one of:
 *
 * ```
 * result=connecting ssid=<ssid>
 * result=connected ssid=<ssid> ip=<ipv4>
 * result=connected ssid=<ssid>
 * result=failed ssid=<ssid> reason=<reason>
 * ```
 *
 * - `<ssid>` is percent-encoded UTF-8 ([encodeSsid]): `A-Z a-z 0-9 - . _ ~` are kept, every other
 *   byte is `%XX` (upper-case hex), so the value never contains a space; decode it with
 *   `decodeURIComponent`.
 * - `ip=` is the panel's Wi-Fi IPv4 address, omitted when unknown.
 * - `reason=` is always last and runs to the end of the line: free English text (e.g.
 *   `wrong password (authentication failed)`, `timed out connecting to 'Home'`,
 *   `Wi-Fi is turned off and could not be turned on`). Newlines are replaced by spaces.
 * - Every request logs `result=connecting` first, then exactly one `connected` or `failed` for
 *   the same ssid (a request rejected up front, e.g. an empty or over-32-byte SSID, gets its
 *   `failed` right away). The buffer keeps older attempts, and a request sent while another is
 *   running ends that one with `result=failed ssid=<old> reason=superseded`: take the last
 *   `connected`/`failed` line with your ssid after your request's `connecting` line.
 * - The password is never logged on this tag (WifiProvisioner's own `S1Kiosk.Improv` lines give
 *   only its length, as for Improv).
 */
object WifiSetupLog {
    const val TAG = "S1Kiosk.WifiSetup"

    fun connecting(ssid: String) = "result=connecting ssid=${encodeSsid(ssid)}"

    fun connected(ssid: String, ip: String?) =
        "result=connected ssid=${encodeSsid(ssid)}" + (ip?.takeIf { it.isNotBlank() }?.let { " ip=$it" } ?: "")

    fun failed(ssid: String, reason: String) =
        "result=failed ssid=${encodeSsid(ssid)} reason=${reason.replace(Regex("[\\r\\n]+"), " ").trim().ifEmpty { "unknown" }}"

    /** RFC 3986 percent-encoding of the UTF-8 bytes, unreserved characters kept. */
    fun encodeSsid(ssid: String): String {
        val sb = StringBuilder()
        for (b in ssid.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
        }
        return sb.toString()
    }
}
