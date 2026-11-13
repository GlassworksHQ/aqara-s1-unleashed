package sh.edm.s1kiosk.radio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ThreadRouterState
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Action
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Health
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Place
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Request

class RadioFollowUpTest {

    private val v4 = HelperFacts(installed = true, version = 4)
    private val v3 = HelperFacts(installed = true, version = 3)
    private val none = HelperFacts(installed = false, version = 0)

    private fun facts(
        mode: RadioMode, helper: HelperFacts = v4, stack: ZigbeeStack = ZigbeeStack.AQARA, stored: Boolean? = null,
        ha: String? = "http://homeassistant.local:8123", status: String = "ok ${mode.label}: x", thread: ThreadRouterState? = null,
    ) = RadioFollowUp.Facts(mode, status, helper, stack, stored, thread, ha, "192.0.2.10")

    // ------------------------------------------------------------------ URL builders

    @Test
    fun urlBuilders() {
        for (base in listOf("http://homeassistant.local:8123", "http://homeassistant.local:8123/", " http://homeassistant.local:8123// ")) {
            assertEquals("http://homeassistant.local:8123/config/integrations/dashboard/add?domain=otbr", RadioStep.otbrAddUrl(base))
            assertEquals("http://homeassistant.local:8123/config/zha/add", RadioStep.zhaAddUrl(base))
        }
        assertEquals("https://ha.example/config/integrations/dashboard/add?domain=otbr", RadioStep.otbrAddUrl("https://ha.example"))
        for (bad in listOf(null, "", "homeassistant.local:8123", "ftp://ha.example")) {
            assertNull(RadioStep.otbrAddUrl(bad)); assertNull(RadioStep.zhaAddUrl(bad))
        }
        assertEquals("http://192.0.2.10:8081", RadioStep.otbrUrl("192.0.2.10"))
        assertEquals("http://<panel IP>:8081", RadioStep.otbrUrl(null))
        assertEquals("tcp://192.0.2.10:6638", RadioStep.z2mPort("192.0.2.10"))
    }

    // ------------------------------------------------------------------ cards per mode

    @Test
    fun off() {
        val c = RadioFollowUp.card(facts(RadioMode.OFF), Place.SETTINGS)
        assertEquals("The radio is off", c.title)
        assertEquals(listOf("Want to turn it on? Tap a role above."), c.lines)
        assertNull(c.qr)
        assertTrue(c.actions.isEmpty()) // helper doesn't say: Leave Thread only in the Thread modes
        val stored = RadioFollowUp.card(facts(RadioMode.OFF, stored = true), Place.SETTINGS)
        assertEquals(listOf(Action.THREAD_LEAVE), stored.actions) // clean up after switching away
        assertNotNull(stored.note)
        assertTrue(RadioFollowUp.card(facts(RadioMode.OFF, stored = true, helper = v3), Place.SETTINGS).actions.isEmpty())
    }

    @Test
    fun coordinatorAqara() {
        val c = RadioFollowUp.card(facts(RadioMode.ZIGBEE_TCP), Place.SETTINGS)
        assertEquals("http://homeassistant.local:8123/config/zha/add", c.qr!!.url)
        assertEquals(listOf("EZSP", "socket://192.0.2.10:6638"), c.kv.map { it.value })
        assertEquals("Add it to ZHA · scan to pair", c.title)
        assertTrue(c.stackChoice)
        assertNull(c.note)
        assertTrue(c.actions.isEmpty())
        assertTrue(c.more.any { it.contains("radio type EZSP") }) // the long text: behind "How does this work?"
    }

