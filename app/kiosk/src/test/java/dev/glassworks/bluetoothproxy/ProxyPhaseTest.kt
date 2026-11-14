package dev.glassworks.bluetoothproxy

import dev.glassworks.bluetoothproxy.ProxyPhase.BLUETOOTH_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.LISTENING
import dev.glassworks.bluetoothproxy.ProxyPhase.LOCATION_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.NO_BLE
import dev.glassworks.bluetoothproxy.ProxyPhase.NEEDS_KEY
import dev.glassworks.bluetoothproxy.ProxyPhase.NO_PERMISSION
import dev.glassworks.bluetoothproxy.ProxyPhase.OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.RETRYING
import dev.glassworks.bluetoothproxy.ProxyPhase.WAITING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.edm.bluetoothproxy.ProxyStatus
import sh.edm.bluetoothproxy.ScanFailure

class ProxyPhaseTest {
    private fun status(subscribed: Boolean = true, failure: ScanFailure? = null) = ProxyStatus(subscribed, scanning = failure == null, failure = failure, advertisementsPerMinute = 0)

    @Test fun whatThePageAndTheEntityShow() {
        val cases = listOf(
            Triple(Triple(true, ScanFailure.NO_BLE, status()), NO_BLE, "no Bluetooth LE wins even when on"),
            Triple(Triple(false, ScanFailure.NO_BLE, null), NO_BLE, "and when off: the pane can't run here"),
            Triple(Triple(false, null, null), OFF, "off"),
            Triple(Triple(false, ScanFailure.BLUETOOTH_OFF, null), OFF, "off hides why it couldn't scan"),
            Triple(Triple(true, ScanFailure.NO_PERMISSION, status()), NO_PERMISSION, ""),
            Triple(Triple(true, ScanFailure.BLUETOOTH_OFF, status(failure = ScanFailure.BLUETOOTH_OFF)), BLUETOOTH_OFF, ""),
            Triple(Triple(true, ScanFailure.LOCATION_OFF, status()), LOCATION_OFF, ""),
            Triple(Triple(true, null, null), WAITING, "on, no engine yet"),
            Triple(Triple(true, null, status(subscribed = false)), WAITING, "nobody listening"),
            Triple(Triple(true, null, status(subscribed = false).copy(needsKey = true)), NEEDS_KEY, "a client was refused: no key"),
            Triple(Triple(true, null, status().copy(needsKey = true)), LISTENING, "subscribed wins"),
            Triple(Triple(true, null, status(failure = ScanFailure.TOO_FREQUENT)), RETRYING, ""),
            Triple(Triple(true, null, status()), LISTENING, ""),
        )
        for ((input, want, why) in cases) assertEquals(why, want, ProxyPhase.of(input.first, input.second, input.third))
    }

    @Test fun anOffPanesStatusEntityIsUnavailable() {
        assertNull(OFF.entityText)
        assertEquals("Waiting for Home Assistant", WAITING.entityText)
    }
}
