package sh.edm.s1kiosk.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake init: `ctl.start/stop` flip init.svc.*, `ctl.restart` is recorded, other props are stored. */
private class FakeInit(helper: Boolean = false) : SysProps {
    val props = HashMap<String, String>()
    val ops = ArrayList<String>()

    init {
        for (s in AqaraRadio.ALL) props["init.svc.$s"] = "running" // stock boot state
        if (helper) props[RadioHelper.PROP_SVC_STATE] = "running"
    }

    override fun get(name: String) = props[name] ?: ""
    override fun set(name: String, value: String) {
        when (name) {
            "ctl.start" -> { ops += "start $value"; props["init.svc.$value"] = "running" }
            "ctl.stop" -> { ops += "stop $value"; props["init.svc.$value"] = "stopped" }
            "ctl.restart" -> ops += "restart $value"
            else -> { ops += "set $name=$value"; props[name] = value }
        }
    }
    override fun sleep(ms: Long) {}
}

class RadioControllerTest {

    private var stored: String? = null
    private fun controller(init: FakeInit, initial: String? = null): RadioController {
        stored = initial
        return RadioController(init, load = { stored }, store = { stored = it }, inline = true, log = {})
    }

    private val stopAll = listOf(
        "stop ha_matter", "stop zigbee_agent", "stop zigbee_host", "stop zigbee_zigbeed", "stop zigbee_socat", "stop zigbee_cpcd",
    )

    @Test
    fun selectOptionsAndDefault() {
        assertEquals(listOf("Off", "Zigbee coordinator (TCP)", "Zigbee router", "Thread border router"), RadioMode.LABELS)
        assertEquals(RadioMode.OFF, RadioMode.DEFAULT)
    }

    @Test
    fun parseAcceptsIdsLabelsAndFallsBackForRetiredValues() {
        assertEquals(RadioMode.THREAD, RadioMode.parse("thread"))
        assertEquals(RadioMode.ZIGBEE_ROUTER, RadioMode.parse("Zigbee router")) // the stub stored labels
        assertEquals(RadioMode.OFF, RadioMode.parse("Aqara (stock)"))
        assertEquals(RadioMode.OFF, RadioMode.parse("aqara"))
        assertEquals(RadioMode.OFF, RadioMode.parse("Thread (future)"))
        assertEquals(RadioMode.OFF, RadioMode.parse(null))
    }

    @Test
    fun servicePlans() {
        fun running(m: RadioMode) = m.aqaraServices.filterValues { it }.keys.toList()
        assertEquals(AqaraRadio.ALL.toSet(), RadioMode.OFF.aqaraServices.keys)
        assertEquals(emptyList<String>(), running(RadioMode.OFF))
        // Zigbee modes reuse cpcd + Aqara's pty pair + zigbeed; never Aqara's EZSP host or bridges.
        val zigbee = listOf(AqaraRadio.CPCD, AqaraRadio.SOCAT, AqaraRadio.ZIGBEED)
        assertEquals(zigbee, running(RadioMode.ZIGBEE_TCP))
        assertEquals(zigbee, running(RadioMode.ZIGBEE_ROUTER))
        assertEquals(listOf(AqaraRadio.CPCD), running(RadioMode.THREAD))
        assertEquals(setOf(RadioMode.ZIGBEE_TCP, RadioMode.ZIGBEE_ROUTER, RadioMode.THREAD), RadioMode.entries.filter { it.needsHelper }.toSet())
    }

    @Test
    fun startupWithNothingStoredStopsAqarasRadioServicesConsumersFirst() {
        val init = FakeInit()
        val c = controller(init, initial = null)
        c.start()
        assertEquals(stopAll, init.ops)
        assertEquals(RadioMode.OFF, c.mode)
        assertTrue(c.statusText(), c.statusText().startsWith("Off: Aqara's radio services stopped"))

        // Re-applying when already off touches nothing.
        init.ops.clear()
        c.start()
        assertTrue(init.ops.isEmpty())
    }

