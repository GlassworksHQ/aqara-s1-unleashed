package io.github.aguilaair.s1kiosk.base

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Wall base support: talks to the stock LocalService ([LocalServiceClient]), detects the mounted
 * base, polls its values and reports to [Listener] (owned by PanelDevice, which turns it into
 * ESPHome entities). All state changes run on one thread ("s1-base").
 *
 * - dock class `14.160.85` is read on connect, every 60 s, and when an event/report mentions a dock
 *   resource; no reply within 15 s counts as "none";
 * - with a switch base: channel count/mask, firmware, relays, power, energy, protection on attach,
 *   then relays/power/energy/protection every 10 s; reports update immediately;
 * - binding failures: logged once, "Base: unavailable", retried every 60 s.
 *
 * Every raw callback payload is logged at debug level (tag `S1Base`) with the DID redacted.
 * The only write is [setRelay] (from Home Assistant).
 */
class WallBase(context: Context, initial: BaseLayout, private val listener: Listener) {

    interface Listener {
        /** Some value changed: publish states. */
        fun onStates()
        /** The entity set must change (debounced). */
        fun onLayout(layout: BaseLayout)
        fun onButton(channel: Int, type: String)
    }

    companion object {
        private const val TAG = "S1Base"
        private const val DID_PROP = "persist.sys.did"
        private const val DOCK_INTERVAL_MS = 60_000L
        private const val POLL_INTERVAL_MS = 10_000L
        private const val RETRY_MS = 60_000L
        private const val NO_REPLY_MS = 15_000L
        private const val MIN_DOCK_READ_GAP_MS = 2_000L
        /** Callbacks whose values describe the panel's resources (the others are requests to it). */
        private val VALUE_CALLBACKS = setOf("onReadDone", "onWriteDone", "onEvent", "onReportDone")
        private val TRIGGER_CALLBACKS = setOf("onEvent", "onReportDone")
    }

