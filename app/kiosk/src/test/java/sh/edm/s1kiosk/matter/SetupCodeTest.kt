package sh.edm.s1kiosk.matter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SetupCodeTest {
    @Test
    fun passcodesFollowTheSpec() {
        assertTrue(SetupCode.valid(20202021))
        assertTrue(SetupCode.valid(1))
        assertTrue(SetupCode.valid(99_999_998))
        for (bad in listOf(0L, 11_111_111L, 22_222_222L, 99_999_999L, 12_345_678L, 87_654_321L, 100_000_000L, -5L)) {
            assertFalse("$bad", SetupCode.valid(bad))
        }
        val r = Random(42)
        repeat(1000) { assertTrue(SetupCode.valid(SetupCode.randomPasscode(r))) }
    }

    @Test
    fun networkNameFromADataset() {
        // Active timestamp (0e), channel (00 03 000019), network name (03) "ha-thread-4108".
        val name = "ha-thread-4108".toByteArray()
        val ds = byteArrayOf(0x0e, 8, 0, 0, 0, 0, 0, 1, 0, 0) +
            byteArrayOf(0x00, 3, 0, 0, 26) +
            byteArrayOf(0x03, name.size.toByte()) + name
        assertEquals("ha-thread-4108", SetupCode.networkName(ds))
        assertNull(SetupCode.networkName(byteArrayOf(0x0e, 8, 0)))
        assertNull(SetupCode.networkName(byteArrayOf()))
    }
}
