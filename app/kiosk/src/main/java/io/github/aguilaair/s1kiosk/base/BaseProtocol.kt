package io.github.aguilaair.s1kiosk.base

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure (JVM-testable) side of the wall base ("bracket"/"dock") support: resource ids, the JSON the
 * stock `com.lumi.systemmanager` LocalService expects, and defensive parsing of its callbacks.
 * See docs/bases.md for where each of these comes from in the stock firmware.
 */
object BaseRes {
    /** Dock class; `value >>> 24`: 1 = switch base, 0x10 = Ethernet base, 0 = none. */
    const val DOCK_CLASS = "14.160.85"
    /** Write 1 = re-detect (not used by the kiosk). */
    const val DOCK_DETECT = "200.1.10003"
    const val CHANNEL_COUNT = "13.8.85"
    const val RELAY_MASK = "14.21.85"
    const val POWER = "0.12.85"
    const val ENERGY = "0.13.85"
    const val PROTECTION = "8.0.2014"
    const val FIRMWARE = "8.0.2022"
    const val CONNECT_STATE = "3.7.85"

    const val MAX_CHANNELS = 3

    /** Relay state of channel 1..3 (read/write 0/1). */
    fun relay(ch: Int) = "4.$ch.85"
    /** Physical button click event of channel 1..3. */
    fun click(ch: Int) = "13.$ch.85"
    /** Channel name of channel 1..3 (only logged; see BaseEntities). */
    fun name(ch: Int) = "4.$ch.28"

    val RELAYS = (1..MAX_CHANNELS).map(::relay)
    val CLICKS = (1..MAX_CHANNELS).map(::click)
    val NAMES = (1..MAX_CHANNELS).map(::name)

    /** Resources whose appearance in an event/report should trigger a dock class re-read. */
    val DOCK_TRIGGERS = setOf(DOCK_CLASS, DOCK_DETECT, CHANNEL_COUNT, RELAY_MASK, CONNECT_STATE)

    /** Read once when a switch base is (re)detected. */
    val ATTACH_READ = listOf(CHANNEL_COUNT, RELAY_MASK, FIRMWARE) + RELAYS + listOf(POWER, ENERGY, PROTECTION) + NAMES

    /** Polled while a switch base is attached. */
    val POLL_READ = RELAYS + listOf(POWER, ENERGY, PROTECTION)
}

enum class BaseKind { UNAVAILABLE, NONE, SWITCH, ETHERNET, UNKNOWN }

/**
 * What is mounted, as far as the entity set is concerned. [channels] are 1-based channel numbers
 * (only for [BaseKind.SWITCH]); [rawClass] is the dock class byte for [BaseKind.UNKNOWN].
 */
data class BaseLayout(val kind: BaseKind, val channels: List<Int> = emptyList(), val rawClass: Int = 0) {

    /** Value of the "Base" text sensor. */
    fun describe(): String = when (kind) {
        BaseKind.UNAVAILABLE -> "unavailable"
        BaseKind.NONE -> "none"
        BaseKind.SWITCH -> "switch (${channels.size}ch)"
        BaseKind.ETHERNET -> "ethernet"
        BaseKind.UNKNOWN -> "unknown (0x%02x)".format(rawClass)
    }

    val attached: Boolean get() = kind == BaseKind.SWITCH || kind == BaseKind.ETHERNET || kind == BaseKind.UNKNOWN

    /** Persisted form (prefs), e.g. `SWITCH:1,2`. */
    fun encode(): String = "${kind.name}:${channels.joinToString(",")}:$rawClass"

    companion object {
        val NONE = BaseLayout(BaseKind.NONE)
        val UNAVAILABLE = BaseLayout(BaseKind.UNAVAILABLE)

        fun decode(s: String?): BaseLayout? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(":")
            val kind = BaseKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            val ch = parts.getOrNull(1).orEmpty().split(",").mapNotNull { it.toIntOrNull() }
                .filter { it in 1..BaseRes.MAX_CHANNELS }.distinct().sorted()
            val raw = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return BaseLayout(kind, if (kind == BaseKind.SWITCH) ch else emptyList(), raw)
        }
    }
}

