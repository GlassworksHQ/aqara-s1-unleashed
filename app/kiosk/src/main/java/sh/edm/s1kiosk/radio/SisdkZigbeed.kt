package sh.edm.s1kiosk.radio

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Silicon Labs' zigbeed 8.2.2 (EmberZNet 8.2.2, EZSP 18) for the coordinator mode's Silicon Labs stack
 * (docs/radio.md "Zigbee stack", docs/research/zigbee2mqtt.md).
 *
 * The binary is Silicon Labs MSLA object code: this project never ships or hosts it. The panel fetches
 * it from Silicon Labs' own GitHub release when the stack is chosen, extracts `usr/local/bin/zigbeed`
 * from the armhf .deb, and hands it to the root helper, which installs it only if its SHA-256 is the
 * pinned one (the same pin is in tools/radio/s1kiosk_radio.sh). Everything here runs as the app, so the
 * parsing of downloaded data never runs as root; the hashes are checked at each step anyway.
 *
 * Only the 253 KB .deb is fetched (an HTTP range of the zip, which stores it uncompressed at a pinned
 * offset). If the server ignores the range, the whole 22.9 MB zip is downloaded and checked instead.
 */
object SisdkZigbeed {
    const val VERSION = "8.2.2"
    const val ZIP_URL = "https://github.com/SiliconLabs/simplicity_sdk/releases/download/v2025.6.3/debian-bookworm.zip"
    const val ZIP_SHA256 = "7ecbd91cdae94da1ec9eb4817a6bc1fe73741106a0c70a40879956bc09833c0e"
    const val ZIP_SIZE = 22_854_995L
    const val DEB_NAME = "debian-bookworm/deb/zigbeed_8.2.2_armhf.deb"
    const val DEB_SHA256 = "dc430b383ad6dfe8e447de6031f8b1b79ca6b6cbc161a95f3a8283fe78091df8"
    /** Where the stored (uncompressed) .deb starts in the zip, and its size. */
    const val DEB_OFFSET = 16_907_628L
    const val DEB_SIZE = 253_484
    const val ELF_PATH = "./usr/local/bin/zigbeed"
    const val ELF_SHA256 = "a66d45842a770e1c449a55208a6d2a3b36786eb1c94bf10d3c3aac317274e3bb"
    const val ELF_SIZE = 663_312

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The .deb's `data.tar.xz` -> the zigbeed ELF, checked against the pin. */
    fun elfFromDeb(deb: ByteArray): ByteArray {
        if (sha256(deb) != DEB_SHA256) throw IOException("zigbeed .deb has the wrong SHA-256")
        val data = arMember(deb) { it.startsWith("data.tar") } ?: throw IOException("no data.tar in the .deb")
        if (!data.first.endsWith(".xz")) throw IOException("unexpected ${data.first} in the .deb")
        val elf = tarMember(XZInputStream(ByteArrayInputStream(data.second)), ELF_PATH)
            ?: throw IOException("no $ELF_PATH in the .deb")
        if (elf.size != ELF_SIZE || sha256(elf) != ELF_SHA256) throw IOException("zigbeed has the wrong SHA-256")
        return elf
    }

    /** The whole zip (checked) -> the .deb. */
    fun debFromZip(zip: File): ByteArray {
        if (zip.length() != ZIP_SIZE || fileSha256(zip) != ZIP_SHA256) throw IOException("debian-bookworm.zip has the wrong SHA-256")
        ZipFile(zip).use { z ->
            val e = z.getEntry(DEB_NAME) ?: throw IOException("no $DEB_NAME in the zip")
            return z.getInputStream(e).use { it.readBytes() }
        }
    }

    /** First `ar` member whose name matches -> (name, bytes). */
    fun arMember(ar: ByteArray, match: (String) -> Boolean): Pair<String, ByteArray>? {
        if (ar.size < 8 || String(ar, 0, 8, Charsets.US_ASCII) != "!<arch>\n") throw IOException("not an ar archive")
        var off = 8
        while (off + 60 <= ar.size) {
            val name = String(ar, off, 16, Charsets.US_ASCII).trim().trimEnd('/')
            val size = String(ar, off + 48, 10, Charsets.US_ASCII).trim().toIntOrNull() ?: throw IOException("bad ar header")
            val start = off + 60
            if (size < 0 || start + size > ar.size) throw IOException("truncated ar member $name")
            if (match(name)) return name to ar.copyOfRange(start, start + size)
            off = start + size + (size and 1)
        }
        return null
    }

