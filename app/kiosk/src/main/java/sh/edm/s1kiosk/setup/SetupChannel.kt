package sh.edm.s1kiosk.setup

import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupStep
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
 * The client is kiln/packages/setup-channel/src/channel.ts (protocol source of truth); keep both in sync.
 *
 * One JSON object per line, UTF-8, both ways. Requests carry an `id`; the reply echoes it:
 * ```
 * → {"id":1,"op":"state"}                         ← {"id":1,"ok":true,"state":{…}}
 * → {"id":2,"op":"ha.set_url","url":"http://…"}   ← {"id":2,"ok":true,"state":{…}} | {"id":2,"ok":false,"error":"…"}
 * → {"id":3,"op":"radio.set_mode","mode":"off"}   ← the same, for each setup step's ops (`<step id>.<op>`)
 * ```
 * The state is the core's (`ip`, `ha`) plus each pane step's own part under its id (`radio`). The S1's
 * first names (`set_ha`, `set_radio`, `set_radio_allow`) still work: Kiln sends them.
 * While a client is connected, `{"event":"state","state":{…}}` is pushed whenever the serialized
 * state changes (checked every [SetupChannel.POLL_MS]). No secrets ever cross it: no tokens, no API
 * key, no passwords. Wi-Fi goes over Improv Serial instead, the installer's other adb socket.
 */
data class ChannelState(val ip: String?, val ha: Ha) {
    data class Ha(val discovered: String?, val url: String, val signedIn: Boolean, val signinUrl: String?)

    /** The core's part of the `SetupState` shape of channel.ts; optional fields are left out when unknown. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        ip?.let { o.put("ip", it) }
        o.put("ha", JSONObject().apply {
            ha.discovered?.let { put("discovered", it) }
            put("url", ha.url)
            put("signedIn", ha.signedIn)
            ha.signinUrl?.let { put("signinUrl", it) }
        })
        return o
    }

    companion object {
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

class SetupChannel(
    private val backend: Backend,
    /** The panes' setup steps: their state and ops ([SetupStep.ops]). */
    steps: List<SetupStep> = emptyList(),
    private val log: (String) -> Unit = {},
) {

    interface Backend {
        /** The core's part of the state. */
        fun state(): ChannelState
        /**
         * Use [url] (already validated) as Home Assistant's address, as the `url` intent extra does.
         * Blocking. [HaChange.afterReply] runs once the reply is written (e.g. a process restart).
         */
        fun setHa(url: String): HaChange
    }

    data class HaChange(val error: String? = null, val afterReply: (() -> Unit)? = null)

    private val steps = steps.toList()

    /** Every step op by its wire name, `<step id>.<op>`. */
    private val ops: Map<String, SetupOp> = steps.flatMap { s -> s.ops.map { (name, op) -> "${s.id}.$name" to op } }.toMap()

    /** Older wire names: the core's, then each step's. */
    private val aliases: Map<String, String> = mapOf("set_ha" to HA_SET_URL) +
        steps.flatMap { s -> s.legacyOps.map { (old, name) -> old to "${s.id}.$name" } }

    init {
        val ids = steps.map { it.id }
        require(ids.none { it in CORE_KEYS } && ids.size == ids.toSet().size) { "Setup step ids must be unique and not ${CORE_KEYS}: $ids" }
        val old = steps.flatMap { it.legacyOps.keys }
        require(old.none { it in CORE_OPS || it in ops } && old.size == old.toSet().size) { "Old op names must be unique and not the core's: $old" }
    }

    /** The whole state: the core's, and each step's part under its id. */
    private fun state(): JSONObject = backend.state().toJson().apply {
        steps.forEach { s -> s.channelState()?.let { put(s.id, it) } }
    }

    /** One request line → the reply line (without "\n") and what to run after writing it. */
    fun handle(line: String): Pair<String, (() -> Unit)?> {
        val req = runCatching { JSONObject(line) }.getOrNull()
            ?: return reply(null, error = "not a JSON object") to null
        val id: Any? = req.opt("id")?.takeIf { it != JSONObject.NULL }
        val asked = req.optString("op", "")
        val op = aliases[asked] ?: asked
        log("request ${id ?: "-"} $asked")
        return when (op) {
            "state" -> reply(id, state = state()) to null
            HA_SET_URL -> {
                val url = req.optString("url", "").trim()
                if (!ChannelState.validHaUrl(url)) {
                    reply(id, error = "That doesn't look like a web address (http://… or https://…).") to null
                } else {
                    val change = backend.setHa(url)
                    if (change.error != null) reply(id, error = change.error) to null
                    else reply(id, state = state()) to change.afterReply
                }
            }
            else -> when (val step = ops[op]) {
                null -> reply(id, error = "Unknown request '$asked'.") to null
                else -> when (val err = step.run(req)) {
                    null -> reply(id, state = state()) to null
                    else -> reply(id, error = err) to null
                }
            }
        }
    }

    /** The state the client last got (reply or event), to push only changes. */
    private var lastSent: String? = null

    private fun reply(id: Any?, state: JSONObject? = null, error: String? = null): String {
        val o = JSONObject()
        id?.let { o.put("id", it) }
        o.put("ok", error == null)
        if (state != null) {
            lastSent = state.toString()
            o.put("state", state)
        }
        error?.let { o.put("error", it) }
        return o.toString()
    }

    /** A state event if the state changed since the client last saw it (nothing before it asked once). */
    fun pushIfChanged(): String? {
        val prev = lastSent ?: return null
        val s = state()
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
        const val HA_SET_URL = "ha.set_url"
        /** The core's keys in the state; no step may take them. */
        val CORE_KEYS = setOf("ip", "ha")
        /** The core's requests, by every name they have. */
        val CORE_OPS = setOf("state", HA_SET_URL, "set_ha")
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
