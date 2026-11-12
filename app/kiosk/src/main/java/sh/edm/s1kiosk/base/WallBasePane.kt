package sh.edm.s1kiosk.base

import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.EspHomeLog
import sh.edm.esphome.EventEntity
import sh.edm.esphome.SwitchEntity
import sh.edm.esphome.TextSensorEntity
import sh.edm.s1kiosk.AqaraCloud
import sh.edm.s1kiosk.KioskPrefs
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The S1 Plus wall base as a pane: the [WallBase] driver, its entities (which change with the mounted
 * base) and Aqara's cloud daemon `ha_master`, which owns `/dev/ttyS3` and, on the LocalService
 * backend, the base itself.
 *
 * [device] is the shell's device thread (esphome-main). Everything the base reports runs there, and
 * so do the backend changes, because [WallBase.reconfigure] blocks until the old link closed its port.
 */
class WallBasePane(
    private val ctx: PaneContext,
    config: WallBase.Config,
    private val device: ScheduledExecutorService,
) : Pane {
    override val id = "wall-base"

    private val prefs = ctx.prefs
    /** What [WallBase] reports to; internal so a test can play the base. */
    internal val baseListener: WallBase.Listener = Listener()
    private val base = WallBase(ctx.context, BaseLayout.decode(prefs.baseLayout) ?: BaseLayout.NONE, baseListener, config)

    /** The layout whose entities Home Assistant has (set before [PaneContext.entitiesChanged]). */
    @Volatile private var layout: BaseLayout = base.tracker.applied()
    /** Built once per layout, so the event entity [onButton] fires is the instance the server has. */
    @Volatile private var built: Pair<BaseLayout, List<ApiEntity>>? = null
    @Volatile private var aqaraCloudStatus = "unknown"

    private val cloudEntities = listOf(
        SwitchEntity(
            "block_aqara_cloud", "Block Aqara cloud", "mdi:cloud-off-outline", EntityCategory.CONFIG,
            get = { prefs.blockAqaraCloud }, set = { prefs.blockAqaraCloud = it },
        ),
        TextSensorEntity(
            "aqara_cloud", "Aqara cloud daemon", "mdi:cloud-outline", EntityCategory.DIAGNOSTIC,
            get = { aqaraCloudStatus },
        ),
    )

    override fun entities(): List<ApiEntity> = cloudEntities + baseEntities()

    @Synchronized private fun baseEntities(): List<ApiEntity> {
        val l = layout
        built?.let { (b, list) -> if (b == l) return list }
        return BaseEntities.build(l, base.tracker, base::setRelay).also { built = l to it }
    }

    /** esphome-main. UART: ha_master stops before the link opens the port. */
    override fun start() {
        val backend = base.config.backend
        if (backend == BaseBackend.UART) applyAqaraCloud("startup, before opening the wall base port", backend)
        base.start()
        if (backend == BaseBackend.LOCALSERVICE) {
            // Give the wall-base check time to read the dock class (it needs ha_master), then apply.
            device.schedule({ applyAqaraCloud("startup") }, 20, TimeUnit.SECONDS)
        }
    }

    override fun stop() = base.stop()

    override fun onEvent(event: PanelEvent) {
        if (event !is PanelEvent.SettingChanged) return
        when (event.key) {
            KioskPrefs.KEY_BLOCK_AQARA_CLOUD, KioskPrefs.KEY_BASE_BACKEND, KioskPrefs.KEY_BASE_TRANSPORT ->
                device.execute { reconfigure("setting ${event.key} changed") }
        }
    }

    /**
     * esphome-main: apply backend/transport/cloud-blocking changes so that ha_master and the UART
     * link never hold /dev/ttyS3 at the same time: to UART, stop ha_master first, then open; away
     * from UART, close the port first, then let ha_master run.
     */
    private fun reconfigure(why: String) {
        val c = wantedConfig(prefs)
        if (c.backend == BaseBackend.UART) {
            applyAqaraCloud(why, c.backend)
            base.reconfigure(c)
        } else {
            base.reconfigure(c)
            applyAqaraCloud(why, c.backend)
        }
    }

    /**
     * esphome-main: stop Aqara's cloud daemon when blocking is on and no wall base needs it, else
     * make sure it runs. Re-applied after every start (init restarts it on boot) and on changes.
     */
    private fun applyAqaraCloud(why: String, backend: BaseBackend = base.config.backend) {
        val run = when {
            // The UART link owns /dev/ttyS3: ha_master is never needed (and must not run).
            backend == BaseBackend.UART -> false
            !prefs.blockAqaraCloud -> true
            else -> {
                // LocalService backend: the base link goes through ha_master.
                val current = base.tracker.current() ?: BaseLayout.decode(prefs.baseLayout) ?: BaseLayout.NONE
                current.kind == BaseKind.SWITCH || current.kind == BaseKind.UNKNOWN
            }
        }
        val ok = AqaraCloud.setDaemon(run)
        aqaraCloudStatus = when {
            !ok -> "error (${AqaraCloud.daemonState().ifBlank { "unknown" }})"
            !run && !prefs.blockAqaraCloud -> "stopped (UART wall base backend owns ${UartBaseLink.SERIAL_PATH})"
            !run -> "blocked"
            prefs.blockAqaraCloud -> "running (needed by the wall base, LocalService backend)"
            else -> "running"
        }
        EspHomeLog.i("Aqara cloud ($why): $aqaraCloudStatus")
        ctx.publishStates()
    }

    /** Called on the "s1-base" thread. */
    private inner class Listener : WallBase.Listener {
        override fun onStates() = ctx.publishStates()

        override fun onLayout(layout: BaseLayout) {
            this@WallBasePane.layout = layout
            prefs.baseLayout = layout.encode()
            ctx.entitiesChanged()
            // Queued after the rebuild, as before: Home Assistant gets the new entities first.
            device.execute { applyAqaraCloud("wall base ${layout.kind}") }
        }

        override fun onPortBlocked(reason: String) {
            // Something (re)started ha_master while the UART backend should own the port.
            device.execute { applyAqaraCloud("wall base port blocked: $reason") }
        }

        override fun onButton(channel: Int, type: String) {
            val e = baseEntities().firstOrNull { it.objectId == BaseEntities.buttonId(channel) } as? EventEntity
            if (e == null) EspHomeLog.w("base button $channel ($type): no event entity in the current set")
            else ctx.sendEvent(e, type)
        }
    }

    companion object {
        /** One instance, so WallBase.Config equality works across re-applies. */
        private val ttyGuard: () -> String? = {
            when (val st = AqaraCloud.daemonState()) {
                "running", "restarting" -> "ha_master is $st and owns ${UartBaseLink.SERIAL_PATH}"
                else -> null
            }
        }

        /** The backend and transport the settings ask for. */
        fun wantedConfig(prefs: KioskPrefs): WallBase.Config =
            WallBase.Config(BaseBackend.resolve(prefs.baseBackend, prefs.blockAqaraCloud), prefs.baseTransport, ttyGuard)
    }
}
