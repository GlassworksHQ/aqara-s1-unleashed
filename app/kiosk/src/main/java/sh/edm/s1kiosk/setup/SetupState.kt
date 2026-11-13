package sh.edm.s1kiosk.setup

import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.SetupStep

/**
 * Decides whether the first-boot setup screen is shown and which step it is on (pure JVM, see
 * SetupStateTest). docs/onboarding.md, step 4.
 *
 * Two separate questions:
 * - **Is the panel "not set up"?** ([SetupDecision.show]) Only strong evidence counts, so the screen
 *   never covers a panel that already works (the desk panel: Wi-Fi, an ESPHome connection from HA,
 *   a saved dashboard URL and a cookie-based HA session without a refresh token):
 *   1. no network for [SetupRules.offlineGraceMs] (boot takes a few seconds to join Wi-Fi);
 *   2. Home Assistant isn't known at all (no saved dashboard URL, nothing discovered);
 *   3. Home Assistant never added the panel: no HA client connected within
 *      [SetupRules.haGraceMs] of startup *and* nothing was ever recorded (no `ha_seen`, no API key,
 *      not skipped by the user);
 *   4. no HA session: no refresh token *and* the page actually landed on HA's login page (or the
 *      user signed out on this panel), unless "Sign in on this panel instead" was chosen;
 *   5. a pane's step isn't done (online, HA step done), e.g. the radio mode was never chosen; the
 *      person answers it under Panes;
 *   and, without the grace period, (1) while Improv (Wi-Fi over Bluetooth) is running or Wi-Fi
 *   details came from the computer: Improv's Allow prompt and the join's progress live in the
 *   Wi-Fi step.
 *   Once shown, it stays until every step is done (no flicker between steps).
 * - **Which step is current while it's shown?** The first one that isn't done, in order Wi-Fi →
 *   Home Assistant (add it, sign in) → Panes (choose, then each step of a pane that is on) → Done.
 *   Done means: online; HA has subscribed to the panel's states (it added the panel) or the user
 *   skipped it; signed in, or the dashboard loaded without asking; Continue on the Panes step (or
 *   nothing to ask); the pane's step says so.
 *
 * Home Assistant comes first, adding and signing in together (#63 round 2): a pane's step can then use
 * the sign-in, e.g. the radio joins Home Assistant's Thread network. Nothing in Panes is needed to sign in.
 */
object SetupFlow {
    const val WIFI = "wifi"
    /** Home Assistant's first sub-step: add the panel. */
    const val HA = "ha"
    /** Home Assistant's second sub-step: sign in. */
    const val SIGNIN = "signin"
    /** Panes' first sub-step: which panes are on (one card per PaneDefinition.setupChoice). */
    const val PANES = "panes"
    const val DONE = "done"

    /** The rail's groups: a step id to the group it sits under, null for a step of its own. */
    fun group(id: String): String? = when (id) {
        WIFI, DONE -> null
        HA, SIGNIN -> HA
        else -> PANES
    }

    /**
     * Every step in order. [panes]: some pane has a card, so the Panes group is there; [paneSteps]: the
     * ids of the steps of the panes that are on, which follow the Panes step in its group.
     */
    fun ids(panes: Boolean, paneSteps: List<String> = emptyList()): List<String> =
        listOf(WIFI, HA, SIGNIN) + (if (panes) listOf(PANES) + paneSteps else emptyList()) + DONE

    private val CORE = setOf(WIFI, HA, PANES, SIGNIN, DONE)

    /**
     * The panes' [steps] that setup can take, in order. A step is left out, with why in [skipped], when
     * its id is a core step's or a key of the setup channel's state (its card would never show, its
     * state would overwrite the core's), or an earlier step's, or when one of its request names is
     * already taken: the core's, or an earlier step's. A bad step costs only itself: it never stops the
     * setup screen or the setup channel.
     */
    fun usable(steps: List<SetupStep>, skipped: (String) -> Unit): List<SetupStep> {
        val ids = HashSet(CORE + SetupChannel.CORE_KEYS)
        val names = HashSet(SetupChannel.CORE_OPS)
        return steps.filter { s ->
            val own = s.ops.keys.map { "${s.id}.$it" } + s.legacyOps.keys
            val clash = own.filter { it in names || own.count { o -> o == it } > 1 }.distinct()
            val why = when {
                s.id in ids -> "its id is a core step ($CORE), a setup-channel state key (${SetupChannel.CORE_KEYS}) or an earlier step's"
                clash.isNotEmpty() -> "request names already taken: $clash"
                else -> null
            }
            if (why != null) skipped("Setup step '${s.id}' left out: $why") else { ids += s.id; names += own }
            why == null
        }
    }

