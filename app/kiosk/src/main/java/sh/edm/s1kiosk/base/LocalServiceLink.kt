package sh.edm.s1kiosk.base

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * [BaseLink] through the stock `com.lumi.systemmanager` LocalService ([LocalServiceClient]), i.e.
 * through `ha_master`, which owns `/dev/ttyS3`. The fallback backend (`base_backend=localservice`,
 * or `auto` with Aqara cloud blocking off).
 *
 * Binding failures: logged once, reported as unavailable, retried every 60 s; rebinds after 60 s
 * without a connection. Payloads are logged at debug level with the DID redacted.
 */
class LocalServiceLink(ctx: Context, executor: Executor, private val listener: BaseLink.Listener) : BaseLink {

    companion object {
        private const val TAG = "S1Base"
        private const val DID_PROP = "persist.sys.did"
        private const val RETRY_MS = 60_000L
        /** Callbacks whose values describe the panel's resources (the others are requests to it). */
        private val VALUE_CALLBACKS = setOf("onReadDone", "onWriteDone", "onEvent", "onReportDone")
        private val TRIGGER_CALLBACKS = setOf("onEvent", "onReportDone", "write", "read")
    }

    private val client = LocalServiceClient(ctx.applicationContext, executor, ClientListener())
    private var did: String? = null
    private var unavailableLogged = false
    private var nextBindAt = 0L
    private var disconnectedSince = 0L
    private var firstReplyLogged = false
    private val loggedResultCodes = HashSet<Int>()

    override val describe = "localservice (ha_master)"
    override val isConnected: Boolean get() = client.isConnected
    override val pollRes: List<String> = BaseRes.POLL_READ
    override val pollIntervalMs = 10_000L
    override val confirmWrites = true

    override fun start() = tryBind(now())

    override fun stop() = client.unbind()

    private fun now() = android.os.SystemClock.elapsedRealtime()

    override fun tick(now: Long) {
        if (client.isConnected) return
        if (!client.isBound) {
            if (now >= nextBindAt) tryBind(now)
        } else if (disconnectedSince > 0 && now - disconnectedSince > RETRY_MS) {
            Log.w(TAG, "LocalService not (re)connected after ${RETRY_MS / 1000} s; rebinding")
            client.unbind()
            markUnavailable("LocalService not connected")
            tryBind(now)
        }
    }

    override fun read(res: List<String>) {
        val d = did ?: return
        val rc = client.read(BaseProtocol.readRequest(d, res)) ?: return
        Log.d(TAG, "read ${res.joinToString(",")} -> $rc")
        if (rc != 0 && loggedResultCodes.add(rc)) Log.w(TAG, "LocalService.read returned $rc (logged once per code)")
    }

    override fun write(res: String, value: Int) {
        val d = did
        if (d == null || !client.isConnected) {
            Log.w(TAG, "write $res=$value ignored: LocalService not connected")
            return
        }
        val rc = client.write(BaseProtocol.writeRequest(d, res, value, System.currentTimeMillis()))
        Log.i(TAG, "write $res=$value -> $rc")
    }

    private fun tryBind(now: Long) {
        nextBindAt = now + RETRY_MS
        if (did == null) {
            did = readDid()
            if (did == null) {
                markUnavailable("system property $DID_PROP not readable")
                return
            }
            Log.i(TAG, "DID found ($DID_PROP)")
        }
        if (client.bind()) {
            disconnectedSince = now
            Log.d(TAG, "binding LocalService")
        }
    }

    private fun markUnavailable(reason: String) {
        if (!unavailableLogged) {
            Log.w(TAG, "Wall base support unavailable: $reason; retrying every ${RETRY_MS / 1000} s")
            unavailableLogged = true
        }
        listener.onUnavailable(reason)
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
        listener.onValues(parsed.values, method, apply = method in VALUE_CALLBACKS, dockTrigger = method in TRIGGER_CALLBACKS)
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
            listener.onConnected()
        }

        override fun onDisconnected() {
            Log.w(TAG, "LocalService disconnected; waiting for it to come back")
            if (disconnectedSince == 0L) disconnectedSince = now()
            listener.onDisconnected()
        }

        override fun onUnavailable(reason: String) {
            nextBindAt = now() + RETRY_MS
            markUnavailable(reason)
        }

        override fun onCallback(method: String, payload: String?) {
            try {
                this@LocalServiceLink.onCallback(method, payload)
            } catch (t: Throwable) {
                Log.w(TAG, "callback $method failed", t)
            }
        }
    }
}
