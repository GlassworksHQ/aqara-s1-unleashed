package sh.edm.bluetoothproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentScanTest {
    @Test fun aStoppedScansLateFailureDoesNotEndTheNewOne() {
        val s = CurrentScan<String>()
        s.begin("A")
        assertEquals("A", s.endAny()) // the engine stopped A
        s.begin("B")
        assertFalse("A's failure arrives late", s.end("A"))
        assertTrue("B is still current", s.end("B"))
        assertFalse("and ends only once", s.end("B"))
        assertNull(s.endAny())
    }
}
