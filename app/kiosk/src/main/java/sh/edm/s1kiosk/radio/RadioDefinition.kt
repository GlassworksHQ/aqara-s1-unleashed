package sh.edm.s1kiosk.radio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupStep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.discovery.HaUrls
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.radio.ui.RadioSettingsView
import sh.edm.s1kiosk.radio.ui.RadioStep

/**
 * What the radio's setup step and settings section read. The app's comes from the running
 * [RadioController] ([GlassworksRuntime.radio]); the mock-ups give fixed values.
 */
interface RadioReadout {
    /** Null while the kiosk service (and so the controller) isn't running. */
    fun controller(): RadioController?

    /** The controller runs: the settings' picker and follow-up card can drive it. */
    fun running(): Boolean = controller() != null

    /** The mode in effect: the controller's, else the stored one. */
    fun mode(): RadioMode

    /** The radio as the follow-up cards and the settings picker show it; null without a controller. */
    fun facts(): RadioFollowUp.Facts?

    /** Home Assistant's address, for the follow-up cards' links. */
    fun haBase(): String?

    /** The panel's LAN address, for the addresses Home Assistant needs. */
    fun panelIp(): String?

    /** The owner's allow-list as typed ("" = Home Assistant only), as `Radio allowed clients` shows it. */
    fun allowedClients(): String = controller()?.allowedClients().orEmpty()

    /** Who the helper's firewall lets in now; null when no firewall is active. */
    fun allowedNow(): String? = controller()?.allowedNow()
}

/**
 * The radio pane ([RadioPane]) for the whole process: the setup step (docs/onboarding.md, "Radio"),
 * the settings menu's Radio section, and the requests both send to the [RadioController]. Built once
 * by [GlassworksRuntime.paneDefinitions].
 */
