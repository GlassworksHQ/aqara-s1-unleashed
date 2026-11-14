package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ApiEntity.shownState]: a device's own screen shows a state as Home Assistant does. */
class ShownStateTest {
    @Test fun numbersKeepTheirPlacesAndUnit() {
        assertEquals("51.5 °C", SensorEntity("t", "T", unit = "°C", accuracy = 1, get = { 51.46f }).shownState())
        assertEquals("23 %", SensorEntity("c", "C", unit = "%", get = { 23.4f }).shownState())
        assertEquals("0.842", SensorEntity("e", "E", accuracy = 3, get = { 0.8421f }).shownState())
        assertEquals("the step sets the places", "0.97", NumberEntity("w", "W", min = 0.1f, max = 0.99f, step = 0.01f, get = { 0.97f }, set = {}).shownState())
        assertEquals("1.5 x", NumberEntity("g", "G", min = 0.5f, max = 4f, step = 0.1f, unit = "x", get = { 1.5f }, set = {}).shownState())
    }

    @Test fun unknownIsNull() {
        assertNull(SensorEntity("t", "T", get = { null }).shownState())
        assertNull(SensorEntity("t", "T", get = { Float.NaN }).shownState())
        assertNull(TextSensorEntity("s", "S", get = { null }).shownState())
        assertNull("an option the select doesn't have", SelectEntity("o", "O", options = listOf("A"), get = { "B" }, set = {}).shownState())
        assertNull("a button has no state", ButtonEntity("b", "B", press = {}).shownState())
    }

    @Test fun switchesAndTexts() {
        assertEquals("On", SwitchEntity("s", "S", get = { true }, set = {}).shownState())
        assertEquals("Off", BinarySensorEntity("b", "B", get = { false }).shownState())
        assertEquals("switch (3ch)", TextSensorEntity("base", "Base", get = { "switch (3ch)" }).shownState())
        assertEquals("a password stays hidden", "••••••", TextEntity("p", "P", mode = 1, get = { "secret" }, set = {}).shownState())
    }

    @Test fun aTimestampIsADate() {
        val shown = SensorEntity("u", "Uptime", deviceClass = "timestamp", stateClass = 0, get = { 1_762_765_920f }).shownState()!!
        assertTrue(shown, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(shown))
    }
}
