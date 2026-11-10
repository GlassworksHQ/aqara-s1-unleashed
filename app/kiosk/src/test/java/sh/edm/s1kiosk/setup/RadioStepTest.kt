package sh.edm.s1kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.radio.RadioController
import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.SysProps
import sh.edm.s1kiosk.setup.RadioStep.Result

class RadioStepTest {

    @Test
    fun offersOnlyWorkingModesOffFirst() {
        assertEquals(listOf(RadioMode.OFF, RadioMode.ZIGBEE_TCP, RadioMode.THREAD), RadioStep.OPTIONS.map { it.mode })
        assertEquals(listOf(RadioMode.THREAD), RadioStep.OPTIONS.filter { it.experimental }.map { it.mode })
        assertEquals(RadioMode.DEFAULT, RadioStep.OPTIONS.first().mode)
        assertTrue(RadioStep.LATER.contains("Radio mode"))
    }

    @Test
    fun comingSoonRolesMatchTheInstaller() {
        assertEquals(listOf(RadioMode.ZIGBEE_ROUTER, RadioMode.THREAD_ROUTER), RadioStep.COMING_SOON.map { it.mode })
        assertEquals(listOf("Zigbee router", "Thread router"), RadioStep.COMING_SOON.map { it.title })
        assertEquals(
            listOf("Extends an existing Zigbee network as a repeater.", "Extends an existing Thread network as a repeater."),
            RadioStep.COMING_SOON.map { it.line },
        )
        // Never selectable: not among the options.
        assertTrue(RadioStep.OPTIONS.none { o -> RadioStep.COMING_SOON.any { it.mode == o.mode } })
    }

    @Test
    fun zhaPath() {
        assertEquals("socket://10.0.0.5:6638", RadioStep.zhaPath("10.0.0.5"))
        assertEquals("socket://<panel IP>:6638", RadioStep.zhaPath(null))
    }

    @Test
    fun classify() {
        val z = RadioMode.ZIGBEE_TCP
        assertEquals(Result.REFUSED, RadioStep.classify(z, false, "anything"))
        assertEquals(Result.OK, RadioStep.classify(RadioMode.OFF, true, "Off: Aqara's radio services stopped, radio idle."))
        assertEquals(Result.ERROR, RadioStep.classify(RadioMode.OFF, true, "error: zigbee_cpcd=running (wanted Off)"))
        assertEquals(Result.OK, RadioStep.classify(z, true, "ok Zigbee coordinator (TCP): EZSP on tcp:6638"))
        assertEquals(Result.APPLYING, RadioStep.classify(z, true, "applying Zigbee coordinator (TCP)"))
        assertEquals(Result.APPLYING, RadioStep.classify(z, true, "ok Off (wanted Zigbee coordinator (TCP))"))
        assertEquals(Result.APPLYING, RadioStep.classify(z, true, "Root helper running, no status yet (wanted Zigbee coordinator (TCP))"))
        assertEquals(Result.ERROR, RadioStep.classify(z, true, "error Zigbee coordinator (TCP): bridge died"))
        assertFalse(RadioStep.canContinue(null))
        assertFalse(RadioStep.canContinue(RadioStep.Applied(z, false, "x")))
        assertTrue(RadioStep.canContinue(RadioStep.Applied(z, true, "applying Zigbee coordinator (TCP)")))
    }

    /** End to end with RadioController (no helper): the reason it reports is what the step shows. */
    @Test
    fun withoutTheHelperARootModeIsRefusedWithControllersReason() {
        val props = object : SysProps {
            val m = HashMap<String, String>()
            override fun get(name: String) = m[name] ?: ""
            override fun set(name: String, value: String) {
                when (name) {
                    "ctl.stop" -> m["init.svc.$value"] = "stopped"
                    "ctl.start" -> m["init.svc.$value"] = "running"
                    else -> m[name] = value
                }
            }
            override fun sleep(ms: Long) {}
        }
        var stored: String? = null
        val c = RadioController(props, load = { stored }, store = { stored = it }, inline = true, log = {})
        var accepted: Boolean? = null
        c.request(RadioMode.ZIGBEE_TCP.label, "test") { accepted = it }
        assertEquals(false, accepted)
        val a = RadioStep.Applied(RadioMode.ZIGBEE_TCP, accepted!!, c.statusText())
        assertEquals(Result.REFUSED, a.result)
        assertTrue(a.status, a.status.contains(RadioHelper.SERVICE))
        assertEquals(null, stored)

        c.request(RadioMode.OFF.label, "test") { accepted = it }
        assertEquals(true, accepted)
        assertEquals(Result.OK, RadioStep.Applied(RadioMode.OFF, true, c.statusText()).result)
        assertEquals("off", stored)
    }
}
