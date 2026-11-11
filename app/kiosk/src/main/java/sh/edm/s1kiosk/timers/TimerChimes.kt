package sh.edm.s1kiosk.timers

import java.util.concurrent.Future

/**
 * The finished-timer chime: [COUNT] chimes [GAP_MS] apart so it's heard across the room, and [stop]
 * for when the done screen is dismissed (the chimes still to come are dropped and the one playing is
 * cut). [schedule] runs an action after a delay (the device's executor), [play] starts one chime,
 * [silence] stops the sound. Thread-safe.
 */
class TimerChimes(
    private val schedule: (delayMs: Long, action: () -> Unit) -> Future<*>,
    private val play: () -> Unit,
    private val silence: () -> Unit,
) {
    private val pending = ArrayList<Future<*>>()

    /** A timer finished: chime now and twice more. A second timer finishing restarts the series. */
    @Synchronized
    fun ring() {
        pending.forEach { it.cancel(false) }
        pending.clear()
        play()
        for (i in 1 until COUNT) pending += schedule(i * GAP_MS) { play() }
    }

    @Synchronized
    fun stop() {
        pending.forEach { it.cancel(false) }
        pending.clear()
        silence()
    }

    companion object {
        const val COUNT = 3
        const val GAP_MS = 3_000L
    }
}
