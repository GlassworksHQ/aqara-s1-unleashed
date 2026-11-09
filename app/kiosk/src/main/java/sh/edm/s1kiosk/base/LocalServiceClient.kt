package sh.edm.s1kiosk.base

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executor

/**
 * Hand-written AIDL client for the stock `com.lumi.systemmanager` LocalService (exported, no
 * permission). Parceling matches the decompiled `ILocalService.Stub.Proxy` / `ILocal.Stub`:
 *
 * - `ILocalService` calls are two-way: `writeInterfaceToken` + `writeString` (or
 *   `writeStrongBinder` for register/unregister), `transact(code, flags = 0)`, `readException`,
 *   then `readInt` for the int-returning methods.
 * - `ILocal` callbacks are two-way too (not oneway): `enforceInterface`, `readString`, then the
 *   stub must `writeNoException` into the reply.
 *
 * Every method and callback is forwarded to [listener] on [executor]; nothing blocks the binder
 * thread that delivers callbacks (the service calls its callbacks synchronously, one by one).
 */
class LocalServiceClient(
    private val ctx: Context,
    private val executor: Executor,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        /** The service died or went away (Android may reconnect by itself). */
        fun onDisconnected()
        /** Binding is impossible (package missing/disabled, null binding, ...). */
        fun onUnavailable(reason: String)
        /** A raw `ILocal` callback: [method] is e.g. `onReadDone`. */
        fun onCallback(method: String, payload: String?)
    }

    companion object {
        private const val TAG = "S1Base"
        const val PKG = "com.lumi.systemmanager"
        const val CLS = "com.lumi.systemmanager.service.gateway.LocalService"
        const val ACTION = "systemmanager.intent.action.LOCAL_SERVER"
        const val SERVICE_DESCRIPTOR = "com.lumi.systemmanager.service.ILocalService"
        const val CALLBACK_DESCRIPTOR = "com.lumi.systemmanager.service.ILocal"

        // ILocalService transaction codes (Stub.TRANSACTION_*)
        const val TX_READ = 1
        const val TX_WRITE = 2
        const val TX_REGISTER = 5
        const val TX_UNREGISTER = 6

        // ILocal transaction codes -> method names (Stub.TRANSACTION_*)
        val CALLBACKS = mapOf(
            1 to "onReadDone", 2 to "onWriteDone", 3 to "onStartSceneDone", 4 to "onEvent",
            5 to "onReportDone", 6 to "onKeepAlive", 7 to "onSubdeviceJoinDone", 8 to "onSubdeviceRemoved",
            9 to "write", 10 to "read",
        )
    }

    /** The `ILocal` stub we register. Lives as long as this client, so unregister matches it. */
    private val callback = object : Binder() {
        init { attachInterface(null, CALLBACK_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val method = CALLBACKS[code] ?: return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            val payload = data.readString()
            reply?.writeNoException()
            executor.execute { listener.onCallback(method, payload) }
            return true
        }
    }

    @Volatile private var remote: IBinder? = null
    @Volatile var isBound = false
        private set
    val isConnected: Boolean get() = remote?.isBinderAlive == true

    private val death = IBinder.DeathRecipient {
        Log.w(TAG, "LocalService binder died")
        remote = null
        executor.execute { listener.onDisconnected() }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            executor.execute {
                if (service == null) { listener.onUnavailable("null binder"); return@execute }
                try {
                    service.linkToDeath(death, 0)
                } catch (e: RemoteException) {
                    listener.onDisconnected()
                    return@execute
                }
                remote = service
                if (register(service, TX_REGISTER)) listener.onConnected() else listener.onDisconnected()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            executor.execute {
                remote?.let { b -> try { b.unlinkToDeath(death, 0) } catch (_: Exception) {} }
                remote = null
                listener.onDisconnected()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            executor.execute {
                Log.w(TAG, "LocalService binding died; unbinding")
                unbind()
                listener.onDisconnected()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            executor.execute {
                unbind()
                listener.onUnavailable("service returned a null binding")
            }
        }
    }

    /** Binds; false (and [Listener.onUnavailable]) when the service can't be bound at all. */
    fun bind(): Boolean {
        if (isBound) return true
        val intent = Intent(ACTION).setComponent(ComponentName(PKG, CLS))
        val ok = try {
            ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "bindService refused: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "bindService failed", e)
            false
        }
        if (!ok) {
            try { ctx.unbindService(conn) } catch (_: Exception) {}
            listener.onUnavailable("bindService returned false ($PKG missing or disabled?)")
            return false
        }
        isBound = true
        return true
    }

    fun unbind() {
        remote?.let { b ->
            register(b, TX_UNREGISTER)
            try { b.unlinkToDeath(death, 0) } catch (_: Exception) {}
        }
        remote = null
        if (isBound) {
            isBound = false
            try { ctx.unbindService(conn) } catch (_: Exception) {}
        }
    }

    /** `ILocalService.read(json)`; the int result (0 = sent?) or null if the call failed. */
    fun read(json: String): Int? = call(TX_READ, json)

    /** `ILocalService.write(json)`. */
    fun write(json: String): Int? = call(TX_WRITE, json)

    private fun call(code: Int, json: String): Int? {
        val b = remote ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(json)
            if (!b.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            Log.w(TAG, "LocalService transaction $code failed: $e")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun register(b: IBinder, code: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            val ok = b.transact(code, data, reply, 0)
            if (ok) reply.readException()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "LocalService ${if (code == TX_REGISTER) "register" else "unregister"} failed: $e")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
