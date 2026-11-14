package sh.edm.bluetoothproxy

import java.util.concurrent.atomic.AtomicReference

/**
 * Which scan is current. Android delivers a scan's failure later, on another thread, so a failure
 * of a scan that was already stopped can arrive while a newer one runs. Only the current scan's
 * end counts. Thread-safe.
 */
internal class CurrentScan<T : Any> {
    private val ref = AtomicReference<T?>()

    /** [scan] is current from now on. */
    fun begin(scan: T) = ref.set(scan)

    /** Ends [scan] if it is still current. False for an older one, or if it already ended. */
    fun end(scan: T): Boolean = ref.compareAndSet(scan, null)

    /** Ends the current scan, if any, and returns it. */
    fun endAny(): T? = ref.getAndSet(null)
}
