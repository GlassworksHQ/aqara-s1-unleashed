package io.github.aguilaair.s1kiosk.sendspin

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** A PCM format a player offers / a stream uses (`player@v1_support.supported_formats` entry). */
data class SsFormat(val codec: String, val channels: Int, val sampleRate: Int, val bitDepth: Int) {
    fun toJson(): JSONObject = JSONObject().put("codec", codec).put("channels", channels)
        .put("sample_rate", sampleRate).put("bit_depth", bitDepth)

    val frameBytes: Int get() = channels * bitDepth / 8

    companion object {
        val PCM_48K_STEREO_16 = SsFormat("pcm", 2, 48_000, 16)

        fun fromJson(o: JSONObject): SsFormat? = try {
            SsFormat(o.getString("codec"), o.getInt("channels"), o.getInt("sample_rate"), o.optInt("bit_depth", 0))
        } catch (_: Exception) { null }
    }
}

/** What the player reports in `client/state` (spec "client/state player object"). */
data class SsPlayerState(
    val volume: Int,
    val muted: Boolean,
    val outputDelayMs: Int,
    val requiredLeadTimeMs: Int,
    val minBufferMs: Int,
    val supportedCommands: List<String> = listOf("volume", "mute", "set_output_delay"),
)

/** Static description of this client for `client/hello`. */
data class SsHelloInfo(
    val name: String,
    val productName: String,
    val manufacturer: String,
    val softwareVersion: String,
    /** Lowercase colon-separated, or null. */
    val macAddress: String?,
    val formats: List<SsFormat>,
    val bufferCapacity: Int,
    val metadataRole: Boolean = true,
)

/** A parsed JSON message: `type` + `payload` object (spec "Communication"). */
class SsJson(val type: String, val payload: JSONObject)

/** Player audio chunk header (binary type 4): `[4][timestamp i64 BE][send_ahead u32 BE][audio]`. */
class SsAudioChunk(val timestampUs: Long, val sendAheadUs: Long, val data: ByteArray, val dataOffset: Int) {
    val dataLength: Int get() = data.size - dataOffset
}

/** Encoders/decoders for the messages the client sends or receives (field names from the spec / aiosendspin 10.2.1). */
object SsMessages {
    const val BIN_JSON = 0
    const val BIN_FRAGMENT = 1
    const val BIN_AUDIO = 4
    const val AUDIO_HEADER = 13

    fun envelope(type: String, payload: JSONObject = JSONObject()): String =
        JSONObject().put("type", type).put("payload", payload).toString()

    fun clientInit(clientId: String): String = envelope(
        "client/init",
        JSONObject().put("client_id", clientId).put("version", 1).put("suite", SsCrypto.SUITE),
    )

    fun noiseHandshake(data: ByteArray): String = envelope("noise/handshake", JSONObject().put("data", SsCrypto.b64url(data)))

    /** Noise message 2's payload is the literal two bytes `{}` (spec "noise/handshake"). */
    val MSG2_PAYLOAD: ByteArray = "{}".toByteArray(Charsets.UTF_8)

    fun clientHello(info: SsHelloInfo, unpairedAccess: Boolean): String {
        val roles = JSONArray().put("player@v1")
        if (info.metadataRole) roles.put("metadata@v1")
        val device = JSONObject()
            .put("product_name", info.productName.utf8Cap(64))
            .put("manufacturer", info.manufacturer.utf8Cap(64))
            .put("software_version", info.softwareVersion.utf8Cap(64))
        info.macAddress?.let { device.put("mac_address", it) }
        val formats = JSONArray().apply { info.formats.forEach { put(it.toJson()) } }
        val p = JSONObject()
            .put("name", info.name.utf8Cap(64))
            .put("device_info", device)
            .put("supported_roles", roles)
            .put("player@v1_support", JSONObject().put("supported_formats", formats).put("buffer_capacity", info.bufferCapacity))
            // Every client offers the Pairing PSK method (spec "Pairing"); its token is handed out via
            // the ESPHome API (SendspinPairingTokenRequest), so no `locations` hint.
            .put("supported_pair_methods", JSONObject().put("pairing_psk", JSONObject()))
            .put("unpaired_access", JSONObject().put("enabled", unpairedAccess))
        return envelope("client/hello", p)
    }

    fun clientTime(t1: Long): String = envelope("client/time", JSONObject().put("client_transmitted", t1))

