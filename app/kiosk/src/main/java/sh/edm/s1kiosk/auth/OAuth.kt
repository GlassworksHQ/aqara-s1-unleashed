package sh.edm.s1kiosk.auth

import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Home Assistant OAuth2 / IndieAuth client logic for the QR sign-in (pure JVM, see OAuthTest).
 *
 * Checked against Home Assistant 2026.11.0:
 * - `components/auth/indieauth.py` `verify_redirect_uri`: the redirect_uri is accepted without any
 *   fetch when it has the **same scheme and netloc** (host + port) as the client_id;
 *   `_parse_client_id`: http(s), no fragment/userinfo, and the host is a domain name or a *local*
 *   IP (private LAN addresses are fine).
 * - `components/auth/__init__.py` `TokenView`: form-encoded POST `/auth/token`;
 *   `grant_type=authorization_code` + `code` + `client_id` returns `access_token`, `token_type`,
 *   `refresh_token`, `expires_in` (1800), `ha_auth_provider`; codes are single use and expire after
 *   10 min (`AUTH_CODE_EXPIRATION`) → `{"error":"invalid_request","error_description":"Invalid code"}`.
 *   `grant_type=refresh_token` + `refresh_token` + `client_id` returns `access_token`, `expires_in`;
 *   the client_id must equal the one the refresh token was created for, else `invalid_request`; an
 *   unknown/revoked token gives `invalid_grant`. `POST /auth/revoke` with `token` always answers 200.
 * - frontend `src/data/auth.ts` `redirectWithAuthCode`: the phone is sent to
 *   `<redirect_uri>?code=<code>&state=<state>` (plus `&storeToken=true` if ticked).
 */
object OAuth {
    const val SIGNIN_PORT = 8099

    /** `http://<host:port>/`: the client_id is the panel's own URL, as the phone reached it. */
    fun clientId(hostPort: String): String = "http://$hostPort/"

    fun redirectUri(hostPort: String): String = "http://$hostPort/callback"

    /**
     * Validates a `Host` header for use in the client_id: an IPv4 address or a hostname, with an
     * optional port. Anything else (userinfo, paths, IPv6 brackets we don't need) is refused.
     */
    fun sanitizeHost(host: String?): String? {
        val h = host?.trim()?.lowercase() ?: return null
        return h.takeIf { HOST_RE.matches(it) }
    }

    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?$")

    fun authorizeUrl(haBase: String, clientId: String, redirectUri: String, state: String): String =
        trimBase(haBase) + "/auth/authorize?response_type=code" +
            "&client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(redirectUri) +
            "&state=" + enc(state)

