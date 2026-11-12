package sh.edm.s1kiosk.esphome

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPowerManager
import sh.edm.esphome.EspHomeLog
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.ScreenController
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.dashboard.Dashboards
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * The guards in [PanelDevice.startInternal], `startServer()`, `readvertise()` and `stop()` against a
 * `stop()` that lands while the device starts. The device runs on a [ManualExecutor] and a
 * [RecordingMdns], so each test chooses the point where `stop()` lands, and then checks that
 * nothing is left running.
 *
 * A test is the esphome-main thread and the service thread in turn: it runs the startup, and
 * `stop()` is called from a hook at the chosen point. Where the hook sits outside the `lifecycle`
 * lock, that is the same as a `stop()` that waited for the step before it. Where the hook sits
 * inside a step (the two marked below), `stop()` runs at the end of that step: a real `stop()`
 * would wait for the lock and run right after it, with the same result.
 *
 * Each test names the guard it pins; removing that guard makes it fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceRacesTest {

    private lateinit var app: Application
    private lateinit var runtime: GlassworksRuntime
    private val exec = ManualExecutor()
    private val mdns = RecordingMdns()
    private lateinit var device: PanelDevice

    private var logHook: ((String) -> Unit)? = null
    private lateinit var originalSink: EspHomeLog.Sink

    private var screenListeners = 0
    private var authListeners = 0
    private var dashboardListeners = 0

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        runtime = GlassworksRuntime(AqaraS1Plus())
        // Panes that start real engines are off, and the wall base takes the backend that only
        // schedules a task on esphome-main (no UART, no Aqara cloud daemon).
        val prefs = KioskPrefs(app)
        prefs.voice.enabled = false
        prefs.sendspin.enabled = false
        prefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        ShadowPowerManager.clearWakeLocks()
        screenListeners = listenerCount(ScreenController)
        authListeners = listenerCount(HaAuth)
        dashboardListeners = listenerCount(Dashboards)
        originalSink = EspHomeLog.sink
        EspHomeLog.sink = EspHomeLog.Sink { level, tag, msg, t ->
            logHook?.invoke(msg)
            originalSink.log(level, tag, msg, t)
        }
    }

    @After fun tearDown() {
        EspHomeLog.sink = originalSink
        if (::device.isInitialized) runCatching { device.stop() }
    }

    private fun newDevice(context: Context) =
        PanelDevice(context, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0)

    /** start() queues the startup on esphome-main; this runs it, and what it queues. */
    private fun startup() {
        if (!::device.isInitialized) device = newDevice(app)
        device.start()
        exec.runDue()
    }

    // ------------------------------------------------------------------ the baseline

    @Test fun aStartupNothingInterruptsLeavesEverythingRunningAndStopTakesItAllDown() {
        startup()

        val port = mdns.starts.single().port
        assertTrue("mDNS is registered", mdns.registered)
        assertTrue("the server listens", listens(port))
        assertNotNull("the radio pane started", runtime.radio)
        assertNotNull("the update manager started", runtime.updates)
        assertTrue("the wake lock is held", ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
        assertEquals("the Wi-Fi lock is held", 1, wifiLocks())
        assertEquals(screenListeners + 1, listenerCount(ScreenController))
        assertEquals(authListeners + 1, listenerCount(HaAuth))
        assertEquals(dashboardListeners + 1, listenerCount(Dashboards))
        assertEquals("the dashboard check and the state refresh are scheduled", 2, exec.periodic)

        device.stop()

        assertNothingRunning(port)
    }

    // ------------------------------------------------------------------ readvertise()

    /** The key HA provisions (or the panel settings set) changes the TXT records. Runs up to the delayed re-registration. */
    private fun changeTheKey() {
        val prefs = KioskPrefs(app)
        prefs.apiKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        prefs.apiEncryption = true
        ShadowLooper.idleMainLooper()
        // onPrefChanged -> replaceKey -> onKeyActivated -> readvertise: mDNS goes, and comes back 1.5 s later.
        exec.runDue()
    }

    /** Guard: `readvertise()` re-registers (and only through `server === s`/`unlessStopped`) while the server runs. */
    @Test fun aKeyChangeReadvertisesWhileTheServerRuns() {
        startup()
        val first = mdns.starts.single()
        assertFalse("no key yet", first.encrypted)
        assertTrue("so a key can be provisioned", first.provisionable)

        changeTheKey()

        assertFalse("the old record is withdrawn first", mdns.registered)
        assertTrue("re-registers 1.5 s later", 1500L in exec.pendingDelays())
        exec.advance(1499)
        assertFalse("not before the old record has gone out", mdns.registered)
        exec.advance(1)
        assertTrue("registered again", mdns.registered)
        assertEquals(
            "with the port it has, and the new key in the TXT records",
            listOf(first, RecordingMdns.Start(first.port, encrypted = true, provisionable = false)),
            mdns.starts,
        )
        assertEquals(listOf("start", "stop", "start"), mdns.calls)
    }

    /** The delayed task due before stop() is the normal case: it registers, once. */
    @Test fun theDelayedReadvertiseBeforeStopRegistersMdns() {
        startup()
        changeTheKey()
        val delayed = exec.detach(1500)

        delayed.run()

        assertTrue(mdns.registered)
        assertEquals("the first registration, and this one", 2, mdns.starts.size)
    }

    /** Guard: `server === s` (the server is already gone once stop() has finished). */
    @Test fun theDelayedReadvertiseAfterStopRegistersNothing() {
        startup()
        changeTheKey()
        val delayed = exec.detach(1500)
        device.stop()

        delayed.run()

        assertFalse("nothing is advertised on a dead port", mdns.registered)
        assertEquals("only the first registration", 1, mdns.starts.size)
    }

    /**
     * Guard: `unlessStopped` in the delayed task (#80). stop() has withdrawn mDNS but has not yet
     * cleared `server`, so `server === s` is still true; only `stopped` keeps the task out.
     */
    @Test fun theDelayedReadvertiseThatRunsInsideStopRegistersNothing() {
        startup()
        changeTheKey()
        val delayed = exec.detach(1500)
        mdns.onStop = { mdns.onStop = null; delayed.run() }

        device.stop()

        assertFalse("nothing is advertised on a dead port", mdns.registered)
        assertEquals("only the first registration", 1, mdns.starts.size)
    }

    // ------------------------------------------------------------------ stop() between the startup steps

    /** Guard: the first step, `if (!unlessStopped { initEntities(...); acquireLocks() }) return`. */
    @Test fun aStopBeforeTheEntitiesAreBuiltBuildsNothingAndHoldsNothing() {
        logHook = { msg ->
            if (msg.startsWith("ESPHome device '${PanelDevice.NAME}'")) { logHook = null; device.stop() }
        }

        startup()

        assertTrue("the entities were never built", runCatching { device.entityList() }.isFailure)
        assertNull("no wake lock was even taken", ShadowPowerManager.getLatestWakeLock())
        assertNothingRunning(port = null)
    }

    /**
     * Guard: `if (!unlessStopped { timeZone.start(); updates.start() }) return`. stop() lands after the
     * entities are built and the locks taken, and before the panes start: the hook is the first read of
     * the speaker setting after that step, in applyVolume(), outside the lock.
     */
    @Test fun aStopAfterTheEntitiesAreBuiltTakesTheLocksBackAndStartsNothingMore() {
        var landed = false
        var builtWhenItLanded = false
        var lockHeldWhenItLanded = false
        val hooked = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                ReadHook(super.getSharedPreferences(name, mode)) { key ->
                    val built = runCatching { device.entityList() }.isSuccess
                    if (key == "speaker_muted" && built && !landed) {
                        landed = true
                        builtWhenItLanded = true
                        lockHeldWhenItLanded = ShadowPowerManager.getLatestWakeLock()?.isHeld == true
                        device.stop()
                    }
                }
        }
        device = newDevice(hooked)

        startup()

        assertTrue("stop() landed after the entities were built", landed && builtWhenItLanded)
        assertTrue("and after the locks were taken", lockHeldWhenItLanded)
        assertNothingRunning(port = null)
        assertTrue("no server was started", mdns.starts.isEmpty())
    }

    /**
     * Guard: `unlessStopped { pane.start() }` for each pane. stop() lands right after the wall base
     * pane started (its start schedules the Aqara cloud check on esphome-main: the hook is the end
     * of that call, inside the step); the radio pane comes after it and must not start.
     */
    @Test fun aStopAfterAPaneStartedStartsNoMorePanes() {
        exec.afterSchedule = { exec.afterSchedule = null; device.stop() }

        startup()

        assertNull("the radio pane, which comes after the wall base, did not start", runtime.radio)
        assertNothingRunning(port = null)
        assertTrue("no server was started", mdns.starts.isEmpty())
    }

    /**
     * Guard: the hand-over in `startServer()`, `unlessStopped { server = s; mdns.start(...) }` and
     * `if (!kept) s.stop()`. stop() lands after `s.start()` has bound the port and before the
     * hand-over, so it finds no server to stop: the hand-over must stop it itself.
     */
    @Test fun aStopBetweenTheServerStartAndTheHandOverStopsTheServerAndRegistersNothing() {
        var port = -1
        logHook = { msg ->
            val m = Regex("^API server listening on (\\d+)").find(msg)
            if (m != null && port == -1) {
                port = m.groupValues[1].toInt()
                assertTrue("the server is up when stop() lands", listens(port))
                device.stop()
            }
        }

        startup()

        assertTrue("stop() landed after the port was bound", port > 0)
        assertFalse("mDNS never registered", mdns.registered)
        assertTrue("and was never asked to", mdns.starts.isEmpty())
        assertNothingRunning(port)
    }

    /**
     * Guard: the last step, `unlessStopped { listeners; schedules }`. stop() lands right after the
     * hand-over (the hook is the end of mDNS's start, inside the step): the server and mDNS go with it,
     * and the listeners and the periodic tasks, which come next, must not be added.
     */
    @Test fun aStopRightAfterTheHandOverRegistersNoListenerAndSchedulesNothing() {
        mdns.onStart = { mdns.onStart = null; device.stop() }

        startup()

        assertEquals("the hand-over happened", 1, mdns.starts.size)
        assertNothingRunning(port = mdns.starts.single().port)
    }

    // ------------------------------------------------------------------ what "nothing is running" means

    private fun assertNothingRunning(port: Int?) {
        assertFalse("mDNS has no record", mdns.registered)
        assertNull("the radio pane is stopped", runtime.radio)
        assertNull("the update manager is stopped", runtime.updates)
        assertFalse("the wake lock is not held", ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
        assertEquals("the Wi-Fi lock is not held", 0, wifiLocks())
        assertEquals("the screen listener is gone", screenListeners, listenerCount(ScreenController))
        assertEquals("the sign-in listener is gone", authListeners, listenerCount(HaAuth))
        assertEquals("the dashboards listener is gone", dashboardListeners, listenerCount(Dashboards))
        assertEquals("no periodic task runs", 0, exec.periodic)
        assertEquals("none was scheduled after the shutdown either", 0, exec.rejectedPeriodic)
        if (port != null) assertTrue("nothing listens on the API port", stopsListening(port))
        // The settings listener shares the file of every pane's settings: a change must not reach esphome-main.
        // Robolectric delivers it on this thread, so a listener left registered is refused right here.
        val refused = exec.refusedFrom(Thread.currentThread())
        runCatching { KioskPrefs(app).speakerMuted = true }
        ShadowLooper.idleMainLooper()
        assertEquals("the settings listener is gone", refused, exec.refusedFrom(Thread.currentThread()))
    }

    private fun wifiLocks() = shadowOf(app.getSystemService(Context.WIFI_SERVICE) as WifiManager).activeLockCount

    /** A closed listening socket can take a moment to refuse connections while its accept thread winds down. */
    private fun stopsListening(port: Int): Boolean {
        val end = System.currentTimeMillis() + 3_000
        while (listens(port)) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(25)
        }
        return true
    }

    private fun listens(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500) }
        true
    } catch (_: java.io.IOException) {
        false
    }

    /** The private `listeners` list of one of the app's singletons: the only way to see who is registered. */
    private fun listenerCount(owner: Any): Int {
        val field = owner.javaClass.getDeclaredField("listeners").apply { isAccessible = true }
        return (field.get(owner) as Collection<*>).size
    }

    /** A settings file that reports every read, so a test can act at the moment the device reads a setting. */
    private class ReadHook(private val d: SharedPreferences, private val onRead: (String) -> Unit) : SharedPreferences by d {
        override fun getBoolean(key: String, defValue: Boolean) = d.getBoolean(key, defValue).also { onRead(key) }
        override fun getFloat(key: String, defValue: Float) = d.getFloat(key, defValue).also { onRead(key) }
    }
}
