package sh.edm.s1kiosk

import android.app.Application
import android.content.Intent
import dev.glassworks.pane.applyFrom
import dev.glassworks.pane.intentExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.frame.AqaraS1Plus

/** The panes' adb extras (app/README.md): each pane's on/off (#67), then its own, from their definitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaneExtrasTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val extras by lazy { GlassworksRuntime(AqaraS1Plus()).paneDefinitions(app).intentExtras() }

    @Test
    fun theSameExtrasAsBefore() {
        assertEquals(
            listOf(
                "voice_enabled", "wake_model", "wake_threshold", "mic_gain", "mic_muted",
                "sendspin_enabled", "sendspin_server", "sendspin_unpaired_access", "sendspin_flac",
                "notifications_enabled", "bluetooth_proxy_enabled", "wall_base_enabled", "base_backend", "base_transport", "time_zone_enabled", "diagnostics_enabled",
            ),
            extras.map { it.name },
        )
    }

    @Test
    fun eachWritesItsSetting() {
        val restart = extras.applyFrom(
            Intent()
                .putExtra("wake_model", "alexa_v0.1.tflite").putExtra("wake_threshold", 0.7f).putExtra("mic_gain", 2f).putExtra("mic_muted", true)
                .putExtra("sendspin_enabled", false).putExtra("sendspin_server", "ws://192.0.2.30:8927").putExtra("sendspin_unpaired_access", true)
                .putExtra("sendspin_flac", false).putExtra("base_backend", "uart").putExtra("base_transport", "tcp:127.0.0.1:9999"),
        )
        assertFalse("applied live: no restart", restart)
        val p = KioskPrefs(app)
        assertEquals("an old model name is migrated", "alexa", p.voice.wakeWord)
        assertEquals("alexa's cutoff", 0.7f, p.voice.wakeCutoff)
        assertEquals(2f, p.voice.micGain)
        assertTrue(p.voice.micMuted)
        assertFalse(p.sendspin.enabled)
        assertEquals("ws://192.0.2.30:8927", p.sendspin.server)
        assertTrue(p.sendspin.unpairedAccess)
        assertFalse(p.sendspin.flac)
        assertEquals("uart", p.wallBase.backend)
        assertEquals("tcp:127.0.0.1:9999", p.wallBase.transport)
    }

    @Test
    fun everyPaneTurnsOffAndOnWithoutARestart() {
        val p = KioskPrefs(app)
        val flags = { listOf(p.voice.enabled, p.sendspin.enabled, p.notifications.enabled, p.bluetoothProxy.enabled, p.wallBase.enabled, p.timeZone.enabled, p.diagnostics.enabled) }
        val all = listOf("voice_enabled", "sendspin_enabled", "notifications_enabled", "bluetooth_proxy_enabled", "wall_base_enabled", "time_zone_enabled", "diagnostics_enabled")
        assertFalse("applied live", extras.applyFrom(Intent().apply { all.forEach { putExtra(it, false) } }))
        assertEquals(List(7) { false }, flags())
        assertFalse(extras.applyFrom(Intent().apply { all.forEach { putExtra(it, true) } }))
        assertEquals(List(7) { true }, flags())
    }

    @Test
    fun absentExtrasChangeNothingAndAWrongTypeGetsTheDefault() {
        val p = KioskPrefs(app)
        p.voice.threshold = 0.9f
        assertFalse(extras.applyFrom(Intent().putExtra("unrelated", true)))
        assertEquals(0.9f, p.voice.threshold)
        extras.applyFrom(Intent().putExtra("wake_threshold", "0.3")) // --es instead of --ef: the word's default
        assertEquals("Hey Jarvis's own cutoff", 0.97f, p.voice.threshold)
        p.voice.wakeWord = "hey_jarvis_v0.1"
        p.voice.threshold = 0.9f
        extras.applyFrom(Intent().putExtra("wake_threshold", "0.3"))
        assertEquals("openWakeWord's default, as before", 0.5f, p.voice.threshold)
        extras.applyFrom(Intent().putExtra("base_backend", 3)) // not a string: ignored
        assertEquals("auto", p.wallBase.backend)
    }
}
