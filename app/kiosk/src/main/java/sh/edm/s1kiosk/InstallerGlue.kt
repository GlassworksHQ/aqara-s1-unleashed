package sh.edm.s1kiosk

import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Small helpers for the installer socket servers (Improv Serial, setup channel). */
object InstallerGlue {
    private val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and waits for it (null on timeout or if it threw). */
    fun <T> onMain(timeoutMs: Long = 5_000L, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val out = AtomicReference<T?>(null)
        val done = CountDownLatch(1)
        main.post {
            try { out.set(block()) } catch (_: Throwable) {} finally { done.countDown() }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out.get()
    }

    fun post(delayMs: Long = 0L, block: () -> Unit) { main.postDelayed(block, delayMs) }

    /** The panel's Wi-Fi IPv4 address (interface wlan0), or null when it has none. */
    fun wlanIpv4(): String? = runCatching {
        NetworkInterface.getByName("wlan0")?.takeIf { it.isUp }?.inetAddresses?.toList()
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()
}
