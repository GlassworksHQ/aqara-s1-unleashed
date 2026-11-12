package sh.edm.s1kiosk.sendspin

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.edm.esphome.SendspinApi

class PairingStatusTest {

    @Test
    fun switchedOffAfterStartingIsDisabled() {
        assertEquals(SendspinApi.STATUS_DISABLED, pairingStatus(enabled = false, started = true))
    }

    @Test
    fun switchedOffNeverStartedIsDisabled() {
        assertEquals(SendspinApi.STATUS_DISABLED, pairingStatus(enabled = false, started = false))
    }

    @Test
    fun onButNotStartedIsNotReady() {
        assertEquals(SendspinApi.STATUS_NOT_READY, pairingStatus(enabled = true, started = false))
    }

    @Test
    fun onAndStartedIsOk() {
        assertEquals(SendspinApi.STATUS_OK, pairingStatus(enabled = true, started = true))
    }
}
