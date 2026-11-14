package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.ViewGroup
import dev.glassworks.bluetoothproxy.BluetoothProxyDefinition
import dev.glassworks.bluetoothproxy.BluetoothProxyModel
import dev.glassworks.bluetoothproxy.BluetoothProxyPrefs
import dev.glassworks.bluetoothproxy.ProxyPhase
import dev.glassworks.bluetoothproxy.ProxyRadio
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.PaneDefinition
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.bluetoothproxy.BleScanner
import sh.edm.bluetoothproxy.ProxyStatus
import sh.edm.bluetoothproxy.ScanFailure
import sh.edm.bluetoothproxy.ScanPower
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.ui.Mockup

/**
 * The Bluetooth proxy pane (#121) as approved: its page in every state, the menu with it off (folded
 * once #212's fold lands), and setup's Panes step before and after its card. Made-up numbers only.
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*BluetoothProxyMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BluetoothProxyMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val app = RuntimeEnvironment.getApplication()

    private class Radio(val why: ScanFailure?) : ProxyRadio {
        override val scanner = object : BleScanner {
            override fun start(power: ScanPower, listener: BleScanner.Listener) {}
            override fun stop() {}
        }
        override fun unavailable() = why
        override fun turnBluetoothOn() = true
        override fun turnLocationOn() = true
    }

    private class Shown(val phase: ProxyPhase, val status: ProxyStatus?) : BluetoothProxyModel {
        override fun phase() = phase
        override fun status() = status
        override fun turnBluetoothOn() = true
        override fun turnLocationOn() = true
    }

    private val hour = listOf(380, 402, 395, 430, 418, 377, 401, 445, 420, 398, 409, 433, 415, 412)
    private val listening = ProxyStatus(true, true, null, 412, 37, hour)
    private val waiting = ProxyStatus(false, false, null, 0, 0, hour.map { 0 })
    private val retrying = ProxyStatus(true, false, ScanFailure.ANDROID_ERROR, 0, 0, hour.map { 0 }, retryInMs = 29_400)

    private fun proxy(on: Boolean, phase: ProxyPhase, status: ProxyStatus?, why: ScanFailure? = null): PaneDefinition {
        val file = KioskPrefs(app).paneFile("bluetooth-proxy")
        file.edit().putBoolean(BluetoothProxyPrefs.KEY_ENABLED, on).commit()
        return BluetoothProxyDefinition(file, Radio(why)) { host, _ -> BluetoothProxyPage(host, Shown(phase, status)) }
    }

    private fun menu(proxy: PaneDefinition?): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f }
        val radio = radioDefinition(app, FixedReadout(null, running = true))
        val panes = listOf(MenuFixture.cardOnly("voice", "Voice", "Assist and timers"), MenuFixture.cardOnly("sendspin", "Music", "A Sendspin speaker"), radio) +
            listOfNotNull(proxy) + MenuFixture.switchOnly(app)
        o.show(MenuFixture.model(app, radio, status = "screen on · presence near · voice=idle", panes = panes))
        Mockup.idle()
        return o
    }

    private fun shot(name: String, o: KioskSettingsOverlay, id: String, then: (KioskSettingsOverlay) -> Unit = {}) {
        o.previewSection(id)
        then(o)
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    /** The panes setup offers: today's four, then this pane, #118's Notifications, and two made up to show a longer list. */
    private val allCards = listOf(
        "Voice" to "Assist and timers", "Music" to "A Sendspin speaker", "Notifications" to "On-screen alerts",
        "Zigbee / Thread radio" to "For your devices", "Bluetooth proxy" to "For BLE sensors", "Wall base" to "The S1's wired base",
        "Camera" to "The panel's camera in HA", "Matter" to "Join Matter devices",
    )

    /** Setup's Panes step with [cards]; [scrolled]: the list scrolled to its end. */
    private fun setup(name: String, cards: List<Pair<String, String>>, scrolled: Boolean = false) {
        val a = Mockup.activity()
        val s = SetupOverlay(a, cards.mapIndexed { i, (t, d) -> SetupPane(PaneChoice(t, d, on = { i % 3 != 2 }, apply = { null }), emptyList()) })
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(
            step = SetupFlow.PANES, done = setOf(SetupFlow.WIFI, SetupFlow.HA),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = false,
        ))
        Mockup.idle()
        if (scrolled) {
            Mockup.layout(s)
            generateSequence(Mockup.label(s, cards.last().first) as android.view.View) { it.parent as? android.view.View }
                .firstOrNull { it is android.widget.ScrollView }?.let { v -> (v as android.widget.ScrollView).scrollTo(0, v.getChildAt(0).height - v.height) }
            Mockup.idle()
        }
        Mockup.save(name, s)
    }

    private fun named(vararg titles: String) = titles.map { t -> allCards.single { it.first == t } }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val id = "bluetooth-proxy"
        shot("bt-00-before-menu", menu(null), "radio")
        shot("bt-01-listening", menu(proxy(true, ProxyPhase.LISTENING, listening)), id)
        shot("bt-03-waiting-for-ha", menu(proxy(true, ProxyPhase.WAITING, waiting)), id)
        shot("bt-04-bluetooth-off", menu(proxy(true, ProxyPhase.BLUETOOTH_OFF, waiting, ScanFailure.BLUETOOTH_OFF)), id)
        shot("bt-05-location-off", menu(proxy(true, ProxyPhase.LOCATION_OFF, waiting, ScanFailure.LOCATION_OFF)), id)
        shot("bt-06-retrying", menu(proxy(true, ProxyPhase.RETRYING, retrying)), id)
        shot("bt-07-no-ble", menu(proxy(false, ProxyPhase.NO_BLE, null, ScanFailure.NO_BLE)), id)
        shot("bt-08-off", menu(proxy(false, ProxyPhase.OFF, null)), id)
        setup("bt-09-setup-panes-four", named("Voice", "Music", "Zigbee / Thread radio", "Wall base"))
        setup("bt-10-setup-panes-five", named("Voice", "Music", "Zigbee / Thread radio", "Bluetooth proxy", "Wall base"))
        setup("bt-11-setup-panes-six", allCards.take(6))
        setup("bt-12-setup-panes-six-scrolled", allCards.take(6), scrolled = true)
        setup("bt-13-setup-panes-eight", allCards)
        setup("bt-14-setup-panes-eight-scrolled", allCards, scrolled = true)
    }
}
