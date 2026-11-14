package sh.edm.s1kiosk.base

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Wall base support: detects the mounted base through a [BaseLink], polls its values and reports
 * to [Listener] (owned by [WallBasePane], which turns it into ESPHome entities). All state changes run
 * on one thread ("s1-base").
 *
 * Links ([config] / [reconfigure]):
 * - [BaseBackend.UART]: [UartBaseLink], the base's own UART protocol on `/dev/ttyS3` (needs
 *   `ha_master` stopped; [Config.portGuard] says when the port may be used) or a TCP emulator.
 * - [BaseBackend.LOCALSERVICE]: [LocalServiceLink], through the stock LocalService / ha_master.
 *
 * - dock class `14.160.85` is read on connect, every 60 s, and when an event/report mentions a dock
 *   resource; no reply within 15 s counts as "none" (the UART link answers 0 itself after an
 *   unanswered handshake);
 * - with a switch base: channel count/mask, firmware, relays, power, energy, protection on attach,
 *   then the link's poll list; reports update immediately;
 * - link failures: logged, "Base: unavailable", retried by the link.
 *
 * The only write is [setRelay] (from Home Assistant).
 */
class WallBase internal constructor(
    context: Context,
    initial: BaseLayout,
    private val listener: Listener,
    config: Config,
    /** Makes the link for a config. A test passes one whose close is slow. */
    private val newLink: ((Config, ScheduledExecutorService, BaseLink.Listener) -> BaseLink)?,
) {
    constructor(context: Context, initial: BaseLayout, listener: Listener, config: Config) :
        this(context, initial, listener, config, null)

    interface Listener {
        /** Some value changed: publish states. */
        fun onStates()
        /** The entity set must change (debounced). */
        fun onLayout(layout: BaseLayout)
        fun onButton(channel: Int, type: String)
        /** The UART link may not open its port now (ha_master runs). */
        fun onPortBlocked(reason: String) {}
    }

    data class Config(
        val backend: BaseBackend,
        /** UART only: `/dev/ttyS3` or `tcp:host:port` (debug: base_emulator.py). */
        val transport: String = UartBaseLink.SERIAL_PATH,
        /** UART only: null when `/dev/ttyS3` may be opened, else why not. */
        val portGuard: () -> String? = { null },
    )

    companion object {
        private const val TAG = "S1Base"
        /** How long a new link waits for the old one to close its port. */
        private const val CLOSE_WAIT_S = 30L
        private const val DOCK_INTERVAL_MS = 60_000L
        private const val NO_REPLY_MS = 15_000L
        private const val MIN_DOCK_READ_GAP_MS = 2_000L
        private const val ENERGY_PREFS = "s1base"
        private const val KEY_ENERGY = "uart_energy_wh"
    }

    private val ctx = context.applicationContext
    /** The "s1-base" thread. A new one for each [start] after a [stop]: the pane can be turned off and on (#67). */
    @Volatile private var exec: ScheduledExecutorService = newThread()
    val tracker = BaseTracker(initial)
    private var link: BaseLink? = null
    @Volatile var config: Config = config
        private set

    private var nextDockReadAt = 0L
    private var lastDockReadAt = 0L
    private var dockReadPendingSince = 0L
    private var nextPollAt = 0L
    private var lastKind: BaseKind? = null

    private fun now() = SystemClock.elapsedRealtime()

    /**
     * Starts the link with [c]: the config wanted now, which may have changed while the base was stopped.
     * After a [stop] whose close isn't done yet, the new link opens only once the old one has closed its
     * port (on the new thread, waiting up to [CLOSE_WAIT_S]).
     */
    fun start(c: Config = config) {
        config = c
        val old = exec
        if (old.isShutdown) {
            exec = newThread()
            exec.execute {
                if (!old.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS)) Log.w(TAG, "the old wall base link still closes after $CLOSE_WAIT_S s; opening anyway")
            }
        }
        exec.execute { startLink() }
        exec.scheduleWithFixedDelay({ safe("tick") { tick() } }, 1, 1, TimeUnit.SECONDS)
    }

    /**
     * Closes the link. Waits up to 3 s for it; a slower close goes on to its end on the "s1-base" thread
     * (shutdown, not shutdownNow: the close isn't cut off), and [start] waits for it.
     */
    fun stop() {
        val e = exec
        try {
            e.submit { link?.stop(); link = null }.get(3, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "the wall base link takes more than 3 s to close; it goes on closing")
        } catch (_: Exception) {}
        e.shutdown()
    }

    /** Swap the link (blocking until the old one closed its port). The tracker and its values stay. */
    fun reconfigure(c: Config) {
        try {
            exec.submit {
                if (c == config && link != null) return@submit
                Log.i(TAG, "wall base backend ${link?.describe ?: "-"} -> ${c.backend.pref} ${if (c.backend == BaseBackend.UART) c.transport else ""}")
                link?.stop()
                link = null
                config = c
                startLink()
            }.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "reconfigure failed: $e")
        }
    }

    val backendDescription: String get() = link?.describe ?: config.backend.pref

    /** From Home Assistant (API connection thread). Dropped while the base is stopped. */
    fun setRelay(channel: Int, on: Boolean) {
        runCatching { exec.execute { relay(channel, on) } }
    }

    /** The "s1-base" thread. */
    private fun relay(channel: Int, on: Boolean) {
        val l = link
        if (l == null || !l.isConnected) {
            Log.w(TAG, "relay $channel -> $on ignored: no link to the base")
            return
        }
        val res = BaseRes.relay(channel)
        l.write(res, if (on) 1 else 0)
        if (l.confirmWrites) {
            // Confirm (the base or a report may also answer by itself).
            exec.schedule({ link?.read(listOf(res)) }, 500, TimeUnit.MILLISECONDS)
            exec.schedule({ link?.read(listOf(res, BaseRes.POWER)) }, 2_000, TimeUnit.MILLISECONDS)
        }
    }

    // ------------------------------------------------------------------ internals (s1-base thread)

    private fun newThread(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "s1-base").apply { isDaemon = true } }

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what failed", t) }
    }

    private fun startLink() {
        // Never two links: one already open (start twice) closes its port first.
        link?.let { it.stop(); link = null }
        val c = config
        val l: BaseLink = newLink?.invoke(c, exec, LinkListener()) ?: when (c.backend) {
            BaseBackend.LOCALSERVICE -> LocalServiceLink(ctx, exec, LinkListener())
            BaseBackend.UART -> {
                val sp = ctx.getSharedPreferences(ENERGY_PREFS, Context.MODE_PRIVATE)
                val tcp = c.transport.startsWith("tcp:")
                // The emulator's energy must not end up in the real counter.
                val key = if (tcp) "${KEY_ENERGY}_test" else KEY_ENERGY
                UartBaseLink(
                    exec, LinkListener(), c.transport,
                    portGuard = if (tcp) ({ null }) else c.portGuard,
                    initialEnergyWh = sp.getFloat(key, 0f),
                    saveEnergy = { sp.edit().putFloat(key, it).apply() },
                    log = { level, msg -> Log.println(level, TAG, msg) },
                )
            }
        }
        link = l
        Log.i(TAG, "wall base link: ${l.describe}")
        l.start()
    }

    private fun tick() {
        val now = now()
        val l = link
        if (l != null) {
            l.tick(now)
            if (l.isConnected) {
                if (now >= nextDockReadAt) readDock()
                if (tracker.current()?.kind == BaseKind.SWITCH && now >= nextPollAt) {
                    nextPollAt = now + l.pollIntervalMs
                    l.read(l.pollRes)
                }
                if (dockReadPendingSince > 0 && now - dockReadPendingSince > NO_REPLY_MS) {
                    dockReadPendingSince = 0
                    if (!tracker.hasDockClass()) {
                        Log.w(TAG, "no reply to the dock class read within ${NO_REPLY_MS / 1000} s; assuming no base")
                        tracker.markDockNoReply()
                        onDetectionMaybeChanged()
                    }
                }
            }
        }
        tracker.due(now)?.let { layout ->
            Log.i(TAG, "base layout ${tracker.applied().describe()} -> ${layout.describe()}; rebuilding entities")
            tracker.markApplied(layout)
            listener.onLayout(layout)
        }
    }

    private fun readDock() {
        val now = now()
        if (now - lastDockReadAt < MIN_DOCK_READ_GAP_MS) return
        lastDockReadAt = now
        nextDockReadAt = now + DOCK_INTERVAL_MS
        if (dockReadPendingSince == 0L) dockReadPendingSince = now
        link?.read(listOf(BaseRes.DOCK_CLASS))
    }

    private fun onValues(values: Map<String, Any>, via: String, apply: Boolean, dockTrigger: Boolean) {
        if (apply) {
            val update = tracker.apply(values, now())
            if (BaseRes.DOCK_CLASS in values) {
                dockReadPendingSince = 0
                val msg = "dock class ${values[BaseRes.DOCK_CLASS]} -> ${tracker.current()?.describe()} (via $via)"
                if (BaseRes.DOCK_CLASS in update.changed) Log.i(TAG, msg) else Log.d(TAG, msg)
            }
            for (c in update.clicks) {
                Log.i(TAG, "button ${c.channel}: raw ${c.raw} -> ${c.type} (via $via)")
                listener.onButton(c.channel, c.type)
            }
            if (update.changed.isNotEmpty()) {
                Log.d(TAG, "changed ${update.changed.joinToString(",")} (via $via)")
                onDetectionMaybeChanged()
                listener.onStates()
            }
        }
        if (dockTrigger && values.keys.any { it in BaseRes.DOCK_TRIGGERS }) readDock()
    }

    private fun onDetectionMaybeChanged() {
        val kind = tracker.current()?.kind
        if (kind == lastKind) return
        Log.i(TAG, "detected base: ${lastKind ?: "?"} -> ${kind ?: "?"}")
        if (lastKind == BaseKind.SWITCH && kind != BaseKind.SWITCH) tracker.clearBaseValues()
        lastKind = kind
        when (kind) {
            BaseKind.SWITCH -> {
                nextPollAt = now() + (link?.pollIntervalMs ?: 10_000L)
                link?.read(BaseRes.ATTACH_READ)
            }
            BaseKind.ETHERNET, BaseKind.UNKNOWN -> link?.read(listOf(BaseRes.FIRMWARE))
            else -> Unit
        }
        listener.onStates()
    }

    private inner class LinkListener : BaseLink.Listener {
        override fun onConnected() {
            tracker.setServiceAvailable(true)
            nextDockReadAt = 0
            lastDockReadAt = 0
            readDock()
            listener.onStates()
        }

        override fun onDisconnected() {
            // Keep the last layout until the link reports unavailable or another dock class.
        }

        override fun onUnavailable(reason: String) {
            tracker.setServiceAvailable(false)
            listener.onStates()
        }

        override fun onValues(values: Map<String, Any>, via: String, apply: Boolean, dockTrigger: Boolean) =
            safe("values via $via") { this@WallBase.onValues(values, via, apply, dockTrigger) }

        override fun onBlocked(reason: String) = listener.onPortBlocked(reason)
    }
}