object BaseProtocol {
    private val RES_RE = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,6}$")

    // ------------------------------------------------------------------ decoding

    /**
     * Dock class value (`14.160.85`) to a base kind. The class byte is the top byte; stock code
     * (`AdvanceFragment`) uses `value >> 24` with 1 = switch dock (`ResCommon.SWITCH_DOCK` =
     * 0x0100F010) and 0x10 = Ethernet dock (`ETH_DOCK` = 0x10000001). Negative values (-1 =
     * stock's "missing" marker) and 0 mean none.
     */
    fun decodeDockClass(value: Int?): Pair<BaseKind, Int> {
        if (value == null || value <= 0) return BaseKind.NONE to 0
        return when (val cls = value ushr 24) {
            0 -> BaseKind.NONE to 0
            1 -> BaseKind.SWITCH to cls
            0x10 -> BaseKind.ETHERNET to cls
            else -> BaseKind.UNKNOWN to cls
        }
    }

    /**
     * Relay channels: bits of the relay mask (`14.21.85`, bit 0 = channel 1) if non-zero, else
     * channels 1..count (`13.8.85`); capped at 3 channels.
     */
    fun channels(mask: Int?, count: Int?): List<Int> {
        if (mask != null && mask > 0) {
            val fromMask = (1..BaseRes.MAX_CHANNELS).filter { mask and (1 shl (it - 1)) != 0 }
            if (fromMask.isNotEmpty()) return fromMask
        }
        if (count != null && count > 0) return (1..count.coerceAtMost(BaseRes.MAX_CHANNELS)).toList()
        return emptyList()
    }

    /** Protection state (`8.0.2014`, `DockConstant.RES_VALUE_DEVICE_PROTECTION_*`). */
    fun protectionText(v: Int?): String? = when (v) {
        null -> null
        0 -> "normal"
        1 -> "over_temperature"
        2 -> "over_load"
        3 -> "over_temperature_error"
        4 -> "over_load_error"
        5 -> "over_temperature_removed"
        6 -> "over_load_removed"
        else -> "unknown ($v)"
    }

    /** Event types declared by the base button event entities. */
    val EVENT_TYPES = listOf("single", "double", "triple", "long", "release", "unknown")

    /**
     * Click event value (`13.x.85`) to an event type. Never observed from a base yet; this follows
     * Aqara's usual multistate convention for switch buttons (0 hold, 1 single, 2 double,
     * 3 triple, 16 hold, 17/255 release). The raw value is always logged.
     */
    fun clickType(raw: Any?): String {
        val v = asInt(raw) ?: return when (raw?.toString()?.trim()?.lowercase()) {
            "single", "click" -> "single"
            "double", "double_click" -> "double"
            "triple" -> "triple"
            "long", "hold", "long_press", "long_click" -> "long"
            "release" -> "release"
            else -> "unknown"
        }
        return when (v) {
            1 -> "single"
            2 -> "double"
            3 -> "triple"
            0, 16 -> "long"
            17, 255 -> "release"
            else -> "unknown"
        }
    }

    fun asInt(v: Any?): Int? = when (v) {
        null, JSONObject.NULL -> null
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toDouble().takeIf { !it.isNaN() }?.toLong()?.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.trim().let { s -> s.toIntOrNull() ?: s.toLongOrNull()?.toInt() ?: s.toDoubleOrNull()?.toLong()?.toInt() }
        else -> null
    }

    fun asFloat(v: Any?): Float? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toFloat().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> v.trim().toFloatOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        else -> null
    }

    fun asText(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        else -> v.toString().trim().takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ requests

    fun readRequest(did: String, res: List<String>): String = JSONObject().apply {
        put("did", did)
        if (res.size == 1) put("res", res[0]) else put("res", JSONArray(res))
    }.toString()

    /** Same shape as stock `LocalServer.writeGatewayRes` / `LocalServiceIml.localWrite`. */
    fun writeRequest(did: String, res: String, value: Int, nowMs: Long): String = JSONObject().apply {
        put("did", did)
        put("res", res)
        put("src", "3,,$nowMs,,,")
        put("value", value)
    }.toString()

    // ------------------------------------------------------------------ replies

    /**
     * One parsed callback payload. [values] maps resource id to its raw JSON value (Number,
     * String, ...). [foreign] is true when the payload carries a `did` that isn't the panel's
     * (e.g. a Zigbee sub-device, which use the same resource ids), in which case [values] is empty.
     */
    data class Parsed(val values: Map<String, Any>, val did: String?, val foreign: Boolean, val action: String?)

    /**
     * Extracts resource values from any of the shapes the stock code handles:
     * `{"did":..,"result":{"14.160.85":123}}` (read replies, `LocalServiceIml`),
     * `{"result":{"res":"4.1.85","value":1}}` (`LocalServer.parseResult`),
     * `{"did":..,"action":"report","res":"4.1.85","value":"1"}` (events, `LocalServer.parseEvent`),
     * and `res_list` / `params` arrays of `{"res_name":..,"value":..}` (heartbeats / reports).
     * Unknown shapes yield no values; never throws.
     */
    fun parse(payload: String?, ourDid: String?): Parsed? {
        if (payload.isNullOrBlank()) return null
        val root = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            return null
        }
        val did = root.optString("did", "").takeIf { it.isNotEmpty() }
            ?: root.optJSONObject("data")?.optString("did", "")?.takeIf { it.isNotEmpty() }
        val action = root.optString("action", "").takeIf { it.isNotEmpty() }
        if (did != null && !ourDid.isNullOrEmpty() && did != ourDid) {
            return Parsed(emptyMap(), did, foreign = true, action = action)
        }
        val out = LinkedHashMap<String, Any>()
        collect(root, out, 0)
        return Parsed(out, did, foreign = false, action = action)
    }

    private fun collect(node: Any?, out: MutableMap<String, Any>, depth: Int) {
        if (depth > 5) return
        when (node) {
            is JSONObject -> {
                val res = node.optString("res", "").ifEmpty { node.optString("res_name", "") }
                if (RES_RE.matches(res) && node.has("value")) {
                    val v = node.opt("value")
                    if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) out[res] = v
                }
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (RES_RE.matches(k)) {
                        if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) out[k] = v
                    } else if (v is JSONObject || v is JSONArray) {
                        collect(v, out, depth + 1)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
        }
    }

    /** Replaces the DID (and its part after a `lumi.` prefix) so payloads can be logged. */
    fun redact(s: String, did: String?): String {
        if (did.isNullOrEmpty()) return s
        var r = s.replace(did, "<did>")
        val bare = did.substringAfter("lumi.", "")
        if (bare.length >= 6) r = r.replace(bare, "<did>")
        return r
    }
}