    @Test
    fun selectingOffStoresTheId() {
        val init = FakeInit()
        val c = controller(init, initial = "Aqara (stock)") // retired value from the stub
        c.request("Off")
        assertEquals(stopAll, init.ops)
        assertEquals("off", stored)
    }

    @Test
    fun rootModesAreRefusedWithoutHelper() {
        val init = FakeInit()
        val c = controller(init, initial = "off")
        for (m in RadioMode.entries.filter { it.needsHelper }) {
            assertFalse(c.available(m))
            c.request(m.label)
            assertEquals(RadioMode.OFF, c.mode)
            assertEquals("off", stored)
            assertTrue(c.statusText(), c.statusText().contains("needs the root helper"))
        }
        assertTrue("no service touched, no helper prop written", init.ops.isEmpty())
    }

    @Test
    fun storedRootModeWithoutHelperFallsBackToOffButKeepsTheChoice() {
        val init = FakeInit()
        val c = controller(init, initial = "thread")
        c.start()
        assertEquals(stopAll, init.ops)
        assertEquals(RadioMode.THREAD, c.mode)
        assertEquals("thread", stored)
        assertTrue(c.statusText(), c.statusText().contains("radio is Off"))
    }

    @Test
    fun unknownLabelIsIgnored() {
        val init = FakeInit()
        val c = controller(init, initial = "off")
        c.request("Bogus")
        assertTrue(init.ops.isEmpty())
        assertEquals(RadioMode.OFF, c.mode)
    }

    @Test
    fun withHelperEveryModeIsDelegated() {
        val init = FakeInit(helper = true)
        val c = controller(init, initial = "off")
        assertTrue(RadioMode.entries.all { c.available(it) })
        c.request("Zigbee coordinator (TCP)")
        assertEquals(listOf("set ${RadioHelper.PROP_MODE}=zigbee_tcp", "restart s1kiosk_radio"), init.ops)
        assertEquals("zigbee_tcp", stored)
        // The app never touches Aqara's services itself when the helper owns the radio.
        assertTrue(init.ops.none { it.startsWith("stop ") || it.startsWith("start ") })

        init.props[RadioHelper.PROP_STATUS] = "ok zigbee_tcp: EZSP on tcp:6638"
        c.refresh()
        assertEquals("ok Zigbee coordinator (TCP): EZSP on tcp:6638", c.statusText())

        init.props[RadioHelper.PROP_STATUS] = "error thread: otbr-agent exited"
        c.refresh()
        assertEquals("error Thread border router: otbr-agent exited (wanted Zigbee coordinator (TCP))", c.statusText())

        // A hand-set experimental helper mode the app has no option for.
        init.props[RadioHelper.PROP_STATUS] = "ok zigbee_tcp_thread"
        c.refresh()
        assertEquals("ok ?", c.statusText().substringBefore(" (wanted"))
    }

    @Test
    fun startupWithHelperDoesNotBounceAHelperAlreadyInMode() {
        val init = FakeInit(helper = true)
        init.props[RadioHelper.PROP_MODE] = "thread"
        init.props[RadioHelper.PROP_STATUS] = "ok thread"
        controller(init, initial = "thread").start()
        assertTrue(init.ops.isEmpty())

        init.props[RadioHelper.PROP_STATUS] = "error thread: x"
        init.props[RadioHelper.PROP_SVC_STATE] = "stopped"
        controller(init, initial = "thread").start()
        assertEquals(listOf("restart s1kiosk_radio"), init.ops)
    }

    @Test
    fun helperStatusParsing() {
        assertEquals(RadioHelper.Status("ok", RadioMode.ZIGBEE_TCP, ""), RadioHelper.parseStatus("ok zigbee_tcp"))
        assertEquals(RadioHelper.Status("applying", RadioMode.OFF, ""), RadioHelper.parseStatus("applying off"))
        assertEquals("a: b", RadioHelper.parseStatus("error thread: a: b")?.detail)
        assertNull(RadioHelper.parseStatus(""))
        assertNull(RadioHelper.parseStatus("weird"))
        assertNull(RadioHelper.parseStatus("done thread"))
    }
}
