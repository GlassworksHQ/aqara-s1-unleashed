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

    private val root by lazy { android.widget.FrameLayout(Mockup.activity()) }

    /** A Madrid panel whose HA has Madrid's rule, and its page; the match waits in [queued]. */
    private fun page(queued: MutableList<Runnable>): TimeZoneView {
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
            override val context = root.context
            override fun addLayer(layer: View) { root.addView(layer) }
            override fun reveal(field: View) {}
            override fun hideKeyboard() {}
        }
        val view = TimeZoneView(host, pane, TimeZoneChoices(TimeZone.getAvailableIDs().toList())) { queued += it }
        root.addView(view.view)
        Mockup.show(root)
        return view
    }

    @Test fun theMatchRunsInTheBackgroundAndItsAnswerShowsAfter() {
        val queued = ArrayList<Runnable>()
        val view = page(queued)
        view.refresh()
        assertEquals("queued, not run on the caller's thread", 1, queued.size)
        assertFalse("nothing to show yet", shown(view, "the same as the panel's"))
        view.refresh()
        assertEquals("one match at a time", 1, queued.size)
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        // Whatever the season: the offset is Madrid's at the time the test runs (CodeRabbit on #235).
        assertTrue(shown(view, "${TimeZoneChoices.offset("Europe/Madrid", java.time.Instant.now())} · the same as the panel's"))
        view.refresh()
        assertEquals("answered: not matched again", 1, queued.size)
    }

    @Test fun aPickerOpenBeforeTheMatchGetsItsCardWhenItLands() {
        // CodeRabbit on #235: the card came only after closing and opening the picker again.
        val queued = ArrayList<Runnable>()
        val view = page(queued)
        view.refresh()
        view.open()
        assertFalse(shownIn(root, "Home Assistant's clock"))
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shownIn(root, "The same as the panel's"))
    }

    private fun shown(v: TimeZoneView, text: String) = shownIn(v.view, text)

    private fun shownIn(v: View, text: String) = runCatching {
        generateSequence(listOf(v)) { l -> l.flatMap { x -> (x as? android.view.ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filterIsInstance<android.widget.TextView>().any { it.text.toString().startsWith(text) && it.isShown }
    }.getOrDefault(false)
}
