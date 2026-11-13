package sh.edm.s1kiosk.auth

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI

/** One Home Assistant websocket connection, as [HaThreadClient] uses it (a fake in tests). */
interface HaSocket {
    fun send(text: String)
    /** The next text message, or null when the connection closed. */
    fun receive(): String?
    fun close()
}

/** What fetching the preferred Thread network's dataset gave. Never log [Ok.tlv]. */
sealed class ThreadFetch {
    class Ok(val tlv: String, val networkName: String, val channel: Int?) : ThreadFetch() {
        override fun toString() = "Ok(network=$networkName, channel=$channel, tlv=<redacted>)"
    }

    /** The token belongs to a user who isn't an administrator (`unauthorized`). */
    object NotAdmin : ThreadFetch() { override fun toString() = "NotAdmin" }

    /** Home Assistant has no preferred Thread network ([known] = how many datasets it has). */
    class NoPreferred(val known: Int) : ThreadFetch() { override fun toString() = "NoPreferred(known=$known)" }

    /** Couldn't reach HA, auth failed, or HA answered something unexpected. */
    class Failed(val reason: String) : ThreadFetch() { override fun toString() = "Failed($reason)" }
}

/**
 * Fetches the active dataset of Home Assistant's *preferred* Thread network over HA's websocket API.
 * Pure JVM (HaThreadClientTest, against a scripted socket).
 *
 * Checked against Home Assistant 2026.11 (`components/thread/websocket_api.py`, `components/websocket_api/auth.py`):
 * - `/api/websocket`: HA sends `auth_required`; the client answers `{"type":"auth","access_token":…}`;
 *   HA answers `auth_ok` or `auth_invalid` (and closes).
 * - `thread/list_datasets` → `{"datasets":[{dataset_id, network_name, channel, preferred, …}]}`.
 * - `thread/get_dataset_tlv` + `dataset_id` → `{"tlv":"<hex>"}`.
 * - Both commands are `@require_admin`: a non-admin user gets `{"success":false,"error":{"code":"unauthorized"}}`.
 */
class HaThreadClient(private val connect: (url: String) -> HaSocket) {

    fun fetchPreferred(haBase: String, accessToken: String): ThreadFetch {
        val url = websocketUrl(haBase) ?: return ThreadFetch.Failed("Home Assistant's address isn't an http(s) URL")
        val ws = try { connect(url) } catch (e: IOException) {
            return ThreadFetch.Failed("can't reach Home Assistant (${e.message ?: e.javaClass.simpleName})")
        }
        try {
            return run(ws, accessToken)
        } catch (e: IOException) {
            return ThreadFetch.Failed("connection to Home Assistant failed (${e.message ?: e.javaClass.simpleName})")
        } catch (e: JSONException) {
            return ThreadFetch.Failed("unexpected answer from Home Assistant")
        } finally {
            runCatching { ws.close() }
        }
    }

