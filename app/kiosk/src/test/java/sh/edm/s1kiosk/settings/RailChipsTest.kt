package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/**
 * The rail's status chips at the panel's size, with real text measuring (native graphics, Inter), so a
 * cut word shows: the legacy graphics mode measures text wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RailChipsTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    private fun open(model: (android.content.Context) -> KioskSettingsOverlay.Model): KioskSettingsOverlay {
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(model(a))
        Mockup.idle()
        return o
    }

    @Test
    fun everyChipWordIsDrawnWhole() {
        // #235 verifier: at the S1's 216 dp rail, "Bluetooth proxy" + "Can't run" drew "Can", "Problem" "Pro".
        fun master(on: Boolean) = SettingsItem.Switch("x", "", { on }) { _, d -> d(null) }
        val problem = dev.glassworks.pane.PaneState(dev.glassworks.pane.PaneState.Kind.PROBLEM, "failed")
        val o = open { _ ->
            KioskSettingsOverlay.Model(listOf(
                SettingsSection("bluetooth-proxy", "Bluetooth proxy", listOf(SettingsItem.Note { "" }), master = master(true), unavailable = { "it has no Bluetooth LE" }),
                SettingsSection("diagnostics", "Bluetooth proxy two", listOf(SettingsItem.Note { "" }), master = master(true), status = { problem }),
                SettingsSection("radio", "Radio", listOf(SettingsItem.Note { "" }), master = master(true), status = { dev.glassworks.pane.PaneState(dev.glassworks.pane.PaneState.Kind.RUNNING, "") }),
            ), "0.4.3", setOf("bluetooth-proxy", "diagnostics", "radio"))
        }
        o.previewSection("radio")
        Mockup.layout(o)
        val chips = generateSequence(listOf<View>(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filter { it.contentDescription?.startsWith("status ") == true }.toList()
        assertEquals(listOf("status Can't run", "status Problem", "status Running"), chips.map { it.contentDescription.toString() })
        for (chip in chips) {
            val word = (chip as ViewGroup).getChildAt(1) as android.widget.TextView
            val needs = word.paint.measureText(word.text.toString())
            assertTrue("${word.text}: ${word.width} px for $needs", word.width - word.paddingLeft - word.paddingRight >= kotlin.math.ceil(needs).toInt())
            val right = IntArray(2).also { chip.getLocationInWindow(it) }[0] + chip.width
            val rail = (chip.parent as View).let { IntArray(2).also { a -> it.getLocationInWindow(a) }[0] + it.width }
            assertTrue("the chip stays inside its row", right <= rail)
        }
    }

    @Test
    fun aChipKeepsItsWordBesideADetail() {
        // #235 verifier: a row with a detail too (setup's) must not squeeze the chip either.
        val a = Mockup.activity()
        val rail = sh.edm.s1kiosk.ui.GroupedRail(a)
        val width = (216 * a.resources.displayMetrics.density).toInt()
        a.setContentView(android.widget.FrameLayout(a).apply { addView(rail, android.widget.FrameLayout.LayoutParams(width, -2)) })
        rail.bind(listOf(
            sh.edm.s1kiosk.ui.GroupedRail.Row("bt", "Bluetooth proxy", group = "panes", detail = "Beta",
                chip = sh.edm.s1kiosk.ui.GroupedRail.Chip("Can't run", sh.edm.s1kiosk.ui.Glass.FA)),
            sh.edm.s1kiosk.ui.GroupedRail.Row("r", "Radio", group = "panes"),
        ), mapOf("panes" to "Panes"), "r")
        // The rail's own width, not the panel's.
        rail.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        rail.layout(0, 0, width, rail.measuredHeight)
        val chip = generateSequence(listOf<View>(rail)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().single { it.contentDescription == "status Can't run" } as ViewGroup
        val word = chip.getChildAt(1) as android.widget.TextView
        assertTrue("${word.width} px for ${word.paint.measureText("Can't run")}",
            word.width - word.paddingLeft - word.paddingRight >= kotlin.math.ceil(word.paint.measureText("Can't run")).toInt())
        assertEquals("the word itself is not shortened", null, word.layout?.getEllipsisCount(0)?.takeIf { it > 0 })
    }
}
