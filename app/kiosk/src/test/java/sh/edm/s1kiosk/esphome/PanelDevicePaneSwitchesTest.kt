package sh.edm.s1kiosk.esphome

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import sh.edm.esphome.EspHomeLog
import sh.edm.esphome.Msg
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

/**
 * #67: a pane's switch starts and stops it while the panel runs, with no app restart. Off stops its
 * work (its threads and timers); on starts it again. Voice also changes what Home Assistant reads in the
 * device info, so Home Assistant is asked to reconnect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDevicePaneSwitchesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val exec = ManualExecutor()
    private val runtime = GlassworksRuntime(AqaraS1Plus())
    private val mdns = RecordingMdns()
    private lateinit var device: PanelDevice
    private val port get() = mdns.starts.single().port

    private fun switch(id: String) = runtime.paneDefinitions(app).single { it.id == id }.switch!!

    @Before fun setUp() {
        val prefs = KioskPrefs(app)
        // No real voice engine or Sendspin player at start; the wall base on the backend that only schedules a task.
        switch("assist").on = false
        switch("sendspin").on = false
        prefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        prefs.voice.wakeWord = "" // voice turned on here downloads no wake word model
        device = PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = exec, mdns = mdns,
            apiPort = 0, apiBindAddress = InetAddress.getLoopbackAddress())
        device.start()
        exec.runDue()
    }

    @After fun tearDown() = device.stop()

    /** Sets a switch as the menu, setup or Home Assistant would, and runs what that queues. */
    private fun set(id: String, on: Boolean) {
        switch(id).on = on
        ShadowLooper.idleMainLooper()
        exec.runDue()
    }

    private fun threadAlive(name: String) = Thread.getAllStackTraces().keys.any { it.name == name && it.isAlive }

    private fun waitThread(name: String, alive: Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (threadAlive(name) != alive && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals("thread $name alive", alive, threadAlive(name))
    }

    @Test fun aPaneOffWhenThePanelStartsIsNotStarted() {
        device.stop()
        set("diagnostics", false)
        val second = ManualExecutor()
        device = PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = second, mdns = RecordingMdns(),
            apiPort = 0, apiBindAddress = InetAddress.getLoopbackAddress())
        device.start()
        second.runDue()
        assertEquals("the dashboard check and the state refresh only", 2, second.periodic)
        // Not started and stopped again by the reconcile at the end of startup: never started.
        assertEquals("diagnostics never scheduled its sample", 2, second.fixedDelayScheduled)
    }

    /**
     * #210 review: a switch turned off after its pane started, and before the shell listens to the
     * settings, still stops the pane once startup is done, and the pane hears its own switch (the wall
     * base then puts Aqara's daemon as the blocking setting wants it). The wall base's start schedules
     * a task: that is when this test turns it off.
     */
    @Test fun aSwitchTurnedOffDuringStartupStopsItsPane() {
        device.stop()
        waitThread("s1-base", false)
        val logged = java.util.Collections.synchronizedList(ArrayList<String>())
        val sink = EspHomeLog.sink
        EspHomeLog.sink = EspHomeLog.Sink { level, tag, msg, t -> logged += msg; sink.log(level, tag, msg, t) }
        try {
            val second = ManualExecutor()
            var once = true
            second.afterSchedule = { if (once) { once = false; switch("wall-base").on = false } }
            device = PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = second, mdns = RecordingMdns(),
                apiPort = 0, apiBindAddress = InetAddress.getLoopbackAddress())
            device.start()
            second.runDue()
            assertEquals("turned off during startup", false, once)
            waitThread("s1-base", false)
            assertTrue("the wall base heard its switch: ${logged.filter { "Aqara" in it }}", logged.any { it.startsWith("Aqara cloud (wall base off)") })
        } finally {
            EspHomeLog.sink = sink
        }
    }

    /**
     * #210 review: a pane that is off when the app starts still hears its own switch once. The wall base
     * then applies the blocking setting to Aqara's daemon, which init restarted at boot.
     */
    @Test fun theWallBaseOffAtStartStillPutsAqarasDaemonRight() {
        device.stop()
        set("wall-base", false)
        val logged = java.util.Collections.synchronizedList(ArrayList<String>())
        val sink = EspHomeLog.sink
        EspHomeLog.sink = EspHomeLog.Sink { level, tag, msg, t -> logged += msg; sink.log(level, tag, msg, t) }
        try {
            val second = ManualExecutor()
            device = PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = second, mdns = RecordingMdns(),
                apiPort = 0, apiBindAddress = InetAddress.getLoopbackAddress())
            device.start()
            second.runDue()
            assertTrue("the wall base applied its daemon policy: ${logged.filter { "Aqara" in it }}", logged.any { it.startsWith("Aqara cloud (wall base off)") })
        } finally {
            EspHomeLog.sink = sink
        }
    }

    /** #210 review: a pane whose start failed isn't recorded as running, so the next switch change tries it again. */
    @Test fun aPaneThatFailedToStartIsTriedAgain() {
        set("diagnostics", false)
        assertEquals(2, exec.periodic)
        exec.refuseNextFixedDelay = true
        set("diagnostics", true) // its start throws: no sample scheduled
        assertEquals(2, exec.periodic)
        set("time-zone", false) // any switch change
        assertEquals("diagnostics started this time", 3, exec.periodic)
    }

    @Test fun diagnosticsOffStopsSamplingAndOnStartsItAgain() {
        assertEquals("the dashboard check, the state refresh and the diagnostics sample", 3, exec.periodic)
        set("diagnostics", false)
        assertEquals("no diagnostics sample", 2, exec.periodic)
        set("diagnostics", true)
        assertEquals(3, exec.periodic)
    }

    @Test fun theWallBaseAndTimeZoneThreadsGoWhenOffAndComeBackWhenOn() {
        exec.runDue()
        waitThread("s1-base", true)
        waitThread("s1-timezone", true)
        set("wall-base", false)
        set("time-zone", false)
        waitThread("s1-base", false)
        waitThread("s1-timezone", false)
        set("wall-base", true)
        set("time-zone", true)
        waitThread("s1-base", true)
        waitThread("s1-timezone", true)
    }

    @Test fun voiceOnOrOffChangesTheDeviceInfoAndHomeAssistantReconnects() {
        Client(port).use { c ->
            assertEquals("voice off: no voice features", 0, c.voiceFeatures())
            set("assist", true)
            c.awaitDisconnectRequest()
        }
        Client(port).use { assertEquals(PanelDevice.VOICE_FEATURES, it.voiceFeatures()) }
        Client(port).use { c ->
            c.voiceFeatures() // answered: the server has this connection, so the fan-out reaches it
            set("assist", false)
            c.awaitDisconnectRequest()
        }
        Client(port).use { assertEquals(0, it.voiceFeatures()) }
    }

    @Test fun anotherSwitchKeepsHomeAssistantConnected() {
        Client(port).use { c ->
            c.voiceFeatures()
            set("diagnostics", false)
            assertEquals("the connection still answers", 0, c.voiceFeatures())
        }
    }

    /**
     * #33: Home Assistant builds the device page's "Visit" link from `webserver_port` (field 10). The
     * panel's device info carries the Manage page's port, with every pane switch on or off.
     */
    @Test fun theDeviceInfoGivesHomeAssistantTheManagePagesPort() {
        Client(port).use { assertEquals(8099, it.deviceInfo().uint32(10)) }
        set("assist", true)
        Client(port).use { assertEquals(8099, it.deviceInfo().uint32(10)) }
    }

    /** A plaintext ESPHome API client, as Home Assistant connects. */
    private class Client(port: Int) : AutoCloseable {
        private val s = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }
        private val input = s.getInputStream()

        init { send(Msg.HELLO_REQUEST, ProtoWriter().string(1, "Home Assistant 2026.11.0").uint32(2, 1).uint32(3, 10).toByteArray()) }

        fun deviceInfo(): ProtoMessage {
            send(Msg.DEVICE_INFO_REQUEST, ByteArray(0))
            return ProtoMessage(read(Msg.DEVICE_INFO_RESPONSE))
        }

        fun voiceFeatures(): Int = deviceInfo().uint32(17)

        fun awaitDisconnectRequest() { read(Msg.DISCONNECT_REQUEST) }

        private fun send(type: Int, payload: ByteArray) {
            val o = ByteArrayOutputStream()
            o.write(0); varint(o, payload.size); varint(o, type); o.write(payload)
            s.getOutputStream().apply { write(o.toByteArray()); flush() }
        }

        /** Reads frames until one of [type]; its payload. */
        private fun read(type: Int): ByteArray {
            while (true) {
                check(input.read() == 0) { "not a plaintext frame" }
                val len = varint(input)
                val t = varint(input)
                val data = input.readNBytes(len)
                if (t == type) return data
            }
        }

        private fun varint(o: ByteArrayOutputStream, v: Int) {
            var x = v
            while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
            o.write(x)
        }

        private fun varint(i: InputStream): Int {
            var shift = 0
            var v = 0
            while (true) {
                val b = i.read()
                check(b >= 0) { "connection closed" }
                v = v or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
        }

        override fun close() = s.close()
    }

    @Test fun onAFrameWithoutACameraTheCameraPaneNeverRunsNorLightsThePill() {
        // Robolectric's device has no camera, as the S1.
        set("camera", true)
        assertEquals(emptyList<Any>(), device.paneList().filter { it.id == "camera" })
        assertEquals(false, runtime.cameraLeaving)
        assertEquals(null, runtime.onCameraLeaving)
        set("camera", false)
    }
}
