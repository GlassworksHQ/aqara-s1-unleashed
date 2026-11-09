package sh.edm.s1kiosk.auth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP for the auth endpoints; an interface so tests can use a fake HA. */
interface HttpPoster {
    /** POSTs a form body; returns (status, body). Status <= 0 means the request didn't complete. */
    fun postForm(url: String, body: String): Pair<Int, String>

    /** GET; returns (status, body), body capped at [maxBytes]. */
    fun get(url: String, maxBytes: Int = 64 * 1024): Pair<Int, String>
}

class UrlConnectionPoster(private val timeoutMs: Int = 10_000) : HttpPoster {
    override fun postForm(url: String, body: String): Pair<Int, String> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.instanceFollowRedirects = false
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        code to text
    } catch (e: IOException) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }

    override fun get(url: String, maxBytes: Int): Pair<Int, String> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = false
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val bytes = stream?.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (buf.size() < maxBytes) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, minOf(n, maxBytes - buf.size()))
            }
            buf.toByteArray()
        } ?: ByteArray(0)
        c.disconnect()
        code to bytes.toString(Charsets.UTF_8)
    } catch (e: IOException) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }
}

/** `/auth/token` and `/auth/revoke` calls (Home Assistant `components/auth/__init__.py`). */
class HaTokenClient(private val http: HttpPoster) {
    fun exchangeCode(haBase: String, clientId: String, code: String): TokenResult {
        val (status, body) = http.postForm(
            OAuth.trimBase(haBase) + "/auth/token",
            OAuth.form("grant_type" to "authorization_code", "code" to code, "client_id" to clientId),
        )
        return TokenResult.parse(status, body)
    }

    fun refresh(auth: StoredAuth): TokenResult {
        val (status, body) = http.postForm(
            OAuth.trimBase(auth.haBase) + "/auth/token",
            OAuth.form("grant_type" to "refresh_token", "refresh_token" to auth.refreshToken, "client_id" to auth.clientId),
        )
        return TokenResult.parse(status, body)
    }

    /** Best effort; HA always answers 200. Returns false only on a transport error. */
    fun revoke(auth: StoredAuth): Boolean =
        http.postForm(OAuth.trimBase(auth.haBase) + "/auth/revoke", OAuth.form("token" to auth.refreshToken)).first > 0
}
