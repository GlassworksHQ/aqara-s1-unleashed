package sh.edm.s1kiosk.radio

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Who may reach the radio's network services (EZSP bridge tcp:6638, OTBR REST tcp:8081), docs/radio.md
 * "Who may connect". Pure (no Android): parsing/normalizing allow-lists and deciding what the app
 * hands to the root helper in [RadioHelper.PROP_ALLOW].
 *
 * By default only Home Assistant may connect: the address of its last authenticated (Noise, provisioned
 * key) ESPHome API connection, one IPv4 and one IPv6 at most. The owner can replace that with an
 * explicit list (`Radio allowed clients` text entity): addresses or CIDRs, or `any` (no firewall).
 */
object RadioAllow {
    const val ANY = "any"

    /** Android's PROP_VALUE_MAX (92) minus the terminator: the prop can't hold more. */
    const val MAX_LEN = 91

    /** Parsed explicit list: [value] is the normalized prop form ("" = automatic, "any", or "a b"). */
    data class Parsed(val value: String?, val error: String?)

    /**
     * Owner input -> normalized list. Tokens separated by spaces or commas. Empty or `auto` = automatic
     * (Home Assistant's address). `any` alone = no restriction. Addresses are canonical (IPv4 dotted,
     * IPv6 RFC 5952), CIDRs are reduced to their network address, /32 and /128 become plain addresses,
     * duplicates are dropped.
     */
    fun parse(text: String): Parsed {
        val tokens = text.split(',', ' ', '\t', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || (tokens.size == 1 && tokens[0].equals("auto", ignoreCase = true))) return Parsed("", null)
        if (tokens.any { it.equals(ANY, ignoreCase = true) }) {
            return if (tokens.size == 1) Parsed(ANY, null) else Parsed(null, "“any” can't be combined with addresses.")
        }
        val out = LinkedHashSet<String>()
        for (t in tokens) {
            out += normalizeEntry(t) ?: return Parsed(null, "Not an IP address or CIDR: “${t.take(48)}”.")
        }
        val v = out.joinToString(" ")
        if (v.length > MAX_LEN) return Parsed(null, "Too long: at most $MAX_LEN characters of addresses.")
        return Parsed(v, null)
    }

    /** One address or CIDR -> canonical form, or null if it isn't one. No DNS: literals only. */
    fun normalizeEntry(s: String): String? {
        val slash = s.indexOf('/')
        val addrPart = if (slash < 0) s else s.substring(0, slash)
        val bytes = parseLiteral(addrPart) ?: return null
        if (slash < 0) return format(bytes)
        val bitsText = s.substring(slash + 1)
        if (bitsText.isEmpty() || bitsText.length > 3 || !bitsText.all { it.isDigit() }) return null
        val bits = bitsText.toInt()
        if (bits > bytes.size * 8) return null
        if (bits == bytes.size * 8) return format(bytes)
        for (i in bytes.indices) {
            val keep = (bits - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xff shl (8 - keep)) and 0xff).toByte()
        }
        return "${format(bytes)}/$bits"
    }

    /**
     * A client's address as the helper should allow it: IPv4 dotted or compressed IPv6 (zone id
     * dropped, IPv4-mapped IPv6 as IPv4). Null for loopback/wildcard (always allowed / meaningless).
     */
    fun clientAddress(a: InetAddress?): String? {
        if (a == null || a.isLoopbackAddress || a.isAnyLocalAddress) return null
        val b = a.address
        val v4 = if (b.size == 16 && isV4Mapped(b)) b.copyOfRange(12, 16) else b
        if (v4.size == 4 && (v4[0].toInt() and 0xff) == 127) return null
        return format(v4)
    }

    /**
     * The value for [RadioHelper.PROP_ALLOW]: the owner's [explicit] list when set, else Home
     * Assistant's last known addresses ("" = none known yet: the helper allows loopback only).
     */
    fun decide(explicit: String?, haV4: String?, haV6: String?): String {
        val e = explicit?.trim().orEmpty()
        if (e.isNotEmpty()) return e
        return listOfNotNull(haV4?.takeIf { it.isNotBlank() }, haV6?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    /**
     * "Who may connect", for the status text. [allowed] is what the helper enforces
     * ([RadioHelper.parseAllowed]); [explicit]: the owner set `Radio allowed clients`.
     */
    fun describe(allowed: RadioHelper.Allowed, explicit: Boolean, haV4: String?, haV6: String?): String {
        if (allowed.any) return "any client (no firewall${if (allowed.source == "conf") ", radio.conf" else ""})"
        if (allowed.addresses.isEmpty()) {
            return if (allowed.source == "conf") "nobody but the panel itself (radio.conf)"
            else "nobody yet (waiting for Home Assistant to connect over ESPHome)"
        }
        val list = allowed.addresses.joinToString(", ")
        val ha = setOfNotNull(haV4, haV6)
        return when {
            allowed.source == "conf" -> "$list (radio.conf)"
            explicit -> "$list (Radio allowed clients)"
            ha.isNotEmpty() && allowed.addresses.toSet() == ha -> "Home Assistant ($list)"
            else -> list
        }
    }

    // ------------------------------------------------------------------ literals

    private fun isV4Mapped(b: ByteArray): Boolean =
        (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1

    /** Strict IPv4 dotted quad or IPv6 literal (optional %zone, dropped) -> bytes. */
    internal fun parseLiteral(s0: String): ByteArray? {
        val s = s0.substringBefore('%')
        if (s.isEmpty() || s.length > 45) return null
        if (':' !in s) return parseV4(s)
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        // Only hex digits, ':' and '.' here, so this is always parsed as a literal (never DNS).
        val a = try { InetAddress.getByName(s) } catch (e: Exception) { return null }
        return when (a) {
            is Inet6Address -> a.address
            is Inet4Address -> ByteArray(16).also { it[10] = -1; it[11] = -1; a.address.copyInto(it, 12) } // ::ffff:a.b.c.d
            else -> null
        }?.let { b -> if (isV4Mapped(b)) b.copyOfRange(12, 16) else b }
    }

    private fun parseV4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            if (p.length > 1 && p[0] == '0') return null // no octal-looking octets
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    /** 4 bytes -> dotted quad; 16 bytes -> RFC 5952 (lowercase, longest zero run of >= 2 groups as ::). */
    internal fun format(b: ByteArray): String {
        if (b.size == 4) return b.joinToString(".") { (it.toInt() and 0xff).toString() }
        val g = IntArray(8) { ((b[2 * it].toInt() and 0xff) shl 8) or (b[2 * it + 1].toInt() and 0xff) }
        var bestStart = -1; var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestLen < 2) return g.joinToString(":") { it.toString(16) }
        val head = (0 until bestStart).joinToString(":") { g[it].toString(16) }
        val tail = (bestStart + bestLen until 8).joinToString(":") { g[it].toString(16) }
        return "$head::$tail"
    }
}
