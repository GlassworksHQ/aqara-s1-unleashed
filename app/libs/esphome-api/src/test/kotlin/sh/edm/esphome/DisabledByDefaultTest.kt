package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `disabled_by_default` reaches Home Assistant in each list response, at the api.proto field number. */
class DisabledByDefaultTest {
    private fun info(e: ApiEntity) = ProtoMessage(e.info().toByteArray())

    @Test fun sensor() {
        val on = info(SensorEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { 1f }))
        assertTrue(on.bool(12))
        assertEquals(EntityCategory.DIAGNOSTIC.value, on.uint32(13))
        assertFalse(info(SensorEntity("a", "A", get = { 1f })).bool(12))
    }

    @Test fun textSensor() {
        val on = info(TextSensorEntity("a", "A", category = EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { "x" }))
        assertTrue(on.bool(6))
        assertEquals(EntityCategory.DIAGNOSTIC.value, on.uint32(7))
        assertFalse(info(TextSensorEntity("a", "A", get = { "x" })).bool(6))
    }
}
