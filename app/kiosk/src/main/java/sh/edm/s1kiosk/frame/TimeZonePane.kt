package sh.edm.s1kiosk.frame

import android.util.Log
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.TextEntity
import sh.edm.esphome.TextSensorEntity

/** The S1's `Time zone` and `Time zone status` entities (docs/install.md "Time zone"), backed by [TimeZoneKeeper]. */
class TimeZonePane(ctx: PaneContext) : Pane {
    override val id = "time-zone"

    private val keeper = TimeZoneKeeper(onChange = ctx.publishStates)

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

    override fun start() = keeper.start()

    override fun stop() = keeper.stop()
}
