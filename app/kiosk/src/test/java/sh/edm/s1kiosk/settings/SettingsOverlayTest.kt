package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.ui.Mockup

/** The menu applies each change on its own (#63): no Save & restart, a restart only offered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SettingsOverlayTest {

    private val prefs get() = KioskPrefs(RuntimeEnvironment.getApplication())
    private var restarts = 0

    private fun open(radioRunning: Boolean = true): KioskSettingsOverlay {
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a).apply { onRestart = { restarts++ } }
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(MenuFixture.model(a, radioDefinition(a, FixedReadout(null, running = radioRunning))))
        Mockup.idle()
        return o
    }

    /** The row whose name is [name]: the label's parents up to the clickable row. */
    private fun row(o: View, name: String): View {
        var v: View = Mockup.label(o, name)
        while (!v.isClickable) v = v.parent as View
        return v
    }

    private fun shown(o: View, text: String) = runCatching { Mockup.label(o, text).isShown }.getOrDefault(false)

    private fun editor(o: View): EditText? = generateSequence(listOf(o)) { level ->
        level.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null }
    }.flatten().filterIsInstance<EditText>().firstOrNull { it.isShown }

    @Test
    fun aSwitchAppliesWhenTapped() {
        val o = open()
        prefs.hideHeader = true
        o.previewSection("dashboard")
        Mockup.idle()
        row(o, "Full screen").performClick()
        Mockup.idle()
        assertFalse(prefs.hideHeader)
        assertFalse(prefs.hideSidebar)
        assertFalse(shown(o, KioskSettingsOverlay.RESTART_TEXT)) // the page follows it live
        assertEquals(0, restarts)
    }

    @Test
    fun aValueThatGeckoReadsAtStartOffersARestart() {
        val o = open()
        o.previewSection("dashboard")
        Mockup.idle()
        row(o, "Zoom").performClick()
        Mockup.idle()
        editor(o)!!.setText("1.5")
        Mockup.label(o, "Save").performClick()
        Mockup.idle()
        assertEquals(1.5f, prefs.zoom)
        assertEquals(null, editor(o)) // the editor closed
        assertTrue(shown(o, "1.5×")) // the row says the new value
        assertTrue(shown(o, KioskSettingsOverlay.RESTART_TEXT))
        assertEquals(0, restarts) // offered, not done
        Mockup.label(o, "Restart now").performClick()
        assertEquals(1, restarts)
    }

    @Test
    fun cancelLeavesTheValue() {
        val o = open()
        prefs.idleOffSec = 120
        o.previewSection("screen")
        Mockup.idle()
        row(o, "Turn off when idle").performClick()
        Mockup.idle()
        editor(o)!!.setText("30")
        Mockup.label(o, "Cancel").performClick()
        Mockup.idle()
        assertEquals(120, prefs.idleOffSec)
        assertTrue(shown(o, "After 2 minutes"))
    }

    @Test
    fun aDisabledFieldDoesNotOpen() {
        // The radio's allow-list shows "" and is disabled without its service: editing it could wipe the
        // list if the service started while the menu was open.
        val o = open(radioRunning = false)
        o.previewSection("radio")
        Mockup.idle()
        Mockup.label(o, "IP addresses or ranges, or any").let { (it.parent.parent as View).performClick() }
        Mockup.idle()
        assertEquals(null, editor(o))
    }
}
