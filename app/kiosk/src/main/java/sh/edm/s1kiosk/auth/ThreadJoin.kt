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
        class Joined(val networkName: String) : State() { override fun toString() = "Joined($networkName)" }
        class Failed(val reason: String) : State() { override fun toString() = "Failed($reason)" }
    }

    /** Hands the dataset to the radio; returns null when accepted, else why not. Set by the service. */
    @Volatile var deliver: ((ThreadFetch.Ok) -> String?)? = null

    @Volatile var state: State = State.Idle
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
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
        synchronized(this) {
            if (state is State.Working) return
            set(State.Working)
        }
        val base = haBase()
        if (base == null) { set(State.Failed("Home Assistant's address isn't known yet")); return }
        panelToken { token ->
            if (token == null) { needsAdmin("the panel isn't signed in to Home Assistant"); return@panelToken }
            exec.execute {
                when (val r = fetch(base, token)) {
                    is ThreadFetch.Ok -> finish(r)
                    ThreadFetch.NotAdmin -> needsAdmin("the panel's Home Assistant user isn't an administrator")
                    is ThreadFetch.NoPreferred -> set(State.Failed(noPreferred(r)))
                    is ThreadFetch.Failed -> set(State.Failed(r.reason))
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
        set(State.Working)
        val r = try {
            fetch(auth.haBase, grant.accessToken)
        } finally {
            // The panel keeps no administrator credentials: revoke now, whatever happened.
            val sent = tokens.revoke(auth)
            AuthLog.i("Thread join: one-off sign-in revoked${if (sent) "" else " (Home Assistant not reachable)"}")
        }
        return when (r) {
            is ThreadFetch.Ok -> {
                val err = finish(r)
                if (err == null) Outcome(true, "Joining ${r.networkName.ifEmpty { "your Thread network" }} ✓",
                    "The panel has your Thread network's settings and is joining it as a Thread router. " +
                        "You can close this page; you were signed out of the panel again.")
                else Outcome(false, "Couldn't join", err)
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
    private fun finish(r: ThreadFetch.Ok): String? {
        val d = deliver
        val err = if (d == null) "the panel's radio isn't running" else d(r)
        set(if (err == null) State.Joined(r.networkName) else State.Failed(err))
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
