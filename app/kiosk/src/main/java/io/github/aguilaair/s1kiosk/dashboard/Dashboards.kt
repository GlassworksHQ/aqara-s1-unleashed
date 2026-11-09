package io.github.aguilaair.s1kiosk.dashboard

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The latest Lovelace dashboard list, reported by the HA frontend in the GeckoView
 * (assets/extension/content.js → MainActivity) and consumed by the ESPHome "Dashboard" select
 * (PanelDevice). Process-wide; listeners run on the reporting thread.
 */
object Dashboards {
    @Volatile var latest: List<Dashboard>? = null
        private set

    private val listeners = CopyOnWriteArrayList<(List<Dashboard>) -> Unit>()

    fun addListener(l: (List<Dashboard>) -> Unit) { listeners += l }
    fun removeListener(l: (List<Dashboard>) -> Unit) { listeners -= l }

    fun report(list: List<Dashboard>) {
        latest = list
        listeners.forEach { runCatching { it(list) } }
    }
}