    @Test
    fun coordinatorSiliconLabs() {
        val c = RadioFollowUp.card(facts(RadioMode.ZIGBEE_TCP, stack = ZigbeeStack.SISDK), Place.SETTINGS)
        assertEquals(listOf("EZSP", "socket://192.0.2.10:6638", "ember", "tcp://192.0.2.10:6638"), c.kv.map { it.value })
        assertTrue(c.note!!.contains("Who may connect, below"))
        // the setup card: one compact Zigbee2MQTT line, no stack choice, no note
        val setup = RadioFollowUp.card(facts(RadioMode.ZIGBEE_TCP, stack = ZigbeeStack.SISDK), Place.SETUP)
        assertEquals(listOf("socket://192.0.2.10:6638", "ember · tcp://192.0.2.10:6638"), setup.kv.map { it.value })
        assertFalse(setup.stackChoice)
        assertNull(setup.note)
        // helper < v4: no stack choice, no Zigbee2MQTT lines, a hint instead
        val old = RadioFollowUp.card(facts(RadioMode.ZIGBEE_TCP, stack = ZigbeeStack.SISDK, helper = v3), Place.SETTINGS)
        assertFalse(old.stackChoice)
        assertEquals(2, old.kv.size)
        assertTrue(old.note!!.contains("web installer"))
    }

    @Test
    fun zigbeeRouter() {
        val f = facts(RadioMode.ZIGBEE_ROUTER, status = "ok Zigbee router (repeater): in a network on channel 15")
        val c = RadioFollowUp.card(f, Place.SETTINGS)
        assertEquals(listOf(Action.ROUTER_JOIN, Action.ROUTER_LEAVE), c.actions)
        assertEquals("in a network on channel 15", c.state)
        assertEquals("Open joining in ZHA, then press Join", c.title)
        assertTrue(c.more.first().contains("press Join within that window"))
        assertEquals("http://homeassistant.local:8123/config/zha/add", c.qr!!.url)
        // the helper says a Thread network is still stored: Leave Thread too
        assertEquals(listOf(Action.ROUTER_JOIN, Action.ROUTER_LEAVE, Action.THREAD_LEAVE), RadioFollowUp.card(f.copy(threadStored = true), Place.SETTINGS).actions)
        // a status that isn't "ok": shown whole
        assertEquals("applying Zigbee router (repeater): waiting for zigbeed",
            RadioFollowUp.card(f.copy(status = "applying Zigbee router (repeater): waiting for zigbeed"), Place.SETTINGS).state)
    }

    @Test
    fun threadBorderRouter() {
        val c = RadioFollowUp.card(facts(RadioMode.THREAD), Place.SETTINGS)
        assertEquals("http://homeassistant.local:8123/config/integrations/dashboard/add?domain=otbr", c.qr!!.url)
        assertEquals(RadioFollowUp.Kv("URL", "http://192.0.2.10:8081", big = true), c.kv.single())
        assertEquals("Scan to add OpenThread Border Router", c.title)
        assertTrue(c.note!!.contains("choose Thread repeater"))
        assertEquals(listOf(Action.THREAD_LEAVE), c.actions) // helper doesn't say: both Thread modes
        assertTrue(RadioFollowUp.card(facts(RadioMode.THREAD, stored = false), Place.SETTINGS).actions.isEmpty())
        assertTrue(RadioFollowUp.card(facts(RadioMode.THREAD, helper = v3), Place.SETTINGS).actions.isEmpty())
    }