class RadioDefinition(
    /** The radio namespace's settings (`kiosk.xml`), the step's two flags included. */
    private val prefs: RadioPrefs,
    /** The panel shows any trace of an earlier setup (SetupState.setUpBefore): the core's facts. */
    private val setUpBefore: () -> Boolean,
    internal val readout: RadioReadout,
) : PaneDefinition {

    override val id = "radio"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    init {
        migrate()
    }

    // ------------------------------------------------------------------ setup step

    /** The step was answered (a mode applied, or skipped = Off). */
    var stepDone: Boolean
        get() = prefs.setupDone
        set(v) { prefs.setupDone = v }

    /**
     * Once per install of a build with the radio step: a panel that was already set up before it never
     * sees the step. Runs when the definition is made, which the app does before discovery can save an
     * HA URL in this process (KioskService.onCreate, MainActivity.onCreate); later runs do nothing.
     */
    private fun migrate() {
        if (prefs.setupMigrated) return
        val done = setUpBefore() || prefs.mode != null
        prefs.setupMigrated = true
        if (done) prefs.setupDone = true
    }

    val step: SetupStep = object : SetupStep {
        override val id = "radio"
        override val reason = "radio mode not chosen"

        /** Answered on the Panes step or over the setup channel, or a mode was chosen with HA's `Radio mode` select. */
        override fun done() = stepDone || prefs.mode != null

        override fun channelState() = channelJson()

        override val ops = mapOf(
            "set_mode" to SetupOp { setMode(it.optString("mode", "")) },
            "set_allow" to SetupOp { setAllow(it.optString("allow", "")) },
        )

        /** What the S1's Kiln (kiln/packages/setup-channel) sends. */
        override val legacyOps = mapOf("set_radio" to "set_mode", "set_radio_allow" to "set_allow")
    }

    override val setupSteps = listOf(step)

    /**
     * The Panes step's radio card: on means a mode other than Off, the modes are the chips. Continue
     * applies it through [setMode], the installer's path, which marks the step answered.
     */
    override val setupChoice = PaneChoice(
        title = "Zigbee / Thread radio",
        detail = "For your devices",
        on = { readout.mode() != RadioMode.OFF },
        options = RadioStep.OPTIONS.filter { it.mode != RadioMode.OFF }.map { PaneChoice.Option(it.mode.id, RadioStep.chip(it)) },
        option = { readout.mode().takeIf { it != RadioMode.OFF }?.id ?: RadioMode.ZIGBEE_TCP.id },
        optionsLabel = "Radio",
        apply = { on, option -> applyChoice(if (on) option ?: RadioMode.ZIGBEE_TCP.id else RadioMode.OFF.id) },
    )

    /**
     * The mode in effect already, and running or on its way: the step is answered without asking the
     * radio again. A mode that failed or was refused (the controller stores it before applying) asks again.
     */
    private fun applyChoice(id: String): String? {
        val r = readout.controller()
        val mode = readout.mode()
        if (r != null && mode.id == id &&
            RadioStep.classify(mode, true, r.statusText()) in setOf(RadioStep.Result.OK, RadioStep.Result.APPLYING)
        ) { stepDone = true; return null }
        return setMode(id)
    }

    // ------------------------------------------------------------------ setup channel

    @Volatile private var helper = false
    @Volatile private var helperAt = 0L

    /** helperInstalled() spawns getprop: not on every 1.5 s poll of the setup channel. */
    private fun helperInstalled(): Boolean {
        val r = readout.controller() ?: return false
        val now = SystemClock.elapsedRealtime()
        if (helperAt == 0L || now - helperAt > HELPER_CACHE_MS) {
            helper = r.helperInstalled()
            helperAt = now
        }
        return helper
    }

    /** The `radio` part of the setup channel's state (channel.ts `SetupState.radio`). */
    internal fun channelJson(): JSONObject {
        val r = readout.controller()
        return channelJson(
            mode = readout.mode().id,
            status = r?.statusText() ?: "The radio controller isn't running yet",
            helper = helperInstalled(),
            allowed = r?.allowedNow(),
        )
    }

    /**
     * Through RadioController.request, like the panel's radio step: waits for the controller to take the
     * request, then up to [RADIO_FOLLOW_MS] for it to report how it went (the client waits 30 s), and
     * marks the step answered on success.
     */
    internal fun setMode(id: String): String? {
        val mode = RadioMode.fromId(id)?.takeIf { m -> RadioStep.OPTIONS.any { it.mode == m } }
            ?: return "Unknown radio mode '$id'."
        val r = readout.controller() ?: return CHANNEL_NOT_RUNNING
        val accepted = AtomicBoolean(false)
        val handled = CountDownLatch(1)
        Log.i(TAG, "radio mode ${mode.label} from the installer")
        r.request(mode.label, "installer") { ok -> accepted.set(ok); handled.countDown() }
        if (!handled.await(RADIO_ACCEPT_MS, TimeUnit.MILLISECONDS)) return "The radio didn't answer in time."
        if (!accepted.get()) return r.statusText()
        val until = SystemClock.elapsedRealtime() + RADIO_FOLLOW_MS
        while (true) {
            val status = r.statusText()
            when (RadioStep.classify(mode, true, status)) {
                RadioStep.Result.OK -> break
                RadioStep.Result.REFUSED, RadioStep.Result.ERROR -> return status
                // Still applying (root helper): the panel's step lets you go on too; the status follows.
                RadioStep.Result.APPLYING -> if (SystemClock.elapsedRealtime() > until) break
            }
            r.refreshSoon()
            Thread.sleep(500)
        }
        stepDone = true // the panel's own radio step doesn't ask again
        return null
    }

    /** Who may reach the radio's TCP services, as the `Radio allowed clients` entity. */
    internal fun setAllow(text: String): String? {
        val r = readout.controller() ?: return CHANNEL_NOT_RUNNING
        r.setAllowedClients(text)?.let { return it }
        Log.i(TAG, "radio allowed clients from the installer")
        // The helper re-reads the list within 3 s; give the reply the enforced state.
        val until = SystemClock.elapsedRealtime() + 5_000
        val want = r.wantedAllow()
        // Poll the helper's one prop here (this isn't the radio thread), then refresh once.
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(500)
            val a = RadioHelper.parseAllowed(ShellProps.get(RadioHelper.PROP_ALLOWED)) ?: break // Off / no firewall
            if ((if (a.any) RadioAllow.ANY else a.addresses.joinToString(" ")) == want) break
        }
        val refreshed = CountDownLatch(1)
        r.refreshSoon()
        r.afterPending { refreshed.countDown() } // the status text in the reply names the new list
        refreshed.await(3, TimeUnit.SECONDS)
        return null
    }

    // ------------------------------------------------------------------ requests from the panel

    /**
     * A request from a follow-up card or the settings menu's picker, on the same RadioController calls as
     * the HA entities (`Radio mode`, `Zigbee stack`, `Join/Leave Zigbee network`, `Leave Thread network`).
     * [done] runs on the main thread: null = accepted (the status line tells the rest), else why not.
     * Join/Leave have no answer of their own: their card follows the status. [refresh] redraws the view
     * that asked, on the main thread.
     */
    fun run(req: RadioFollowUp.Request, source: String, done: (String?) -> Unit, refresh: () -> Unit) {
        val r = readout.controller() ?: run { done(NOT_RUNNING); return }
        Log.i(MAIN_TAG, "$source: radio request $req")
        when (req) {
            is RadioFollowUp.Request.Mode -> r.request(req.mode.label, source) { ok ->
                val st = r.statusText(); main.post { done(if (ok) null else st); refresh() }
            }
            is RadioFollowUp.Request.Stack -> r.setZigbeeStack(req.stack.label) { ok ->
                val why = r.helperStatusText().substringBefore(". v"); main.post { done(if (ok) null else why); refresh() }
            }
            is RadioFollowUp.Request.Act -> when (req.action) {
                RadioFollowUp.Action.ROUTER_JOIN -> r.routerJoin()
                RadioFollowUp.Action.ROUTER_LEAVE -> r.routerLeave()
                RadioFollowUp.Action.THREAD_LEAVE -> r.threadLeave()
                RadioFollowUp.Action.THREAD_JOIN_HA -> {
                    Log.i(MAIN_TAG, "Join Home Assistant's Thread network ($source)")
                    HaAuth.threadJoin.start()
                }
            }
        }
        main.postDelayed(refresh, 400)
    }

    /**
     * `Thread dataset` typed or pasted in the settings menu, the same path as HA's text entity: null when
     * the radio took it, else why not. Never logged.
     */
    internal fun setDataset(hex: String): String? {
        val r = readout.controller() ?: return NOT_RUNNING
        return r.setThreadDataset(hex)
    }

    // ------------------------------------------------------------------ settings

    /** The settings menu's Radio section: the modes as chips and the panel, then who may connect. */
    override fun settings(): List<SettingsSection> {
        val running = readout.running()
        val allow = SettingsItem.Text(
            "IP addresses or ranges, or any", "empty = Home Assistant only", SettingsItem.TextKind.ADDRESS,
            // Same setting as HA's "Radio allowed clients" (docs/radio.md "Firewall"): empty = Home Assistant only.
            value = readout::allowedClients,
            enabled = { running },
            shown = { it.ifBlank { "Home Assistant only" } },
            save = ::saveAllow,
        )
        val allowedNow = SettingsItem.Note { allowedNow(running, readout.allowedNow()) }
        val items = if (running) listOf(
            SettingsItem.Native(NATIVE_KEY) { host -> RadioSettingsView(host, this) },
            SettingsItem.Group("Who may connect", listOf(allow, allowedNow)),
        ) else listOf(allow, allowedNow) // nothing to drive without the controller: no picker or card
        return listOf(SettingsSection(id, "Radio", items, heading = "Zigbee / Thread radio"))
    }

    /** A new allow-list: the old list stays on an error; otherwise done once the radio thread handed it to the helper. */
    private fun saveAllow(text: String, done: (String?) -> Unit) {
        val r = readout.controller()
        val wanted = text.trim()
        if (r == null || wanted == r.allowedClients()) { done(null); return }
        val err = r.setAllowedClients(wanted)
        if (err != null) done("Radio allow-list not changed: $err")
        else r.afterPending { done(null) }
    }

    /** The app's: the controller [RadioPane] puts on the runtime while it runs. */
    class AppReadout(
        private val runtime: GlassworksRuntime,
        private val prefs: RadioPrefs,
        private val haBase: () -> String?,
    ) : RadioReadout {
        override fun controller() = runtime.radio
        override fun mode() = runtime.radio?.mode ?: RadioMode.parse(prefs.mode)

        /** Cached values: no getprop here. */
        override fun facts(): RadioFollowUp.Facts? {
            val r = runtime.radio ?: return null
            return RadioFollowUp.Facts(
                mode = r.mode, status = r.statusText(), helper = r.helperShown(), stack = r.zigbeeStack,
                threadStored = r.threadStored(), thread = r.threadRouterState(),
                haBase = haBase(), panelIp = panelIp(),
            )
        }

        override fun haBase() = haBase.invoke()
        override fun panelIp() = HaUrls.localIpv4()
    }

    companion object {
        private const val TAG = "S1Kiosk.SetupChannel"
        private const val MAIN_TAG = "S1Kiosk"
        internal const val NATIVE_KEY = "radio"
        internal const val NOT_RUNNING = "The kiosk service isn't running yet; try again in a moment."
        private const val CHANNEL_NOT_RUNNING = "The radio controller isn't running yet; try again in a moment."
        /** Wait for RadioController to take the request, then for it to report how it went (client waits 30 s). */
        private const val RADIO_ACCEPT_MS = 15_000L
        private const val RADIO_FOLLOW_MS = 10_000L
        private const val HELPER_CACHE_MS = 10_000L

        /** The line under the radio allow-list: who may connect right now. */
        fun allowedNow(radioRunning: Boolean, allowNow: String?): String = when {
            !radioRunning -> "Kiosk service not running"
            allowNow == null -> "Allowed now: no firewall active (radio off or old root helper)"
            allowNow.isEmpty() -> "Allowed now: only the panel itself (Home Assistant hasn't connected yet)"
            else -> "Allowed now: ${allowNow.replace(" ", ", ")}"
        }

        /** The modes onboarding offers, as the setup channel lists them (the panel's radio step, same texts). */
        fun channelOptions(): JSONArray = JSONArray().apply {
            RadioStep.OPTIONS.forEach { o ->
                put(JSONObject().apply {
                    put("id", o.mode.id)
                    put("label", o.title)
                    put("detail", o.line)
                    // optional in channel.ts RadioOption: only sent when true
                    if (o.experimental) put("experimental", true)
                    if (o.repeater) put("repeater", true)
                })
            }
        }

        /**
         * channel.ts `SetupState.radio`. [allowed]: who the helper's firewall lets reach the radio's TCP
         * services (EZSP :6638, OTBR REST :8081): "any", "" (nobody but the panel yet: Home Assistant
         * hasn't connected), or space-separated addresses/CIDRs. Left out when unknown: Off, no helper,
         * or a helper without the firewall.
         */
        fun channelJson(mode: String, status: String, helper: Boolean, allowed: String? = null): JSONObject = JSONObject().apply {
            put("mode", mode)
            put("status", status)
            put("options", channelOptions())
            put("helper", helper)
            allowed?.let { put("allowed", it) }
        }
    }
}
