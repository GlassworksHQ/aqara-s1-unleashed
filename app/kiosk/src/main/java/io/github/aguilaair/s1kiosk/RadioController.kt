package io.github.aguilaair.s1kiosk

import android.util.Log

/**
 * Zigbee/Thread radio mode of the panel. Stub: it only logs. The mode is chosen from Home Assistant
 * (`select.s1_panel_radio_mode`) and persisted in [KioskPrefs.radioMode]; the actual switching
 * (stopping Aqara's stack, exposing the radio as a router / TCP coordinator) is not implemented yet.
 */
object RadioController {
    val MODES = listOf("Aqara (stock)", "Off", "Zigbee router", "Zigbee coordinator (TCP)", "Thread (future)")

    fun apply(mode: String) {
        Log.i("S1Kiosk.Radio", "Radio mode '$mode' requested (not implemented yet; no change made)")
    }
}
