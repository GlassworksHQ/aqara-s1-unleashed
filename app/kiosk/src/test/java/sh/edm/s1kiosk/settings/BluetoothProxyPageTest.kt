package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.View
import dev.glassworks.bluetoothproxy.BluetoothProxyModel
import dev.glassworks.bluetoothproxy.ProxyPhase
import dev.glassworks.pane.SettingsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.bluetoothproxy.ProxyStatus
import sh.edm.s1kiosk.ui.Mockup

/** The page says what the proxy does, and its buttons do what they say. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class BluetoothProxyPageTest {
    private class Model(var phase: ProxyPhase, var status: ProxyStatus? = null, var bluetoothOk: Boolean = true) : BluetoothProxyModel {
        val asked = ArrayList<String>()
        override fun phase() = phase
        override fun status() = status
        override fun turnBluetoothOn() = bluetoothOk.also { asked += "bluetooth" }
        override fun turnLocationOn() = true.also { asked += "location" }
    }

    private fun page(m: Model): View {
        val a = Mockup.activity()
        val host = object : SettingsItem.NativeHost {
            override val context = a
            override fun addLayer(layer: View) {}
            override fun reveal(field: View) {}
            override fun hideKeyboard() {}
        }
        return BluetoothProxyPage(host, m).view.also { a.setContentView(it) }
    }

    private fun shown(root: View, text: String) = Mockup.label(root, text).let { it.isShown }

    @Test fun listeningShowsTheNumbersHomeAssistantGets() {
        val v = page(Model(ProxyPhase.LISTENING, ProxyStatus(true, true, null, 412, 37)))
        assertTrue(shown(v, "Listening for Home Assistant · 37 devices nearby"))
        assertTrue(shown(v, "412 a minute"))
        assertTrue(shown(v, "Listening"))
    }

    @Test fun bluetoothOffOffersToTurnItOn() {
        val m = Model(ProxyPhase.BLUETOOTH_OFF)
        val v = page(m)
        Mockup.label(v, "Turn on Bluetooth").performClick()
        assertEquals(listOf("bluetooth"), m.asked)
    }

    @Test fun whenThePanelWontTurnItOnThePageSaysSo() {
        val m = Model(ProxyPhase.BLUETOOTH_OFF, bluetoothOk = false)
        val v = page(m)
        Mockup.label(v, "Turn on Bluetooth").performClick()
        assertTrue(shown(v, "This panel doesn't let apps do that. Turn it on in the panel's own settings."))
    }

    @Test fun locationOffOffersToTurnItOn() {
        val m = Model(ProxyPhase.LOCATION_OFF)
        Mockup.label(page(m), "Turn on location").performClick()
        assertEquals(listOf("location"), m.asked)
    }

    @Test fun offShowsTheEntitiesUnavailable() {
        val v = page(Model(ProxyPhase.OFF))
        assertTrue(shown(v, "The Bluetooth proxy is off. The panel doesn't scan, and Home Assistant no longer hears devices through it."))
        assertEquals(2, all(v).count { it.text == "Unavailable" && it.isShown })
    }

    private fun all(root: View): List<android.widget.TextView> = buildList {
        fun walk(x: View) {
            if (x is android.widget.TextView) add(x)
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(root)
    }
}
