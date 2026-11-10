package sh.edm.s1kiosk.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the openWakeWord models the panel is missing, straight from the official v0.5.1 GitHub
 * release, and checks each against a pinned SHA-256. They can't ship in the APK or be hosted by the
 * web installer: the models are CC BY-NC-SA 4.0 (the code is Apache-2.0), so the panel fetches them
 * itself, like tools/voice/fetch-models.sh does on a computer.
 */
class ModelFetcher(
    private val open: (URL) -> InputStream = ::httpGet,
) {
    companion object {
        const val VERSION = "v0.5.1"
        private const val BASE = "https://github.com/dscripka/openWakeWord/releases/download/$VERSION"

        /** File -> SHA-256 of the v0.5.1 release asset (the copies tools/voice/fetch-models.sh verified). */
        val FILES: Map<String, String> = linkedMapOf(
            TfliteWakeWord.MELSPEC_FILE to "96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8",
            TfliteWakeWord.EMBEDDING_FILE to "c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8",
            "hey_jarvis_v0.1.tflite" to "14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2",
            "alexa_v0.1.tflite" to "7333a317a790070a7f3432b81d9439c779481cc4ebd67c73da7174ea3cf48397",
            "hey_mycroft_v0.1.tflite" to "bf9e43136afd3ca323698820a6e32a47f885ef4c30a3b8b577ec71688a9d64d8",
        )
        private const val MAX_BYTES = 8L * 1024 * 1024

        private fun httpGet(url: URL): InputStream {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true // github.com -> release-assets.githubusercontent.com (both https)
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream
        }

        fun sha256(f: File): String = f.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** Files of [FILES] that are absent or don't match their hash. */
    fun missing(dir: File): List<String> = FILES.filter { (name, sha) ->
        val f = File(dir, name)
        !f.isFile || f.length() < 8 || sha256(f) != sha
    }.keys.toList()

    /**
     * Fetches whatever [missing] reports. Each file goes to a `.part` file first and is renamed only
     * after its SHA-256 matched, so a half download never looks like a model. Returns the names fetched.
     */
    @Throws(IOException::class)
    fun ensure(dir: File): List<String> {
        val need = missing(dir)
        if (need.isEmpty()) return need
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        for (name in need) {
            val part = File(dir, "$name.part")
            try {
                open(URL("$BASE/$name")).use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("$name is larger than expected")
                            out.write(buf, 0, n)
                        }
                    }
                }
                val got = sha256(part)
                if (got != FILES.getValue(name)) throw IOException("$name failed its SHA-256 check (got ${got.take(16)}…)")
                if (!part.renameTo(File(dir, name))) throw IOException("cannot rename $part")
                VoiceLog.i("Downloaded wake word model $name")
            } finally {
                part.delete()
            }
        }
        return need
    }
}
