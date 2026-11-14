package sh.edm.s1kiosk.manage

import android.app.Application
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.ManagePages
import dev.glassworks.manage.paneRow
import dev.glassworks.manage.text
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.web.PageShell.esc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.PaneFixtures
import sh.edm.s1kiosk.settings.S1Settings
import sh.edm.s1kiosk.ui.Mockup
import java.net.URLEncoder

/**
 * #242: the panel and the Manage page show the same settings, editable on both, from one model. Walks every
 * section and item the panel's menu draws (the S1's own definitions, every pane running) and checks the
 * Manage page draws the same item, changeable when the panel's is, and that a change from the browser
 * reaches the item's own save. A setting on one side only fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class PanelManageParityTest {
    private val app = RuntimeEnvironment.getApplication()
    private val panes = PaneFixtures(app)

    @After fun shutdown() = panes.shutdown()

    /**
     * Views only the panel draws for now, by section: the Manage page says "On the panel" for them. A new
     * native view fails the test until it is here, with its reason, or has typed items the page can draw.
     */
    private val panelOnly = mapOf(
        "radio" to "the roles picker, the Thread dataset and the radio's actions (RadioSettingsView)",
        "time-zone" to "the zone picker (TimeZoneView)",
        "wall-base" to "the relays' cards, or what a base adds (WallBaseViews)",
        "bluetooth-proxy" to "the devices and Allow, which needs someone at the panel (BluetoothProxyPage)",
    )

    private fun walk(items: List<SettingsItem>, prefix: String = "", f: (SettingsItem, String) -> Unit) {
        for (i in items) {
            f(i, prefix + SettingsChange.titleOf(i))
            if (i is SettingsItem.Group) walk(i.items, prefix + i.title + "/", f)
        }
    }

    /** What the panel's row and the Manage page's both say for [i]. */
    private fun words(i: SettingsItem): List<String> = when (i) {
        is SettingsItem.Note -> listOf(i.text())
        is SettingsItem.Status -> listOf(i.text())
        is SettingsItem.Info -> listOf(i.title(), i.detail()).filter { it.isNotEmpty() }
        is SettingsItem.States -> listOf(i.title) + i.rows().flatMap { listOf(it.name, it.value) }
        is SettingsItem.Tiles -> i.tiles().flatMap { listOf(it.name, it.value) }
        is SettingsItem.Native -> emptyList()
        is SettingsItem.Text -> listOf(i.title, SettingsChange.shown(i))
        is SettingsItem.Slider -> listOf(i.title, i.shown(i.value()))
        is SettingsItem.Duration -> listOf(i.title, i.shown(i.value()))
        is SettingsItem.TimeRange -> listOf(i.title, i.shown(i.value()))
        is SettingsItem.Choice -> listOf(i.title, SettingsChange.shown(i))
        else -> listOf(SettingsChange.titleOf(i))
    }

    /** The value [i] has now, as a form sends it: posting it back must save and change nothing. */
    private fun current(i: SettingsItem): Map<String, String> = when (i) {
        is SettingsItem.Text -> mapOf("value" to i.value())
        is SettingsItem.Switch -> mapOf("value" to if (i.value()) "on" else "off")
        is SettingsItem.Slider -> mapOf("value" to SettingsChange.plain(i.value()))
        is SettingsItem.Duration -> mapOf("value" to i.value().toString())
        is SettingsItem.Choice -> mapOf("value" to i.value())
        is SettingsItem.TimeRange -> SettingsChange.parseRange(i.value())
            ?.let { (a, b) -> mapOf("on" to "1", "from" to SettingsChange.hhmm(a), "to" to SettingsChange.hhmm(b)) } ?: mapOf("on" to "0")
        else -> error("not changeable: $i")
    }

    @Test fun everySettingOnThePanelIsOnTheManagePageAndChangesThere() {
        val model = panes.model(panes.all())
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.reload = model
        o.show(model())
        Mockup.idle()

        val f = ManageFixture()
        f.statusFn = { model().let { m -> f.status.copy(panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }, sections = m.sections) } }
        val b = f.Browser().also { it.signInWithHa() }
        val natives = HashSet<String>()
        var changed = 0

        for (sec in model().sections) {
            o.previewSection(sec.id)
            Mockup.idle()
            val path = "/" + sec.id
            val page = b.get(path).text()
            fun onPanel(text: String) = runCatching { Mockup.label(o, text) }.isSuccess
            assertTrue("${sec.id}: no row takes the code dialog's backdrop class", Regex("""class="[^"]*\bdim\b""").find(page) == null)
            assertTrue("${sec.id}: the heading", onPanel(sec.heading) && page.contains(esc(sec.heading)))
            sec.master?.takeIf { sec.unavailable() == null }?.let {
                assertTrue("${sec.id}: its on/off on the page", page.contains("name=\"key\" value=\"${SettingsChange.MASTER}\""))
            }
            walk(sec.items) { item, key ->
                for (w in words(item)) {
                    assertTrue("${sec.id}: the panel shows \"$w\"", onPanel(w))
                    assertTrue("${sec.id}: the Manage page shows \"$w\"", page.contains(esc(w)))
                }
                if (item is SettingsItem.Native) {
                    natives += sec.id
                    assertTrue("${sec.id}: a native view (${item.key}) the Manage page can't draw yet must be listed in panelOnly", sec.id in panelOnly)
                    assertTrue("${sec.id}: the Manage page says it's on the panel", page.contains(ManagePages.ON_THE_PANEL))
                }
                val form = page.contains("name=\"key\" value=\"${esc(key)}\"")
                val link = page.contains("$path?edit=${esc(URLEncoder.encode(key, "UTF-8"))}\"")
                assertEquals("${sec.id}: \"$key\" is changeable on the Manage page exactly when it is on the panel", SettingsChange.canChange(item), form || link)
            }
            // Each change goes through the item's own save: its value now, sent back, saves and changes nothing.
            for ((key, item) in SettingsChange.changeable(sec)) {
                if (key == SettingsChange.MASTER && sec.unavailable() != null) continue
                val before = current(item)
                val r = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to sec.id, "key" to key, "confirmed" to "1") + before)
                assertEquals("${sec.id}: \"$key\" saved from the browser (${r.text().substringAfter("class=\"err\"", "").take(120)})", 303, r.status)
                assertEquals("${sec.id}: \"$key\" kept its value", before, current(item))
                changed++
            }
        }
        assertEquals("every change ran on the panel's main thread", changed, f.onMainCalls)
        assertTrue("the panes with native views ran: $natives", natives.containsAll(listOf("radio", "time-zone", "wall-base")))
    }

    @Test fun theManagePageReadsThePanelMenusOwnModel() {
        val runtime = GlassworksRuntime(AqaraS1Plus())
        val prefs = KioskPrefs(app)
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
        val menu = S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), S1Settings.hasDeviceSettings(runtime, app), fullScreen = {})
        fun shape(s: List<SettingsSection>) = s.map { sec ->
            val items = ArrayList<String>()
            walk(sec.items) { i, key -> items += "${i::class.simpleName} $key" }
            listOf(sec.id, sec.heading, sec.master?.title, items)
        }
        assertEquals(shape(menu.sections), shape(manage.status().sections))
    }
}
