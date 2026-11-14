package sh.edm.s1kiosk.settings

import android.app.Application
import dev.glassworks.pane.Pane
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.frame.TimeZoneView
import sh.edm.s1kiosk.ui.Mockup

/**
 * Every pane's settings page (#212) at the panel's size, from the S1's own definitions and running panes.
 * Illustrative values only: made-up names and RFC 5737 addresses.
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*PanePagesMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanePagesMockups {
    private val app = RuntimeEnvironment.getApplication()

    @Before fun interFromSources() = Mockup.interFromSources()
    @After fun shutdown() = panes.shutdown()

    private val panes = PaneFixtures(app)

    /** The menu with [running] panes; the panes in [off] are turned off. */
    private fun menu(running: List<Pane>, off: Set<String> = emptySet()): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val model = panes.model(running, off)
        o.reload = model
        o.show(model())
        Mockup.idle()
        return o
    }

    /** The time zone page matches Home Assistant's zone on a background thread and posts the answer: let it land. */
    private fun matched() = repeat(40) { Thread.sleep(25); Mockup.idle() }

    private fun shot(name: String, o: KioskSettingsOverlay, id: String, then: (KioskSettingsOverlay) -> Unit = {}) {
        o.previewSection(id)
        then(o)
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    /** #249: a chip word wider than the backstop (GroupedRail.CHIP_WORD_MAX_DP) shortens; no pane says one this long today. */
    @Test
    fun chipBackstop() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        fun master(on: Boolean) = dev.glassworks.pane.SettingsItem.Switch("x", "", { on }) { _, d -> d(null) }
        val long = dev.glassworks.pane.PaneState(dev.glassworks.pane.PaneState.Kind.RUNNING, "", chip = "Waiting for Bluetooth to turn on")
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(KioskSettingsOverlay.Model(listOf(
            dev.glassworks.pane.SettingsSection("assist", "Voice", listOf(dev.glassworks.pane.SettingsItem.Note { "Voice's page" }), master = master(true)),
            dev.glassworks.pane.SettingsSection("bluetooth-proxy", "Bluetooth proxy", listOf(dev.glassworks.pane.SettingsItem.Note { "A word longer than any today's shortens with an ellipsis; the row stays inside the rail." }), master = master(true), status = { long }),
            dev.glassworks.pane.SettingsSection("radio", "Radio", listOf(dev.glassworks.pane.SettingsItem.Note { "" }), master = master(true)),
        ), "0.4.3", setOf("assist", "bluetooth-proxy", "radio")))
        Mockup.idle()
        shot("249-chip-backstop", o, "bluetooth-proxy")
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("212-01-diagnostics", menu(listOf(panes.diagnostics())), "diagnostics")
        shot("212-02-wall-base", menu(listOf(panes.wallBase())), "wall-base")
        shot("212-03-wall-base-none", menu(listOf(panes.wallBase(attached = false))), "wall-base")
        shot("212-08-voice", menu(listOf(panes.voice())), "assist")
        shot("212-09-voice-scrolled", menu(listOf(panes.voice())), "assist") { o ->
            Mockup.layout(o)
            o.previewScrollTo(Mockup.label(o, "Timers running"), alignBottom = true)
        }
        shot("212-10-music", menu(listOf(panes.music())), "sendspin")
        shot("212-11-radio-addition", menu(listOf(panes.radio())), "radio") { o ->
            Mockup.layout(o)
            o.previewScrollTo(Mockup.label(o, "v4 · root side ready"), alignBottom = true)
        }
        shot("212-12-rail-radio", menu(listOf(panes.radio())), "radio")
        shot("212-07b-off-panes-folded", menu(listOf(panes.radio(), panes.music(), panes.timeZone()), off = setOf("sendspin", "time-zone")), "radio")
        shot("212-07c-off-pane-open", menu(listOf(panes.radio(), panes.music(), panes.timeZone()), off = setOf("sendspin", "time-zone")), "sendspin")
        val tz = panes.timeZone()
        shot("212-04-time-zone", menu(listOf(tz)), "time-zone") { matched() }
        shot("212-05-time-zone-picker", menu(listOf(tz)), "time-zone") { o ->
            matched(); (o.previewNative("time-zone-${System.identityHashCode(tz)}") as TimeZoneView).open()
        }
        // Home Assistant on another clock than the panel's: "Use it" keeps a zone with HA's clock.
        val london = panes.timeZone("GMT0BST,M3.5.0/1,M10.5.0")
        shot("212-05b-time-zone-picker-use-it", menu(listOf(london)), "time-zone") { o ->
            matched(); (o.previewNative("time-zone-${System.identityHashCode(london)}") as TimeZoneView).open()
        }
    }
}