    @Test
    fun threadRouter() {
        // not joined yet: Get it from Home Assistant first, the copy steps as text. No QR code on the card:
        // the only one is the admin sign-in's, which Get it from Home Assistant shows when it needs it.
        val waiting = ThreadRouterState(false, null, null)
        val c = RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, thread = waiting), Place.SETTINGS)
        assertNull(c.qr)
        assertNull(RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, thread = waiting), Place.SETUP).qr)
        assertEquals("Not joined", c.badge)
        assertNull(c.state) // the title already says it
        assertTrue(c.datasetField)
        assertEquals(listOf(Action.THREAD_JOIN_HA), c.actions) // no Leave: nothing to leave
        // a network but not in it yet: Leave (start over), no Get it
        val joining = RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, thread = ThreadRouterState(true, "detached", null)), Place.SETTINGS)
        assertEquals(listOf(Action.THREAD_LEAVE), joining.actions)
        assertEquals("Joining", joining.badge)
        assertTrue(c.note!!.contains("network key"))
        assertEquals(1, c.lines.size)
        assertTrue(c.lines[0].startsWith("Get it from Home Assistant"))
        assertTrue(c.more.any { it.contains("Active dataset TLVs") }) // copying by hand: behind "How does this work?"
        assertTrue(c.more.any { it.contains("admin users") })
        // joined: the network's name large, the role under it, no QR (nothing left to scan)
        val joined = RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, thread = ThreadRouterState(true, "router", "Example-Net")), Place.SETTINGS)
        assertEquals("Example-Net", joined.headline)
        assertTrue(joined.subline!!.startsWith("Router"))
        assertEquals("Dataset set", joined.badge)
        assertNull(joined.qr)
        assertTrue(joined.datasetField)
        assertTrue(RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, thread = ThreadRouterState(true, "child", null)), Place.SETTINGS)
            .subline!!.contains("becomes a router"))
        // the setup screen: no dataset field
        assertFalse(RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER), Place.SETUP).datasetField)
        // no Leave with a helper that says nothing is stored, or without v4
        assertEquals(listOf(Action.THREAD_JOIN_HA), RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, stored = false), Place.SETTINGS).actions)
        assertEquals(listOf(Action.THREAD_JOIN_HA), RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, helper = none), Place.SETTINGS).actions)
        // joined: nothing to get
        assertFalse(Action.THREAD_JOIN_HA in joined.actions)
    }

    @Test
    fun theSetupScreenGetsTheCalmVersion() {
        // short: at most two lines, only the Join buttons, no dataset field, no Leave, a note only on the border router
        for (m in RadioMode.entries) {
            val full = RadioFollowUp.card(facts(m, stored = true), Place.SETTINGS)
            val c = RadioFollowUp.card(facts(m, stored = true), Place.SETUP)
            assertTrue(m.name, c.lines.size <= 2)
            assertTrue(m.name, c.lines.sumOf { it.length } < (full.lines + full.more + listOfNotNull(full.note)).sumOf { it.length })
            assertFalse(m.name, c.stackChoice || c.datasetField)
            assertEquals(m.name, when (m) {
                RadioMode.ZIGBEE_ROUTER -> listOf(Action.ROUTER_JOIN)
                RadioMode.THREAD_ROUTER -> listOf(Action.THREAD_JOIN_HA)
                else -> emptyList()
            }, c.actions)
            assertEquals(m.name, m == RadioMode.THREAD, c.note != null)
            assertEquals(m.name, full.qr, c.qr) // the same QR codes
        }
        assertTrue(RadioFollowUp.card(facts(RadioMode.THREAD), Place.SETUP).note!!.contains("Thread router (repeater)"))
        val child = ThreadRouterState(true, "child", "Example-Net")
        assertEquals("joined “Example-Net”, a router in a few minutes",
            RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, thread = child), Place.SETUP).state)
        assertEquals("not joined yet",
            RadioFollowUp.card(facts(RadioMode.THREAD_ROUTER, thread = ThreadRouterState(false, null, null)), Place.SETUP).state)
    }

    @Test
    fun noQrWithoutHomeAssistantButTheTextStays() {
        for (m in RadioMode.entries) for (place in Place.entries) {
            val c = RadioFollowUp.card(facts(m, ha = null), place)
            assertNull("$m $place", c.qr)
            assertTrue("$m $place", c.title.isNotEmpty() || c.lines.isNotEmpty())
            assertFalse("$m $place", (c.lines + c.title).any { it.contains("scan", ignoreCase = true) })
        }
        assertEquals("http://192.0.2.10:8081", RadioFollowUp.card(facts(RadioMode.THREAD, ha = null), Place.SETTINGS).kv.single().value)
    }

    // ------------------------------------------------------------------ confirmations

    @Test
    fun whatAsksFirst() {
        val f = facts(RadioMode.ZIGBEE_ROUTER)
        assertNull(RadioFollowUp.ask(Request.Act(Action.ROUTER_JOIN), f))
        assertTrue(RadioFollowUp.ask(Request.Act(Action.ROUTER_LEAVE), f)!!.text.contains("remove it from ZHA too"))
        assertTrue(RadioFollowUp.ask(Request.Act(Action.THREAD_LEAVE), f)!!.text.contains("Deletes the stored Thread network and its key"))
        val off = RadioFollowUp.ask(Request.Mode(RadioMode.OFF), f)!!
        assertEquals("Turn the radio off?", off.title)
        assertEquals("The radio stops. The panel stops repeating for your Zigbee network.", off.body)
        assertFalse(off.destructive)
        assertTrue(RadioFollowUp.ask(Request.Act(Action.ROUTER_LEAVE), f)!!.destructive) // red Leave
        assertTrue(RadioFollowUp.ask(Request.Act(Action.THREAD_LEAVE), f)!!.destructive)
        val sw = RadioFollowUp.ask(Request.Mode(RadioMode.THREAD_ROUTER), facts(RadioMode.ZIGBEE_TCP))!!
        assertEquals("Switch to Thread repeater?", sw.title) // the picker's words (#63 D3)
        assertTrue(sw.body.startsWith("The radio restarts. ZHA loses its Zigbee radio"))
        assertEquals("Switch", sw.button)
        assertEquals(RadioMode.ZIGBEE_TCP, sw.from); assertEquals(RadioMode.THREAD_ROUTER, sw.to)
        assertEquals("The radio starts.", RadioFollowUp.ask(Request.Mode(RadioMode.THREAD), facts(RadioMode.OFF))!!.body)
        assertNull(RadioFollowUp.ask(Request.Mode(RadioMode.ZIGBEE_ROUTER), f)) // already active
        assertNotNull(RadioFollowUp.ask(Request.Stack(ZigbeeStack.SISDK), facts(RadioMode.ZIGBEE_TCP)))
        assertNull(RadioFollowUp.ask(Request.Stack(ZigbeeStack.AQARA), facts(RadioMode.ZIGBEE_TCP)))
    }

    @Test
    fun confirmThenRun() {
        val flow = RadioFollowUp.Flow { 0L }
        val f = facts(RadioMode.ZIGBEE_ROUTER)
        val leave = Request.Act(Action.ROUTER_LEAVE)
        assertNull(flow.press(leave, f))           // asks first
        assertEquals(leave, flow.asking!!.request)
        assertEquals(leave, flow.confirm())          // "Leave"
        assertNull(flow.asking)
    }

    @Test
    fun cancelRunsNothing() {
        val flow = RadioFollowUp.Flow { 0L }
        assertNull(flow.press(Request.Act(Action.THREAD_LEAVE), facts(RadioMode.THREAD_ROUTER)))
        flow.cancel()
        assertNull(flow.asking)
        assertNull(flow.confirm())
    }

    @Test
    fun joinRunsAtOnceAndEndsWithTheStatus() {
        var now = 0L
        val flow = RadioFollowUp.Flow { now }
        val f = facts(RadioMode.ZIGBEE_ROUTER, status = "ok Zigbee router (repeater): ready to join: open permit-join")
        val join = Request.Act(Action.ROUTER_JOIN)
        assertEquals(join, flow.press(join, f))
        flow.started(join, f.status)
        assertNull("nothing else while it runs", flow.press(Request.Act(Action.ROUTER_LEAVE), f))
        assertFalse(flow.tick(f.status))
        assertFalse(flow.tick("ok Zigbee router (repeater): joining: scanning"))
        assertEquals(join, flow.running)
        assertTrue(flow.tick("ok Zigbee router (repeater): in a network on channel 15"))
        assertNull(flow.running)
        assertNull(flow.result)
        // no change at all: gives up after two minutes and says so
        flow.started(join, f.status)
        now = 121_000
        assertTrue(flow.tick(f.status))
        assertTrue(flow.result!!.startsWith("No change yet"))
    }

    @Test
    fun threadLeaveEndsWithTheHelpersAnswer() {
        val flow = RadioFollowUp.Flow { 0L }
        val r = Request.Act(Action.THREAD_LEAVE)
        flow.started(r, "ok Thread router (repeater): joined “N” as a router")
        assertFalse(flow.tick("ok Thread router (repeater): joined “N” as a router (leaving the Thread network…)"))
        assertTrue(flow.tick("ok Thread router (repeater): no dataset yet (Thread network left: Thread dataset and router settings deleted)"))
        assertEquals("Thread network left.", flow.result)
    }

    @Test
    fun modeAndStackEndWithTheirAnswer() {
        val flow = RadioFollowUp.Flow { 0L }
        val r = Request.Mode(RadioMode.THREAD)
        flow.started(r, "ok Off")
        assertFalse(flow.tick("anything")) // only Join/Leave follow the status
        flow.finish("Thread border router needs the root helper")
        assertNull(flow.running)
        assertEquals("Thread border router needs the root helper", flow.result)
    }

    // ------------------------------------------------------------------ health, preview, refusals

    @Test
    fun healthFromTheStatus() {
        assertEquals(Health.OFF, RadioFollowUp.health(facts(RadioMode.OFF, status = "ok Off: Aqara radio services stopped")))
        assertEquals(Health.RUNNING, RadioFollowUp.health(facts(RadioMode.ZIGBEE_TCP)))
        assertEquals(Health.STARTING, RadioFollowUp.health(facts(RadioMode.ZIGBEE_TCP, status = "applying Zigbee coordinator (TCP): waiting for zigbeed")))
        assertEquals(Health.STARTING, RadioFollowUp.health(facts(RadioMode.THREAD, status = "Thread border router: applying…")))
        assertEquals(Health.ERROR, RadioFollowUp.health(facts(RadioMode.THREAD, status = "error Thread border router: otbr-agent not installed")))
        assertEquals(Health.ERROR, RadioFollowUp.health(facts(RadioMode.THREAD, status = "Thread border router needs the root helper (…). Still in Off.")))
        assertEquals(Health.JOINED, RadioFollowUp.health(facts(RadioMode.ZIGBEE_ROUTER, status = "ok Zigbee router (repeater): in a network on channel 15")))
        assertEquals(Health.WAITING, RadioFollowUp.health(facts(RadioMode.ZIGBEE_ROUTER, status = "ok Zigbee router (repeater): ready to join: open permit-join")))
        assertEquals(Health.JOINED, RadioFollowUp.health(facts(RadioMode.THREAD_ROUTER, thread = ThreadRouterState(true, "child", "N"))))
        assertEquals(Health.WAITING, RadioFollowUp.health(facts(RadioMode.THREAD_ROUTER, thread = ThreadRouterState(true, "detached", "N"))))
        assertEquals(Health.WAITING, RadioFollowUp.health(facts(RadioMode.THREAD_ROUTER, thread = ThreadRouterState(false, null, null))))
    }

    @Test
    fun previewAndRefusalTexts() {
        for (m in RadioMode.entries) assertTrue(m.name, RadioFollowUp.pitch(m).isNotBlank())
        assertTrue(RadioFollowUp.pitch(RadioMode.THREAD_ROUTER).contains("already have"))
        assertEquals(
            "The panel's boot software is too old for this role. Update it once with the web installer; your settings stay.",
            RadioFollowUp.refusedText("Thread router (repeater) needs the root helper v2 or newer (installed: v1; update the panel's boot image). Still in Off."),
        )
        assertTrue(RadioFollowUp.refusedText("Thread border router needs the root helper (init service s1kiosk_radio is not installed; …)").contains("only Off works"))
        assertEquals("something else", RadioFollowUp.refusedText("something else"))
    }
}
