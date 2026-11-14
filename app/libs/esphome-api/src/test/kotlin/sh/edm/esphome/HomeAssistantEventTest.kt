package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The wire format of a Home Assistant bus event. The golden bytes come from aioesphomeapi 46.25.0's
 * own protobuf classes (`src/test/resources/esphome_golden.py` prints them, as `ha_event`).
 */
class HomeAssistantEventTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun encodesAsTheClientDoes() {
        val e = HomeAssistantEvent("esphome.notification_action", linkedMapOf("action" to "open_gate", "tag" to "doorbell"))
        assertEquals(
            "0a1b657370686f6d652e6e6f74696669636174696f6e5f616374696f6e12130a06616374696f6e12096f70656e5f67617465" +
                "120f0a037461671208646f6f7262656c6c2801",
            e.encode().toByteArray().hex(),
        )
    }

    @Test
    fun onlyTheEsphomeDomain() {
        // Home Assistant drops any other domain ("Can only generate events under esphome domain!").
        assertThrows(IllegalArgumentException::class.java) { HomeAssistantEvent("homeassistant.restart") }
        assertThrows(IllegalArgumentException::class.java) { HomeAssistantEvent("esphome.Bad-Name") }
    }
}
