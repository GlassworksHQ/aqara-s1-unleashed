package sh.edm.s1kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.improv.ImprovState
import sh.edm.s1kiosk.setup.WifiStep.Action
import sh.edm.s1kiosk.setup.WifiStep.Phase

class WifiStepTest {

    private val on = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZATION_REQUIRED)

    @Test
    fun offOffersToStart() {
        val l = WifiStep.improv(ImprovFacts())
        assertEquals(Phase.OFF, l.phase)
        assertEquals(listOf(Action.START), l.actions)
    }

    @Test
    fun startFailureShowsTheReason() {
        val l = WifiStep.improv(ImprovFacts(startError = "bluetoothLeAdvertiser is null (no LE peripheral mode)"))
        assertEquals(Phase.UNAVAILABLE, l.phase)
        assertEquals(WifiStep.Tone.ROSE, l.tone)
        assertTrue(l.detail, l.detail.startsWith("BluetoothLeAdvertiser is null (no LE peripheral mode)."))
        assertEquals(listOf(Action.RETRY), l.actions)
    }

    @Test
    fun startingWhileBluetoothComesUp() {
        // The pending start (Bluetooth turning on): a session, no advertisement yet.
        val l = WifiStep.improv(on.copy(advertising = false))
        assertEquals(Phase.STARTING, l.phase)
        assertEquals(listOf(Action.NOT_NOW), l.actions)
    }

    @Test
    fun readySaysWhereToFindItInHomeAssistant() {
        val l = WifiStep.improv(on)
        assertEquals(Phase.READY, l.phase)
        assertEquals("Bluetooth setup is on", l.title)
        assertTrue(l.detail.contains("Settings → Devices & services → Discovered"))
        assertTrue(l.detail.contains("Improv via BLE"))
        assertEquals(listOf(Action.ALLOW, Action.NOT_NOW), l.actions)
    }

    @Test
    fun connectedClientAsksForPermission() {
        val l = WifiStep.improv(on.copy(clients = 1))
        assertEquals(Phase.ASKING, l.phase)
        assertEquals("Home Assistant wants to send Wi-Fi", l.title)
        assertEquals(listOf(Action.ALLOW, Action.NOT_NOW), l.actions)
    }

    @Test
    fun authorizedCountsDown() {
        val l = WifiStep.improv(on.copy(state = ImprovState.AUTHORIZED, authLeftMs = 119_001))
        assertEquals(Phase.AUTHORIZED, l.phase)
        assertTrue(l.label, l.label.endsWith("allowed for 2:00"))
        assertEquals(listOf(Action.STOP), l.actions)
        val approve = WifiStep.improv(on.copy(state = ImprovState.AUTHORIZED, needsApproval = true))
        assertTrue(approve.detail.contains("notification"))
    }

    @Test
    fun connectingAndConnected() {
        val c = WifiStep.improv(on.copy(state = ImprovState.PROVISIONING, ssid = "Home"))
        assertEquals(Phase.CONNECTING, c.phase)
        assertEquals("Connecting to Home…", c.title)
        assertTrue(c.actions.isEmpty())
        assertEquals("Turning Wi-Fi on…", WifiStep.improv(on.copy(state = ImprovState.PROVISIONING, progress = "Turning Wi-Fi on…")).detail)
        val ok = WifiStep.improv(on.copy(state = ImprovState.PROVISIONED, ssid = "Home"))
        assertEquals(Phase.CONNECTED, ok.phase)
        assertEquals("Connected to Home", ok.title)
        assertEquals(WifiStep.Tone.GREEN, ok.tone)
    }

    @Test
    fun failedConnectShowsTheReasonAndStaysAllowed() {
        val l = WifiStep.improv(on.copy(state = ImprovState.AUTHORIZED, ssid = "Home", failure = "wrong password (authentication failed)"))
        assertEquals(Phase.FAILED, l.phase)
        assertEquals("Couldn't connect to Home", l.title)
        assertTrue(l.detail.startsWith("Wrong password (authentication failed)."))
        assertEquals(WifiStep.Tone.ROSE, l.tone)
    }

    @Test
    fun clock() {
        assertEquals("0:00", WifiStep.clock(-5))
        assertEquals("0:01", WifiStep.clock(1))
        assertEquals("1:05", WifiStep.clock(65_000))
    }
}
