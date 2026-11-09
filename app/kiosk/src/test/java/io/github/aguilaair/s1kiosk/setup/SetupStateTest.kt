package io.github.aguilaair.s1kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {

    private val min = 60_000L

    /**
     * The already-configured desk panel: Wi-Fi, HA connects over ESPHome, a saved dashboard URL
     * (so HA is known), a cookie session (no refresh token, never on the login page).
     */
    private fun deskPanel(uptimeMs: Long, haSeen: Boolean, apiKey: Boolean, hello: Boolean) = SetupInputs(
        online = true, offlineForMs = 0, haKnown = true, haSeen = haSeen, apiKeySet = apiKey, haSkipped = false,
        haHelloThisProcess = hello, uptimeMs = uptimeMs, signedIn = false, onAuthPage = false,
        signedOut = false, usePanelLogin = false,
    )

    private val fresh = SetupInputs(
        online = true, offlineForMs = 0, haKnown = false, haSeen = false, apiKeySet = false, haSkipped = false,
        haHelloThisProcess = false, uptimeMs = 5_000, signedIn = false, onAuthPage = false,
        signedOut = false, usePanelLogin = false,
    )

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
        assertEquals(SetupStep.DONE, d.step)
    }

    @Test
    fun deskPanelShortWifiDropoutIsIgnored() {
        val i = deskPanel(10 * min, haSeen = true, apiKey = true, hello = true).copy(online = false, offlineForMs = 20_000)
        assertFalse(SetupState.decide(i).show)
        val long = i.copy(offlineForMs = 31_000)
        val d = SetupState.decide(long)
        assertTrue(d.show)
        assertEquals(SetupStep.WIFI, d.step)
        // Back online: the sticky screen is done and goes away.
        val back = SetupState.decide(i.copy(online = true, offlineForMs = 0, showing = true))
        assertFalse(back.show)
        assertEquals(SetupStep.DONE, back.step)
    }

    @Test
    fun cookieSessionWithoutRefreshTokenIsNotASigninTrigger() {
        val d = SetupState.decide(deskPanel(5 * min, haSeen = true, apiKey = true, hello = true))
        assertTrue(d.signinDone)
        assertFalse(d.show)
    }

    @Test
    fun loginPageShowsSignin() {
        val i = deskPanel(5 * min, haSeen = true, apiKey = true, hello = true).copy(onAuthPage = true)
        val d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupStep.SIGNIN, d.step)
        assertEquals(listOf("sign-in needed"), d.reasons)
        // "Sign in on this panel instead": leave HA's login page alone.
        assertFalse(SetupState.decide(i.copy(usePanelLogin = true)).show)
        // A refresh token means it isn't needed.
        assertFalse(SetupState.decide(i.copy(signedIn = true)).show)
    }

    @Test
    fun signOutOnThePanelShowsSignin() {
        val i = deskPanel(5 * min, haSeen = true, apiKey = true, hello = true).copy(signedOut = true)
        assertEquals(SetupStep.SIGNIN, SetupState.decide(i).step)
        assertTrue(SetupState.decide(i).show)
    }

    @Test
    fun freshPanelOffline() {
        val boot = fresh.copy(online = false, offlineForMs = 10_000)
        assertFalse("boot grace", SetupState.decide(boot).show)
        val d = SetupState.decide(boot.copy(offlineForMs = 45_000))
        assertTrue(d.show)
        assertEquals(SetupStep.WIFI, d.step)
        assertFalse(d.wifiDone)
    }

    @Test
    fun freshPanelWalksThroughTheSteps() {
        // Online, HA not found yet: HA step with the manual URL field.
        var i = fresh
        var d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupStep.HA, d.step)
        assertTrue(d.wifiDone)

        // HA found by mDNS; not added yet, within the 2 min grace: the trigger is gone but the
        // screen is already up, so it stays (no flicker) on "Add to Home Assistant".
        i = i.copy(haKnown = true, showing = true)
        d = SetupState.decide(i)
        assertTrue(d.show)
        assertEquals(SetupStep.HA, d.step)
        assertTrue(d.reasons.isEmpty())

        // HA's discovery probe says Hello: not "added" yet.
        i = i.copy(haHelloThisProcess = true, onAuthPage = true)
        d = SetupState.decide(i)
        assertEquals(SetupStep.HA, d.step)
        assertFalse(d.haDone)

        // HA added the panel (subscribed): on to sign-in.
        i = i.copy(haSeen = true)
        d = SetupState.decide(i)
        assertEquals(SetupStep.SIGNIN, d.step)
        assertTrue(d.haDone)

        // Signed in with the QR code: done, the screen goes.
        i = i.copy(signedIn = true, onAuthPage = false)
        d = SetupState.decide(i)
        assertEquals(SetupStep.DONE, d.step)
        assertFalse(d.show)
    }

    @Test
    fun notAddedAfterGraceShowsAddToHa() {
        val i = fresh.copy(haKnown = true)
        assertFalse(SetupState.decide(i.copy(uptimeMs = 119_000)).show)
        val d = SetupState.decide(i.copy(uptimeMs = 121_000))
        assertTrue(d.show)
        assertEquals(SetupStep.HA, d.step)
        assertEquals(listOf("not added to home assistant"), d.reasons)
        // Any recorded evidence suppresses it.
        assertFalse(SetupState.decide(i.copy(uptimeMs = 121_000, apiKeySet = true)).show)
        assertFalse(SetupState.decide(i.copy(uptimeMs = 121_000, haSeen = true)).show)
        assertFalse(SetupState.decide(i.copy(uptimeMs = 121_000, haHelloThisProcess = true)).show)
    }

    @Test
    fun skipCompletesTheHaStep() {
        val i = fresh.copy(haKnown = true, uptimeMs = 5 * min, showing = true, haSkipped = true)
        val d = SetupState.decide(i)
        assertTrue(d.haDone)
        assertFalse(d.show)
        assertEquals(SetupStep.SIGNIN, SetupState.decide(i.copy(onAuthPage = true)).step)
    }

    @Test
    fun stepParsing() {
        assertEquals(SetupStep.WIFI, SetupStep.parse("wifi"))
        assertEquals(SetupStep.HA, SetupStep.parse(" HA "))
        assertEquals(SetupStep.SIGNIN, SetupStep.parse("signin"))
        assertEquals(SetupStep.DONE, SetupStep.parse("done"))
        assertEquals(null, SetupStep.parse("nope"))
        assertEquals(null, SetupStep.parse(null))
    }
}
