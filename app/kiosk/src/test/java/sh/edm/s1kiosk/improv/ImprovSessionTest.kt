package sh.edm.s1kiosk.improv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImprovSessionTest {

    private class Fake : ImprovSession.Effects {
        val states = mutableListOf<ImprovState>()
        val errors = mutableListOf<ImprovError>()
        val results = mutableListOf<ByteArray>()
        var identifies = 0
        val wifi = mutableListOf<Pair<String, String>>()
        var networks = listOf(ScannedNetwork("Home", -50, "WPA2"))
        var maxResult = Improv.MAX_PACKET
        override fun publishState(state: ImprovState) { states += state }
        override fun publishError(error: ImprovError) { errors += error }
        override fun sendResult(packet: ByteArray) { results += packet }
        override fun identify() { identifies++ }
        override fun startWifi(ssid: String, password: String) { wifi += ssid to password }
        override fun scanNetworks() = networks
        override fun deviceInfo() = listOf("S1 Kiosk", "0.1.0", "chip", "S1 Panel")
        override fun maxResultSize() = maxResult
    }

    private lateinit var fx: Fake
    private lateinit var s: ImprovSession
    private var now = 1_000L

    @Before fun setUp() {
        fx = Fake()
        s = ImprovSession(fx, authTimeoutMs = 60_000)
        s.start(requireAuthorization = true, now = now)
    }

    private fun write(p: ByteArray) = s.onRpcWrite(p, now)
    private fun wifiCmd(ssid: String = "Home", pw: String = "password1") = Improv.encode(Improv.CMD_WIFI_SETTINGS, listOf(ssid, pw))

    @Test fun startsAwaitingAuthorization() {
        assertEquals(ImprovState.AUTHORIZATION_REQUIRED, s.state)
        assertEquals(listOf(ImprovState.AUTHORIZATION_REQUIRED), fx.states)
        assertEquals(listOf(ImprovError.NONE), fx.errors)
        assertEquals(0x07, s.capabilities)
    }

    @Test fun wifiSettingsRejectedUntilAuthorized() {
        write(wifiCmd())
        assertEquals(ImprovError.NOT_AUTHORIZED, s.error)
        assertTrue(fx.wifi.isEmpty())
        s.authorize(now)
        assertEquals(ImprovState.AUTHORIZED, s.state)
        write(wifiCmd())
        assertEquals(ImprovError.NONE, s.error)
        assertEquals(ImprovState.PROVISIONING, s.state)
        assertEquals(listOf("Home" to "password1"), fx.wifi)
    }

    @Test fun authorizationLapses() {
        s.authorize(now)
        s.tick(now + 59_999)
        assertEquals(ImprovState.AUTHORIZED, s.state)
        s.tick(now + 60_000)
        assertEquals(ImprovState.AUTHORIZATION_REQUIRED, s.state)
        assertEquals(0L, s.authorizedUntil)
    }

    @Test fun successSendsRedirectThenProvisioned() {
        s.authorize(now); write(wifiCmd())
        s.onWifiResult(true, "http://192.168.3.39:8099/", now)
        assertEquals(ImprovState.PROVISIONED, s.state)
        val r = fx.results.single()
        assertEquals(Improv.CMD_WIFI_SETTINGS, r[0].toInt())
        assertEquals("http://192.168.3.39:8099/", String(Improv.strings(r)!!.single()))
        // result is sent before the PROVISIONED state (ESPHome order)
        assertEquals(ImprovState.PROVISIONED, fx.states.last())
        // provisioned: no more credentials, no identify
        write(wifiCmd())
        assertEquals(ImprovError.NOT_AUTHORIZED, s.error)
        write(Improv.encode(Improv.CMD_IDENTIFY, emptyList()))
        assertEquals(0, fx.identifies)
    }

    @Test fun successWithoutUrlSendsEmptyList() {
        s.authorize(now); write(wifiCmd())
        s.onWifiResult(true, null, now)
        assertEquals(0, Improv.strings(fx.results.single())!!.size)
    }

    @Test fun failureReportsUnableToConnectAndRestartsAuthTimeout() {
        s.authorize(now); write(wifiCmd())
        now += 50_000
        s.onWifiResult(false, null, now)
        assertEquals(ImprovError.UNABLE_TO_CONNECT, s.error)
        assertEquals(ImprovState.AUTHORIZED, s.state)
        assertEquals(now + 60_000, s.authorizedUntil)
        // a second failure notifies again because each packet first resets the error to NONE
        write(wifiCmd(pw = "password2"))
        s.onWifiResult(false, null, now)
        assertEquals(
            listOf(ImprovError.NONE, ImprovError.UNABLE_TO_CONNECT, ImprovError.NONE, ImprovError.UNABLE_TO_CONNECT),
            fx.errors,
        )
    }

    @Test fun lateWifiResultIsIgnored() {
        s.onWifiResult(true, "http://x/", now)
        assertEquals(ImprovState.AUTHORIZATION_REQUIRED, s.state)
        assertTrue(fx.results.isEmpty())
    }

    @Test fun identifyAndDeviceInfoNeedNoAuthorization() {
        write(Improv.encode(Improv.CMD_IDENTIFY, emptyList()))
        assertEquals(1, fx.identifies)
        write(Improv.encode(Improv.CMD_DEVICE_INFO, emptyList()))
        val strings = Improv.strings(fx.results.single())!!.map { String(it) }
        // py-improv-ble-client 2.0.1 DeviceInfoRes requires exactly 4 strings
        assertEquals(listOf("S1 Kiosk", "0.1.0", "chip", "S1 Panel"), strings)
        assertEquals(ImprovState.AUTHORIZATION_REQUIRED, s.state)
    }

    @Test fun scanUsesTheNotificationBudget() {
        fx.networks = (1..30).map { ScannedNetwork("Net$it", -40 - it, "WPA2") }
        fx.maxResult = 50
        write(Improv.encode(Improv.CMD_SCAN_WIFI, emptyList()))
        val r = fx.results.single()
        assertTrue(r.size <= 50)
        assertEquals(Improv.CMD_SCAN_WIFI, r[0].toInt())
    }

    @Test fun badPacketsSetErrors() {
        write(byteArrayOf(0x02, 0x00, 0x05)) // bad checksum
        assertEquals(ImprovError.INVALID_RPC, s.error)
        write(Improv.encode(Improv.CMD_HOSTNAME, emptyList()))
        assertEquals(ImprovError.UNKNOWN_RPC, s.error)
        s.authorize(now)
        write(Improv.encode(Improv.CMD_WIFI_SETTINGS, listOf("", "x")))
        assertEquals(ImprovError.INVALID_RPC, s.error)
        write(Improv.encode(Improv.CMD_WIFI_SETTINGS, listOf("x".repeat(33), "x")))
        assertEquals(ImprovError.INVALID_RPC, s.error)
        assertTrue(fx.wifi.isEmpty())
    }

    @Test fun fragmentedWriteAndDisconnectDropsPartialPacket() {
        s.authorize(now)
        val p = wifiCmd()
        write(p.copyOfRange(0, 10))
        s.onDisconnect()
        write(p.copyOfRange(10, p.size)) // tail alone is garbage (claims a long length), must not combine
        assertTrue(fx.wifi.isEmpty())
        now += ImprovSession.FRAGMENT_TIMEOUT_MS + 1 // stale fragment is dropped before the next write
        write(p.copyOfRange(0, 10)); write(p.copyOfRange(10, p.size))
        assertEquals(listOf("Home" to "password1"), fx.wifi)
    }

    @Test fun provisionedExternally() {
        s.onProvisionedExternally()
        assertEquals(ImprovState.PROVISIONED, s.state)
    }

    @Test fun startWithoutAuthorization() {
        val f2 = Fake()
        val s2 = ImprovSession(f2)
        s2.start(requireAuthorization = false, now = 0)
        assertEquals(ImprovState.AUTHORIZED, s2.state)
        assertEquals(ImprovSession.DEFAULT_AUTH_TIMEOUT_MS, s2.authorizedUntil)
    }
}