    /** A step id from `--es setup_step <id>` (a pane's step too, e.g. `radio`), or null. */
    fun parse(s: String?, panes: Boolean, paneSteps: List<String> = emptyList()): String? {
        val t = s?.trim() ?: return null
        return ids(panes, paneSteps).firstOrNull { it.equals(t, ignoreCase = true) }
    }
}

/** A pane on setup's Panes step: its card, and the steps it adds under Panes while the card is on. */
class SetupPane(val choice: PaneChoice, val steps: List<SetupStep>)

/** A pane's setup step as [SetupState] sees it. */
data class PaneStepFacts(
    val id: String,
    val done: Boolean,
    /** Why the screen shows while it isn't done, for the log. */
    val reason: String,
    /** Its pane is on (as chosen on the Panes step, or as it runs): only then is it a sub-step. */
    val wanted: Boolean = true,
)

data class SetupInputs(
    /** A network with internet/LAN connectivity is up. */
    val online: Boolean,
    /** How long the panel has been offline (0 when online). */
    val offlineForMs: Long,
    /** Home Assistant's base URL is known (saved dashboard URL, discovery, or manual entry). */
    val haKnown: Boolean,
    /** Persisted: an HA client subscribed to the panel's states at some point (`ha_seen`). */
    val haSeen: Boolean,
    /** An ESPHome API key is set (HA provisioned one, or it was set by hand). */
    val apiKeySet: Boolean,
    /** Persisted: the user chose "Skip" on the Add-to-HA step. */
    val haSkipped: Boolean,
    /** A client identifying as Home Assistant said Hello in this process (also HA's discovery probe). */
    val haHelloThisProcess: Boolean,
    /** Time since the app process started. */
    val uptimeMs: Long,
    /** A refresh token from the QR sign-in is stored. */
    val signedIn: Boolean,
    /** The GeckoView is on HA's `/auth/authorize` page. */
    val onAuthPage: Boolean,
    /** The user signed out on this panel (this process) and hasn't signed in again. */
    val signedOut: Boolean,
    /** "Sign in on this panel instead" was chosen (this process). */
    val usePanelLogin: Boolean,
    /** The setup screen is already up (not a preview): it stays until every step is done. */
    val showing: Boolean = false,
    /** The page shows Home Assistant without asking to sign in (a session it already has). */
    val dashboardLoaded: Boolean = false,
    /** Continue on the Panes step, in this process. */
    val panesChosen: Boolean = false,
    /** The panes' steps, in order. */
    val paneSteps: List<PaneStepFacts> = emptyList(),
    /** Improv (Wi-Fi over Bluetooth), for the Wi-Fi step. */
    val improv: ImprovFacts = ImprovFacts(),
    /** No trace of an earlier setup at all (see [SetupState.neverSetUp]): a fresh panel. */
    val neverSetUp: Boolean = false,
    /** A Wi-Fi join sent from the computer (web installer) in this process, if any. */
    val installer: InstallerWifi? = null,
)

data class SetupRules(
    val offlineGraceMs: Long = 30_000,
    val haGraceMs: Long = 120_000,
)

data class SetupDecision(
    val show: Boolean,
    /** The current step's id while shown ([SetupFlow.DONE] when nothing is left). */
    val step: String,
    val wifiDone: Boolean,
    val haDone: Boolean,
    val signinDone: Boolean,
    /** Why it is shown (for the log), empty when hidden. */
    val reasons: List<String>,
    /** Improv's status block for the Wi-Fi step. */
    val improv: WifiStep.ImprovLine = WifiStep.improv(ImprovFacts()),
    /** The Panes step is answered (or there was nothing to ask). */
    val panesDone: Boolean = true,
    /** The ids of the panes' steps that are done. */
    val paneDone: Set<String> = emptySet(),
) {
    /** Every step that is done, by id ([SetupFlow.DONE] never is: it's where the screen ends). */
    val done: Set<String>
        get() = paneDone + listOfNotNull(
            SetupFlow.WIFI.takeIf { wifiDone }, SetupFlow.HA.takeIf { haDone },
            SetupFlow.SIGNIN.takeIf { signinDone }, SetupFlow.PANES.takeIf { panesDone },
        )
}

object SetupState {

