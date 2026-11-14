package sh.edm.s1kiosk

import android.content.pm.ServiceInfo
import dev.glassworks.platform.PlatformApi

/**
 * The foreground service types [KioskService] runs with, and the calls that set them.
 *
 * Microphone for the voice satellite. Camera for the camera pane's presence with the screen off (#117),
 * only once Android allows the camera. Location so the S1 can scan for Wi-Fi with the screen off
 * (#3): on Android 10 a granted location permission is allowed in the foreground only, and a
 * foreground service counts as foreground for location only with the location type.
 *
 * Each type goes in only when it may: location when it is granted, and microphone when it is
 * granted or the platform does not check it ([PlatformApi.foregroundServiceTypesNeedTheirPermission]).
 * Android 14 also refuses a type the app is not eligible for right now, such as location started
 * from the background, so [promote] then drops types until one start is accepted. Every refusal is
 * logged, so the first cause is not hidden by the next. Android 14 refuses a service with no type
 * at all, so when neither type is left the service runs as [CONNECTED_DEVICE], which needs no
 * runtime permission (the ESPHome API and Improv over BLE). The S1 never gets there.
 *
 * [promote] runs again whenever a grant may have changed. It calls [startWith] only when the
 * wanted types differ from the ones the service runs with.
 *
 * Call it on the main thread only. [types] is not synchronized, and `startForeground` belongs to
 * the service's thread; callbacks from other threads post to it first.
 */
class KioskForeground(
    private val platform: PlatformApi,
    /** A start was refused: a missing permission, or (Android 14+) a type or start the system forbids. */
    private val onRefused: (types: Int, e: RuntimeException) -> Unit,
    private val startWith: (types: Int) -> Unit,
) {
    /** The types the service runs with, or null before the first [promote]. */
    var types: Int? = null
        private set

    private fun wanted(micGranted: Boolean, locationGranted: Boolean, cameraGranted: Boolean): Int {
        val t = (if (micGranted || !platform.foregroundServiceTypesNeedTheirPermission) MICROPHONE else 0) or
            (if (locationGranted) LOCATION else 0) or
            (if (cameraGranted) CAMERA else 0)
        return if (t == 0) CONNECTED_DEVICE else t
    }

    /**
     * Returns the types the service runs with after this call. When every type is refused, the
     * first start throws the last refusal, and a later one keeps the types the service runs with.
     */
    fun promote(micGranted: Boolean, locationGranted: Boolean, cameraGranted: Boolean = false): Int {
        val want = wanted(micGranted, locationGranted, cameraGranted)
        val running = types
        if (running == want) return running
        // Every subset of the wanted types, most types first; among equals, keep the microphone, then
        // location, then the camera. So two refused types still leave the third.
        val kinds = listOf(MICROPHONE, LOCATION, CAMERA).filter { want and it != 0 }
        val subsets = (1 until (1 shl kinds.size)).map { m -> kinds.filterIndexed { i, _ -> m shr i and 1 == 1 }.fold(0) { a, k -> a or k } }
        val keep = { t: Int -> (if (t and MICROPHONE != 0) 4 else 0) + (if (t and LOCATION != 0) 2 else 0) + (if (t and CAMERA != 0) 1 else 0) }
        val tries = subsets.sortedWith(compareByDescending<Int> { Integer.bitCount(it) }.thenByDescending(keep))
            .plus(CONNECTED_DEVICE).distinct()
        for ((i, t) in tries.withIndex()) {
            try {
                startWith(t)
                types = t
                return t
            } catch (e: RuntimeException) {
                // SecurityException: a missing permission. IllegalStateException: Android 14's
                // ServiceStartNotAllowedException family (a type that is missing or not allowed, a
                // start from the background). [startWith] is only startForeground, so no other bug
                // lands here; anything else still throws.
                if (e !is SecurityException && e !is IllegalStateException) throw e
                onRefused(t, e)
                if (i == tries.lastIndex && running == null) throw e
            }
        }
        return running!!
    }

    companion object {
        const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        const val LOCATION = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        const val CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        const val CAMERA = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

        /** The manifest names of [types], for the log: `microphone, location`. */
        fun names(types: Int): String =
            listOf(MICROPHONE to "microphone", LOCATION to "location", CAMERA to "camera", CONNECTED_DEVICE to "connectedDevice")
                .filter { types and it.first != 0 }.joinToString(", ") { it.second }
    }
}