    /** A regular file from a (ustar/GNU) tar stream, at most 16 MiB. */
    fun tarMember(input: InputStream, path: String): ByteArray? {
        val hdr = ByteArray(512)
        while (true) {
            if (!readFully(input, hdr)) return null
            if (hdr.all { it.toInt() == 0 }) return null
            val name = cstr(hdr, 0, 100).let { n -> cstr(hdr, 345, 155).let { p -> if (p.isNotEmpty()) "$p/$n" else n } }
            val size = cstr(hdr, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            if (size < 0 || size > 16L * 1024 * 1024) throw IOException("tar member too big")
            val type = hdr[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            if ((type == '0' || type == '\u0000') && (name == path || "./$name" == path || name == "./$path")) {
                val b = ByteArray(size.toInt())
                if (!readFully(input, b)) throw IOException("truncated tar")
                return b
            }
            skipFully(input, padded)
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.US_ASCII)
    }

    private fun readFully(input: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) { if (n == 0) return false; throw IOException("truncated tar") }
            n += r
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("truncated tar")
            left -= r
        }
    }

    fun fileSha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val r = s.read(b); if (r < 0) break; md.update(b, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Downloads and extracts zigbeed into [dest] (written as `<dest>.tmp`, then renamed; owner-only).
     * [progress] gets short status texts. Throws on any failure; nothing is left behind then.
     */
    fun fetch(dest: File, cacheDir: File, userAgent: String, progress: (String) -> Unit = {}) {
        progress("downloading zigbeed $VERSION from Silicon Labs")
        val deb = try {
            fetchDebByRange(userAgent)
        } catch (e: RangeUnsupported) {
            progress("downloading Silicon Labs' debian-bookworm.zip (22.9 MB)")
            val zip = File(cacheDir, "sisdk-debian-bookworm.zip.part")
            try {
                download(ZIP_URL, zip, userAgent, ZIP_SIZE)
                debFromZip(zip)
            } finally { zip.delete() }
        }
        val elf = elfFromDeb(deb)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.delete()
        tmp.writeBytes(elf)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("rename failed") }
        progress("zigbeed $VERSION downloaded (SHA-256 matches), handing it to the root helper")
    }

    private class RangeUnsupported : IOException("no range support")

    private fun open(url: String, userAgent: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true // https -> https only
        c.setRequestProperty("User-Agent", userAgent)
        return c
    }

    private fun fetchDebByRange(userAgent: String): ByteArray {
        val c = open(ZIP_URL, userAgent)
        c.setRequestProperty("Range", "bytes=$DEB_OFFSET-${DEB_OFFSET + DEB_SIZE - 1}")
        try {
            when (c.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {}
                HttpURLConnection.HTTP_OK -> throw RangeUnsupported()
                else -> throw IOException("HTTP ${c.responseCode} from Silicon Labs' release")
            }
            // at most DEB_SIZE + 1 bytes (InputStream.readNBytes needs API 33; the panel is API 29)
            val b = c.inputStream.use { s ->
                val buf = ByteArray(DEB_SIZE + 1); var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
            if (b.size != DEB_SIZE || sha256(b) != DEB_SHA256) throw RangeUnsupported() // e.g. a rebuilt zip: check the whole file
            return b
        } finally { c.disconnect() }
    }

    private fun download(url: String, to: File, userAgent: String, size: Long) {
        val c = open(url, userAgent)
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode} from Silicon Labs' release")
            var n = 0L
            c.inputStream.use { s ->
                to.outputStream().use { o ->
                    val b = ByteArray(1 shl 16)
                    while (true) {
                        val r = s.read(b); if (r < 0) break
                        n += r; if (n > size) throw IOException("download larger than expected")
                        o.write(b, 0, r)
                    }
                }
            }
        } finally { c.disconnect() }
    }
}
