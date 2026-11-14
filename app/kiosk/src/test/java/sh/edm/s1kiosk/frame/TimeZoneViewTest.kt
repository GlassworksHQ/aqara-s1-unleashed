package sh.edm.s1kiosk.frame

import android.app.Application
import android.os.Looper
import android.view.View
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.SettingsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.SysProps
import sh.edm.s1kiosk.ui.Mockup
import java.util.TimeZone

/** #235 verifier N1: matching Home Assistant's zone reads a year of offsets, so it never runs on the main thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimeZoneViewTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun theMatchRunsInTheBackgroundAndItsAnswerShowsAfter() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        val props = object : SysProps {
            val p = mutableMapOf(TimeZoneKeeper.PROP to "Europe/Madrid", TimeZoneKeeper.PROP_STATUS to "ok Europe/Madrid")
            override fun get(name: String) = p[name].orEmpty()
            override fun set(name: String, value: String) { p[name] = value }
        }
        val ctx = PaneContext(
            app, KioskPrefs(app)::paneFile, DeviceIdentity("t", "T", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
            publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        )
        val pane = TimeZonePane(ctx, TimeZonePrefs(KioskPrefs(app).paneFile("time-zone")), TimeZoneKeeper(props, inline = true))
        pane.start()
        pane.onEvent(PanelEvent.HomeAssistantTimeZone("CET-1CEST,M3.5.0,M10.5.0/3"))
        val host = object : SettingsItem.NativeHost {
            override val context = Mockup.activity()
            override fun addLayer(layer: View) {}
            override fun reveal(field: View) {}
            override fun hideKeyboard() {}
        }
        val queued = ArrayList<Runnable>()
        val view = TimeZoneView(host, pane, TimeZoneChoices(TimeZone.getAvailableIDs().toList())) { queued += it }
        Mockup.show(view.view)
        view.refresh()
        assertEquals("queued, not run on the caller's thread", 1, queued.size)
        assertFalse("nothing to show yet", shown(view, "the same as the panel's"))
        view.refresh()
        assertEquals("one match at a time", 1, queued.size)
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shown(view, "UTC+01:00 · the same as the panel's"))
        view.refresh()
        assertEquals("answered: not matched again", 1, queued.size)
    }

    private fun shown(v: TimeZoneView, text: String) = runCatching { Mockup.label(v.view, text).isShown }.getOrDefault(false)
}