    private val ctx = context.applicationContext
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "s1-base").apply { isDaemon = true } }
    val tracker = BaseTracker(initial)
    private val client = LocalServiceClient(ctx, exec, ClientListener())

    private var did: String? = null
    private var unavailableLogged = false
    private var nextBindAt = 0L
    private var disconnectedSince = 0L
    private var nextDockReadAt = 0L
    private var lastDockReadAt = 0L
    private var dockReadPendingSince = 0L
    private var nextPollAt = 0L
    private var lastKind: BaseKind? = null
    private var firstReplyLogged = false
    private val loggedResultCodes = HashSet<Int>()

    private fun now() = SystemClock.elapsedRealtime()

    fun start() {
        exec.execute { tryBind() }
        exec.scheduleWithFixedDelay({ safe("tick") { tick() } }, 1, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        try {
            exec.submit { client.unbind() }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        exec.shutdownNow()
    }

    /** From Home Assistant (API connection thread). */
    fun setRelay(channel: Int, on: Boolean) {
        exec.execute {
            val d = did
            if (d == null || !client.isConnected) {
                Log.w(TAG, "relay $channel -> $on ignored: LocalService not connected")
                return@execute
            }
            val res = BaseRes.relay(channel)
            val rc = client.write(BaseProtocol.writeRequest(d, res, if (on) 1 else 0, System.currentTimeMillis()))
            Log.i(TAG, "write $res=${if (on) 1 else 0} -> $rc")
            // Confirm (the base or a report may also answer by itself).
            exec.schedule({ read(listOf(res)) }, 500, TimeUnit.MILLISECONDS)
            exec.schedule({ read(listOf(res, BaseRes.POWER)) }, 2_000, TimeUnit.MILLISECONDS)
        }
    }

    // ------------------------------------------------------------------ internals (s1-base thread)

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what failed", t) }
    }

    private fun tryBind() {
        nextBindAt = now() + RETRY_MS
        if (did == null) {
            did = readDid()
            if (did == null) {
                markUnavailable("system property $DID_PROP not readable")
                return
            }
            Log.i(TAG, "DID found ($DID_PROP)")
        }
        if (client.bind()) {
            disconnectedSince = now()
            Log.d(TAG, "binding LocalService")
        }
    }

    private fun markUnavailable(reason: String) {
        if (!unavailableLogged) {
            Log.w(TAG, "Wall base support unavailable: $reason; retrying every ${RETRY_MS / 1000} s")
            unavailableLogged = true
        }
        tracker.setServiceAvailable(false)
        listener.onStates()
    }

    private fun tick() {
        val now = now()
        if (!client.isConnected) {
            if (!client.isBound) {
                if (now >= nextBindAt) tryBind()
            } else if (disconnectedSince > 0 && now - disconnectedSince > RETRY_MS) {
                Log.w(TAG, "LocalService not (re)connected after ${RETRY_MS / 1000} s; rebinding")
                client.unbind()
                markUnavailable("LocalService not connected")
                tryBind()
            }
        } else {
            if (now >= nextDockReadAt) readDock()
            if (tracker.current()?.kind == BaseKind.SWITCH && now >= nextPollAt) {
                nextPollAt = now + POLL_INTERVAL_MS
                read(BaseRes.POLL_READ)
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
        read(listOf(BaseRes.DOCK_CLASS))
    }

    private fun read(res: List<String>) {
        val d = did ?: return
        val rc = client.read(BaseProtocol.readRequest(d, res)) ?: return
        Log.d(TAG, "read ${res.joinToString(",")} -> $rc")
        if (rc != 0 && loggedResultCodes.add(rc)) Log.w(TAG, "LocalService.read returned $rc (logged once per code)")
    }

    private fun onCallback(method: String, payload: String?) {
        Log.d(TAG, "$method: ${payload?.let { BaseProtocol.redact(it, did) }}")
        // onKeepAlive / onStartSceneDone / onSubdevice*: logged only.
        if (method !in VALUE_CALLBACKS && method != "write" && method != "read") return
        val parsed = BaseProtocol.parse(payload, did) ?: return
        if (parsed.foreign) return // another device (e.g. a Zigbee sub-device): same resource ids
        if (!firstReplyLogged && method == "onReadDone" && parsed.values.isNotEmpty()) {
            firstReplyLogged = true
            Log.i(TAG, "LocalService answers reads (${parsed.values.keys.joinToString(",")})")
        }
        if (method in VALUE_CALLBACKS) {
            val update = tracker.apply(parsed.values, now())
            if (BaseRes.DOCK_CLASS in parsed.values) {
                dockReadPendingSince = 0
                val msg = "dock class ${parsed.values[BaseRes.DOCK_CLASS]} -> ${tracker.current()?.describe()}"
                if (BaseRes.DOCK_CLASS in update.changed) Log.i(TAG, msg) else Log.d(TAG, msg)
            }
            for (c in update.clicks) {
                Log.i(TAG, "button ${c.channel}: raw ${c.raw} -> ${c.type} (via $method)")
                listener.onButton(c.channel, c.type)
            }
            if (update.changed.isNotEmpty()) {
                onDetectionMaybeChanged()
                listener.onStates()
            }
        }
        val dockMentioned = parsed.values.keys.any { it in BaseRes.DOCK_TRIGGERS }
        if (dockMentioned && method in TRIGGER_CALLBACKS + setOf("write", "read")) readDock()
    }

    private fun onDetectionMaybeChanged() {
        val kind = tracker.current()?.kind
        if (kind == lastKind) return
        Log.i(TAG, "detected base: ${lastKind ?: "?"} -> ${kind ?: "?"}")
        if (lastKind == BaseKind.SWITCH && kind != BaseKind.SWITCH) tracker.clearBaseValues()
        lastKind = kind
        when (kind) {
            BaseKind.SWITCH -> {
                nextPollAt = now() + POLL_INTERVAL_MS
                read(BaseRes.ATTACH_READ)
            }
            BaseKind.ETHERNET, BaseKind.UNKNOWN -> read(listOf(BaseRes.FIRMWARE))
            else -> Unit
        }
        listener.onStates()
    }

    @SuppressLint("PrivateApi")
    private fun readDid(): String? {
        val viaReflection = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, DID_PROP) as? String
        } catch (_: Throwable) {
            null
        }
        if (!viaReflection.isNullOrBlank()) return viaReflection.trim()
        return try {
            val p = ProcessBuilder("getprop", DID_PROP).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(2, TimeUnit.SECONDS)
            out.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private inner class ClientListener : LocalServiceClient.Listener {
        override fun onConnected() {
            Log.i(TAG, "LocalService connected, callback registered")
            unavailableLogged = false
            disconnectedSince = 0
            tracker.setServiceAvailable(true)
            nextDockReadAt = 0
            lastDockReadAt = 0
            readDock()
            listener.onStates()
        }

        override fun onDisconnected() {
            Log.w(TAG, "LocalService disconnected; waiting for it to come back")
            if (disconnectedSince == 0L) disconnectedSince = now()
        }

        override fun onUnavailable(reason: String) {
            nextBindAt = now() + RETRY_MS
            markUnavailable(reason)
        }

        override fun onCallback(method: String, payload: String?) = safe("callback $method") {
            this@WallBase.onCallback(method, payload)
        }
    }
}
