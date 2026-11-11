package sh.edm.improv

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

/**
 * An abstract Unix socket that only adb may open (`adb forward tcp:N localabstract:<name>`, or a
 * WebUSB adb client opening the `localabstract:<name>` service): an accept thread, the [AdbPeer]
 * check, and one thread per connection running [serve]. Connections may come one after another or
 * side by side (at most [MAX_CONNECTIONS]). Having USB adb access to the device (an authorized key)
 * is the authorization: other apps on the device are refused.
 */
class AdbSocketServer(
    private val name: String,
    private val tag: String,
    private val serve: (input: InputStream, output: OutputStream) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private val open = Collections.synchronizedSet(HashSet<LocalSocket>())

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread({ acceptLoop() }, "adb-$name").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { server?.close() }
        // close() doesn't wake a blocked accept() on every Android version: poke it.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(name)) } }
        synchronized(open) { open.toList() }.forEach { s -> runCatching { s.shutdownInput() }; runCatching { s.close() } }
    }

    private fun acceptLoop() {
        var backoff = 1000L
        while (running) {
            val srv = try {
                LocalServerSocket(name)
            } catch (e: Exception) {
                // The old service instance may still hold the name for a moment.
                Log.w(tag, "cannot listen on @$name: ${e.message}; retrying")
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            server = srv
            Log.i(tag, "listening on @$name")
            try {
                while (running) handle(srv.accept())
            } catch (e: Exception) {
                if (running) Log.w(tag, "accept failed: ${e.message}")
            } finally {
                runCatching { srv.close() }
            }
        }
    }

    private fun handle(s: LocalSocket) {
        if (!running) return s.close()
        val uid = runCatching { s.peerCredentials.uid }.getOrDefault(-1)
        if (!AdbPeer.allowed(uid)) {
            Log.w(tag, "refused a connection from uid $uid (only adb: uid 0 or 2000)")
            runCatching { s.close() }
            return
        }
        if (open.size >= MAX_CONNECTIONS) {
            Log.w(tag, "too many connections; refused one")
            runCatching { s.close() }
            return
        }
        open += s
        Thread({
            Log.i(tag, "adb client connected (uid $uid)")
            try {
                s.inputStream.use { input -> s.outputStream.use { output -> serve(input, output) } }
            } catch (e: Exception) {
                Log.i(tag, "connection ended: ${e.message}")
            } finally {
                open -= s
                runCatching { s.close() }
                Log.i(tag, "adb client disconnected")
            }
        }, "adb-$name-conn").apply { isDaemon = true; start() }
    }

    companion object {
        const val MAX_CONNECTIONS = 4
    }
}