    /** `http://h:8123/` or `http://h:8123/lovelace/x` → `http://h:8123` (scheme://host[:port]). */
    fun origin(url: String): String? = try {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || u.host.isNullOrEmpty()) null
        else scheme + "://" + u.rawAuthority.lowercase()
    } catch (_: Exception) {
        null
    }

    fun trimBase(url: String): String = url.trim().trimEnd('/')

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    /** Parses a URL query (`a=1&b=x%20y`); repeated keys keep the first value. */
    fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = dec(if (i < 0) part else part.substring(0, i))
            val v = if (i < 0) "" else dec(part.substring(i + 1))
            out.putIfAbsent(k, v)
        }
        return out
    }

    private fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private val random = SecureRandom()

    fun randomState(): String {
        val b = ByteArray(24)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

/**
 * One pending sign-in: what /signin (or /thread) promised HA, needed again at its callback.
 * [purpose] keeps the two flows apart: a /signin state is refused at /thread/callback and back.
 */
data class PendingSignIn(
    val clientId: String, val redirectUri: String, val haBase: String, val createdAt: Long,
    val purpose: String = PURPOSE_SIGNIN,
) {
    companion object {
        const val PURPOSE_SIGNIN = "signin"
        const val PURPOSE_THREAD = "thread"
    }
}

/**
 * OAuth `state` values handed out by /signin. Single use, expire after [ttlMs] (HA's own codes live
 * 10 minutes), at most [max] outstanding (oldest dropped). Thread-safe.
 */
class StateStore(
    private val ttlMs: Long = 10 * 60_000L,
    private val max: Int = 16,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pending = LinkedHashMap<String, PendingSignIn>()

    @Synchronized
    fun create(clientId: String, redirectUri: String, haBase: String, purpose: String = PendingSignIn.PURPOSE_SIGNIN): String {
        prune()
        while (pending.size >= max) pending.remove(pending.keys.first())
        val s = OAuth.randomState()
        pending[s] = PendingSignIn(clientId, redirectUri, haBase, clock(), purpose)
        return s
    }

    /**
     * Returns and removes the pending sign-in for [state], or null if unknown/expired/used, or made
     * for another [purpose] (then it is left alone).
     */
    @Synchronized
    fun consume(state: String?, purpose: String = PendingSignIn.PURPOSE_SIGNIN): PendingSignIn? {
        if (state.isNullOrEmpty()) return null
        prune()
        val p = pending[state] ?: return null
        if (p.purpose != purpose) return null
        return pending.remove(state)
    }

    @Synchronized
    fun size(): Int { prune(); return pending.size }

    private fun prune() {
        val now = clock()
        pending.entries.removeAll { now - it.value.createdAt >= ttlMs }
    }
}

/** Parsed `/auth/token` reply. */
sealed class TokenResult {
    data class Ok(
        val accessToken: String,
        val expiresIn: Long,
        /** Only for grant_type=authorization_code. */
        val refreshToken: String?,
    ) : TokenResult() {
        override fun toString() = "Ok(expiresIn=$expiresIn, refreshToken=${if (refreshToken != null) "<set>" else "none"})"
    }

    /** OAuth error (`invalid_grant`, `invalid_request`, `access_denied`, …) or a transport problem. */
    data class Error(val code: String, val description: String, val httpStatus: Int) : TokenResult() {
        /** HA no longer knows the refresh token / code: the panel must sign in again. */
        val isRevoked: Boolean get() = code == "invalid_grant" || code == "access_denied"
        val isInvalidCode: Boolean get() = code == "invalid_request" && description.contains("code", ignoreCase = true)
    }

    companion object {
        fun parse(status: Int, body: String): TokenResult {
            val json = try { JSONObject(body) } catch (_: JSONException) { null }
            if (status in 200..299 && json != null) {
                val at = json.optString("access_token", "")
                if (at.isNotEmpty()) {
                    return Ok(at, json.optLong("expires_in", 1800L), json.optString("refresh_token", "").ifEmpty { null })
                }
                return Error("invalid_response", "no access_token in reply", status)
            }
            if (json != null && json.has("error")) {
                return Error(json.optString("error"), json.optString("error_description", ""), status)
            }
            return Error(if (status <= 0) "network_error" else "http_$status", body.take(200), status)
        }
    }
}

/**
 * Caches the access token until [marginMs] before it expires (`expires_in` is 1800 s on HA).
 * Thread-safe.
 */
class AccessTokenCache(private val marginMs: Long = 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private var token: String? = null
    private var expiresAt = 0L

    @Synchronized
    fun put(accessToken: String, expiresInSec: Long) {
        token = accessToken
        expiresAt = clock() + expiresInSec * 1000L
    }

    /** The cached token if it is still valid for at least the margin. */
    @Synchronized
    fun get(): String? = token?.takeIf { clock() < expiresAt - marginMs }

    /** Seconds left (for the frontend's `expires_in`). */
    @Synchronized
    fun secondsLeft(): Long = ((expiresAt - clock()) / 1000L).coerceAtLeast(0L)

    @Synchronized
    fun clear() { token = null; expiresAt = 0L }
}

/** What is stored after a successful sign-in. Never log [refreshToken]. */
data class StoredAuth(val haBase: String, val clientId: String, val refreshToken: String) {
    override fun toString() = "StoredAuth(haBase=$haBase, clientId=$clientId, refreshToken=<redacted>)"
}

/**
 * URLs the kiosk loads in GeckoView once the panel is signed in. The HA frontend switches to the
 * external auth bridge when `window.externalApp` exists or the query contains `external_auth=1`
 * (frontend `src/data/external.ts` `isExternal`); the content script only installs the bridge on
 * pages carrying the flag, so the app adds it to every top-level HA page load.
 */
object FrontendUrls {
    const val FLAG = "external_auth=1"

    fun hasFlag(url: String): Boolean {
        val q = url.substringBefore('#').substringAfter('?', "")
        return q.split('&').any { it == FLAG }
    }

    /** Adds `external_auth=1` to the query, keeping any fragment. */
    fun withFlag(url: String): String {
        if (hasFlag(url)) return url
        val hash = url.indexOf('#')
        val main = if (hash >= 0) url.substring(0, hash) else url
        val frag = if (hash >= 0) url.substring(hash) else ""
        val sep = when {
            !main.contains('?') -> "?"
            main.endsWith("?") || main.endsWith("&") -> ""
            else -> "&"
        }
        return main + sep + FLAG + frag
    }

    fun sameOrigin(url: String?, base: String?): Boolean {
        if (url == null || base == null) return false
        val a = OAuth.origin(url) ?: return false
        return a == OAuth.origin(base)
    }

    private fun path(url: String): String = try { java.net.URI(url).rawPath ?: "/" } catch (_: Exception) { "/" }

    /** HA's login page (what an unauthenticated frontend redirects to). */
    fun isAuthorizePage(url: String?, base: String?): Boolean =
        url != null && sameOrigin(url, base) && path(url).startsWith("/auth/authorize")

    /** A top-level HA page load that should get the flag (not the /auth/ or /api/ endpoints). */
    fun needsFlag(url: String, base: String?): Boolean {
        if (!sameOrigin(url, base) || hasFlag(url)) return false
        val p = path(url)
        return !p.startsWith("/auth/") && !p.startsWith("/api/")
    }
}
