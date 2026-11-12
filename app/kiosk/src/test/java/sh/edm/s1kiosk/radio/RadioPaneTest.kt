package sh.edm.s1kiosk.radio

import android.app.Application
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaAuth
import java.net.InetAddress

/** A panel with a v4 root helper; `setprop` is stored, nothing runs. Documentation addresses only (RFC 5737). */
private class HelperProps : SysProps {
    val props = HashMap<String, String>().apply {
        this[RadioHelper.PROP_SVC_STATE] = "running"
        this[RadioHelper.PROP_VERSION] = RadioHelper.BUNDLE_VERSION.toString()
    }
    override fun get(name: String) = props[name] ?: ""
    override fun set(name: String, value: String) { props[name] = value }
    override fun sleep(ms: Long) {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RadioPaneTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()
    private val runtime = GlassworksRuntime()
    private val props = HelperProps()
    private val allow = MemoryAllowStore()
    private var stored: String? = "thread_router"
    private var published = 0

    private val pane by lazy {
        val ctx = PaneContext(
            app, KioskPrefs(app),
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = { published++ }, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        )
        val controller = RadioController(
            props, load = { stored }, store = { stored = it }, inline = true, log = {},
            filesDir = tmp.root, allow = allow,
        )
        RadioPane(ctx, runtime, panelIp = { null }, radio = controller)
    }

    private fun entity(objectId: String): ApiEntity = pane.entities().first { it.objectId == objectId }

    private fun command(objectId: String, value: String) =
        entity(objectId).command(ProtoMessage(ProtoWriter().string(2, value).toByteArray()))

    @After fun tearDown() = pane.stop()

    @Test fun onlyAnAuthenticatedHomeAssistantSetsWhoMayReachTheRadio() {
        pane.onEvent(PanelEvent.HomeAssistantSubscribed(InetAddress.getByName("192.0.2.10"), authenticated = false))
        assertNull(allow.haV4)

        pane.onEvent(PanelEvent.HomeAssistantSubscribed(InetAddress.getByName("192.0.2.11"), authenticated = true))
        assertEquals("192.0.2.11", allow.haV4)
        assertEquals("192.0.2.11", props.get(RadioHelper.PROP_ALLOW))
    }

    @Test fun theRadioModeSelectGoesToTheController() {
        assertEquals(RadioMode.THREAD_ROUTER, pane.radio.mode)

        command("radio_mode", RadioMode.OFF.label)

        assertEquals(RadioMode.OFF, pane.radio.mode)
        assertEquals("off", stored)
    }

    @Test fun allowedClientsGoToTheControllerAndArePublished() {
        command("radio_allowed_clients", "198.51.100.23")

        assertEquals("198.51.100.23", pane.radio.allowedClients())
        assertEquals("198.51.100.23", allow.explicit)
        assertEquals(1, published)
    }

    @Test fun whileItRunsTheAppReachesTheControllerAndThreadJoinDeliversToIt() {
        pane.start()
        assertSame(pane.radio, runtime.radio)
        assertNotNull(HaAuth.threadJoin.deliver)

        val before = published
        HaAuth.threadJoin.arm()
        HaAuth.threadJoin.disarm()
        assertEquals("a Thread join change is published", before + 2, published)

        pane.stop()
        assertNull(runtime.radio)
        assertNull(HaAuth.threadJoin.deliver)
    }
}
