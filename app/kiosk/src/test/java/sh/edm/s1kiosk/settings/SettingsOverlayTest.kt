package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
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

    private fun open(
        radioRunning: Boolean = true,
        model: (android.content.Context) -> KioskSettingsOverlay.Model = { a -> MenuFixture.model(a, radioDefinition(a, FixedReadout(null, running = radioRunning))) },
    ): KioskSettingsOverlay {
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a).apply { onRestart = { restarts++ } }
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(model(a))
        Mockup.idle()
        return o
    }

    /** A menu of one section: [items] under [master], named "Test". */
    private fun one(items: List<SettingsItem>, master: SettingsItem.Switch? = null, unavailable: String? = null): KioskSettingsOverlay = open(model = { _ ->
        KioskSettingsOverlay.Model(listOf(SettingsSection("test", "Test", items, master = master, unavailable = { unavailable })), "0.4.3", setOf("test"))
    })

    /** Waits for an answer from another thread to reach the main thread. */
    private fun settle() { repeat(20) { Mockup.idle(); Thread.sleep(5) } }

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
        assertEquals(null, editor(o)) // picked, never typed: no keyboard
        Mockup.label(o, "Cancel").performClick()
        Mockup.idle()
        assertEquals(120, prefs.idleOffSec)
        assertTrue(shown(o, "After 2 minutes"))
    }

    @Test
    fun aDurationAppliesWhenPicked() {
        val o = open()
        prefs.idleOffSec = 120
        o.previewSection("screen")
        Mockup.idle()
        row(o, "Turn off when idle").performClick()
        Mockup.idle()
        Mockup.label(o, "5 minutes").performClick()
        settle()
        assertEquals(300, prefs.idleOffSec)
        assertTrue(shown(o, "After 5 minutes"))
    }

    @Test
    fun theNightWindowIsSteppedAndCanBeOff() {
        val o = open()
        prefs.nightWindow = "22:00-07:00"
        o.previewSection("screen")
        Mockup.idle()
        row(o, "At night").performClick()
        Mockup.idle()
        Mockup.label(o, "22:00").let { assertTrue(it.isShown) }
        o.findViewsWithText("From later").performFirstClick()
        o.findViewsWithText("To earlier").performFirstClick()
        Mockup.label(o, "Save").performClick()
        settle()
        assertEquals("22:30-06:30", prefs.nightWindow)
        assertTrue(shown(o, "22:30 – 06:30"))
    }

    private fun View.findViewsWithText(desc: String): List<View> {
        val out = ArrayList<View>()
        findViewsWithText(out, desc, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return out
    }
    private fun List<View>.performFirstClick() { first { it.isShown }.performClick(); Mockup.idle() }

    @Test
    fun aRefusedSwitchSaysWhyAndALaterChangeClearsIt() {
        // F4: the switch goes back with the reason; the next change that works takes the reason away.
        var refuse = true
        var value = false
        val o = one(listOf(SettingsItem.Switch("Thing", "", { value }) { v, done -> if (refuse) done("the thing is busy") else { value = v; done(null) } }))
        row(o, "Thing").performClick()
        settle()
        assertTrue(shown(o, "the thing is busy"))
        assertFalse(value)
        refuse = false
        row(o, "Thing").performClick()
        settle()
        assertTrue(value)
        assertFalse(shown(o, "the thing is busy"))
    }

    @Test
    fun aSaveThatThrowsIsSaidNotACrash() {
        // F8: a save that throws (a pref, a binder call) shows what went wrong; the menu stays.
        val o = one(listOf(SettingsItem.Switch("Thing", "", { false }) { _, _ -> error("binder died") }))
        row(o, "Thing").performClick()
        settle()
        assertTrue(shown(o, "That didn't save: binder died."))
        assertTrue(o.isShowing)
    }

    @Test
    fun aSliderSavesWhenLetGoAndARefusalPutsItBack() {
        var saved = 1f
        var refuse: String? = "no permission"
        val o = one(listOf(SettingsItem.Slider("Range", { saved }, 0f, 2.5f, 0.1f, { "Up to $it m" }) { v, done -> if (refuse == null) saved = v; done(refuse) }))
        val slider = generateSequence(listOf<View>(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filterIsInstance<sh.edm.s1kiosk.ui.GlassSlider>().first()
        slider.value = 2f
        slider.onSet!!(2f)
        settle()
        assertTrue(shown(o, "no permission"))
        assertEquals(1f, slider.value) // back to what the sensor has
        refuse = null
        slider.value = 2f
        slider.onSet!!(2f)
        settle()
        assertEquals(2f, saved)
        assertFalse(shown(o, "no permission"))
    }

    @Test
    fun aPanesSwitchAsksFirstWhenItSaysSo() {
        var on = true
        val master = SettingsItem.Switch("Test", "", { on }, confirm = { v -> if (!v) SettingsItem.Confirm("Turn it off?", "It stops.", "Turn off") else null }) { v, done -> on = v; done(null) }
        val o = one(listOf(SettingsItem.Note { "body" }), master = master)
        Mockup.label(o, "Test").let { } // the heading
        val toggle = generateSequence(listOf<View>(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().first { it.contentDescription == "Test on" }
        toggle.performClick()
        Mockup.idle()
        assertTrue(shown(o, "Turn it off?"))
        assertTrue(on) // not until it's answered
        Mockup.label(o, "Turn off").performClick()
        settle()
        assertFalse(on)
    }

    @Test
    fun aPaneThatCantRunIsGreyedWithItsReason() {
        val o = one(listOf(SettingsItem.Note { "body" }), master = SettingsItem.Switch("Test", "", { true }) { _, done -> done(null) }, unavailable = "it needs a part")
        assertTrue(shown(o, "Can't run")) // in the rail
        assertTrue(shown(o, "This pane can't run on this panel: it needs a part."))
        assertFalse(generateSequence(listOf<View>(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().any { it.contentDescription == "Test on" && it.isShown }) // no switch for what can't run
    }

    @Test
    fun theRailGroupsThePanes() {
        val o = open()
        o.previewSection("dashboard")
        Mockup.idle()
        assertTrue(shown(o, "Panes")) // folded: one row
        assertFalse(shown(o, "Voice"))
        o.previewSection("radio")
        Mockup.idle()
        assertTrue(shown(o, "Voice")); assertTrue(shown(o, "Music")); assertTrue(shown(o, "Panes ›"))
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
