package sh.edm.s1kiosk

import android.content.pm.ServiceInfo

/**
 * The foreground service types [KioskService] runs with.
 *
 * Microphone for the voice satellite. Location so the S1 can scan for Wi-Fi with the screen off
 * (#3): on Android 10 a granted location permission is allowed in the foreground only, and a
 * foreground service counts as foreground for location only with the location type.
 *
 * The location type goes in only when location is granted: from Android 14 a location type without
 * the permission throws. Android 14 also throws for a location type started from the background, so
 * [start] then starts again without it. The service never fails to start over location.
 */
object KioskForeground {
    const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    const val LOCATION = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    fun types(locationGranted: Boolean): Int = MICROPHONE or (if (locationGranted) LOCATION else 0)

    /** Calls [startWith] with [types]; on a SecurityException over location, once more without it. Returns the types it ran with. */
    fun start(locationGranted: Boolean, startWith: (Int) -> Unit): Int {
        val t = types(locationGranted)
        return try {
            startWith(t)
            t
        } catch (e: SecurityException) {
            if (t and LOCATION == 0) throw e
            (t and LOCATION.inv()).also(startWith)
        }
    }
}
