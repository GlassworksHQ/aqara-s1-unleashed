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
 * Turned off, the root helper stops keeping the zone, and the pane keeps it in [prefs] for when it is turned on.
 */
class TimeZonePane(
    ctx: PaneContext,
    private val prefs: TimeZonePrefs,
    private val keeper: TimeZoneKeeper = TimeZoneKeeper(onChange = ctx.publishStates),
) : Pane {
    override val id = "time-zone"

    private val entities = listOf(
        // Authenticated only: the zone is written to a property the root helper applies.
        TextEntity(
            "time_zone", "Time zone", "mdi:map-clock", maxLength = 64, pattern = "^[A-Za-z0-9_+/-]*$",
            get = { keeper.text() },
            set = { v ->
                keeper.set(v)?.let { Log.w(TimeZoneKeeper.TAG, "Time zone: $it") }
                ctx.publishStates()
            },
        ).apply { authOnly = true },
        TextSensorEntity("time_zone_status", "Time zone status", "mdi:clock-check-outline", EntityCategory.DIAGNOSTIC, get = { keeper.statusText() }),
    )

    override fun entities(): List<ApiEntity> = entities

    override fun start() {
        // Turned on again: the zone it kept when it was turned off.
        prefs.kept.takeIf { it.isNotEmpty() }?.let { keeper.keepAgain(it); prefs.kept = "" }
        keeper.start()
    }

    override fun stop() = keeper.stop()

    override fun onEvent(event: PanelEvent) {
        // The core has stopped the pane already; stop() alone also runs when the app stops, which keeps the zone.
        if (event is PanelEvent.SettingChanged && event.key == TimeZonePrefs.KEY_ENABLED && !prefs.enabled) {
            keeper.release().takeIf { it.isNotEmpty() }?.let { prefs.kept = it }
        }
    }
}
