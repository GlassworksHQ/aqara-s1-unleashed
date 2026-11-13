package sh.edm.s1kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {

    private val min = 60_000L

    /** The S1's one pane step, the radio. */
    private fun radio(done: Boolean) = listOf(PaneStepFacts("radio", done, "radio mode not chosen"))

    /**
     * The already-configured desk panel: Wi-Fi, HA connects over ESPHome, a saved dashboard URL
     * (so HA is known), a cookie session (no refresh token, never on the login page). Its radio step
     * was marked done by the upgrade migration (the saved URL alone is enough, see radioUpgrade*).
     */
    private fun deskPanel(uptimeMs: Long, haSeen: Boolean, apiKey: Boolean, hello: Boolean) = SetupInputs(
        online = true, offlineForMs = 0, haKnown = true, haSeen = haSeen, apiKeySet = apiKey, haSkipped = false,
        haHelloThisProcess = hello, uptimeMs = uptimeMs, signedIn = false, onAuthPage = false,
        signedOut = false, usePanelLogin = false,
        paneSteps = radio(SetupState.setUpBefore(haKnown = true, haSeen = haSeen, apiKeySet = apiKey, haSkipped = false)),
    )

    private val fresh = SetupInputs(
        online = true, offlineForMs = 0, haKnown = false, haSeen = false, apiKeySet = false, haSkipped = false,
        haHelloThisProcess = false, uptimeMs = 5_000, signedIn = false, onAuthPage = false,
        signedOut = false, usePanelLogin = false, paneSteps = radio(false),
    )

    @Test
    fun aHeldPanesStepGivesWayToALostWifi() {
        // CodeRabbit on #160: after a refusal Panes held the screen, also with Wi-Fi gone, and Panes can't fix Wi-Fi.
        val online = SetupState.decide(fresh.copy(haKnown = true, haSeen = true, signedIn = true))
        assertEquals(SetupFlow.PANES, online.holdingPanes(true).step)
        val offline = SetupState.decide(fresh.copy(online = false, offlineForMs = 60_000, neverSetUp = true))
        assertEquals(SetupFlow.WIFI, offline.holdingPanes(true).step)
        assertEquals(online, online.holdingPanes(false))
    }

    @Test
    fun freshOfflinePanelShowsSetupAtOnce() {
        // First boot after the installer: nothing set up, no Wi-Fi yet. No 30 s "waiting" screen.
        val first = fresh.copy(online = false, offlineForMs = 0, uptimeMs = 1_000, neverSetUp = true)
        val d = SetupState.decide(first)
        assertTrue(d.reasons.toString(), d.show)
        assertEquals(SetupFlow.WIFI, d.step)
    }

    @Test
    fun neverSetUpIsFalseForAnyTraceOfSetup() {
        assertTrue(SetupState.neverSetUp(false, false, false, false, false, false))
        val traces = listOf(
            SetupState.neverSetUp(true, false, false, false, false, false),
            SetupState.neverSetUp(false, true, false, false, false, false),
            SetupState.neverSetUp(false, false, true, false, false, false),
            SetupState.neverSetUp(false, false, false, true, false, false),
            SetupState.neverSetUp(false, false, false, false, true, false),
            SetupState.neverSetUp(false, false, false, false, false, true),
        )
        assertTrue(traces.none { it })
    }

    @Test
    fun deskPanelNeverShowsSetup() {
        // Every combination the desk panel can be in after an update to this build: just booted,
        // before HA reconnects, before ha_seen was ever written, with or without the old API key.
        for (uptime in listOf(0L, 10_000L, 2 * min - 1, 2 * min, 30 * min))
            for (seen in listOf(false, true))
                for (key in listOf(false, true))
                    for (hello in listOf(false, true)) {
                        // haSeen=false, key=false, hello=false after 2 min is "HA never connected": not the desk panel.
                        if (!seen && !key && !hello && uptime >= 2 * min) continue
                        val d = SetupState.decide(deskPanel(uptime, seen, key, hello))
                        assertFalse("uptime=$uptime seen=$seen key=$key hello=$hello: ${d.reasons}", d.show)
                    }
    }

    @Test
    fun deskPanelOnceHaSeenStaysHiddenEvenWhenHaIsDown() {
        val d = SetupState.decide(deskPanel(60 * min, haSeen = true, apiKey = false, hello = false))
        assertFalse(d.show)
        assertEquals(SetupFlow.DONE, d.step)
    }

    @Test
    fun deskPanelShortWifiDropoutIsIgnored() {
        val i = deskPanel(10 * min, haSeen = true, apiKey = true, hello = true).copy(online = false, offlineForMs = 20_000)
        assertFalse(SetupState.decide(i).show)
        val long = i.copy(offlineForMs = 31_000)
        val d = SetupState.decide(long)
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        // Back online: the sticky screen is done and goes away.
        val back = SetupState.decide(i.copy(online = true, offlineForMs = 0, showing = true))
        assertFalse(back.show)
        assertEquals(SetupFlow.DONE, back.step)
    }

    @Test
    fun cookieSessionWithoutRefreshTokenIsNotASigninTrigger() {
        val d = SetupState.decide(deskPanel(5 * min, haSeen = true, apiKey = true, hello = true))
        assertTrue(d.signinDone)
        assertFalse(d.show)
    }

    @Test
    fun freshOfflinePanelDoesNotShowSigninAsDone() {
        // Factory-reset panel, no Wi-Fi yet: nothing is known about sign-in, so it must not be ticked.
        val d = SetupState.decide(fresh.copy(online = false, offlineForMs = 60_000))
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        assertFalse(d.wifiDone)
        assertFalse(d.haDone)
        assertFalse(d.signinDone)
    }

    @Test
    fun loginPageShowsSignin() {
        val i = deskPanel(5 * min, haSeen = true, apiKey = true, hello = true).copy(onAuthPage = true)
        val d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertEquals(listOf("sign-in needed"), d.reasons)
        // "Sign in on this panel instead": leave HA's login page alone.
        assertFalse(SetupState.decide(i.copy(usePanelLogin = true)).show)
        // A refresh token means it isn't needed.
        assertFalse(SetupState.decide(i.copy(signedIn = true)).show)
    }

    @Test
    fun signOutOnThePanelShowsSignin() {
        val i = deskPanel(5 * min, haSeen = true, apiKey = true, hello = true).copy(signedOut = true)
        assertEquals(SetupFlow.SIGNIN, SetupState.decide(i).step)
        assertTrue(SetupState.decide(i).show)
    }

    @Test
    fun freshPanelOffline() {
        val boot = fresh.copy(online = false, offlineForMs = 10_000)
        assertFalse("boot grace", SetupState.decide(boot).show)
        val d = SetupState.decide(boot.copy(offlineForMs = 45_000))
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        assertFalse(d.wifiDone)
    }

    @Test
    fun freshPanelWalksThroughTheSteps() {
        // Online, HA not found yet: HA step with the manual URL field.
        var i = fresh
        var d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.HA, d.step)
        assertTrue(d.wifiDone)

        // HA found by mDNS; not added yet, within the 2 min grace: the trigger is gone but the
        // screen is already up, so it stays (no flicker) on "Add to Home Assistant".
        i = i.copy(haKnown = true, showing = true)
        d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.HA, d.step)
        assertTrue(d.reasons.isEmpty())

        // HA's discovery probe says Hello: not "added" yet.
        i = i.copy(haHelloThisProcess = true, onAuthPage = true)
        d = SetupState.decide(i)
        assertEquals(SetupFlow.HA, d.step)
        assertFalse(d.haDone)

        // HA added the panel (subscribed): on to signing in, the other half of Home Assistant.
        i = i.copy(haSeen = true)
        d = SetupState.decide(i)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertTrue(d.haDone)
        assertTrue(d.show)

        // Signed in with the QR code: on to Panes, which panes are on.
        i = i.copy(signedIn = true, onAuthPage = false)
        d = SetupState.decide(i)
        assertEquals(SetupFlow.PANES, d.step)
        assertFalse(SetupFlow.PANES in d.done)

        // Continue with the radio on: its own step under Panes.
        i = i.copy(panesChosen = true)
        d = SetupState.decide(i)
        assertEquals("radio", d.step)
        assertTrue(SetupFlow.PANES in d.done)

        // The radio's mode applied and its follow-up seen: done, the screen goes.
        i = i.copy(paneSteps = radio(true))
        d = SetupState.decide(i)
        assertEquals(SetupFlow.DONE, d.step)
        assertFalse(d.show)
    }

    @Test
    fun notAddedAfterGraceShowsAddToHa() {
        val i = fresh.copy(haKnown = true)
        assertFalse(SetupState.decide(i.copy(uptimeMs = 119_000)).show)
        val d = SetupState.decide(i.copy(uptimeMs = 121_000))
        assertTrue(d.show)
        assertEquals(SetupFlow.HA, d.step)
        assertEquals(listOf("not added to home assistant"), d.reasons)
        // Any recorded evidence suppresses it (a panel with its radio step done).
        val r = i.copy(paneSteps = radio(true))
        assertFalse(SetupState.decide(r.copy(uptimeMs = 121_000, apiKeySet = true)).show)
        assertFalse(SetupState.decide(r.copy(uptimeMs = 121_000, haSeen = true)).show)
        assertFalse(SetupState.decide(r.copy(uptimeMs = 121_000, haHelloThisProcess = true)).show)
        // Without it, the HA step being done moves on to signing in (a pane's step still waits).
        assertEquals(SetupFlow.SIGNIN, SetupState.decide(i.copy(uptimeMs = 121_000, haSeen = true)).step)
    }

    @Test
    fun skipCompletesTheHaStep() {
        val i = fresh.copy(haKnown = true, uptimeMs = 5 * min, showing = true, haSkipped = true)
        val d = SetupState.decide(i)
        assertTrue(d.haDone)
        assertEquals(SetupFlow.SIGNIN, d.step) // a pane's step waits: sign-in must be known first
        assertEquals(SetupFlow.PANES, SetupState.decide(i.copy(dashboardLoaded = true)).step)
        assertTrue(d.show)
        val r = i.copy(paneSteps = radio(true))
        assertFalse(SetupState.decide(r).show)
        assertEquals(SetupFlow.SIGNIN, SetupState.decide(r.copy(onAuthPage = true)).step)
    }

    @Test
    fun stepParsing() {
        val steps = listOf("radio")
        assertEquals(SetupFlow.WIFI, SetupFlow.parse("wifi", true, steps))
        assertEquals(SetupFlow.HA, SetupFlow.parse(" HA ", true, steps))
        assertEquals(SetupFlow.SIGNIN, SetupFlow.parse("signin", true, steps))
        assertEquals(SetupFlow.PANES, SetupFlow.parse("panes", true, steps))
        assertEquals("radio", SetupFlow.parse("radio", true, steps)) // a pane's own step
        // Step order: the rail and the preview rely on it. Home Assistant (add, sign in), then Panes
        // (choose, then the panes' steps).
        assertEquals(listOf("wifi", "ha", "signin", "panes", "radio", "done"), SetupFlow.ids(true, steps))
        assertEquals(listOf("wifi", "ha", "signin", "panes", "done"), SetupFlow.ids(true))
        assertEquals(listOf("wifi", "ha", "signin", "done"), SetupFlow.ids(false))
        assertEquals(SetupFlow.DONE, SetupFlow.parse("done", true, steps))
        assertEquals(null, SetupFlow.parse("radio", false, steps))
        assertEquals(null, SetupFlow.parse("panes", false))
        assertEquals(null, SetupFlow.parse("nope", true, steps))
        assertEquals(null, SetupFlow.parse(null, true, steps))
        // The rail's groups.
        assertEquals(listOf(null, "ha", "ha", "panes", "panes", null), SetupFlow.ids(true, steps).map(SetupFlow::group))
    }

    @Test
    fun paneStepsComeUnderPanesAfterSignin() {
        val two = listOf(PaneStepFacts("radio", false, "radio mode not chosen"), PaneStepFacts("camera", false, "camera not set up"))
        val i = fresh.copy(haKnown = true, haSeen = true, paneSteps = two, onAuthPage = true)
        var d = SetupState.decide(i)
        assertEquals(SetupFlow.SIGNIN, d.step)
        assertEquals(listOf("sign-in needed", "radio mode not chosen"), d.reasons)
        val signed = i.copy(signedIn = true, onAuthPage = false)
        assertEquals(SetupFlow.PANES, SetupState.decide(signed).step) // which panes are on, first
        val chosen = signed.copy(panesChosen = true)
        assertEquals("radio", SetupState.decide(chosen).step)
        d = SetupState.decide(chosen.copy(paneSteps = listOf(two[0].copy(done = true), two[1])))
        assertEquals("camera", d.step)
        assertEquals(setOf("wifi", "ha", "signin", "panes", "radio"), d.done)
        assertEquals(listOf("camera not set up"), d.reasons)
        // A pane turned off on Panes: its steps don't ask any more.
        d = SetupState.decide(chosen.copy(paneSteps = listOf(two[0].copy(done = true), two[1].copy(wanted = false)), showing = true))
        assertEquals(SetupFlow.DONE, d.step)
        assertFalse(d.show)
        // A frame with no pane steps: Wi-Fi, Home Assistant, done.
        assertEquals(SetupFlow.DONE, SetupState.decide(signed.copy(paneSteps = emptyList())).step)
    }

    // ---- radio step

    @Test
    fun anyTraceOfAnEarlierSetupCounts() {
        fun up(known: Boolean = false, seen: Boolean = false, key: Boolean = false, skipped: Boolean = false) =
            SetupState.setUpBefore(known, seen, key, skipped)
        assertFalse("fresh install", up())
        assertTrue(up(known = true))
        assertTrue(up(seen = true))
        assertTrue(up(key = true))
        assertTrue(up(skipped = true))
    }

    @Test
    fun setUpPanelNeverSeesTheRadioStep() {
        // Desk panel after the upgrade, every trigger it can hit: a long Wi-Fi dropout, a sign-out.
        val desk = deskPanel(10 * min, haSeen = true, apiKey = true, hello = true)
        assertTrue(desk.paneSteps.single().done)
        assertFalse(SetupState.decide(desk).show)
        val dropout = SetupState.decide(desk.copy(online = false, offlineForMs = 60_000))
        assertEquals(SetupFlow.WIFI, dropout.step)
        val back = SetupState.decide(desk.copy(showing = true))
        assertFalse(back.show)
        assertEquals(SetupFlow.DONE, back.step)
        val signout = SetupState.decide(desk.copy(signedOut = true, showing = true))
        assertEquals(SetupFlow.SIGNIN, signout.step)
        assertTrue(SetupFlow.PANES in signout.done)
    }

    @Test
    fun unansweredRadioStepComesBackAfterAReboot() {
        // Fresh panel rebooted on the radio step: HA added it, nothing chosen yet, screen not up.
        val i = fresh.copy(haKnown = true, haSeen = true, uptimeMs = 3_000, dashboardLoaded = true)
        val d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupFlow.PANES, d.step)
        assertEquals(listOf("radio mode not chosen"), d.reasons)
        // Answered (persisted, or a mode chosen with HA's select: RadioDefinitionTest): gone for good.
        assertFalse(SetupState.decide(i.copy(paneSteps = radio(true))).show)
        // A mode chosen with HA's select also answers it.
    }

    @Test
    fun radioStepNeedsWifiAndHaFirst() {
        val i = fresh.copy(online = false, offlineForMs = 60_000, haKnown = true, haSeen = true)
        assertEquals(SetupFlow.WIFI, SetupState.decide(i).step)
        assertFalse("not a reason while offline", "radio mode not chosen" in SetupState.decide(i).reasons)
        assertEquals(SetupFlow.HA, SetupState.decide(fresh.copy(haKnown = true, showing = true)).step)
    }

    // ---- Improv / installer Wi-Fi

    @Test
    fun improvRunningShowsTheWifiStepWithoutTheGrace() {
        val boot = fresh.copy(online = false, offlineForMs = 5_000)
        assertFalse(SetupState.decide(boot).show)
        val improv = ImprovFacts(running = true, state = sh.edm.improv.ImprovState.AUTHORIZATION_REQUIRED)
        val d = SetupState.decide(boot.copy(improv = improv))
        assertTrue(d.show)
        assertEquals(SetupFlow.WIFI, d.step)
        assertEquals(WifiStep.Phase.STARTING, d.improv.phase)
        assertEquals(WifiStep.Phase.READY, SetupState.decide(boot.copy(improv = improv.copy(advertising = true))).improv.phase)
        // Installer Wi-Fi in flight: same.
        val inst = InstallerWifi("Home", InstallerWifi.Phase.CONNECTING)
        assertTrue(SetupState.decide(boot.copy(installer = inst)).show)
        // Online: Improv running on demand doesn't bring the screen up on a set-up panel.
        val desk = deskPanel(10 * min, haSeen = true, apiKey = true, hello = true)
        assertFalse(SetupState.decide(desk.copy(improv = improv.copy(advertising = true))).show)
    }
}
