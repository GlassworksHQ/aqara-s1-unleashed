/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The person who signed in at Home Assistant, as `auth/current_user` answers. */
data class HaUser(val name: String, val isAdmin: Boolean)

/** What the Manage page needs from Home Assistant. The frame's app implements it over its HA client. */
interface HaGateway {
    sealed class Tokens {
        class Ok(val accessToken: String, val refreshToken: String?) : Tokens() {
            override fun toString() = "Ok(<redacted>)"
        }
        /** [invalidCode]: the code was used or is too old. [unreachable]: HA didn't answer. */
        class Failed(val reason: String, val invalidCode: Boolean = false, val unreachable: Boolean = false) : Tokens()
    }

    /** `POST <haBase>/auth/token`, `grant_type=authorization_code`, with the PKCE verifier. */
    fun exchangeCode(haBase: String, clientId: String, code: String, codeVerifier: String): Tokens

    /** `auth/current_user` over HA's websocket, with [accessToken]. Null when HA didn't say. */
    fun currentUser(haBase: String, accessToken: String): HaUser?

    /** `POST <haBase>/auth/revoke` with the refresh token: HA drops it and every access token made from it. */
    fun revoke(haBase: String, clientId: String, refreshToken: String): Boolean
}

/**
 * The Manage page's side of "sign in with Home Assistant": the panel is the OAuth client, at the HA
 * address it uses itself, and only when that address is on the local network.
 *
 * 1. [start]: a new state and PKCE verifier, kept for [ttlMs], bound to the browser that asked (the
 *    pre-sign-in cookie). The browser goes to HA's own login page; the panel never sees the password.
 * 2. [finish]: the state must match that browser; the code is exchanged, `auth/current_user` must say
 *    `is_admin`, and the refresh token is revoked straight away, whatever the answer. The Manage page
 *    then keeps its own session; no HA token is stored.
 *
 * `client_id` is `http://<host>/` and the redirect `http://<host>/manage/callback`: same scheme, host and
 * port, which HA's IndieAuth accepts without fetching anything (`components/auth/indieauth.py`).
 */
class HaSignIn(
    private val gateway: HaGateway,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** Which addresses count as the local network. Tests add the RFC 5737 ranges their examples use. */
    private val local: (InetAddress) -> Boolean = ::isLocalAddress,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10 * 60_000L,
    /** A problem worth a line in the log (a revoke HA didn't take). Never gets a token. */
    private val warn: (String) -> Unit = {},
) {
    class Pending(val state: String, val verifier: String, val browser: String, val haBase: String, val clientId: String,
                  val redirectUri: String, val remember: Boolean, val next: String, val createdMs: Long)

    sealed class Result {
        /** [haBase]: the HA address the person was checked against. */
        class Ok(val user: HaUser, val remember: Boolean, val next: String, val haBase: String) : Result()
        class Failed(val title: String, val text: String) : Result()
    }

    private val random = SecureRandom()
    private val pending = LinkedHashMap<String, Pending>()

    /** The URL to send the browser to, or why not. */
    fun start(haBase: String, host: String, browser: String, remember: Boolean, next: String): Pair<String?, String?> {
        val base = haBase.trim().trimEnd('/')
        if (!isLocal(base)) return null to "The panel only signs in through Home Assistant on your own network, and its address ($base) isn't. Use the code on the panel."
        val clientId = "http://$host/"
        val redirect = "http://$host$CALLBACK"
        val p = Pending(token(24), token(32), browser, base, clientId, redirect, remember, next, clock())
        synchronized(pending) {
            prune()
            pending[p.state] = p
            while (pending.size > 100) pending.remove(pending.keys.first())
        }
        val url = base + "/auth/authorize?response_type=code&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirect) +
            "&state=" + enc(p.state) + "&code_challenge=" + enc(challenge(p.verifier)) + "&code_challenge_method=S256"
        return url to null
    }

    /** HA sent the browser back with [code] and [state]. [browser] is its pre-sign-in cookie. */
    fun finish(state: String?, code: String?, error: String?, browser: String?): Result {
        val p = synchronized(pending) { prune(); state?.let { pending.remove(it) } }
            ?: return Result.Failed("This sign-in has expired", "Start again from the Manage page.")
        // Another browser's state (login CSRF): refuse, the code is not used.
        if (browser == null || browser != p.browser) return Result.Failed("Can't sign in", "Start again from the Manage page, in this browser.")
        if (error != null) return Result.Failed("Sign-in cancelled", "Home Assistant answered: $error.")
        if (code.isNullOrEmpty()) return Result.Failed("Can't sign in", "Home Assistant didn't send an authorization code.")
        val tokens = gateway.exchangeCode(p.haBase, p.clientId, code, p.verifier)
        if (tokens !is HaGateway.Tokens.Ok) {
            val f = tokens as HaGateway.Tokens.Failed
            return when {
                f.invalidCode -> Result.Failed("This sign-in has expired", "Start again from the Manage page.")
                f.unreachable -> Result.Failed("Can't reach Home Assistant", "Use the code on the panel instead.")
                else -> Result.Failed("Can't sign in", "Home Assistant refused the sign-in (${f.reason}).")
            }
        }
        val user = try {
            gateway.currentUser(p.haBase, tokens.accessToken)
        } finally {
            // Revoked whatever happens next: the panel keeps no HA token from this sign-in. Once more if HA
            // didn't take it; if it still didn't, the token stays in HA (the person's profile) until removed.
            tokens.refreshToken?.let {
                if (!gateway.revoke(p.haBase, p.clientId, it) && !gateway.revoke(p.haBase, p.clientId, it)) {
                    warn("Home Assistant didn't take the revoke of the Manage sign-in's token; it stays under the user's profile until removed there")
                }
            }
        }
        return when {
            user == null -> Result.Failed("Can't sign in", "Home Assistant didn't say who signed in.")
            !user.isAdmin -> Result.Failed("Administrators only", "${user.name} isn't a Home Assistant administrator. Sign in with an administrator's account.")
            else -> Result.Ok(user, p.remember, p.next, p.haBase)
        }
    }

    private fun prune() {
        val now = clock()
        pending.entries.removeAll { now - it.value.createdMs >= ttlMs }
    }

    /**
     * True when [haBase]'s host is on the local network: a private, link-local or loopback address, an
     * mDNS `.local` name, or a name that resolves only to such addresses. A Nabu Casa or other public URL
     * is refused.
     */
    fun isLocal(haBase: String): Boolean {
        val host = try { URI(haBase).host } catch (_: Exception) { null }?.trim('[', ']')?.lowercase() ?: return false
        if (host.isEmpty() || host.endsWith(".nabu.casa")) return false
        // mDNS names only exist on the local link (RFC 6762), and Android 10 can't resolve them itself.
        if (host.endsWith(".local")) return true
        val addrs = try { resolve(host) } catch (_: Exception) { return false }
        return addrs.isNotEmpty() && addrs.all(local)
    }

    private fun token(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    companion object {
        const val CALLBACK = "/manage/callback"

        fun isLocalAddress(a: InetAddress): Boolean =
            a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
                (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) // fc00::/7, unique local

        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
