package sh.edm.s1kiosk.sendspin

import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ApiServer
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.SendspinApi
import sh.edm.esphome.SendspinTokenSource
import sh.edm.esphome.SwitchEntity
import sh.edm.esphome.TextSensorEntity
import sh.edm.s1kiosk.KioskPrefs

/** The Music Assistant player (Sendspin, docs/sendspin.md) as a pane: its two entities, its lifecycle and its settings. */
class SendspinPane(ctx: PaneContext) : Pane {
    override val id = "sendspin"

    private val prefs: KioskPrefs = ctx.prefs

    // runCatching: the player reports its stop to the device's executor, which is already shut down by then.
    private val player = SendspinPlayer(ctx.context, prefs, ctx.device.macAddress) { runCatching { ctx.publishStates() } }

    override fun entities(): List<ApiEntity> = listOf(
        SwitchEntity(
            "sendspin", "Music Assistant player (Sendspin)", "mdi:speaker-wireless", EntityCategory.CONFIG,
            get = { prefs.sendspinEnabled }, set = { prefs.sendspinEnabled = it },
        ),
        TextSensorEntity(
            "sendspin_status", "Music Assistant player status", "mdi:speaker-wireless", EntityCategory.DIAGNOSTIC,
            get = { player.statusText() },
        ),
    )

    override fun bind(server: ApiServer) {
        server.sendspin = object : SendspinTokenSource {
            override fun clientIdIfStarted() = player.takeIf { it.started }?.clientId ?: ""
            override fun pairingToken(): Pair<Int, String?> = when {
                !player.started -> (if (prefs.sendspinEnabled) SendspinApi.STATUS_NOT_READY else SendspinApi.STATUS_DISABLED) to null
                else -> SendspinApi.STATUS_OK to player.pairingToken()
            }
        }
    }

    override fun start() {
        if (prefs.sendspinEnabled) player.start()
    }

    override fun stop() = player.stop("shutdown")

    override fun onEvent(event: PanelEvent) {
        when (event) {
            is PanelEvent.SettingChanged -> when (event.key) {
                KioskPrefs.KEY_SENDSPIN_ENABLED -> if (prefs.sendspinEnabled) player.start() else player.stop("user_request")
                // A new client/hello must carry the changed setting: reconnect.
                KioskPrefs.KEY_SENDSPIN_UNPAIRED, KioskPrefs.KEY_SENDSPIN_SERVER -> player.restartSession()
                KioskPrefs.KEY_SENDSPIN_FLAC -> player.onFlacSettingChanged()
            }
            // Sendspin keeps its timeline (the group plays on); the panel just goes quiet locally.
            is PanelEvent.SpeakerClaimed -> player.setDucked(event.claimed)
        }
    }
}