    fun clientState(available: Boolean, player: SsPlayerState?): String {
        val p = JSONObject().put("available", available)
        if (player != null) {
            p.put(
                "player",
                JSONObject()
                    .put("volume", player.volume.coerceIn(0, 100))
                    .put("muted", player.muted)
                    .put("output_delay_ms", player.outputDelayMs.coerceIn(0, 5000))
                    .put("required_lead_time_ms", player.requiredLeadTimeMs.coerceAtLeast(0))
                    .put("min_buffer_ms", player.minBufferMs.coerceIn(0, 5000))
                    .put("supported_commands", JSONArray(player.supportedCommands)),
            )
        }
        return envelope("client/state", p)
    }

    fun goodbye(reason: String): String = envelope("client/goodbye", JSONObject().put("reason", reason))

    fun pairInit(index: Int): String = envelope("client/pair-init", JSONObject().put("pairing_index", index))

    fun pairFinalize(longTermPsk: ByteArray): String =
        envelope("client/pair-finalize", JSONObject().put("long_term_psk", SsCrypto.b64url(longTermPsk)))

    fun pairAbort(reason: String): String = envelope("pair/abort", JSONObject().put("reason", reason))

    /** Parses a JSON message; null unless it is an object with a string `type` and an object `payload`. */
    fun parse(text: String): SsJson? = try {
        val o = JSONObject(text)
        val t = o.opt("type") as? String
        val p = o.opt("payload") as? JSONObject
        if (t == null || p == null) null else SsJson(t, p)
    } catch (_: Exception) { null }

    /** Parses a decrypted type-4 message; null when shorter than the 13-byte header (a protocol error). */
    fun parseAudio(plain: ByteArray): SsAudioChunk? {
        if (plain.size < AUDIO_HEADER) return null
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (plain[i].toLong() and 0xFF)
        var ahead = 0L
        for (i in 9..12) ahead = (ahead shl 8) or (plain[i].toLong() and 0xFF)
        return SsAudioChunk(ts, ahead, plain, AUDIO_HEADER)
    }

    /** Builds a type-4 message (tests). */
    fun audioMessage(timestampUs: Long, sendAhead: Long, audio: ByteArray): ByteArray {
        val b = ByteArray(AUDIO_HEADER + audio.size)
        b[0] = BIN_AUDIO.toByte()
        for (i in 0 until 8) b[1 + i] = (timestampUs ushr (56 - 8 * i)).toByte()
        for (i in 0 until 4) b[9 + i] = (sendAhead ushr (24 - 8 * i)).toByte()
        System.arraycopy(audio, 0, b, AUDIO_HEADER, audio.size)
        return b
    }

    private fun String.utf8Cap(max: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= max) return this
        var s = this
        while (s.toByteArray(Charsets.UTF_8).size > max) s = s.dropLast(1)
        return s
    }
}

/** Thrown for a protocol error: the connection must be closed (spec "Out-of-range values", "Fragmentation"). */
class SsProtocolException(msg: String) : Exception(msg)

/**
 * Receive side of Sendspin fragmentation (spec "Fragmentation"): `[1][flags][orig_type][data]` then
 * `[1][flags][data]`; flags bit 1 = first, bit 0 = last, bits 2-7 reserved.
 */
class SsReassembler(private val maxBytes: Int = 16 * 1024 * 1024) {
    private var buf: ByteArrayOutputStream? = null
    private var origType = 0

    /** Returns the complete plaintext (type byte first), or null while a fragmented message is incomplete. */
    @Throws(SsProtocolException::class)
    fun accept(plain: ByteArray): ByteArray? {
        if (plain.isEmpty()) throw SsProtocolException("empty plaintext")
        if (plain[0].toInt() != SsMessages.BIN_FRAGMENT) {
            if (buf != null) throw SsProtocolException("non-fragment message while a fragmented one is in flight")
            return plain
        }
        if (plain.size < 2) throw SsProtocolException("fragment missing flags")
        val flags = plain[1].toInt() and 0xFF
        if (flags and 0xFC != 0) throw SsProtocolException("fragment reserved flag bits set")
        val first = flags and 0x02 != 0
        val last = flags and 0x01 != 0
        val dataStart: Int
        if (first) {
            if (buf != null) throw SsProtocolException("first fragment while one is in flight")
            if (plain.size < 3) throw SsProtocolException("first fragment missing orig_type")
            origType = plain[2].toInt() and 0xFF
            if (origType == SsMessages.BIN_FRAGMENT) throw SsProtocolException("fragment orig_type 1")
            buf = ByteArrayOutputStream()
            dataStart = 3
        } else {
            if (buf == null) throw SsProtocolException("continuation fragment with none in flight")
            dataStart = 2
        }
        val b = buf!!
        b.write(plain, dataStart, plain.size - dataStart)
        if (b.size() > maxBytes) throw SsProtocolException("fragmented message too large")
        if (!last) return null
        buf = null
        return byteArrayOf(origType.toByte()) + b.toByteArray()
    }
}
