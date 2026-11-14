package sh.edm.s1kiosk.sendspin

import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ApiServer
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.SendspinApi
import sh.edm.esphome.SendspinTokenSource
import sh.edm.esphome.TextSensorEntity

/**
 * The Music Assistant player (Sendspin, docs/sendspin.md) as a pane: its two entities, its lifecycle and its settings.
 * [haBase] is the Home Assistant the panel talks to, for the `auto` server address.
 */
class SendspinPane(ctx: PaneContext, haBase: () -> String?) : Pane {
    override val id = "sendspin"

    private val prefs = SendspinPrefs(ctx.prefs(id))

    /** Internal: the settings page (SendspinDefinition) reads it and sets its volume. */
    internal val player = SendspinPlayer(ctx.context, prefs, haBase, ctx.device, ctx.publishStates)

    // Its on/off (`sendspin`) is the core's, from SendspinDefinition.switch.
    override fun entities(): List<ApiEntity> = listOf(
        TextSensorEntity(
            "sendspin_status", "Music Assistant player status", "mdi:speaker-wireless", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { player.statusText() },
        ),
    )

    override fun bind(server: ApiServer) {
        server.sendspin = object : SendspinTokenSource {
            override fun clientIdIfStarted() = player.takeIf { it.started }?.clientId ?: ""
            override fun pairingToken(): Pair<Int, String?> {
                val status = pairingStatus(prefs.enabled, player.started)
                return status to if (status == SendspinApi.STATUS_OK) player.pairingToken() else null
            }
        }
    }

    /** The core starts it while the switch is on, and stops it when it is turned off. */
    override fun start() = player.start()

    // client/goodbye's reason: the person turned the player off, or the panel is stopping.
    override fun stop() = player.stop(if (prefs.enabled) "shutdown" else "user_request")

    override fun onEvent(event: PanelEvent) {
        when (event) {
            is PanelEvent.SettingChanged -> when (event.key) {
                // A new client/hello must carry the changed setting: reconnect.
                SendspinPrefs.KEY_UNPAIRED, SendspinPrefs.KEY_SERVER -> player.restartSession()
                SendspinPrefs.KEY_FLAC -> player.onFlacSettingChanged()
            }
            // Sendspin keeps its timeline (the group plays on); the panel just goes quiet locally.
            is PanelEvent.SpeakerClaimed -> player.setDucked(event.claimed)
        }
    }
}

// `started` means "started since boot" and is never cleared, so the setting has to be checked first.
internal fun pairingStatus(enabled: Boolean, started: Boolean): Int = when {
    !enabled -> SendspinApi.STATUS_DISABLED
    !started -> SendspinApi.STATUS_NOT_READY
    else -> SendspinApi.STATUS_OK
}
