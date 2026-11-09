package io.github.aguilaair.s1kiosk.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One Wyoming event: a type, a JSON data object and an optional binary payload.
 *
 * Wire format (rhasspy/wyoming `wyoming/event.py`):
 * ```
 * {"type": "...", "version": "...", "data_length": N, "payload_length": M}\n
 * <N bytes of UTF-8 JSON data><M bytes of payload>
 * ```
 * Readers must also accept an inline `"data": {...}` object in the header (the older format),
 * merged with the separate data block if both are present.
 */
class WyomingEvent(
    val type: String,
    val data: JSONObject = JSONObject(),
    val payload: ByteArray? = null,
) {
    override fun toString(): String {
        val p = payload?.let { ", payload=${it.size}B" } ?: ""
        return "WyomingEvent($type, $data$p)"
    }
}

/** Event type names, verified against rhasspy/wyoming master (see docs/voice.md). */
object WyomingTypes {
    // info.py
    const val DESCRIBE = "describe"
    const val INFO = "info"

    // satellite.py
    const val RUN_SATELLITE = "run-satellite"
    const val PAUSE_SATELLITE = "pause-satellite"

    // ping.py
    const val PING = "ping"
    const val PONG = "pong"

    // wake.py
    const val DETECT = "detect"
    const val DETECTION = "detection"

    // pipeline.py
    const val RUN_PIPELINE = "run-pipeline"

    // audio.py
    const val AUDIO_START = "audio-start"
    const val AUDIO_CHUNK = "audio-chunk"
    const val AUDIO_STOP = "audio-stop"

    // vad.py
    const val VOICE_STARTED = "voice-started"
    const val VOICE_STOPPED = "voice-stopped"

    // asr.py
    const val TRANSCRIBE = "transcribe"
    const val TRANSCRIPT = "transcript"

    // tts.py
    const val SYNTHESIZE = "synthesize"

    // snd.py
    const val PLAYED = "played"

    // error.py
    const val ERROR = "error"
}

object WyomingCodec {
    /** Protocol version written in headers (the `wyoming` Python package version we target). */
    const val VERSION = "1.11.0"

    private const val MAX_HEADER = 1 shl 20
    private const val MAX_BLOCK = 32 shl 20

    /** Reads one event, or returns null at a clean end of stream. Throws on malformed input. */
    fun read(input: InputStream): WyomingEvent? {
        val line = readLine(input) ?: return null
        val header = JSONObject(String(line, Charsets.UTF_8))
        val type = header.getString("type")

        // Older format: data inline in the header. Newer: separate block of data_length bytes.
        val data = header.optJSONObject("data") ?: JSONObject()
        val dataLength = header.optInt("data_length", 0)
        if (dataLength > 0) {
            val block = JSONObject(String(readExactly(input, dataLength), Charsets.UTF_8))
            for (key in block.keys()) data.put(key, block.get(key))
        }

        val payloadLength = header.optInt("payload_length", 0)
        val payload = if (payloadLength > 0) readExactly(input, payloadLength) else null
        return WyomingEvent(type, data, payload)
    }

    /** Serializes an event in the current format (separate data block). */
    fun encode(event: WyomingEvent): ByteArray {
        val header = JSONObject()
        header.put("type", event.type)
        header.put("version", VERSION)

        var dataBytes: ByteArray? = null
        if (event.data.length() > 0) {
            dataBytes = event.data.toString().toByteArray(Charsets.UTF_8)
            header.put("data_length", dataBytes.size)
        }
        val payload = event.payload?.takeIf { it.isNotEmpty() }
        if (payload != null) header.put("payload_length", payload.size)

        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(headerBytes.size + 1 + (dataBytes?.size ?: 0) + (payload?.size ?: 0))
        out.write(headerBytes)
        out.write('\n'.code)
        if (dataBytes != null) out.write(dataBytes)
        if (payload != null) out.write(payload)
        return out.toByteArray()
    }

    fun write(out: OutputStream, event: WyomingEvent) {
        out.write(encode(event))
        out.flush()
    }

    private fun readLine(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw EOFException("Stream ended inside a Wyoming header")
            }
            if (b == '\n'.code) {
                // Tolerate blank lines between events.
                if (buf.size() == 0) continue
                return buf.toByteArray()
            }
            buf.write(b)
            if (buf.size() > MAX_HEADER) throw IOException("Wyoming header too long")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        if (n > MAX_BLOCK) throw IOException("Wyoming block too large: $n")
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("Stream ended inside a Wyoming data/payload block")
            off += r
        }
        return out
    }
}

/**
 * Builds the `info` event data (reply to `describe`), following `wyoming/info.py`:
 * every Artifact has name, attribution{name,url}, installed, description, version.
 * Home Assistant (`wyoming/data.py`) treats the service as a satellite when
 * `satellite.installed` is true, and resolves a `detection` name to a phrase through `wake`.
 */
object WyomingInfo {
    private const val PROJECT_URL = "https://github.com/aguilaair/aqara-s1-unleashed"
    private const val OWW_URL = "https://github.com/dscripka/openWakeWord"

    fun build(
        satelliteName: String,
        area: String?,
        version: String,
        wakeModelName: String,
        wakePhrase: String,
        wakeInstalled: Boolean,
        sndRate: Int = 22_050,
    ): JSONObject {
        fun attribution(name: String, url: String) = JSONObject().put("name", name).put("url", url)
        fun artifact(name: String, description: String?, ver: String?, attr: JSONObject, installed: Boolean = true) =
            JSONObject()
                .put("name", name)
                .put("attribution", attr)
                .put("installed", installed)
                .put("description", description ?: JSONObject.NULL)
                .put("version", ver ?: JSONObject.NULL)
        fun format(rate: Int) = JSONObject().put("rate", rate).put("width", 2).put("channels", 1)

        val owwAttr = attribution("dscripka", OWW_URL)
        val wakeModel = artifact(wakeModelName, wakePhrase, null, owwAttr, wakeInstalled)
            .put("languages", JSONArray().put("en"))
            .put("phrase", wakePhrase)
        val wake = artifact("openWakeWord", "openWakeWord (on-device, TFLite)", "0.5.1", owwAttr, wakeInstalled)
            .put("models", JSONArray().put(wakeModel))

        val selfAttr = attribution("S1 Kiosk", PROJECT_URL)
        val mic = artifact("android-audiorecord", "Panel microphone", version, selfAttr)
            .put("mic_format", format(SatelliteController.MIC_RATE))
        val snd = artifact("android-audiotrack", "Panel speaker", version, selfAttr)
            .put("snd_format", format(sndRate))

        val satellite = artifact(satelliteName, satelliteName, version, selfAttr)
            .put("area", area ?: JSONObject.NULL)
            .put("has_vad", false)
            .put("active_wake_words", if (wakeInstalled) JSONArray().put(wakeModelName) else JSONArray())
            .put("max_active_wake_words", 1)
            .put("supports_trigger", false)

        return JSONObject()
            .put("asr", JSONArray())
            .put("tts", JSONArray())
            .put("handle", JSONArray())
            .put("intent", JSONArray())
            .put("wake", JSONArray().put(wake))
            .put("mic", JSONArray().put(mic))
            .put("snd", JSONArray().put(snd))
            .put("satellite", satellite)
    }
}
