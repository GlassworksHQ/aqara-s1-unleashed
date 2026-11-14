/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.web.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Manage page's gate (#33): who gets in, how, and what a page from elsewhere can't make it do. */
class ManageServerTest {
    private val f = ManageFixture()

    // ------------------------------------------------------------------ the gate and the QR pages

    @Test fun anUnsignedBrowserGetsTheSignInPageAndComesBackWhereItWasGoing() {
        val b = f.Browser()
        val r = b.get("/log?level=error")
        assertEquals(200, r.status)
        val html = r.text()
        assertTrue(html.contains("Manage this panel."))
        assertTrue(html.contains("""name="next" value="/log?level=error""""))
        assertFalse("no Manage content before signing in", html.contains("Download diagnostics"))
        val cb = b.signInWithHa(next = "/log?level=error")
        assertTrue("the callback sends the browser on to /log", cb.text().contains("url=/log?level=error"))
        assertTrue(b.get("/log").text().contains("Download diagnostics"))
    }

    @Test fun theQrPagesWorkWithoutASessionAndKeepTheirPaths() {
        val b = f.Browser()
        for (p in listOf("/signin", "/signin/continue", "/callback", "/thread", "/thread/continue", "/thread/callback")) {
            assertTrue(b.get(p).text().contains("QR $p"))
        }
        assertEquals(6, f.qrSeen.size)
    }

    @Test fun beforeSetupTheRootIsTheQrSignInAfterItTheManagePage() {
        f.setUp = false
        assertTrue(f.Browser().get("/").text().contains("QR /"))
        f.setUp = true
        assertTrue(f.Browser().get("/").text().contains("Manage this panel."))
    }

    @Test fun aQrPageOffersTheWayBackToManageOnlyToASignedInBrowser() {
        val b = f.Browser()
        assertFalse(b.get("/signin").text().contains("href=\"/\""))
        b.signInWithHa()
        assertTrue(b.get("/signin").text().contains("""<a class="to" href="/">Manage this panel"""))
    }

    @Test fun beforeSetupABrowserSignedInWithTheCodeStillGetsTheOverview() {
        f.setUp = false
        val b = f.Browser()
        b.get("/log")
        b.post("/manage/code", mapOf("next" to "/"))
        assertEquals("/", b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code)).headers["Location"])
        assertTrue(b.get("/").text().contains("Running"))
        assertTrue("others still get the QR sign-in", f.Browser().get("/").text().contains("QR /"))
    }

    @Test fun aSignInStartedBeforeHasAddressChangedIsRefused() {
        val b = f.Browser()
        b.get("/")
        val loc = b.post("/manage/ha", mapOf("next" to "/")).headers["Location"]!!
        val state = HttpServer.parseQuery(loc.substringAfter('?'))["state"]
        f.haBase = "http://192.0.2.11:8123"
        val cb = b.get("/manage/callback?code=CODE&state=$state")
        assertEquals(409, cb.status)
        assertNull(b.cookies[ManageServer.SESSION])
        assertEquals("the token is still revoked", listOf("REFRESH"), f.revoked)
    }

    @Test fun aLinkFromAnotherSiteReloadsItselfSoTheStrictCookieComesAlong() {
        val b = f.Browser()
        b.signInWithHa()
        // HA's "Visit": a cross-site navigation, which leaves the SameSite=Strict cookie behind.
        val cookie = b.cookies.remove(ManageServer.SESSION)
        val r = b.get("/", mapOf("sec-fetch-site" to "cross-site"))
        assertTrue(r.text().contains("http-equiv=\"refresh\""))
        b.cookies[ManageServer.SESSION] = cookie!!
        assertTrue(b.get("/", mapOf("sec-fetch-site" to "same-origin")).text().contains("Running"))
    }

    @Test fun onlyThePanelsOwnNamesAreServed() {
        // DNS rebinding: a name that resolves here but isn't the panel's, a spoofed .local, another address.
        for (h in listOf("attacker.example:8099", "evil.local:8099", "panel:8099", "192.0.2.41:8099", "[fd00::41]:8099")) {
            assertEquals(h, 400, f.Browser(host = h).get("/").status)
        }
        for (h in listOf("192.0.2.40:8099", "glassworks-panel.local:8099", "[fd00::40]:8099", "192.0.2.40")) {
            assertEquals(h, 200, f.Browser(host = h).get("/").status)
        }
        val own = setOf("192.0.2.40")
        assertFalse(ManageServer.hostAllowed("192.0.2.40:80x", own))
        assertFalse(ManageServer.hostAllowed("192.0.2.40:8099:1", own))
        assertFalse(ManageServer.hostAllowed(null, own))
    }

