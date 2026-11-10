package sh.edm.s1kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WifiSetupLogTest {

    @Test
    fun exactFormats() {
        assertEquals("S1Kiosk.WifiSetup", WifiSetupLog.TAG)
        assertEquals("result=connecting ssid=Home", WifiSetupLog.connecting("Home"))
        assertEquals("result=connected ssid=Home ip=192.168.1.20", WifiSetupLog.connected("Home", "192.168.1.20"))
        assertEquals("result=connected ssid=Home", WifiSetupLog.connected("Home", null))
        assertEquals(
            "result=failed ssid=Home reason=wrong password (authentication failed)",
            WifiSetupLog.failed("Home", "wrong password (authentication failed)"),
        )
        assertEquals("result=failed ssid=Home reason=a b", WifiSetupLog.failed("Home", "a\nb"))
        assertEquals("result=failed ssid= reason=unknown", WifiSetupLog.failed("", " "))
    }

    @Test
    fun ssidIsPercentEncoded() {
        assertEquals("My%20Wi-Fi_5G.~", WifiSetupLog.encodeSsid("My Wi-Fi_5G.~"))
        assertEquals("caf%C3%A9%3Dx%25", WifiSetupLog.encodeSsid("café=x%"))
        assertFalse(WifiSetupLog.connecting("a b reason=x").contains(" reason="))
    }
}
