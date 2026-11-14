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

    /** #242: a change made on the Manage page (or in Home Assistant) shows on the open menu at its next refresh. */
    @Test
    fun rowsFollowAChangeMadeElsewhere() {
        prefs.idleOffSec = 120
        prefs.autoBrightness = true
        val o = open()
        o.reload = { MenuFixture.model(o.context, radioDefinition(o.context, FixedReadout(null, running = true))) }
        o.previewSection("screen")
        Mockup.idle()
        assertTrue(shown(o, "After 2 minutes"))
        prefs.idleOffSec = 300
        prefs.autoBrightness = false
        o.refresh()
        Mockup.idle()
        assertTrue("the duration's row follows", shown(o, "After 5 minutes"))
        assertFalse(shown(o, "After 2 minutes"))
        val toggle = generateSequence(listOf<View>(row(o, "Brightness"))) { level ->
            level.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null }
        }.flatten().filterIsInstance<sh.edm.s1kiosk.ui.Glass.Toggle>().single()
        assertFalse("the switch follows", toggle.checked)
    }

    /** #242 for #252: a sign-in on the panel. The password field starts empty, never shows it back, and Clear removes it. */
    @Test
    fun aSignInSavesClearsAndNeverShowsThePassword() {
        var stored: Pair<String, String>? = null
        val o = one(listOf(SettingsItem.SignIn("Sign-in", { stored != null }, { stored?.first }) { v, d -> stored = v; d(null) }))
        val secret = "made-up-secret-7"
        fun texts(): List<String> = generateSequence(listOf<View>(o)) { level ->
            level.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null }
        }.flatten().filterIsInstance<android.widget.TextView>().map { it.text.toString() }.toList()
        row(o, "Sign-in").performClick()
        Mockup.idle()
        val fields = generateSequence(listOf<View>(o)) { level ->
            level.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null }
        }.flatten().filterIsInstance<EditText>().filter { it.isShown }.toList()
        assertEquals("a user name and a password", 2, fields.size)
        fields[0].setText("viewer"); fields[1].setText(secret)
        Mockup.label(o, "Save").performClick()
        settle()
        assertEquals("viewer" to secret, stored)
        assertTrue(shown(o, "Set"))
        assertFalse("no view holds the password once saved", texts().any { it.contains(secret) })
        row(o, "Sign-in").performClick()
        Mockup.idle()
        assertTrue(texts().none { it.contains(secret) })
        Mockup.label(o, "Clear").performClick()
        settle()
        assertEquals(null, stored)
        assertTrue(shown(o, "Not set"))
    }

    /** A row whose value and hint are empty still follows: its value view is there, gone until it has words. */
    @Test
    fun anEmptyRowFollowsAValueSetElsewhere() {
        var now = ""
        val o = one(listOf(SettingsItem.Text("Allowed", "", SettingsItem.TextKind.ADDRESS, { now }) { _, d -> d(null) }))
        assertFalse(shown(o, "192.0.2.20"))
        now = "192.0.2.20"
        o.refresh()
        Mockup.idle()
        assertTrue(shown(o, "192.0.2.20"))
    }

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
    fun aRestartIsStillOfferedWhenTheEditorClosedBeforeItSaved() {
        // CodeRabbit on #163: the save's answer came after Cancel, and the restart it needs was dropped.
        var finish: ((String?) -> Unit)? = null
        val o = one(listOf(SettingsItem.Text("Zoom", "Auto", SettingsItem.TextKind.DECIMAL, { "" }, restartsApp = true) { _, done -> finish = done }))
        row(o, "Zoom").performClick()
        Mockup.idle()
        editor(o)!!.setText("1.5")
        Mockup.label(o, "Save").performClick()
        Mockup.label(o, "Cancel").performClick() // closed while the save runs
        Mockup.idle()
        finish!!(null) // it saved
        settle()
        assertTrue(shown(o, KioskSettingsOverlay.RESTART_TEXT))
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
    fun backClosesOnlyTheTopmostLayer() {
        // Root's panel run: Back on the idle picker closed the picker and the whole menu.
        val o = open()
        o.previewSection("screen")
        Mockup.idle()
        row(o, "Turn off when idle").performClick()
        Mockup.idle()
        assertTrue(shown(o, "Cancel"))
        o.back()
        Mockup.idle()
        assertFalse(shown(o, "Cancel")) // the picker closed
        assertTrue(o.isShowing) // the menu stays
        o.back()
        assertFalse(o.isShowing)
    }

    @Test
    fun backClosesAPanesDialogBeforeTheMenu() {
        // Verifier 6d0f2cd7: the radio's own dialog (NativeView.back) is a layer of its own.
        val o = open(radioRunning = true)
        o.previewSection("radio")
        Mockup.idle()
        val radio = o.previewNative("radio") as sh.edm.s1kiosk.radio.ui.RadioSettingsView
        val ask = sh.edm.s1kiosk.radio.ui.RadioFollowUp.Ask(
            sh.edm.s1kiosk.radio.ui.RadioFollowUp.Request.Mode(sh.edm.s1kiosk.radio.RadioMode.THREAD), "Switch to Thread border router?", "It restarts.", "Switch",
        )
        var answer: Boolean? = null
        radio.previewDialog.show(ask) { answer = it }
        Mockup.idle()
        assertTrue(radio.previewDialog.isOpen)
        o.back()
        Mockup.idle()
        assertFalse(radio.previewDialog.isOpen) // the dialog closed, as a no
        assertEquals(false, answer)
        assertTrue(o.isShowing) // the menu stays
        o.back()
        assertFalse(o.isShowing)
    }

    @Test
    fun theRailFitsWithThePanesOpen() {
        // Root's panel run: with Voice, Music and Radio open, About was clipped under Done. The fixture's
        // radio is off, so it folds under "Off · 1" (#212) while an on pane is picked.
        val o = open()
        o.previewSection("voice")
        Mockup.layout(o)
        assertTrue(shown(o, "About"))
        assertTrue(o.railFits)
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

    /** Panes a, b and c; [off] are turned off. The model is made again on each reload, as MainActivity does. */
    private fun panes(off: MutableSet<String>): KioskSettingsOverlay {
        fun model() = KioskSettingsOverlay.Model(listOf("a", "b", "c").map { id ->
            val master = SettingsItem.Switch(id.uppercase(), "", { id !in off }) { on, done -> if (on) off -= id else off += id; done(null) }
            SettingsSection(id, id.uppercase(), listOf(SettingsItem.Note { if (id in off) "$id is off" else "$id is on" }), master = master)
        }, "0.4.3", setOf("a", "b", "c"))
        return open(model = { model() }).apply { reload = ::model }
    }

    @Test
    fun offPanesFoldUnderOffAtTheEndOfTheGroup() {
        val o = panes(mutableSetOf("a"))
        o.previewSection("b")
        Mockup.idle()
        assertTrue(shown(o, "B")); assertTrue(shown(o, "C")); assertTrue(shown(o, "Off · 1"))
        assertFalse("folded", shown(o, "A"))
        val rail = generateSequence(Mockup.label(o, "B") as View) { it.parent as? View }.first { it is sh.edm.s1kiosk.ui.GroupedRail } as ViewGroup
        val order = List(rail.childCount) { rail.getChildAt(it).contentDescription?.toString() }
        assertEquals(listOf("Panes", "B", "C", "Off: 1 panes"), order)
        // Tapping the fold opens its first pane, and the fold stays open while it is picked.
        rail.getChildAt(3).performClick()
        Mockup.idle()
        assertTrue(shown(o, "a is off")); assertTrue(shown(o, "A"))
    }

    @Test
    fun aPaneTurnedOffFoldsAndItsPageChangesAtOnce() {
        val o = panes(mutableSetOf())
        o.previewSection("b")
        Mockup.idle()
        assertTrue(shown(o, "b is on"))
        o.findViewsWithText("B on").performFirstClick()
        settle()
        assertTrue("the page was made again", shown(o, "b is off"))
        assertTrue(shown(o, "Off · 1"))
    }

    @Test
    fun homeAssistantsRowsFollowTheirValues() {
        var watts = "12.4 W"
        val o = one(listOf(SettingsItem.States("In Home Assistant") { listOf(SettingsItem.StateRow("Base power", watts), SettingsItem.StateRow("Base firmware", "0.0.0_0034", offByDefault = true)) }))
        assertTrue(shown(o, "12.4 W"))
        assertTrue(shown(o, KioskSettingsOverlay.OFF_BY_DEFAULT))
        watts = "3.1 W"
        o.refresh()
        assertTrue(shown(o, "3.1 W"))
    }

    @Test
    fun aChoiceAppliesWhenPicked() {
        var word = "okay_nabu"
        val o = one(listOf(SettingsItem.Choice("Wake word", { word }, { listOf("okay_nabu" to "Okay Nabu", "hey_jarvis" to "Hey Jarvis") }) { v, done -> word = v; done(null) }))
        assertTrue(shown(o, "Okay Nabu"))
        row(o, "Wake word").performClick()
        Mockup.idle()
        Mockup.label(o, "Hey Jarvis").performClick()
        settle()
        assertEquals("hey_jarvis", word)
        assertTrue(shown(o, "Hey Jarvis"))
    }

    @Test
    fun everyPaneHasItsStatusChipAfterItsName() {
        // Maintainer, #243 (2026-11-14): a chip after every pane's name, Running / On / Off / Can't run, the
        // Manage page's words; every name starts at the same x.
        fun master(on: Boolean) = SettingsItem.Switch("x", "", { on }) { _, d -> d(null) }
        val running = dev.glassworks.pane.PaneState(dev.glassworks.pane.PaneState.Kind.RUNNING, "Zigbee coordinator")
        val o = open(model = { _ ->
            KioskSettingsOverlay.Model(listOf(
                SettingsSection("dashboard", "Dashboard", listOf(SettingsItem.Note { "" })),
                SettingsSection("voice", "Voice", listOf(SettingsItem.Note { "" }), master = master(true)),
                SettingsSection("radio", "Radio", listOf(SettingsItem.Note { "" }), master = master(true), status = { running }),
                SettingsSection("base", "Wall base", listOf(SettingsItem.Note { "" }), master = master(true), unavailable = { "no base port" }),
                SettingsSection("music", "Music", listOf(SettingsItem.Note { "" }), master = master(false)),
            ), "0.4.3", setOf("voice", "radio", "base", "music"))
        })
        o.previewSection("music") // opens the fold too
        Mockup.layout(o)
        fun chipOf(name: String): String? {
            val row = Mockup.label(o, name).parent as ViewGroup
            assertEquals("the name first", Mockup.label(o, name), row.getChildAt(0))
            return (1 until row.childCount).map { row.getChildAt(it) }.firstOrNull { it.contentDescription?.startsWith("status ") == true }
                ?.contentDescription?.removePrefix("status ")?.toString()
        }
        assertEquals("On", chipOf("Voice"))
        assertEquals("Running", chipOf("Radio"))
        assertEquals("Can't run", chipOf("Wall base"))
        assertEquals("Off", chipOf("Music"))
        assertEquals("a core section has none", null, runCatching { chipOf("Dashboard") }.getOrNull())
        fun x(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[0]
        val xs = listOf("Voice", "Radio", "Wall base", "Music").map { x(Mockup.label(o, it)) }.toSet()
        assertEquals("every pane's name starts at the same x", 1, xs.size)
    }

    @Test
    fun aPaneTurnedOffFromHomeAssistantChangesItsOpenPage() {
        // CodeRabbit on #235: the switch followed, the page kept the pane's controls.
        val off = mutableSetOf<String>()
        val o = panes(off)
        o.previewSection("b")
        Mockup.idle()
        assertTrue(shown(o, "b is on"))
        off += "b"
        o.refresh()
        Mockup.idle()
        assertTrue(shown(o, "b is off"))
        assertTrue(shown(o, "Off · 1"))
    }

    @Test
    fun aPageWhoseItemsChangeWhileOpenIsDrawnAgain() {
        // CodeRabbit on #235: a base mounted while the page was open kept the "no base" card.
        var attached = false
        fun model() = KioskSettingsOverlay.Model(listOf(SettingsSection("wall-base", "Wall base",
            if (attached) listOf(SettingsItem.Info({ "Relay 1" }, { "On" })) else listOf(SettingsItem.Note { "No base connected" }),
        )), "0.4.3", setOf("wall-base"))
        val o = open(model = { model() }).apply { reload = ::model }
        assertTrue(shown(o, "No base connected"))
        o.refresh()
        assertTrue("the same shape: not drawn again", shown(o, "No base connected"))
        attached = true
        o.refresh()
        Mockup.idle()
        assertTrue(shown(o, "Relay 1"))
        assertFalse(shown(o, "No base connected"))
    }

    @Test
    fun aSliderFollowsAValueChangedElsewhere() {
        // CodeRabbit on #235: Home Assistant set the volume, the open page kept the old one.
        var volume = 40f
        val o = one(listOf(SettingsItem.Slider("Volume", { volume }, 0f, 100f, 1f, { "${it.toInt()} %" }) { v, done -> volume = v; done(null) }))
        assertTrue(shown(o, "40 %"))
        volume = 25f
        o.refresh()
        assertTrue(shown(o, "25 %"))
        val slider = Mockup.label(o, "Volume").let { generateSequence(listOf<View>(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filterIsInstance<sh.edm.s1kiosk.ui.GlassSlider>().single() }
        assertEquals(25f, slider.value, 0f)
    }

    @Test
    fun aPageIsDrawnFromThePaneThatRunsNow() {
        // CodeRabbit on #235: after a device restart the tiles kept reading the stopped pane.
        class Run(val reading: String)
        var running = Run("23 % (stopped pane)")
        fun model() = running.let { r ->
            KioskSettingsOverlay.Model(listOf(SettingsSection("diagnostics", "Diagnostics", listOf(SettingsItem.Info({ "CPU" }, { r.reading })), source = r)), "0.4.3", setOf("diagnostics"))
        }
        val o = open(model = { model() }).apply { reload = ::model }
        assertTrue(shown(o, "23 % (stopped pane)"))
        running = Run("9 % (new pane)")
        o.refresh()
        Mockup.idle()
        assertTrue(shown(o, "9 % (new pane)"))
    }

    @Test
    fun nativeViewsOfAnOldPaneAreLetGoAndOnlyTheShownOneRefreshes() {
        // #235 verifier N5: views keyed by the running pane piled up across device restarts, and every
        // kept view refreshed every tick, shown or not.
        var run = 1
        val refreshed = HashMap<String, Int>()
        fun native(key: String) = SettingsItem.Native(key) { host ->
            object : SettingsItem.NativeView {
                override val view = android.widget.TextView(host.context).apply { text = key }
                override fun refresh() { refreshed[key] = (refreshed[key] ?: 0) + 1 }
            }
        }
        fun model() = KioskSettingsOverlay.Model(listOf(
            SettingsSection("a", "A", listOf(native("a-run$run"))),
            SettingsSection("b", "B", listOf(native("b-run$run"))),
        ), "0.4.3", setOf("a", "b"))
        val o = open(model = { model() }).apply { reload = ::model }
        o.previewSection("b")
        o.previewSection("a")
        Mockup.idle()
        assertEquals(setOf("a-run1", "b-run1"), o.keptNatives)
        refreshed.clear()
        o.refresh()
        assertEquals("only the page on screen", mapOf("a-run1" to 1), refreshed.toMap())
        run = 2 // the device restarted: new panes, new keys
        o.refresh()
        Mockup.idle()
        assertEquals("the old pane's views are let go of", setOf("a-run2"), o.keptNatives)
    }

    @Test
    fun aPaneTurnedBackOnFromHomeAssistantRedrawsItsPageAndLeavesTheFold() {
        // #235 verifier N6.
        val off = mutableSetOf("b")
        val o = panes(off)
        o.previewSection("b")
        Mockup.idle()
        assertTrue(shown(o, "b is off"))
        assertTrue(shown(o, "Off · 1"))
        off -= "b"
        o.refresh()
        Mockup.idle()
        assertTrue("the page is the pane's own again", shown(o, "b is on"))
        assertFalse("nothing left to fold", shown(o, "Off · 1"))
    }

    @Test
    fun aChoiceFollowsAValueChangedElsewhere() {
        // CodeRabbit on #235: Home Assistant picked another wake word; the open page kept the old name.
        var word = "okay_nabu"
        val o = one(listOf(SettingsItem.Choice("Wake word", { word }, { listOf("okay_nabu" to "Okay Nabu", "hey_jarvis" to "Hey Jarvis") }) { v, done -> word = v; done(null) }))
        assertTrue(shown(o, "Okay Nabu"))
        word = "hey_jarvis"
        o.refresh()
        assertTrue(shown(o, "Hey Jarvis"))
        assertFalse(shown(o, "Okay Nabu"))
    }
}