    @Test fun afterSignInOnlyPathsOnThisPanel() {
        assertEquals("/", ManageServer.safeNext("//evil.example/"))
        assertEquals("/", ManageServer.safeNext("https://evil.example/"))
        assertEquals("/", ManageServer.safeNext("/\\evil.example"))
        assertEquals("/", ManageServer.safeNext("/manage/callback"))
        assertEquals("/radio", ManageServer.safeNext("/radio"))
    }

    // ------------------------------------------------------------------ sign in with Home Assistant

    @Test fun signingInWithHaAsksTheLocalHaWithPkceAndKeepsNoHaToken() {
        val b = f.Browser()
        b.get("/")
        val start = b.post("/manage/ha", mapOf("next" to "/", "remember" to "1"))
        assertEquals(303, start.status)
        val loc = start.headers["Location"]!!
        assertTrue(loc.startsWith("http://192.0.2.10:8123/auth/authorize?"))
        val q = HttpServer.parseQuery(loc.substringAfter('?'))
        assertEquals("http://192.0.2.40:8099/", q["client_id"])
        assertEquals("http://192.0.2.40:8099/manage/callback", q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        val cb = b.get("/manage/callback?code=CODE&state=${q["state"]}")
        assertEquals(200, cb.status)
        assertEquals(listOf("REFRESH"), f.revoked)
        val cookie = cb.headers["Set-Cookie"]!!.lines().first { it.startsWith(ManageServer.SESSION) }
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        assertTrue("remembered for 30 days", cookie.contains("Max-Age=2592000"))
        assertFalse("the session is the panel's own, not HA's token", cookie.contains("ACCESS") || cookie.contains("REFRESH"))
        assertFalse(f.dir.walk().filter { it.isFile }.any { it.readText().contains("REFRESH") || it.readText().contains("ACCESS") })
    }

    @Test fun withoutRememberTheCookieDiesWithTheBrowser() {
        val b = f.Browser()
        val cb = b.signInWithHa(remember = false)
        assertFalse(cb.headers["Set-Cookie"]!!.lines().first { it.startsWith(ManageServer.SESSION) }.contains("Max-Age"))
    }

    @Test fun onlyAdministratorsGetInAndTheTokenIsRevokedAnyway() {
        f.user = HaUser("Sam", isAdmin = false)
        val b = f.Browser()
        val cb = b.signInWithHa()
        assertEquals(403, cb.status)
        assertTrue(cb.text().contains("Administrators only"))
        assertNull(b.cookies[ManageServer.SESSION])
        assertEquals(listOf("REFRESH"), f.revoked)
    }

    @Test fun anotherBrowsersSignInCantBeFinishedHere() {
        // Login CSRF: the attacker starts a sign-in and sends the victim to the callback with the attacker's state.
        val attacker = f.Browser()
        attacker.get("/")
        val loc = attacker.post("/manage/ha", mapOf("next" to "/")).headers["Location"]!!
        val state = HttpServer.parseQuery(loc.substringAfter('?'))["state"]
        val victim = f.Browser()
        victim.get("/")
        val cb = victim.get("/manage/callback?code=ATTACKERS&state=$state")
        assertEquals(403, cb.status)
        assertNull(victim.cookies[ManageServer.SESSION])
        assertTrue("the code is never exchanged", f.exchanged.isEmpty())
    }

    @Test fun aPublicHaAddressIsNotSignedInWith() {
        f.haBase = "https://example.ui.nabu.casa"
        val b = f.Browser()
        assertFalse(b.get("/").text().contains("Continue with Home Assistant"))
        assertEquals(409, b.post("/manage/ha", mapOf("next" to "/")).status)
    }

    @Test fun theSessionIsNewAtSignInNeverOneTheBrowserBrought() {
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = "chosen-by-an-attacker-0123456789"
        assertTrue(b.get("/").text().contains("Manage this panel."))
        b.signInWithHa()
        assertNotEquals("chosen-by-an-attacker-0123456789", b.cookies[ManageServer.SESSION])
        assertTrue(b.get("/").text().contains("Running"))
    }

    @Test fun sessionsEndWhenThePanelsHaAddressChanges() {
        val b = f.Browser()
        b.signInWithHa()
        f.haBase = "http://192.0.2.11:8123"
        assertTrue(b.get("/").text().contains("Manage this panel."))
    }

    // ------------------------------------------------------------------ forms from elsewhere

    @Test fun aFormFromAnotherSiteDoesNothing() {
        val b = f.Browser()
        b.signInWithHa()
        val csrf = b.csrf()
        // Right token, but posted from another origin: refused.
        assertEquals(403, b.post("/action/restart", mapOf("csrf" to csrf), mapOf("origin" to "http://attacker.example")).status)
        // No Origin at all and not same-origin: refused.
        assertEquals(403, b.post("/action/restart", mapOf("csrf" to csrf), emptyMap()).status)
        // The right origin without the token: refused.
        assertEquals(403, b.post("/action/restart", emptyMap()).status)
        assertTrue(f.ran.isEmpty())
        // From this page: runs.
        assertEquals(303, b.post("/action/restart", mapOf("csrf" to csrf)).status)
        assertEquals(listOf("restart"), f.ran)
    }

    @Test fun anotherSessionsTokenDoesntWork() {
        val a = f.Browser()
        a.signInWithHa()
        val b = f.Browser()
        b.signInWithHa()
        assertEquals(403, b.post("/action/reload", mapOf("csrf" to a.csrf())).status)
        assertTrue(f.ran.isEmpty())
    }

    // ------------------------------------------------------------------ the code on the panel

    @Test fun theCodeOnThePanelSignsInWhenHaIsDown() {
        f.tokens = HaGateway.Tokens.Failed("network_error", unreachable = true)
        val b = f.Browser()
        b.get("/about")
        val show = b.post("/manage/code", mapOf("next" to "/about", "remember" to "1"))
        assertEquals(303, show.status)
        val code = f.shown.last()!!.code
        assertEquals("/manage/code?next=%2Fabout&remember=1", show.headers["Location"])
        val ok = b.post("/manage/code", mapOf("next" to "/about", "remember" to "1", "code" to code))
        assertEquals("/about", ok.headers["Location"])
        assertTrue(b.get("/about").text().contains("Aqara Panel Hub S1 Plus"))
        assertNull("the panel hides the code once used", f.shown.last())
    }

    @Test fun aCodeShownForOneBrowserDoesntSignInAnother() {
        val a = f.Browser()
        a.get("/")
        a.post("/manage/code", mapOf("next" to "/"))
        val code = f.shown.last()!!.code
        val b = f.Browser()
        b.get("/")
        assertEquals(401, b.post("/manage/code", mapOf("next" to "/", "code" to code)).status)
        assertNull(b.cookies[ManageServer.SESSION])
    }

    @Test fun guessingTheCodeIsLimited() {
        val b = f.Browser()
        b.get("/")
        b.post("/manage/code", mapOf("next" to "/"))
        val code = f.shown.last()!!.code
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(4) { assertEquals(401, b.post("/manage/code", mapOf("next" to "/", "code" to wrong)).status) }
        assertEquals(401, b.post("/manage/code", mapOf("next" to "/", "code" to wrong)).status)
        // Five wrong tries: that code is gone, even typed right.
        assertEquals(401, b.post("/manage/code", mapOf("next" to "/", "code" to code)).status)
        assertNull(b.cookies[ManageServer.SESSION])
        // Five more on a new code (after the 30 s a code stays): code entry locks for everyone.
        f.now += 30_000
        b.post("/manage/code", mapOf("next" to "/"))
        val second = f.shown.last()!!.code
        val wrong2 = if (second == "000000") "111111" else "000000"
        repeat(4) { b.post("/manage/code", mapOf("next" to "/", "code" to wrong2)) }
        assertEquals(429, b.post("/manage/code", mapOf("next" to "/", "code" to wrong2)).status)
        assertEquals(429, f.Browser().post("/manage/code", mapOf("next" to "/")).status)
    }

    @Test fun rebootingNeedsTheCodeOnThePanelAtThatMoment() {
        val b = f.Browser()
        b.signInWithHa()
        val csrf = b.csrf()
        assertEquals("/confirm/reboot", b.post("/action/reboot", mapOf("csrf" to csrf)).headers["Location"])
        val shown = f.shown.last()!!
        assertEquals(ManageServer.PURPOSE_REBOOT, shown.purpose)
        val wrong = if (shown.code == "000000") "111111" else "000000"
        assertEquals(401, b.post("/confirm/reboot", mapOf("csrf" to csrf, "code" to wrong)).status)
        assertTrue(f.ran.isEmpty())
        assertEquals(303, b.post("/confirm/reboot", mapOf("csrf" to csrf, "code" to shown.code)).status)
        assertEquals(listOf("reboot"), f.ran)
    }

    @Test fun whenThePanelCantShowACodeThePageSaysSo() {
        f.code.canDisplay = { false }
        val b = f.Browser()
        b.get("/")
        val r = b.post("/manage/code", mapOf("next" to "/"))
        assertEquals(503, r.status)
        assertTrue(r.text().contains("The panel can't show a code right now."))
        assertTrue(f.shown.isEmpty())
        // Signed in, Reboot can't ask for one either: it says so and doesn't reboot.
        f.code.canDisplay = { true }
        b.signInWithHa()
        f.code.canDisplay = { false }
        assertEquals("/?done=nocode", b.post("/action/reboot", mapOf("csrf" to b.csrf())).headers["Location"])
        assertTrue(b.get("/?done=nocode").text().contains("Rebooting needs it."))
        assertTrue(f.ran.isEmpty())
    }

    @Test fun anotherBrowserCantReplaceACodeThatJustWentUp() {
        val a = f.Browser()
        a.get("/")
        a.post("/manage/code", mapOf("next" to "/"))
        val code = f.shown.last()!!.code
        val b = f.Browser()
        b.get("/")
        val busy = b.post("/manage/code", mapOf("next" to "/"))
        assertEquals(429, busy.status)
        assertTrue(busy.text().contains("The panel is showing another code."))
        // a asking again keeps its code on the screen, and it still works.
        a.post("/manage/code", mapOf("next" to "/"))
        assertEquals(1, f.shown.size)
        assertEquals("/", a.post("/manage/code", mapOf("next" to "/", "code" to code)).headers["Location"])
    }

    @Test fun theCapOnNewCodesSaysSoAndLogsWhoAsked() {
        // Six new codes in ten minutes, from different browsers, each after the last one's 30 s.
        repeat(6) { i ->
            f.now += 31_000
            val b = f.Browser(remote = "192.0.2.${60 + i}")
            b.get("/")
            assertEquals(303, b.post("/manage/code", mapOf("next" to "/")).status)
        }
        f.now += 31_000
        val stranger = f.Browser(remote = "192.0.2.99")
        stranger.get("/")
        val r = stranger.post("/manage/code", mapOf("next" to "/"))
        assertEquals(429, r.status)
        assertTrue(r.text().contains("too many codes in the last 10 minutes"))
        assertFalse("not the other limit's words", r.text().contains("showing another code"))
        assertTrue(f.events.toString(), f.events.any { it.contains("Too many code requests") && it.contains("192.0.2.99") })
    }

    @Test fun withoutRebootTheButtonAndTheActionAreGone() {
        f.canReboot = false
        val b = f.Browser()
        b.signInWithHa()
        assertFalse(b.get("/").text().contains("/action/reboot"))
        assertEquals("/", b.post("/action/reboot", mapOf("csrf" to b.csrf())).headers["Location"])
        assertTrue("no code shown", f.shown.isEmpty())
        assertTrue(f.ran.isEmpty())
    }

    @Test fun aRevokeHaDidntTakeIsTriedAgainThenLogged() {
        f.revokeWorks = false
        f.Browser().signInWithHa()
        assertEquals(listOf("REFRESH", "REFRESH"), f.revoked)
        assertEquals(1, f.warnings.size)
        assertFalse("the warning never holds the token", f.warnings.single().contains("REFRESH"))
    }

    @Test fun aSignInCodeDoesntConfirmAReboot() {
        val b = f.Browser()
        b.signInWithHa()
        val csrf = b.csrf()
        b.post("/manage/code", mapOf("next" to "/"))
        val signInCode = f.shown.last()!!.code
        assertEquals(401, b.post("/confirm/reboot", mapOf("csrf" to csrf, "code" to signInCode)).status)
        assertTrue(f.ran.isEmpty())
    }

    // ------------------------------------------------------------------ security and diagnostics

    @Test fun browsersCanBeSignedOutFromTheList() {
        val a = f.Browser()
        a.signInWithHa()
        val b = f.Browser(ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
        b.signInWithHa()
        val page = a.get("/security").text()
        assertTrue(page.contains("Safari on Mac"))
        assertTrue(page.contains("Chrome on Windows"))
        val other = f.sessions.list(f.haBase).first { it.browser == "Chrome on Windows" }
        assertEquals(303, a.post("/security/signout", mapOf("csrf" to a.csrf(), "id" to other.id)).status)
        assertTrue(b.get("/").text().contains("Manage this panel."))
        assertTrue(a.get("/").text().contains("Running"))
    }

    @Test fun theDiagnosticsFileHasNoTokensOrKeys() {
        val b = f.Browser()
        b.signInWithHa()
        f.log.append(LogEntry(f.now, LogEntry.Level.WARNING, "Radio", "joined with network_key=00112233445566778899aabbccddeeff"))
        val r = b.get("/log/diagnostics.txt")
        assertEquals(200, r.status)
        assertTrue(r.headers["Content-Disposition"]!!.startsWith("attachment"))
        val text = r.text()
        assertTrue(text.contains("http://192.0.2.10:8123"))
        for (secret in listOf("eyJhbGciOiJIUzI1NiJ9", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "00112233445566778899aabbccddeeff")) {
            assertFalse("leaked $secret", text.contains(secret))
        }
    }

    @Test fun everyPageCarriesTheSecurityHeaders() {
        val b = f.Browser()
        for (r in listOf(b.get("/"), b.signInWithHa(), b.get("/"), b.get("/log"))) {
            assertTrue(r.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
            assertEquals("DENY", r.headers["X-Frame-Options"])
            // Not no-referrer: Chromium then sends "Origin: null" on this page's own forms (seen 2026-11-12).
            assertEquals("same-origin", r.headers["Referrer-Policy"])
        }
        // The sign-in form posts to the panel, which sends the browser on to HA: HA's origin is allowed as a form target.
        assertTrue(f.Browser().get("/").headers["Content-Security-Policy"]!!.contains("form-action 'self' http://192.0.2.10:8123"))
    }

    @Test fun pagesForEveryPlaceInTheNav() {
        val b = f.Browser()
        b.signInWithHa()
        for (p in listOf("/", "/dashboard", "/screen", "/assist", "/sendspin", "/radio", "/wall-base", "/log", "/security", "/about")) {
            assertEquals(p, 200, b.get(p).status)
        }
        assertEquals(404, b.get("/nope").status)
        val radio = b.get("/radio").text()
        assertTrue("the radio page links to the Thread join's QR flow", radio.contains("href=\"/thread\""))
        assertTrue(radio.contains("socket://192.0.2.40:6638"))
        assertNotNull(Regex("Needs the wall base attached").find(b.get("/").text()))
    }

    @Test fun aPaneThatIsOffShowsNoSettingsOrLinks() {
        f.status = f.status.copy(panes = f.status.panes.map {
            if (it.id == "radio") it.copy(state = dev.glassworks.pane.PaneState(dev.glassworks.pane.PaneState.Kind.OFF, "Turned off")) else it
        })
        val b = f.Browser()
        b.signInWithHa()
        val radio = b.get("/radio").text()
        assertTrue(radio.contains("Turned off"))
        assertTrue(radio.contains(ManagePages.TURNED_OFF))
        assertFalse("no Thread join while the radio is off", radio.contains("href=\"/thread\""))
        assertFalse("no settings while it is off", radio.contains("socket://192.0.2.40:6638"))
        assertFalse(radio.contains("Change these on the panel"))
    }

    @Test fun theSessionIsBoundToTheAddressTheSignInWasCheckedAgainst() {
        val b = f.Browser()
        b.get("/")
        val start = b.post("/manage/ha", mapOf("next" to "/", "remember" to "1"))
        val state = HttpServer.parseQuery(start.headers["Location"]!!.substringAfter('?'))["state"]!!
        // The address changes while the callback runs: after the session lookup and the check, before the session is made.
        var reads = 0
        f.haBaseFn = { if (++reads <= 2) "http://192.0.2.10:8123" else "http://192.0.2.11:8123" }
        b.get("/manage/callback?code=CODE&state=$state", mapOf("sec-fetch-site" to "cross-site"))
        assertFalse("a session checked against .10 never works for .11", b.get("/").text().contains("Reload dashboard"))
    }

    @Test fun aSignOutThePanelCantSaveSaysSo() {
        val b = f.Browser()
        b.signInWithHa()
        val csrf = b.csrf()
        // The sessions file can't be written or deleted: its directory is read-only.
        java.io.File(f.dir, "sessions.json").delete()
        java.io.File(f.dir, "sessions.json").mkdirs()
        java.io.File(java.io.File(f.dir, "sessions.json"), "x").writeText("x")
        val r = b.post("/manage/signout", mapOf("csrf" to csrf))
        assertEquals(500, r.status)
        assertTrue(r.text().contains("couldn't save"))
    }

    @Test fun anAddressChangeEndsEverySessionEvenOnesNotUsedSince() {
        val one = f.Browser()
        one.signInWithHa()
        val two = f.Browser(ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
        two.signInWithHa()
        val home = f.haBase
        f.haBase = "http://192.0.2.11:8123"
        one.get("/") // only this browser comes back while the address is new
        f.haBase = home
        assertFalse("the other browser's session ended too", two.get("/").text().contains("Reload dashboard"))
        assertTrue(f.sessions.list(home).isEmpty())
    }

    @Test fun haVisitOverPlainHttpReloadsOnceAndLandsSignedIn() {
        // On the LAN's http:// address the browser sends no Sec-Fetch-Site at all.
        val b = f.Browser(fetchMetadata = false)
        b.signInWithHa()
        val cookie = b.cookies.remove(ManageServer.SESSION)!!
        val r = b.get("/log?level=warning")
        val to = Regex("url=([^\"]+)\"").find(r.text())!!.groupValues[1].replace("&amp;", "&")
        assertTrue(to, to.startsWith("/log?") && "level=warning" in to && "${ManageServer.RELOADED}=1" in to)
        b.cookies[ManageServer.SESSION] = cookie // the reload from the panel's own page is same-site: the cookie comes
        val back = b.get(to)
        assertEquals(303, back.status)
        assertEquals("/log?level=warning", back.headers["Location"])
    }

    @Test fun theReloadHappensOnceThenItsTheSignInPage() {
        val b = f.Browser(fetchMetadata = false)
        val first = b.get("/log")
        assertTrue(first.text().contains("http-equiv=\"refresh\""))
        val again = b.get("/log?${ManageServer.RELOADED}=1")
        assertFalse("no second reload", again.text().contains("http-equiv=\"refresh\""))
        assertTrue(again.text().contains("Manage this panel"))
        assertTrue("next has no mark", again.text().contains("name=\"next\" value=\"/log\""))
    }

    @Test fun aTextSettingShowsItsValueAsThePanelDoes() {
        f.status = f.status.copy(sections = f.status.sections + dev.glassworks.pane.SettingsSection("screen2", "Screen", listOf(
            dev.glassworks.pane.SettingsItem.Text("Screen off after", "Never", dev.glassworks.pane.SettingsItem.TextKind.NUMBER, { "120" },
                shown = { "After ${it.toInt() / 60} minutes" }) { _, d -> d(null) },
        )))
        val b = f.Browser()
        b.signInWithHa()
        val page = b.get("/screen2").text()
        assertTrue(page, page.contains("After 2 minutes"))
    }

    @Test fun theReloadNeverLeavesThePanel() {
        // `////attacker.example/` reaches the server as the path `//attacker.example/`, which a browser reads as a host.
        val anon = f.Browser(fetchMetadata = false)
        val page = anon.get("//attacker.example/").text()
        assertFalse(page, page.contains("attacker.example"))
        assertTrue(page.contains("url=/?${ManageServer.RELOADED}=1"))
        val b = f.Browser()
        b.signInWithHa()
        val r = b.get("//attacker.example/?${ManageServer.RELOADED}=1")
        assertEquals(303, r.status)
        assertEquals("/", r.headers["Location"])
    }
}
