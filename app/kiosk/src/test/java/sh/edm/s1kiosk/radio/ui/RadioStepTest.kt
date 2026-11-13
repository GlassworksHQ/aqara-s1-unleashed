package sh.edm.s1kiosk.radio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.radio.RadioController
import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.SysProps
import sh.edm.s1kiosk.radio.ui.RadioStep.Result

class RadioStepTest {

    @Test
    fun offersEveryModeOffFirstWithHonestTags() {
        assertEquals(
            listOf(RadioMode.OFF, RadioMode.ZIGBEE_TCP, RadioMode.THREAD, RadioMode.ZIGBEE_ROUTER, RadioMode.THREAD_ROUTER),
            RadioStep.OPTIONS.map { it.mode },
        )
        // Verified: Off, the Zigbee coordinator and the Thread border router. The repeaters are experimental (docs/radio.md).
        assertEquals(
            listOf(RadioMode.ZIGBEE_ROUTER, RadioMode.THREAD_ROUTER),
            RadioStep.OPTIONS.filter { it.experimental }.map { it.mode },
        )
        assertEquals(listOf(RadioMode.ZIGBEE_ROUTER, RadioMode.THREAD_ROUTER), RadioStep.OPTIONS.filter { it.repeater }.map { it.mode })
        assertEquals(RadioMode.DEFAULT, RadioStep.OPTIONS.first().mode)
        assertTrue(RadioStep.LATER.contains("Radio mode"))
        assertTrue(RadioStep.COMING_SOON.isEmpty())
    }

    @Test
    fun zhaPath() {
        assertEquals("socket://10.0.0.5:6638", RadioStep.zhaPath("10.0.0.5"))
        assertEquals("socket://<panel IP>:6638", RadioStep.zhaPath(null))
    }

    @Test
    fun zhaAddUrlForThePairingQr() {
        assertEquals("http://ha.local:8123/config/zha/add", RadioStep.zhaAddUrl("http://ha.local:8123/"))
        assertEquals("https://ha.example/config/zha/add", RadioStep.zhaAddUrl(" https://ha.example "))
        assertEquals(null, RadioStep.zhaAddUrl(null))
        assertEquals(null, RadioStep.zhaAddUrl("ha.local:8123"))
    }

    @Test
    fun nextStepTexts() {
        assertTrue(RadioStep.nextStep(RadioMode.ZIGBEE_TCP, "10.0.0.5")!!.endsWith("socket://10.0.0.5:6638"))
        assertTrue(RadioStep.nextStep(RadioMode.THREAD, "10.0.0.5")!!.contains("http://10.0.0.5:8081"))
        assertEquals(null, RadioStep.nextStep(RadioMode.OFF, null))
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
