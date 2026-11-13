package sh.edm.esphome

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private val VALUES = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

/**
 * Standard base64 (RFC 4648, `+` and `/`), read the way `java.util.Base64.getDecoder()` reads it: the
 * padding is optional, and anything outside the alphabet makes the text invalid. Null when invalid.
 * Ours because `java.util.Base64` is API 26 on Android, and this library runs on 23.
 */
internal fun decodeBase64(text: String): ByteArray? {
    val pad = when {
        text.endsWith("==") -> 2
        text.endsWith("=") -> 1
        else -> 0
    }
    val n = text.length - pad
    if (n % 4 == 1 || (pad > 0 && text.length % 4 != 0)) return null
    val out = ByteArray(n * 3 / 4)
    var bits = 0
    var count = 0
    var o = 0
    for (i in 0 until n) {
        val c = text[i].code
        val v = if (c < VALUES.size) VALUES[c] else -1
        if (v < 0) return null
        bits = (bits shl 6) or v
        count += 6
        if (count >= 8) {
            count -= 8
            out[o++] = (bits shr count).toByte()
        }
    }
    return out
}
