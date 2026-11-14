package sh.edm.s1kiosk.manage

import android.app.Application
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.PaneFixtures
import sh.edm.s1kiosk.ui.Mockup
import java.io.File

/**
 * #242/#243: each settings page on the panel (PNG, 1440×720) and on the Manage page (HTML, to render at
 * desktop and phone width, dark and light), from the same model, for side-by-side screenshots.
 * Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*PanelManageMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelManageMockups {
    private val app = RuntimeEnvironment.getApplication()
    private val panes = PaneFixtures(app)

    @Before fun interFromSources() = Mockup.interFromSources()
    @After fun shutdown() = panes.shutdown()

    @Test
    fun sideBySide() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val out = File(Mockup.dir!!, "parity").apply { mkdirs() }
        val model = panes.model(panes.all(), off = setOf("bluetooth-proxy"))
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.reload = model
        o.show(model())
        Mockup.idle()
        val f = ManageFixture()
        f.statusFn = { model().let { m -> f.status.copy(panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }, sections = m.sections) } }
        val b = f.Browser().also { it.signInWithHa() }

        fun pair(name: String, id: String, query: String = "", panel: (KioskSettingsOverlay) -> Unit = {}) {
            o.previewSection(id)
            panel(o)
            Mockup.idle()
            Mockup.save("parity/$name-panel", o)
            File(out, "$name-manage.html").writeBytes(b.get("/$id$query").body)
        }
        for (s in model().sections.filter { it.id !in setOf("log", "about") }) pair(s.id, s.id)
        pair("screen-edit-idle", "screen", "?edit=Turn+off+when+idle") { it.previewEdit("Turn off when idle") }
        pair("screen-edit-night", "screen", "?edit=At+night") { it.previewEdit("At night") }
        pair("dashboard-edit-address", "dashboard", "?edit=Home+Assistant") { it.previewEdit("Home Assistant") }
        pair("assist-edit-wake-word", "assist", "?edit=Listening%2FWake+word") { it.previewEdit("Wake word") }
        File(out, "overview-manage.html").writeBytes(b.get("/").body)
        // A switch that asks first: the radio's off.
        o.previewSection("radio"); o.previewConfirm("radio"); Mockup.idle(); Mockup.save("parity/radio-confirm-panel", o)
        File(out, "radio-confirm-manage.html").writeBytes(b.post("/settings", mapOf("csrf" to b.csrf(), "section" to "radio", "key" to "on", "value" to "off")).body)
    }

    /** #252's stream sign-in as #242 makes it editable: the sheet on the panel and the dialog on the Manage page, one set. */
    @Test
    fun signIn() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val out = File(Mockup.dir!!, "parity").apply { mkdirs() }
        var stored: Pair<String, String>? = "viewer" to "made-up-pass"
        val camera = dev.glassworks.pane.SettingsSection("camera", "Camera", listOf(dev.glassworks.pane.SettingsItem.Group("ONVIF stream", listOf(
            dev.glassworks.pane.SettingsItem.SignIn("Sign-in", { stored != null }, { stored?.first }, { stored?.second }) { v, d -> stored = v; d(null) },
            dev.glassworks.pane.SettingsItem.Info({ "Address" }, { "rtsp://192.0.2.40:8554/camera" }),
        ))), master = dev.glassworks.pane.SettingsItem.Switch("Camera", "", { true }) { _, d -> d(null) })
        val m = KioskSettingsOverlay.Model(listOf(camera), "0.4.3", setOf("camera"))
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(m)
        Mockup.idle()
        o.previewSection("camera"); o.previewEdit("Sign-in"); Mockup.idle()
        Mockup.save("parity/camera-sign-in-panel", o)
        val f = ManageFixture()
        f.statusFn = { f.status.copy(panes = listOf(paneRow("camera", camera)), sections = listOf(camera)) }
        val b = f.Browser().also { it.signInWithHa() }
        File(out, "camera-sign-in-manage.html").writeBytes(b.get("/camera?edit=ONVIF+stream%2FSign-in").body)
        // After Show: the password in its field, on the panel and on the page.
        Mockup.label(o, "Show").performClick(); Mockup.idle()
        Mockup.save("parity/camera-sign-in-shown-panel", o)
        File(out, "camera-sign-in-shown-manage.html").writeBytes(b.post("/settings/reveal", mapOf("csrf" to b.csrf(), "section" to "camera", "key" to "ONVIF stream/Sign-in")).body)
    }
}
