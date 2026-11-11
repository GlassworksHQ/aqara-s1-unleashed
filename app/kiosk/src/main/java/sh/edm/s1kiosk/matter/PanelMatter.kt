package sh.edm.s1kiosk.matter

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.rsmatter.android.MatterNode
import org.rsmatter.android.ThreadBorderRouters
import sh.edm.s1kiosk.ScreenController
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The panel as a Matter device (GitHub issue #1, docs/matter.md): a phone commissions it over BLE
 * by scanning the QR code the panel shows, and sends it the home's Thread dataset, which goes to
 * the radio exactly like the other ways in ([deliver]: RadioController.joinThreadNetwork). After
 * that the panel stays a Matter device in the phone's home (and Home Assistant): the screen (On/Off,
 * endpoint 1), presence (occupancy sensor, 2) and ambient light (light sensor, 3).
 *
 * Built on rs-matter-android (app/third_party/rs-matter). It uses the Matter SDK's *test* device
 * certificate, so Home Assistant only accepts it with the Matter Server's "test-net DCL" option on.
 *
 * Process-wide, like HaAuth; [init] from KioskService. Listeners run on the main thread.
 */
object PanelMatter {
    private const val TAG = "S1Matter"
    private const val PREFS = "matter"

    sealed class State {
        object Off : State() { override fun toString() = "Off" }
        object Starting : State() { override fun toString() = "Starting" }
        /** Commissionable over BLE: show [qr] (and [code] for typing). */
        data class Pairing(val qr: String, val code: String) : State()
        /** Commissioned; [network] is the Thread network it was given, if any this run. */
        data class Paired(val network: String?) : State()
        data class Failed(val reason: String) : State()
    }

    /** Hands a Thread dataset (hex) to the radio; null when taken, else why not. Set by the service. */
    @Volatile var deliver: ((String) -> String?)? = null

    @Volatile var state: State = State.Off
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private lateinit var app: Context
    private var node: MatterNode? = null
    private var borderRouters: ThreadBorderRouters? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var qr: String? = null
    private var code: String? = null
    private val lux = LuxReport()
    private val screenListener: () -> Unit = { report() }

    /** The panel's endpoints: screen, presence, ambient light. */
    private const val EP_SCREEN = 1
    private const val EP_PRESENCE = 2
    private const val EP_LIGHT = 3

    /** Pushes the screen, presence and (throttled) light to the node. */
    private fun report() {
        val n = node ?: return
        n.setOnOff(EP_SCREEN, ScreenController.screenOn)
        n.setOccupied(EP_PRESENCE, ScreenController.near)
        lux.next(ScreenController.lux.toDouble(), System.currentTimeMillis())?.let { n.setIlluminance(EP_LIGHT, it) }
    }

    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    private fun set(s: State) {
        state = s
        Log.i(TAG, "Matter: $s")
        main.post { listeners.forEach { runCatching { it(s) } } }
    }

    /** Whether the panel has been commissioned (it then runs the node at every start). */
    fun paired(): Boolean = ::app.isInitialized && prefs().getBoolean("paired", false)

