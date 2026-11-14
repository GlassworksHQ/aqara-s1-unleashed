package sh.edm.s1kiosk.frame

import android.util.Log
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.TextEntity
import sh.edm.esphome.TextSensorEntity

/**
 * The S1's `Time zone` and `Time zone status` entities (docs/install.md "Time zone"), backed by [TimeZoneKeeper].
 * Turned off, the root helper stops keeping the zone ([TimeZoneKeeper.OFF]), and the pane keeps it in [prefs]
 * for when it is turned on.
 */
class TimeZonePane(
    private val ctx: PaneContext,
    private val prefs: TimeZonePrefs,
    private val keeper: TimeZoneKeeper = TimeZoneKeeper(onChange = ctx.publishStates),
) : Pane {
    override val id = "time-zone"

    private val entities = listOf(
        // Authenticated only: the zone is written to a property the root helper applies.
        TextEntity(
            "time_zone", "Time zone", "mdi:map-clock", disabledByDefault = true, maxLength = 64, pattern = "^[A-Za-z0-9_+/-]*$",
            get = { keeper.text() },
            set = { v ->
                keeper.set(v)?.let { Log.w(TimeZoneKeeper.TAG, "Time zone: $it") }
                ctx.publishStates()
            },
        ).apply { authOnly = true },
        TextSensorEntity("time_zone_status", "Time zone status", "mdi:clock-check-outline", EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { keeper.statusText() }),
    )

    override fun entities(): List<ApiEntity> = entities

    /** Home Assistant's own zone, once it answered the panel's time request; null until then. For the settings page (#212). */
    @Volatile var homeAssistantZone: String? = null
        private set

    /** The zone the panel keeps, "" for none. */
    val kept: String get() = keeper.text()

    /** How keeping it goes ([TimeZoneKeeper.statusText]). */
    val status: String get() = keeper.statusText()

    /** Keeps [zone], as Home Assistant's `time_zone` does: null, or why not. */
    fun keep(zone: String): String? = keeper.set(zone).also { ctx.publishStates() }

    override fun start() {
        // Turned on again: the zone it kept when it was turned off (or none).
        keeper.keepAgain(prefs.kept)
        prefs.kept = ""
        keeper.start()
    }

    override fun stop() = keeper.stop()

    override fun onEvent(event: PanelEvent) {
        if (event is PanelEvent.HomeAssistantTimeZone) homeAssistantZone = event.zone
        // The core has stopped the pane already; stop() alone also runs when the app stops, which keeps the zone.
        if (event is PanelEvent.SettingChanged && event.key == TimeZonePrefs.KEY_ENABLED && !prefs.enabled) {
            keeper.release().takeIf { it.isNotEmpty() }?.let { prefs.kept = it }
        }
    }
}
