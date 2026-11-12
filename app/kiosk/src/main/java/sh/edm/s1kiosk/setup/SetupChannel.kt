package sh.edm.s1kiosk.setup

import sh.edm.improv.AdbPeer
import org.json.JSONArray
import org.json.JSONObject
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.discovery.HaDiscovery
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The setup channel (pure; the Android side is SetupChannelServer): everything the panel's own
 * onboarding asks for besides Wi-Fi, so the web installer can do it from the computer. Abstract
 * socket `@s1kiosk_setup`, reached through adb `localabstract:`; only adb is accepted (AdbPeer).
 * The client is kiln/src/setup/channel.ts (protocol source of truth); keep both in sync.
 *
 * One JSON object per line, UTF-8, both ways. Requests carry an `id`; the reply echoes it:
 * ```
 * → {"id":1,"op":"state"}                      ← {"id":1,"ok":true,"state":{…}}
 * → {"id":2,"op":"set_ha","url":"http://…"}    ← {"id":2,"ok":true,"state":{…}} | {"id":2,"ok":false,"error":"…"}
 * → {"id":3,"op":"set_radio","mode":"off"}     ← {"id":3,"ok":true,"state":{…}} | {"id":3,"ok":false,"error":"…"}
 * → {"id":4,"op":"set_radio_allow","allow":""} ← {"id":4,"ok":true,"state":{…}} | {"id":4,"ok":false,"error":"…"}
 * ```
 * While a client is connected, `{"event":"state","state":{…}}` is pushed whenever the serialized
 * state changes (checked every [SetupChannel.POLL_MS]). No secrets ever cross it: no tokens, no API
 * key, no passwords.
 */
data class ChannelState(val ip: String?, val ha: Ha, val radio: Radio) {
    data class Ha(val discovered: String?, val url: String, val signedIn: Boolean, val signinUrl: String?)
    /**
     * [allowed]: who the helper's firewall lets reach the radio's TCP services (EZSP :6638, OTBR REST
     * :8081): "any", "" (nobody but the panel yet: Home Assistant hasn't connected), or space-separated
     * addresses/CIDRs. Null (left out) when unknown: Off, no helper, or a helper without the firewall.
     */
    data class Radio(val mode: String, val status: String, val options: List<Option>, val helper: Boolean, val allowed: String? = null)
    /** [repeater]: a role inside someone else's network (Zigbee/Thread router); optional, only sent when true. */
    data class Option(val id: String, val label: String, val detail: String, val experimental: Boolean = false, val repeater: Boolean = false)

    /** The `SetupState` shape of channel.ts; optional fields are left out when unknown. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        ip?.let { o.put("ip", it) }
        o.put("ha", JSONObject().apply {
            ha.discovered?.let { put("discovered", it) }
            put("url", ha.url)
            put("signedIn", ha.signedIn)
            ha.signinUrl?.let { put("signinUrl", it) }
        })
        o.put("radio", JSONObject().apply {
            put("mode", radio.mode)
            put("status", radio.status)
            put("options", JSONArray().apply {
                radio.options.forEach { opt ->
                    put(JSONObject().apply {
                        put("id", opt.id)
                        put("label", opt.label)
                        put("detail", opt.detail)
                        if (opt.experimental) put("experimental", true)
                        if (opt.repeater) put("repeater", true)
                    })
                }
            })
            put("helper", radio.helper)
            radio.allowed?.let { put("allowed", it) }
        })
        return o
    }

    companion object {
        /** The modes onboarding offers: the panel's radio step ([RadioStep.OPTIONS]), same texts. */
        val OPTIONS: List<Option> = RadioStep.OPTIONS.map { Option(it.mode.id, it.title, it.line, it.experimental, it.repeater) }

        /** The panel's sign-in page (its QR code link), once it has an address and knows HA. */
        fun signinUrl(ip: String?, haUrl: String?): String? =
            if (ip.isNullOrEmpty() || haUrl.isNullOrEmpty()) null else "http://$ip:${OAuth.SIGNIN_PORT}/signin"

        /** The HA address the app found by itself (mDNS, or HA's ESPHome connection); not one typed in. */
        fun discovered(haUrl: String, source: String): String? =
            haUrl.takeIf { it.isNotEmpty() && (source == HaDiscovery.SOURCE_MDNS || source == HaDiscovery.SOURCE_ESPHOME) }

        /** http(s)://host[:port][/path], no spaces or quotes (channel.ts validHaUrl). */
        fun validHaUrl(u: String): Boolean {
            if (u.any { it.isWhitespace() || it == '\'' || it == '"' }) return false
            val x = runCatching { URI(u) }.getOrNull() ?: return false
            val scheme = x.scheme?.lowercase()
            // URI leaves host null for names java.net doesn't like (e.g. with "_"); fall back to the authority.
            val host = x.host ?: x.rawAuthority?.substringAfter('@')?.substringBefore(':')
            return (scheme == "http" || scheme == "https") && !host.isNullOrEmpty()
        }
    }
}

class SetupChannel(private val backend: Backend, private val log: (String) -> Unit = {}) {

