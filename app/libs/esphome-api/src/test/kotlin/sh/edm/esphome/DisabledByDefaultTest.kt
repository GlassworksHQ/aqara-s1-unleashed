package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `disabled_by_default` and `entity_category` reach Home Assistant in each list response, at the
 * api.proto field numbers of that response.
 */
class DisabledByDefaultTest {
    private class Case(val type: String, val disabledField: Int, val categoryField: Int, val make: (Boolean) -> ApiEntity)

    private val cases = listOf(
        Case("binary_sensor", 7, 9) { BinarySensorEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, get = { true }) },
        Case("sensor", 12, 13) { SensorEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, get = { 1f }) },
        Case("text_sensor", 6, 7) { TextSensorEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, get = { "x" }) },
        Case("switch", 7, 8) { SwitchEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, get = { true }, set = {}) },
        Case("number", 9, 10) {
            NumberEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, min = 0f, max = 1f, step = 1f, get = { 0f }, set = {})
        },
        Case("select", 7, 8) {
            SelectEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, options = listOf("x"), get = { "x" }, set = {})
        },
        Case("text", 6, 7) { TextEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, get = { "x" }, set = {}) },
        Case("button", 6, 7) { ButtonEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, press = {}) },
        Case("event", 6, 7) { EventEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, eventTypes = listOf("x")) },
        Case("update", 6, 7) {
            UpdateEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = it, get = {
                UpdateStateData(true, false, false, 0f, "", "", "", "", "")
            }, onCommand = {})
        },
    )

    private fun info(e: ApiEntity) = ProtoMessage(e.info().toByteArray())

    @Test fun everyTypeSendsBothFields() {
        for (c in cases) {
            val off = info(c.make(true))
            assertTrue("${c.type}: disabled_by_default at ${c.disabledField}", off.bool(c.disabledField))
            assertEquals("${c.type}: entity_category at ${c.categoryField}", EntityCategory.DIAGNOSTIC.value, off.uint32(c.categoryField))
            assertFalse("${c.type}: enabled by default unless asked", info(c.make(false)).bool(c.disabledField))
        }
    }
}
