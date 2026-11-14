package sh.edm.s1kiosk.sendspin

import dev.glassworks.frame.Capability
import android.content.SharedPreferences
import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import java.net.URI

/** The Sendspin player ([SendspinPane]) for the whole process: its on/off and adb extras (app/README.md). [file] is its settings file. */
class SendspinDefinition(
    file: SharedPreferences,
    /**
     * Runs the page's volume changes: each writes to Music Assistant's socket, which the main thread must
     * not do. One thread, so they reach Music Assistant in the order they were made: the runtime's
     * settings worker. A test runs them by hand.
     */
    private val background: (Runnable) -> Unit,
) : PaneDefinition {
    override val id = "sendspin"
    override val requires = setOf(Capability.SPEAKER)

    private val prefs = SendspinPrefs(file)

    // The Home Assistant switch the panel has always had: its id, name and icon stay.
    override val switch = PaneSwitch(
        file, SendspinPrefs.KEY_ENABLED, default = true, title = "Music", detail = "A Sendspin speaker",
        objectId = "sendspin", entityName = "Music Assistant player (Sendspin)", icon = "mdi:speaker-wireless",
        inSetup = true,
        offNote = "Music is off. Music Assistant doesn't see this panel as a speaker until you turn it on.",
    )

    /** The Music Assistant connection, what plays, its volume and the stream's format (#212). */
    override fun settings(running: Pane?): List<SettingsSection> {
        val p = running as? SendspinPane ?: return emptyList()
        val player = p.player
        // The player's status is the line under the heading; Home Assistant's own sensor is listed too.
        return listOf(SettingsSection(id, "Music", covers = emptySet(), status = { PaneState(PaneState.Kind.RUNNING, "Sendspin · Music Assistant") }, items = listOfNotNull(
            SettingsItem.Status(text = { status(player.status, player.serverUrl) }, ok = { connected(player.status.phase) }),
            SettingsItem.Info({ "Now playing" }, { player.nowPlayingText ?: "Nothing" }),
            SettingsItem.Slider("Volume", { player.volume.toFloat() }, 0f, 100f, 1f, { "${it.toInt()} %" }) { v, done ->
                background { player.setVolume(v.toInt()); done(null) }
            },
            SettingsItem.Info({ "Audio" }, { audio(player.status) }),
        )))
    }

    companion object {
        private fun connected(phase: SendspinSession.Phase) = phase == SendspinSession.Phase.READY || phase == SendspinSession.Phase.PLAYING

        /** The line under the heading: how the player is connected, and to where. */
        fun status(s: SendspinSession.Status, url: String?): String {
            val name = s.serverName ?: "Music Assistant"
            val host = url?.let { runCatching { URI(it).host }.getOrNull() }
            return when (s.phase) {
                SendspinSession.Phase.READY, SendspinSession.Phase.PLAYING -> "Connected to $name" + (host?.let { " · $it" } ?: "")
                SendspinSession.Phase.CONNECTING, SendspinSession.Phase.HANDSHAKE -> "Connecting to Music Assistant"
                SendspinSession.Phase.HELD -> "Waiting for $name to let the panel in"
                SendspinSession.Phase.PAIRING -> "Pairing with $name"
                SendspinSession.Phase.CLOSED -> "Not connected to Music Assistant"
            }
        }

        /** The stream's format. The player offers only 48 kHz stereo, so the server sends that: the codec is the choice. */
        fun audio(s: SendspinSession.Status): String =
            if (s.phase != SendspinSession.Phase.PLAYING || s.codec == null) "Not playing" else "${s.codec.uppercase()} · 48 kHz · stereo"
    }

    override val extras = listOf(
        IntentExtra.string("sendspin_server") { prefs.server = it },
        IntentExtra.bool("sendspin_unpaired_access", default = false) { prefs.unpairedAccess = it },
        IntentExtra.bool("sendspin_flac", default = true) { prefs.flac = it },
    )
}