    interface Backend {
        fun state(): ChannelState
        /**
         * Use [url] (already validated) as Home Assistant's address, as the `url` intent extra does.
         * Blocking. [HaChange.afterReply] runs once the reply is written (e.g. a process restart).
         */
        fun setHa(url: String): HaChange
        /** Apply radio mode [id] (a RadioMode id); blocking until it succeeded or failed. Null = ok, else why not. */
        fun setRadio(id: String): String?
        /**
         * Who may reach the radio's TCP services, as the `Radio allowed clients` entity: addresses or
         * CIDRs, `any`, or "" = Home Assistant only (automatic). Null = stored, else why not.
         */
        fun setRadioAllow(text: String): String? = "Not supported by this panel."
    }

    data class HaChange(val error: String? = null, val afterReply: (() -> Unit)? = null)

    /** One request line → the reply line (without "\n") and what to run after writing it. */
    fun handle(line: String): Pair<String, (() -> Unit)?> {
        val req = runCatching { JSONObject(line) }.getOrNull()
            ?: return reply(null, error = "not a JSON object") to null
        val id: Any? = req.opt("id")?.takeIf { it != JSONObject.NULL }
        val op = req.optString("op", "")
        log("request ${id ?: "-"} $op")
        return when (op) {
            "state" -> reply(id, state = backend.state()) to null
            "set_ha" -> {
                val url = req.optString("url", "").trim()
                if (!ChannelState.validHaUrl(url)) {
                    reply(id, error = "That doesn't look like a web address (http://… or https://…).") to null
                } else {
                    val change = backend.setHa(url)
                    if (change.error != null) reply(id, error = change.error) to null
                    else reply(id, state = backend.state()) to change.afterReply
                }
            }
            "set_radio" -> {
                val mode = req.optString("mode", "")
                when {
                    ChannelState.OPTIONS.none { it.id == mode } -> reply(id, error = "Unknown radio mode '$mode'.") to null
                    else -> when (val err = backend.setRadio(mode)) {
                        null -> reply(id, state = backend.state()) to null
                        else -> reply(id, error = err) to null
                    }
                }
            }
            "set_radio_allow" -> when (val err = backend.setRadioAllow(req.optString("allow", ""))) {
                null -> reply(id, state = backend.state()) to null
                else -> reply(id, error = err) to null
            }
            else -> reply(id, error = "Unknown request '$op'.") to null
        }
    }

    /** The state the client last got (reply or event), to push only changes. */
    private var lastSent: String? = null

    private fun reply(id: Any?, state: ChannelState? = null, error: String? = null): String {
        val o = JSONObject()
        id?.let { o.put("id", it) }
        o.put("ok", error == null)
        if (state != null) {
            val s = state.toJson()
            lastSent = s.toString()
            o.put("state", s)
        }
        error?.let { o.put("error", it) }
        return o.toString()
    }

    /** A state event if the state changed since the client last saw it (nothing before it asked once). */
    fun pushIfChanged(): String? {
        val prev = lastSent ?: return null
        val s = backend.state().toJson()
        val now = s.toString()
        if (now == prev) return null
        lastSent = now
        return JSONObject().put("event", "state").put("state", s).toString()
    }

    /**
     * Serves one connection until the input ends: requests are handled in order on this thread, and
     * between them the state is checked every [pollMs] for a push. A helper thread only splits the
     * input into lines.
     */
    fun serve(input: InputStream, output: OutputStream, pollMs: Long = POLL_MS) {
        val lines = LinkedBlockingQueue<Any>()
        val reader = Thread({
            try { splitLines(input) { lines.put(it) } } catch (_: Exception) {} finally { lines.put(EOF) }
        }, "s1-setup-reader").apply { isDaemon = true; start() }
        fun write(s: String) { output.write((s + "\n").toByteArray(Charsets.UTF_8)); output.flush() }
        try {
            while (true) {
                val item = lines.poll(pollMs, TimeUnit.MILLISECONDS)
                if (item === EOF) break
                if (item is String) {
                    if (item.isBlank()) continue
                    val (r, after) = handle(item)
                    write(r)
                    after?.let { runCatching(it).onFailure { e -> log("after reply: $e") } }
                }
                pushIfChanged()?.let(::write)
            }
        } finally {
            reader.interrupt()
        }
    }

    companion object {
        const val SOCKET = "s1kiosk_setup"
        const val POLL_MS = 1_500L
        /** Longer lines are dropped (requests are tiny). */
        const val MAX_LINE = 16 * 1024
        private val EOF = Any()

        /** Splits [input] into UTF-8 lines (LF, optional CR), dropping lines over [MAX_LINE] bytes. */
        fun splitLines(input: InputStream, onLine: (String) -> Unit) {
            val buf = java.io.ByteArrayOutputStream()
            var tooLong = false
            val chunk = ByteArray(1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) return
                for (i in 0 until n) {
                    val b = chunk[i]
                    if (b == '\n'.code.toByte()) {
                        if (!tooLong) onLine(buf.toString("UTF-8").trimEnd('\r'))
                        buf.reset()
                        tooLong = false
                    } else if (!tooLong) {
                        buf.write(b.toInt())
                        if (buf.size() > MAX_LINE) { tooLong = true; buf.reset() }
                    }
                }
            }
        }
    }
}
