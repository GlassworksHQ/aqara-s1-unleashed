package sh.edm.s1kiosk.frame

/**
 * When to ask Android for a Wi-Fi scan while the screen is off (#3). Plain Kotlin, driven by
 * [ScreenOffWifiScanner] on the panel and by tests on the JVM.
 *
 * Android 10 does no periodic scans with the screen off while disconnected. It relies on PNO scans,
 * which the S1's Wi-Fi chip rejects (`PnoScanListener onFailure: -3(not supported)`). So nothing
 * scans and nothing reconnects until the screen wakes. Any scan's results go through the framework's
 * network selection, so an app scan is enough to reconnect.
 *
 * The delays back off to [delaysMs]' last value and stay there. Android 10 lets a foreground app
 * (the kiosk's foreground service counts) start 4 scans in 120 s. The default delays start at most
 * 3 in any 120 s, so no scan is throttled.
 */
class WifiScanPolicy(private val delaysMs: List<Long> = listOf(10_000, 30_000, 60_000, 120_000)) {

    data class Wifi(val enabled: Boolean, val connecting: Boolean, val screenOn: Boolean) {
        val needsScan: Boolean get() = enabled && !connecting && !screenOn
    }

    sealed interface Next {
        /** No scan is wanted. Cancel the pending one, if any. */
        data object Stop : Next
        /** Keep the pending scan as it is. */
        data object Keep : Next
        /** Scan after [delayMs], in place of any pending scan. */
        data class ScanIn(val delayMs: Long) : Next
    }

    /** Scans started since the scans began; null while no scans are wanted. */
    var scans: Int? = null
        private set

    /** After any change to [w]. */
    fun onChange(w: Wifi): Next = when {
        !w.needsScan -> { scans = null; Next.Stop }
        scans == null -> { scans = 0; Next.ScanIn(delaysMs[0]) }
        else -> Next.Keep
    }

    /** When the pending scan is due. [Next.ScanIn] means: scan now, and again after its delay. */
    fun onDue(w: Wifi): Next {
        if (!w.needsScan) return onChange(w)
        val n = (scans ?: 0) + 1
        scans = n
        return Next.ScanIn(delaysMs[minOf(n, delaysMs.lastIndex)])
    }
}
