package io.github.aguilaair.s1kiosk.setup

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live Home Assistant ↔ panel signals for the setup screen, fed by the ESPHome device (PanelDevice)
 * from API connection threads. Listeners run on the main thread.
 */
object SetupSignals {
    /** A client whose Hello said "Home Assistant" connected in this process (also HA's discovery probe). */
    @Volatile var haHello = false
        private set

    /** Home Assistant subscribed to the panel's states in this process: it has added the panel. */
    @Volatile var haSubscribed = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun onHaHello() {
        if (haHello) return
        haHello = true
        notifyChanged()
    }

    fun onHaSubscribed() {
        haHello = true
        if (haSubscribed) return
        haSubscribed = true
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { runCatching { it() } } }
    }
}
