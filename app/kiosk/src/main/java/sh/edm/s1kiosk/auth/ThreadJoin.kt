package sh.edm.s1kiosk.auth

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * "Join Home Assistant's Thread network" (GitHub issue #1): the panel gets the active dataset of HA's
 * preferred Thread network by itself, so nobody copies the dataset by hand. Pure JVM (ThreadJoinTest).
 *
 * HA's Thread websocket commands are admin-only ([HaThreadClient]), and the panel's own sign-in is
 * often a non-admin dashboard user. So:
 * 1. [start] first tries the panel's own sign-in ([panelToken]). If that works (an admin), done.
 * 2. Otherwise the panel shows a QR code for `http://<panel>:8099/thread` and [arm]s for [armMs].
 *    An administrator scans it and signs in to HA on their phone (the same OAuth flow as the panel's
 *    sign-in, [SignInServer]). [onGrant] gets the one-off tokens, fetches the dataset with the access
 *    token, and **revokes the refresh token straight away**, whatever happened: the panel keeps no
 *    admin credentials.
 * 3. The dataset goes to [deliver] (RadioController: the root helper's dataset hand-off, then
 *    *Thread router* mode). It is never logged or shown.
 */
class ThreadJoin(
    private val client: HaThreadClient,
    private val tokens: HaTokenClient,
    /** HA's base URL the panel uses, null while unknown. */
    private val haBase: () -> String?,
    /** The panel's own access token (HaAuth.accessToken), or null when it isn't signed in. */
    private val panelToken: (done: (String?) -> Unit) -> Unit,
    /** Where background work runs (fetches block on the network). */
    private val exec: Executor,
    private val armMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class State {
        object Idle : State() { override fun toString() = "Idle" }
        object Working : State() { override fun toString() = "Working" }
        /** Waiting for an administrator to scan the QR code (until [until], clock ms). */
        class NeedsAdmin(val until: Long, val why: String) : State() { override fun toString() = "NeedsAdmin($why)" }
        /** [revoked]: false when the one-off administrator sign-in couldn't be deleted (HA unreachable). */
        class Joined(val networkName: String, val revoked: Boolean = true) : State() {
            override fun toString() = "Joined($networkName${if (revoked) "" else ", sign-in not revoked"})"
        }
        class Failed(val reason: String) : State() { override fun toString() = "Failed($reason)" }
    }

    /** Hands the dataset to the radio; returns null when accepted, else why not. Set by the service. */
    @Volatile var deliver: ((ThreadFetch.Ok) -> String?)? = null

    @Volatile var state: State = State.Idle
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    /** Bumped by every [start] and [onGrant]: a result from an older attempt is dropped. */
    private var attempt = 0
    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    private fun set(s: State) {
        state = s
        AuthLog.i("Thread join: $s")
        listeners.forEach { runCatching { it(s) } }
    }

    /** True while `/thread` may start an administrator sign-in. */
    fun armed(): Boolean = (state as? State.NeedsAdmin)?.let { clock() < it.until } == true

    /** Shows the QR route for [armMs] (the panel is showing the QR code); not while a fetch runs. */
    @Synchronized
    fun arm(why: String = "an administrator's sign-in is needed") {
        if (state is State.Working) return
        needsAdmin(why)
    }

    /** A fetch ended needing an administrator: offer the QR route for [armMs]. */
    private fun needsAdmin(why: String) = set(State.NeedsAdmin(clock() + armMs, why))

    /** Stops offering the QR route (the QR code went away). */
    @Synchronized
    fun disarm() {
        if (state is State.NeedsAdmin) set(State.Idle)
    }

    /**
     * Tries the panel's own sign-in; ends in [State.Joined], [State.NeedsAdmin] (armed: show the QR
     * code) or [State.Failed]. No-op while a fetch is running.
     */
    fun start() {
        val mine = synchronized(this) {
            if (state is State.Working) return
            set(State.Working)
            ++attempt
        }
        val base = haBase()
        if (base == null) { set(State.Failed("Home Assistant's address isn't known yet")); return }
        // Applies a result only if no newer attempt (another start, or an admin's sign-in) came since.
        fun current(apply: () -> Unit) = synchronized(this) { if (attempt == mine) apply() }
        panelToken { token ->
            if (token == null) { current { needsAdmin("the panel isn't signed in to Home Assistant") }; return@panelToken }
            exec.execute {
                val r = fetch(base, token)
                current {
                    when (r) {
                        is ThreadFetch.Ok -> finish(r)
                        ThreadFetch.NotAdmin -> needsAdmin("the panel's Home Assistant user isn't an administrator")
                        is ThreadFetch.NoPreferred -> set(State.Failed(noPreferred(r)))
                        is ThreadFetch.Failed -> set(State.Failed(r.reason))
                    }
                }
            }
        }
    }

    /** What the phone sees after `/thread/callback`. */
    data class Outcome(val ok: Boolean, val title: String, val text: String)

    /**
     * `/thread/callback` exchanged the code: fetch with these one-off tokens, then revoke them.
     * Runs on the sign-in server's connection thread (blocks for the fetch).
     */
    fun onGrant(auth: StoredAuth, grant: TokenResult.Ok): Outcome {
        synchronized(this) { attempt++; set(State.Working) }
        var revoked = false
        val r = try {
            fetch(auth.haBase, grant.accessToken)
        } finally {
            // The panel keeps no administrator credentials: revoke now, whatever happened.
            revoked = tokens.revoke(auth)
            if (revoked) AuthLog.i("Thread join: one-off sign-in revoked")
            else AuthLog.w("Thread join: couldn't revoke the one-off sign-in (Home Assistant not reachable)")
        }
        val notRevoked = " The panel couldn't delete this sign-in: remove it under your Home Assistant profile → Security → Refresh tokens."
        return when (r) {
            is ThreadFetch.Ok -> {
                val err = finish(r, revoked)
                if (err == null) Outcome(true, "Joining ${r.networkName.ifEmpty { "your Thread network" }} ✓",
                    "The panel has your Thread network's settings and is joining it as a Thread router. You can close this page." +
                        if (revoked) " You were signed out of the panel again." else notRevoked)
                else Outcome(false, "Couldn't join", err + if (revoked) "" else notRevoked)
            }
            ThreadFetch.NotAdmin -> {
                needsAdmin("the user who signed in isn't an administrator")
                Outcome(false, "An administrator is needed",
                    "Only Home Assistant administrators can read the Thread network's settings. Scan the code again and sign in as one.")
            }
            is ThreadFetch.NoPreferred -> noPreferred(r).let { set(State.Failed(it)); Outcome(false, "No Thread network", it) }
            is ThreadFetch.Failed -> { set(State.Failed(r.reason)); Outcome(false, "Couldn't join", r.reason.replaceFirstChar { it.uppercase() } + ".") }
        }
    }

    /** [HaThreadClient.fetchPreferred], with any unexpected exception as [ThreadFetch.Failed]. */
    private fun fetch(base: String, token: String): ThreadFetch = try {
        client.fetchPreferred(base, token)
    } catch (e: RuntimeException) {
        AuthLog.w("Thread join: fetch failed", e)
        ThreadFetch.Failed("unexpected error (${e.javaClass.simpleName})")
    }

    /** Delivers the dataset; returns null when accepted. */
    private fun finish(r: ThreadFetch.Ok, revoked: Boolean = true): String? {
        val d = deliver
        val err = if (d == null) "the panel's radio isn't running" else d(r)
        set(if (err == null) State.Joined(r.networkName, revoked) else State.Failed(err))
        return err
    }

    private fun noPreferred(r: ThreadFetch.NoPreferred) =
        if (r.known == 0) "Home Assistant has no Thread network yet (Settings → Devices & services → Thread)"
        else "Home Assistant knows ${r.known} Thread network${if (r.known == 1) "" else "s"} but none is preferred (Settings → Devices & services → Thread)"

    companion object {
        const val PATH = "/thread"
        const val CALLBACK = "/thread/callback"

        /** The URL the QR code shows. */
        fun url(panelIp: String?): String = "http://${panelIp ?: "<panel-ip>"}:${OAuth.SIGNIN_PORT}$PATH"

        /** One line for the panel's status (ESPHome sensor, setup screen, settings menu). */
        fun describe(s: State, now: Long = System.currentTimeMillis()): String = when (s) {
            State.Idle -> "Not started"
            State.Working -> "Getting the Thread network from Home Assistant…"
            is State.NeedsAdmin ->
                if (now < s.until) "Scan the QR code on the panel and sign in as a Home Assistant administrator (${s.why})"
                else "The QR code expired; start again"
            is State.Joined -> "Joining ${s.networkName.ifEmpty { "Home Assistant's Thread network" }} as a Thread router"
            is State.Failed -> "Couldn't join: ${s.reason}"
        }
    }
}