    @Synchronized
    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        if (paired()) start()
    }

    /**
     * "Pair with a phone": starts the node, which opens a commissioning window if it isn't paired. A
     * paired panel opens one again: to pair after it was removed, or with another phone or hub.
     */
    @Synchronized
    fun startPairing() {
        if (node != null && state is State.Pairing) return
        val running = node
        if (running != null && paired()) running.openCommissioningWindow() else start()
        main.removeCallbacks(windowOver)
        main.postDelayed(windowOver, PAIRING_WINDOW_MS)
    }

    /** The commissioning window is over and nobody paired: stop the node (and BLE). */
    private val windowOver = Runnable { if (!paired()) stop() }

    /** "Close" on the pairing screen. A paired panel keeps its node running. */
    fun cancelPairing() {
        main.removeCallbacks(windowOver)
        if (!paired()) stop()
    }

    /** rs-matter's basic commissioning window. */
    private const val PAIRING_WINDOW_MS = 15 * 60_000L

    /** Forget the Matter pairing: the panel leaves every fabric (phone, Home Assistant) for good. */
    @Synchronized
    fun forget() {
        stop()
        File(app.filesDir, "matter").deleteRecursively()
        prefs().edit().clear().apply()
        set(State.Off)
    }

    @Synchronized
    fun stop() {
        node?.stop()
        node = null
        ScreenController.removeListener(screenListener)
        borderRouters?.stop()
        borderRouters = null
        multicast?.let { runCatching { it.release() } }
        multicast = null
        if (state !is State.Failed) set(State.Off)
    }

    @Synchronized
    private fun start() {
        if (node != null) return
        set(State.Starting)
        val (discriminator, passcode) = setupCode()
        multicast = app.getSystemService(WifiManager::class.java)?.createMulticastLock("s1kiosk-matter")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        val routers = ThreadBorderRouters(app).also { it.start() }
        borderRouters = routers
        val n = MatterNode(
            app,
            MatterNode.Config(
                storageDir = File(app.filesDir, "matter"),
                discriminator = discriminator,
                passcode = passcode,
                deviceName = "S1 Panel",
                endpoints = listOf(
                    MatterNode.Endpoint.OnOff(EP_SCREEN),
                    MatterNode.Endpoint.OccupancySensor(EP_PRESENCE),
                    MatterNode.Endpoint.LightSensor(EP_LIGHT),
                ),
            ),
            object : MatterNode.Listener {
                override fun onThreadScan(): List<MatterNode.ThreadNetwork> = routers.networks()

                override fun onThreadDataset(dataset: ByteArray): Boolean {
                    val hex = dataset.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val err = deliver?.invoke(hex) ?: "the panel's radio isn't running"
                    if (err != null) {
                        Log.w(TAG, "Thread dataset refused: $err")
                        set(State.Failed(err))
                        return false
                    }
                    // The phone provisions the network as the last step before it completes the
                    // commissioning: from here on the panel is paired (and runs the node at start).
                    prefs().edit().putBoolean("paired", true).apply()
                    set(State.Paired(SetupCode.networkName(dataset)))
                    return true
                }

                override fun onOnOff(endpoint: Int, on: Boolean) {
                    if (endpoint != EP_SCREEN) return
                    main.post { if (on) ScreenController.wake("matter") else ScreenController.sleep("matter") }
                }

                override fun onEvent(kind: String, detail: String) = onNodeEvent(kind, detail)
            },
        )
        node = n
        ScreenController.addListener(screenListener)
        lux.reset()
        if (!n.start()) {
            node = null
            set(State.Failed("the Matter node didn't start"))
        } else {
            report()
        }
    }

    private fun onNodeEvent(kind: String, detail: String) {
        when (kind) {
            "qr_code" -> qr = detail
            "pairing_code" -> code = detail
            "state" -> when (detail) {
                "commissionable" -> set(State.Pairing(qr.orEmpty(), code.orEmpty()))
                "commissioned" -> {
                    prefs().edit().putBoolean("paired", true).apply()
                    if (state !is State.Paired) set(State.Paired(null))
                }
            }
            "stopped" -> if (detail.isNotEmpty()) set(State.Failed(detail))
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The panel's own discriminator and setup passcode, made once (Matter spec 5.1.7.1). */
    private fun setupCode(): Pair<Int, Long> {
        val p = prefs()
        if (!p.contains("passcode")) {
            val r = SecureRandom()
            p.edit()
                .putInt("discriminator", r.nextInt(4096))
                .putLong("passcode", SetupCode.randomPasscode(r))
                .apply()
        }
        return p.getInt("discriminator", 3840) to p.getLong("passcode", 20202021)
    }

}

/** Setup passcodes (Matter Core spec 5.1.7.1) and dataset helpers. Pure, for tests. */
object SetupCode {
    /** The Network Name TLV (type 3) of an operational dataset, for the status line. */
    fun networkName(dataset: ByteArray): String? {
        var i = 0
        while (i + 2 <= dataset.size) {
            val type = dataset[i].toInt() and 0xff
            val len = dataset[i + 1].toInt() and 0xff
            if (i + 2 + len > dataset.size) return null
            if (type == 3) return String(dataset, i + 2, len, Charsets.UTF_8)
            i += 2 + len
        }
        return null
    }

    /** 00000000, 11111111 … 99999999, 12345678 and 87654321 are not allowed. */
    fun valid(passcode: Long): Boolean =
        passcode in 1..99_999_998 &&
            passcode !in (1..9).map { d -> (0 until 8).fold(0L) { a, _ -> a * 10 + d } } &&
            passcode != 12_345_678L && passcode != 87_654_321L

    fun randomPasscode(r: java.util.Random): Long {
        while (true) {
            val p = 1 + r.nextInt(99_999_998).toLong()
            if (valid(p)) return p
        }
    }
}

/**
 * Which ambient-light readings go to Matter: the sensor reports often and noisily, and each change is
 * a report to every subscriber. A new value goes out when it moves by 1000 or more in Matter's log
 * scale (about 26 % in lux), or after a minute with any change. Pure, for tests.
 */
class LuxReport(private val step: Int = 1000, private val everyMs: Long = 60_000) {
    private var last: Int? = null
    private var lastAt = 0L

    fun reset() { last = null; lastAt = 0 }

    /** The lux value to report now, or null to skip this reading. */
    fun next(lux: Double, now: Long): Double? {
        if (lux.isNaN()) return null
        val v = org.rsmatter.android.Units.illuminance(lux)
        val prev = last
        val send = prev == null || kotlin.math.abs(v - prev) >= step || (v != prev && now - lastAt >= everyMs)
        if (!send) return null
        last = v
        lastAt = now
        return lux
    }
}