    private fun run(ws: HaSocket, accessToken: String): ThreadFetch {
        val hello = next(ws) ?: return ThreadFetch.Failed("Home Assistant closed the connection")
        if (hello.optString("type") != "auth_required") return ThreadFetch.Failed("unexpected greeting from Home Assistant")
        ws.send(JSONObject().put("type", "auth").put("access_token", accessToken).toString())
        val auth = next(ws) ?: return ThreadFetch.Failed("Home Assistant closed the connection while signing in")
        when (auth.optString("type")) {
            "auth_ok" -> {}
            "auth_invalid" -> return ThreadFetch.Failed("Home Assistant refused the sign-in")
            else -> return ThreadFetch.Failed("unexpected sign-in answer from Home Assistant")
        }

        ws.send(JSONObject().put("id", 1).put("type", "thread/list_datasets").toString())
        val list = result(ws, 1)
        list.error?.let { return it }
        val datasets = list.value?.optJSONArray("datasets") ?: JSONArray()
        var preferred: JSONObject? = null
        for (i in 0 until datasets.length()) {
            val d = datasets.optJSONObject(i) ?: continue
            if (d.optBoolean("preferred", false)) { preferred = d; break }
        }
        val p = preferred ?: return ThreadFetch.NoPreferred(datasets.length())
        val id = p.optString("dataset_id", "").ifEmpty { return ThreadFetch.Failed("the preferred network has no dataset id") }

        ws.send(JSONObject().put("id", 2).put("type", "thread/get_dataset_tlv").put("dataset_id", id).toString())
        val tlvRes = result(ws, 2)
        tlvRes.error?.let { return it }
        val tlv = tlvRes.value?.optString("tlv", "").orEmpty()
        if (!isHexTlv(tlv)) return ThreadFetch.Failed("Home Assistant's dataset isn't hex TLVs")
        val channel = if (p.has("channel") && !p.isNull("channel")) p.optInt("channel") else null
        return ThreadFetch.Ok(tlv.lowercase(), p.optString("network_name", ""), channel)
    }

    private class Res(val value: JSONObject?, val error: ThreadFetch?)

    /** Waits for the `result` with [id] (events and other ids are skipped). */
    private fun result(ws: HaSocket, id: Int): Res {
        while (true) {
            val m = next(ws) ?: return Res(null, ThreadFetch.Failed("Home Assistant closed the connection"))
            if (m.optString("type") != "result" || m.optInt("id", -1) != id) continue
            if (m.optBoolean("success", false)) return Res(m.optJSONObject("result") ?: JSONObject(), null)
            val err = m.optJSONObject("error")
            val code = err?.optString("code").orEmpty()
            return Res(null, when (code) {
                "unauthorized" -> ThreadFetch.NotAdmin
                // No Thread integration loaded, or a Home Assistant older than 2023.3 (when
                // thread/list_datasets and get_dataset_tlv came): it doesn't know the command. No version
                // check beyond this: the answer itself says it, and the words say what to do.
                "unknown_command" -> ThreadFetch.Failed(NO_THREAD_API)
                else -> ThreadFetch.Failed("Home Assistant answered ${code.ifEmpty { "an error" }}")
            })
        }
    }

    private fun next(ws: HaSocket): JSONObject? {
        val t = ws.receive() ?: return null
        return JSONObject(t)
    }

    companion object {
        /** `http://h:8123/x` → `ws://h:8123/api/websocket`, `https://…` → `wss://…`; null if not http(s). */
        /** Home Assistant doesn't know the Thread commands. */
        const val NO_THREAD_API = "Home Assistant doesn't offer Thread networks here: add its Thread integration (Settings → Devices & services), on Home Assistant 2023.3 or later"

        fun websocketUrl(haBase: String): String? {
            val origin = OAuth.origin(haBase) ?: return null
            val u = URI(origin)
            val scheme = if (u.scheme == "https") "wss" else "ws"
            return "$scheme://${u.rawAuthority}/api/websocket"
        }

        fun isHexTlv(s: String): Boolean =
            s.isNotEmpty() && s.length % 2 == 0 && s.length <= 2 * 254 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

/** [HaSocket] over the kiosk's own RFC 6455 client (ws:// and wss://). */
class WsHaSocket private constructor(private val ws: sh.edm.s1kiosk.sendspin.WsClient) : HaSocket {
    override fun send(text: String) = ws.sendText(text)

    override fun receive(): String? {
        while (true) {
            val m = ws.receive() ?: return null
            if (m.text) return m.data.toString(Charsets.UTF_8)
        }
    }

    override fun close() = ws.close()

    companion object {
        @Throws(IOException::class)
        fun connect(url: String): WsHaSocket =
            WsHaSocket(sh.edm.s1kiosk.sendspin.WsClient.connect(url, connectTimeoutMs = 8_000, readTimeoutMs = 15_000))
    }
}