    fun decide(i: SetupInputs, rules: SetupRules = SetupRules()): SetupDecision {
        val wantsSignin = !i.signedIn && !i.usePanelLogin && (i.onAuthPage || i.signedOut)
        val haEverRecorded = i.haSeen || i.apiKeySet || i.haSkipped

        val wifiDone = i.online
        val haDone = i.haKnown && haEverRecorded
        val pending = i.paneSteps.firstOrNull { !it.done }
        val panesDone = i.panesChosen || pending == null
        val pendingWanted = i.paneSteps.firstOrNull { it.wanted && !it.done }

        val reasons = ArrayList<String>(4)
        // Improv's Allow prompt and the installer's join progress live in the Wi-Fi step: no grace then.
        val wifiSetupActive = i.improv.running || (i.installer != null && i.installer.phase != InstallerWifi.Phase.CONNECTED)
        // A fresh panel has no network to wait for: show setup at once instead of a "waiting" screen.
        if (!i.online && (i.offlineForMs >= rules.offlineGraceMs || wifiSetupActive || i.neverSetUp)) reasons += "offline"
        if (i.online && !i.haKnown) reasons += "home assistant not found"
        if (i.online && i.haKnown && !haEverRecorded && !i.haHelloThisProcess && i.uptimeMs >= rules.haGraceMs) {
            reasons += "not added to home assistant"
        }
        if (i.online && i.haKnown && wantsSignin) reasons += "sign-in needed"
        // Before Continue on Panes every pending step asks; after it, only those of panes that are on.
        val asking = if (i.panesChosen) pendingWanted else pending
        if (wifiDone && haDone && asking != null) reasons += asking.reason

        // Only "done" once it's actually known: a stored sign-in, or Wi-Fi + HA are done and nothing asks
        // for one. While a pane's step still waits, "not asked yet" isn't enough: Panes follows Sign in,
        // so the screen would move on, then come back when HA's login page loads. It waits for the
        // dashboard to load without asking (a session it already has) or the panel's own login page.
        val panesWaiting = !panesDone || pendingWanted != null
        val signinDone = i.signedIn ||
            (wifiDone && haDone && !wantsSignin && (!panesWaiting || i.dashboardLoaded || i.usePanelLogin))
        val step = when {
            !wifiDone -> SetupFlow.WIFI
            !haDone -> SetupFlow.HA
            !signinDone -> SetupFlow.SIGNIN
            !panesDone -> SetupFlow.PANES
            pendingWanted != null -> pendingWanted.id
            else -> SetupFlow.DONE
        }
        // Once up, the screen stays until everything is done, so it doesn't flicker away between
        // steps (e.g. Wi-Fi joined, HA not discovered yet). It can't start on its own though.
        val show = reasons.isNotEmpty() || (i.showing && step != SetupFlow.DONE)
        val paneDone = i.paneSteps.filter { it.done }.map { it.id }.toSet()
        return SetupDecision(show, step, wifiDone, haDone, signinDone, reasons, WifiStep.improv(i.improv), panesDone, paneDone)
    }

    /**
     * True when nothing shows this panel was ever set up: no Home Assistant known or recorded, no API
     * key, no skipped or answered steps, no sign-in. Such a panel skips the offline grace period.
     */
    fun neverSetUp(
        haKnown: Boolean, haSeen: Boolean, apiKeySet: Boolean, haSkipped: Boolean, paneStepDone: Boolean, signedIn: Boolean,
    ): Boolean = !(haKnown || haSeen || apiKeySet || haSkipped || paneStepDone || signedIn)

    /**
     * Any trace of an earlier setup: a known Home Assistant (saved dashboard URL or discovered), HA
     * added the panel or provisioned an API key, or the HA step was skipped. A step added in a later
     * build is marked done on a panel like that, so it never sees it. A fresh install has none of these.
     */
    fun setUpBefore(haKnown: Boolean, haSeen: Boolean, apiKeySet: Boolean, haSkipped: Boolean): Boolean =
        haKnown || haSeen || apiKeySet || haSkipped
}

/**
 * The setup screen holds Panes while Continue applies or after a refusal (SetupOverlay.holdsPanes), but
 * never over a lost Wi-Fi: Panes has no way to fix that, so the Wi-Fi step comes first, and Panes again
 * once the panel is back on.
 */
fun SetupDecision.holdingPanes(held: Boolean): SetupDecision =
    if (held && wifiDone) copy(show = true, step = SetupFlow.PANES) else this
