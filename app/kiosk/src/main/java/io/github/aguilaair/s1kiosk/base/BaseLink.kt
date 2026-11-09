package io.github.aguilaair.s1kiosk.base

/**
 * A way to reach the wall base. [WallBase] owns one and drives it from its "s1-base" thread; every
 * method is called on that thread and every [Listener] callback is delivered on it.
 *
 * - [UartBaseLink]: speaks the base's UART protocol on `/dev/ttyS3` (or a TCP emulator) itself.
 * - [LocalServiceLink]: goes through the stock LocalService, i.e. through `ha_master`.
 */
interface BaseLink {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onUnavailable(reason: String)
        /**
         * Resource values. [apply]: they describe the panel's resources (else they are requests
         * *to* the panel, only used as a [dockTrigger]). [dockTrigger]: an unsolicited
         * event/report, so a dock resource in it should make WallBase re-read the dock class.
         */
        fun onValues(values: Map<String, Any>, via: String, apply: Boolean, dockTrigger: Boolean)
        /** The link can't own its port right now (e.g. ha_master runs); see PanelDevice. */
        fun onBlocked(reason: String) {}
    }

    /** Short description for logs, e.g. `uart /dev/ttyS3`. */
    val describe: String
    val isConnected: Boolean
    /** Poll list and period while a switch base is attached. */
    val pollRes: List<String>
    val pollIntervalMs: Long
    /** Confirm a relay write with reads (LocalService gives no reliable write result). */
    val confirmWrites: Boolean

    fun start()
    /** Called every second by WallBase. */
    fun tick(now: Long)
    fun read(res: List<String>)
    fun write(res: String, value: Int)
    fun stop()
}

/** Which link WallBase uses; see [resolve]. */
enum class BaseBackend(val pref: String) {
    UART("uart"), LOCALSERVICE("localservice");

    companion object {
        const val AUTO = "auto"
        val PREF_VALUES = listOf(AUTO, UART.pref, LOCALSERVICE.pref)

        /**
         * `uart` / `localservice` as asked; `auto` = UART, except when Aqara cloud blocking is
         * off: then `ha_master` runs and owns `/dev/ttyS3`, so the base must go through it.
         */
        fun resolve(pref: String?, blockAqaraCloud: Boolean): BaseBackend = when (pref?.trim()?.lowercase()) {
            UART.pref -> UART
            LOCALSERVICE.pref -> LOCALSERVICE
            else -> if (blockAqaraCloud) UART else LOCALSERVICE
        }
    }
}
