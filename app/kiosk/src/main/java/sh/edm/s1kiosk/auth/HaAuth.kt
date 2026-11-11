package sh.edm.s1kiosk.auth

import android.content.Context
import android.os.Handler
import android.os.Looper
import sh.edm.s1kiosk.KioskPrefs
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The panel's Home Assistant credentials: the refresh token from the QR sign-in (stored in the
 * private `auth` prefs, never logged) and a cached access token. Process-wide; the sign-in server
 * is started by KioskService, the GeckoView bridge in MainActivity asks for access tokens.
 */
object HaAuth {
    /** Result for the frontend's `externalAuthSetToken(success, data)` callback. */
    sealed class Access {
        data class Ok(val accessToken: String, val expiresIn: Long) : Access() {
            override fun toString() = "Ok(expiresIn=$expiresIn)"
        }
        data class Failed(val reason: String, val signedOut: Boolean) : Access()
    }

    private lateinit var prefs: KioskPrefs
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ha-auth").apply { isDaemon = true } }
    private val http = UrlConnectionPoster()
    private val tokens = HaTokenClient(http)
    private val cache = AccessTokenCache()
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var server: SignInServer? = null

    /**
     * "Join Home Assistant's Thread network" (ThreadJoin): the panel's own token first, else a one-off
     * administrator sign-in through the sign-in server's `/thread`. The service sets [ThreadJoin.deliver].
     */
    val threadJoin: ThreadJoin = ThreadJoin(
        client = HaThreadClient { url -> WsHaSocket.connect(url) },
        tokens = tokens,
        haBase = { if (::prefs.isInitialized) prefs.haBase else null },
        panelToken = { done ->
            if (!signedIn) done(null)
            else accessToken(force = false) { a -> done((a as? Access.Ok)?.accessToken) }
        },
        exec = exec,
    )

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = KioskPrefs(context.applicationContext)
    }

    val signedIn: Boolean get() = ::prefs.isInitialized && prefs.hasAuth

    /** The HA base URL the stored token belongs to, if signed in. */
    val signedInBase: String? get() = if (::prefs.isInitialized) prefs.auth?.haBase else null

    /** Listener gets the new signed-in state on the main thread. */
    fun addListener(l: (Boolean) -> Unit) { listeners += l }
    fun removeListener(l: (Boolean) -> Unit) { listeners -= l }

    private fun notifyChanged() {
        val v = signedIn
        main.post { listeners.forEach { runCatching { it(v) } } }
    }

    /** Starts the sign-in web server on [OAuth.SIGNIN_PORT] (retries if the port is busy). */
    @Synchronized
    fun startServer(context: Context) {
        init(context)
        if (server != null) return
        val s = SignInServer(OAuth.SIGNIN_PORT, { prefs.haBase }, tokens, ::onSignedIn, thread = threadJoin)
        try {
            s.start()
            server = s
        } catch (e: IOException) {
            AuthLog.w("Cannot listen on ${OAuth.SIGNIN_PORT}: ${e.message}; retrying in 10 s")
            main.postDelayed({ startServer(context) }, 10_000)
        }
    }

    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
    }

    private fun onSignedIn(auth: StoredAuth, first: TokenResult.Ok) {
        prefs.auth = auth
        cache.put(first.accessToken, first.expiresIn)
        AuthLog.i("Refresh token stored for ${auth.haBase}")
        notifyChanged()
    }

    /**
     * Gets an access token (cached until 60 s before `expires_in`, or refreshed with
     * `grant_type=refresh_token`); [force] skips the cache. [done] runs on the auth thread.
     * An `invalid_grant` / `access_denied` reply (token revoked in HA) deletes the refresh token.
     */
    fun accessToken(force: Boolean, done: (Access) -> Unit) {
        exec.execute {
            val auth = prefs.auth
            if (auth == null) { done(Access.Failed("not signed in", signedOut = true)); return@execute }
            if (!force) cache.get()?.let { done(Access.Ok(it, cache.secondsLeft())); return@execute }
            when (val r = tokens.refresh(auth)) {
                is TokenResult.Ok -> {
                    cache.put(r.accessToken, r.expiresIn)
                    done(Access.Ok(r.accessToken, r.expiresIn))
                }
                is TokenResult.Error -> {
                    AuthLog.w("token refresh failed: ${r.code} (${r.httpStatus})")
                    if (r.isRevoked || (r.code == "invalid_request" && r.httpStatus == 400)) {
                        // invalid_request = client_id mismatch; the token can't be used either way.
                        clearLocal()
                        done(Access.Failed(r.code, signedOut = true))
                    } else {
                        done(Access.Failed(r.code, signedOut = false))
                    }
                }
            }
        }
    }

    /** Revokes the refresh token at HA (best effort) and deletes it. [done] on the auth thread. */
    fun signOut(reason: String, done: () -> Unit = {}) {
        exec.execute {
            val auth = prefs.auth
            if (auth != null) {
                val ok = tokens.revoke(auth)
                AuthLog.i("Signed out ($reason); revoke ${if (ok) "sent" else "not reachable"}")
            }
            clearLocal()
            done()
        }
    }

    private fun clearLocal() {
        val had = prefs.hasAuth
        prefs.auth = null
        cache.clear()
        if (had) notifyChanged()
    }
}
