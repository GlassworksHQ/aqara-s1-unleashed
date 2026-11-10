package sh.edm.s1kiosk.setup

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
 *   5. the radio mode was never chosen on this panel (online, HA step done). A panel that was set up
 *      before the radio step existed has it marked done once ([radioStepDoneOnUpgrade]), so it
 *      never sees it;
 *   and, without the grace period, (1) while Improv (Wi-Fi over Bluetooth) is running or Wi-Fi
 *   details came from the computer: Improv's Allow prompt and the join's progress live in the
 *   Wi-Fi step.
 *   Once shown, it stays until every step is done (no flicker between steps).
 * - **Which step is current while it's shown?** The first one that isn't done, in order Wi-Fi →
 *   Add to HA → Radio → Sign in. Done means: online; HA has subscribed to the panel's states (it
 *   added the panel) or the user skipped it; a radio mode was applied or the step skipped; signed in
 *   or not needed.
 *
 * Radio comes before Sign in: it belongs with adding the panel (Zigbee coordinator means adding ZHA
 * in the same Devices & services page), and signing in is what loads the dashboard, so it stays the
 * hand-off at the end instead of being interrupted by one more question.
 */
enum class SetupStep(val id: String) {
    WIFI("wifi"), HA("ha"), RADIO("radio"), SIGNIN("signin"), DONE("done");

    companion object {
        fun parse(s: String?): SetupStep? = entries.firstOrNull { it.id.equals(s?.trim(), ignoreCase = true) }
    }
}

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
    /** Persisted: the radio step was answered (or the panel was set up before the step existed). */
    val radioStepDone: Boolean = false,
    /** A radio mode is stored (chosen with HA's `Radio mode` select): the question is answered. */
    val radioModeStored: Boolean = false,
    /** Improv (Wi-Fi over Bluetooth), for the Wi-Fi step. */
    val improv: ImprovFacts = ImprovFacts(),
    /** A Wi-Fi join sent from the computer (web installer) in this process, if any. */
    val installer: InstallerWifi? = null,
)

data class SetupRules(
    val offlineGraceMs: Long = 30_000,
    val haGraceMs: Long = 120_000,
)

data class SetupDecision(
    val show: Boolean,
    /** The current step while shown ([SetupStep.DONE] when nothing is left). */
    val step: SetupStep,
    val wifiDone: Boolean,
    val haDone: Boolean,
    val signinDone: Boolean,
    /** Why it is shown (for the log), empty when hidden. */
    val reasons: List<String>,
    val radioDone: Boolean = true,
    /** Improv's status block for the Wi-Fi step. */
    val improv: WifiStep.ImprovLine = WifiStep.improv(ImprovFacts()),
)

object SetupState {

    fun decide(i: SetupInputs, rules: SetupRules = SetupRules()): SetupDecision {
        val wantsSignin = !i.signedIn && !i.usePanelLogin && (i.onAuthPage || i.signedOut)
        val haEverRecorded = i.haSeen || i.apiKeySet || i.haSkipped

        val wifiDone = i.online
        val haDone = i.haKnown && haEverRecorded
        val radioDone = i.radioStepDone || i.radioModeStored

        val reasons = ArrayList<String>(4)
        // Improv's Allow prompt and the installer's join progress live in the Wi-Fi step: no grace then.
        val wifiSetupActive = i.improv.running || (i.installer != null && i.installer.phase != InstallerWifi.Phase.CONNECTED)
        if (!i.online && (i.offlineForMs >= rules.offlineGraceMs || wifiSetupActive)) reasons += "offline"
        if (i.online && !i.haKnown) reasons += "home assistant not found"
        if (i.online && i.haKnown && !haEverRecorded && !i.haHelloThisProcess && i.uptimeMs >= rules.haGraceMs) {
            reasons += "not added to home assistant"
        }
        if (i.online && i.haKnown && wantsSignin) reasons += "sign-in needed"
        if (wifiDone && haDone && !radioDone) reasons += "radio mode not chosen"

        // Only "done" once it's actually known: a stored sign-in, or Wi-Fi + HA are done and nothing
        // asks for one. (Before, a fresh offline panel showed sign-in as complete.)
        val signinDone = i.signedIn || (wifiDone && haDone && !wantsSignin)
        val step = when {
            !wifiDone -> SetupStep.WIFI
            !i.haKnown -> SetupStep.HA
            !haDone -> SetupStep.HA
            !radioDone -> SetupStep.RADIO
            !signinDone -> SetupStep.SIGNIN
            else -> SetupStep.DONE
        }
        // Once up, the screen stays until everything is done, so it doesn't flicker away between
        // steps (e.g. Wi-Fi joined, HA not discovered yet). It can't start on its own though.
        val show = reasons.isNotEmpty() || (i.showing && step != SetupStep.DONE)
        return SetupDecision(show, step, wifiDone, haDone, signinDone, reasons, radioDone, WifiStep.improv(i.improv))
    }

    /**
     * Run once when a build with the radio step first starts (KioskPrefs.migrateRadioStep): true =
     * the panel was already set up, so mark the step done and never show it. Any trace of an earlier
     * setup counts: a known Home Assistant (saved dashboard URL or discovered), HA added the panel or
     * provisioned an API key, the HA step was skipped, or a radio mode was chosen in HA. A fresh
     * install has none of these at its first start.
     */
    fun radioStepDoneOnUpgrade(
        haKnown: Boolean, haSeen: Boolean, apiKeySet: Boolean, haSkipped: Boolean, radioModeStored: Boolean,
    ): Boolean = haKnown || haSeen || apiKeySet || haSkipped || radioModeStored
}
