package sh.edm.s1kiosk.sendspin

import android.app.Application
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.SettingsItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.sendspin.SendspinSession.Phase
import sh.edm.s1kiosk.sendspin.SendspinSession.Status

/** The Music page (#212): its words, and its volume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicPageTest {
    @Test fun theVolumeIsSentOffTheCallingThread() {
        // CodeRabbit on #235: the slider wrote to Music Assistant's socket on the main thread.
        val app = RuntimeEnvironment.getApplication()
        val ctx = PaneContext(
            app, KioskPrefs(app)::paneFile,
            DeviceIdentity("test", "Test", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
            publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        )
        val pane = SendspinPane(ctx, haBase = { null })
        pane.player.setVolume(40)
        val queued = ArrayList<Runnable>()
        val page = SendspinDefinition(KioskPrefs(app).paneFile("sendspin")) { queued += it }.settings(pane).single()
        val volume = page.items.filterIsInstance<SettingsItem.Slider>().single()
        var answered: String? = "not yet"
        volume.save(25f) { answered = it }
        assertEquals("nothing ran on the caller's thread", 40, pane.player.volume)
        assertEquals("not yet", answered)
        queued.single().run()
        assertEquals(25, pane.player.volume)
        assertEquals(null, answered)
    }

    @Test fun theStatusLine() {
        assertEquals("Connected to Music Assistant · 192.0.2.30",
            SendspinDefinition.status(Status(Phase.PLAYING, serverName = "Music Assistant"), "ws://192.0.2.30:8927/sendspin"))
        assertEquals("Connected to Music Assistant", SendspinDefinition.status(Status(Phase.READY), null))
        assertEquals("Waiting for Living room MA to let the panel in", SendspinDefinition.status(Status(Phase.HELD, serverName = "Living room MA"), null))
        assertEquals("Not connected to Music Assistant", SendspinDefinition.status(Status(Phase.CLOSED), "ws://192.0.2.30:8927/sendspin"))
    }

    @Test fun theAudioLine() {
        assertEquals("FLAC · 48 kHz · stereo", SendspinDefinition.audio(Status(Phase.PLAYING, codec = "flac")))
        assertEquals("Not playing", SendspinDefinition.audio(Status(Phase.READY, codec = "flac")))
    }
}
