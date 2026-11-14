/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #242: the Manage page changes the panel's settings through their own saves, with the panel's checks and #178's guards. */
class ManageSettingsTest {
    private var address = "http://homeassistant.local:8123"
    private var range = 1.2f
    private var idle = 120
    private var radioOn = true
    private var hide = true
    private val f = ManageFixture()

    init {
        val screen = SettingsSection("screen", "Screen", listOf(
            SettingsItem.Text("Home Assistant", "Found automatically", SettingsItem.TextKind.ADDRESS, { address }, restartsApp = true) { v, d -> address = v; d(null) },
            SettingsItem.Switch("Full screen", "Hide Home Assistant's header and sidebar", { hide }) { v, d -> hide = v; d(null) },
            SettingsItem.Slider("Presence range", { range }, 0f, 2.5f, 0.1f, { "Up to $it m" }) { v, d -> range = v; d(null) },
            SettingsItem.Duration("Turn off when idle", { idle }, listOf(30, 60, 120, 0), { "After $it s" }) { v, d -> idle = v; d(null) },
        ))
        val radio = SettingsSection("radio", "Radio", listOf(SettingsItem.Note { "The radio's page" }), heading = "Zigbee / Thread radio",
            master = SettingsItem.Switch("Zigbee / Thread radio", "", { radioOn },
                confirm = { on -> if (on) null else SettingsItem.Confirm("Turn the radio off?", "Zigbee devices stop.", "Turn off") }) { v, d -> radioOn = v; d(null) })
        f.statusFn = {
            f.status.copy(
                panes = listOf(paneRow("radio", radio)),
                sections = listOf(screen, radio),
            )
        }
    }

    private fun signedIn() = f.Browser().also { it.signInWithHa() }

    private fun form(b: ManageFixture.Browser, section: String, key: String, vararg more: Pair<String, String>) =
        mapOf("csrf" to b.csrf(), "section" to section, "key" to key) + more

    @Test fun aChangeGoesThroughTheItemsOwnSaveOnTheMainThread() {
        val b = signedIn()
        val r = b.post("/settings", form(b, "screen", "Turn off when idle", "value" to "60"))
        assertEquals(303, r.status)
        assertEquals("/screen", r.headers["Location"])
        assertEquals(60, idle)
        assertEquals(1, f.onMainCalls)
        assertTrue(f.events.any { it == "Alex changed Screen › Turn off when idle" })
        assertTrue("the page shows the new value", b.get("/screen").text().contains("After 60 s"))
    }

    @Test fun whatThePanelWouldntTakeTheBrowserCantSend() {
        val b = signedIn()
        val r = b.post("/settings", form(b, "screen", "Presence range", "value" to "9"))
        assertEquals(422, r.status)
        assertTrue(r.text().contains("Pick from 0 to 2.5."))
        assertEquals(1.2f, range)
        val d = b.post("/settings", form(b, "screen", "Turn off when idle", "value" to "45"))
        assertEquals(422, d.status)
        assertTrue("the choices' dialog stays open with why", d.text().contains("class=\"dlg\"") && d.text().contains(SettingsChange.PICK))
        assertEquals(120, idle)
    }

    @Test fun formsFromElsewhereOrWithoutTheTokenChangeNothing() {
        val b = signedIn()
        assertEquals(403, b.post("/settings", mapOf("section" to "screen", "key" to "Turn off when idle", "value" to "60")).status)
        assertEquals(403, b.post("/settings", form(b, "screen", "Turn off when idle", "value" to "60"), mapOf("origin" to "http://evil.example")).status)
        assertEquals(303, f.Browser().post("/settings", mapOf("section" to "screen", "key" to "Turn off when idle", "value" to "60")).status)
        assertEquals(120, idle)
        assertEquals(0, f.onMainCalls)
    }

    @Test fun aPanesOnOffNeedsAHomeAssistantSignIn() {
        val b = f.Browser()
        b.post("/manage/code", mapOf("next" to "/"))
        b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
        val page = b.get("/radio").text()
        assertTrue("the switch shows, locked", page.contains("role=\"switch\" aria-checked=\"true\" aria-label=\"Zigbee / Thread radio\" disabled"))
        assertTrue(page.contains(esc(ManagePages.NEEDS_HA)))
        val r = b.post("/settings", form(b, "radio", "on", "value" to "off", "confirmed" to "1"))
        assertEquals(403, r.status)
        assertTrue(radioOn)
        // Its own settings don't need it: the code is someone at the panel.
        assertEquals(303, b.post("/settings", form(b, "screen", "Full screen", "value" to "off")).status)
        assertFalse(hide)
    }

    @Test fun aSwitchThatAsksOnThePanelAsksHereFirst() {
        val b = signedIn()
        val ask = b.post("/settings", form(b, "radio", "on", "value" to "off"))
        assertEquals(200, ask.status)
        assertTrue(ask.text().contains("Turn the radio off?") && ask.text().contains("Zigbee devices stop.") && ask.text().contains(">Turn off</button>"))
        assertTrue("asked, not done", radioOn)
        assertEquals(303, b.post("/settings", form(b, "radio", "on", "value" to "off", "confirmed" to "1")).status)
        assertFalse(radioOn)
        assertTrue("on doesn't ask", b.post("/settings", form(b, "radio", "on", "value" to "on")).status == 303 && radioOn)
    }

    @Test fun aChangeThatNeedsARestartOffersOne() {
        val b = signedIn()
        val r = b.post("/settings", form(b, "screen", "Home Assistant", "value" to "http://192.0.2.10:8123"))
        assertEquals("/screen?done=restart", r.headers["Location"])
        assertEquals("http://192.0.2.10:8123", address)
        val page = b.get("/screen?done=restart").text()
        assertTrue(page.contains(SettingsChange.RESTART_TEXT))
        assertTrue(page.contains("action=\"/action/restart\""))
    }

    @Test fun aFailedTextKeepsWhatWasTypedAndThePageBehindIsInert() {
        val zoom = SettingsSection("dashboard", "Dashboard", listOf(
            SettingsItem.Text("Zoom", "Auto", SettingsItem.TextKind.DECIMAL, { "1.5" }) { _, d -> d(null) }))
        f.statusFn = { f.status.copy(sections = listOf(zoom)) }
        val b = signedIn()
        val r = b.post("/settings", form(b, "dashboard", "Zoom", "value" to "1,25"))
        assertEquals(422, r.status)
        val page = r.text()
        assertTrue("what was typed, not the stored value", page.contains("name=\"value\" value=\"1,25\""))
        assertTrue(page.contains("role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"dlg-t\""))
        assertTrue(page.contains("<aside class=\"side\" inert>") && page.contains("<main class=\"main\" inert>"))
        assertFalse("no dialog, nothing inert", b.get("/dashboard").text().contains(" inert>"))
    }

    /**
     * #242 for #252: the camera stream's sign-in. The password is hidden on every page, shows only after Show
     * (a POST from a browser signed in with Home Assistant), and is never in the Log.
     */
    @Test fun aSignInsPasswordIsHiddenUntilShownAndNeverLogged() {
        val secret = "made-up-secret-7"
        var stored: Pair<String, String>? = "viewer" to secret
        val camera = SettingsSection("camera", "Camera", listOf(SettingsItem.Group("ONVIF stream", listOf(
            SettingsItem.SignIn("Sign-in", { stored != null }, { stored?.first }, { stored?.second }) { v, d -> stored = v; d(null) }))))
        f.statusFn = { f.status.copy(sections = listOf(camera)) }
        val key = "ONVIF stream/Sign-in"
        val b = signedIn()
        val hidden = ArrayList<String>()
        fun post(path: String, vararg p: Pair<String, String>) = b.post(path, form(b, "camera", key, *p))
        hidden += b.get("/camera").text()
        val dialog = b.get("/camera?edit=ONVIF+stream%2FSign-in").text().also { hidden += it }
        assertTrue("hidden by default: an empty password field", dialog.contains("type=\"password\" name=\"password\" value=\"\""))
        assertTrue("Show asks the panel for it", dialog.contains("formaction=\"/settings/reveal\">Show</button>"))
        assertEquals("a GET can't show it", 404, b.get("/settings/reveal").status)
        hidden += b.get("/settings/reveal?section=camera&key=ONVIF+stream%2FSign-in").text()

        val shown = post("/settings/reveal")
        assertEquals(200, shown.status)
        assertTrue("shown on the explicit action", shown.text().contains("type=\"text\" name=\"password\" value=\"$secret\""))
        assertTrue(f.events.contains("Alex showed Camera › ONVIF stream › Sign-in's password"))

        hidden += post("/settings", "user" to "view:er", "password" to "").also { assertEquals(422, it.status) }.text()
        assertEquals(303, post("/settings", "user" to "viewer2", "password" to "").status)
        assertEquals("an empty field keeps the password", "viewer2" to secret, stored)
        hidden += b.get("/camera").text()
        assertEquals(303, post("/settings", "clear" to "1").status)
        assertNull("Clear removes it", stored)
        for (p in hidden) assertFalse("the password is only on the page that showed it", p.contains(secret))
        assertFalse("never in the Log", f.events.any { it.contains(secret) })
        assertTrue(f.events.contains("Alex set Camera › ONVIF stream › Sign-in") && f.events.contains("Alex cleared Camera › ONVIF stream › Sign-in"))
    }

    /** Enter submits a form's first submit button: in a sign-in's dialog that is Save, never Show or Clear. */
    @Test fun enterInASignInsDialogSaves() {
        val camera = SettingsSection("camera", "Camera", listOf(SettingsItem.SignIn("Sign-in", { true }, { "viewer" }, { "made-up-secret-7" }) { _, d -> d(null) }))
        f.statusFn = { f.status.copy(sections = listOf(camera)) }
        fun firstSubmit(page: String) = Regex("""<button[^>]*type="submit"[^>]*>""").find(page.substringAfter("<form class=\"dlg\""))!!.value
        val b = signedIn()
        val before = firstSubmit(b.get("/camera?edit=Sign-in").text())
        val after = firstSubmit(b.post("/settings/reveal", form(b, "camera", "Sign-in")).text())
        for (button in listOf(before, after)) {
            assertTrue(button, button.startsWith("<button class=\"dflt\" type=\"submit\""))
            assertFalse("no formaction or clear on the default", button.contains("formaction") || button.contains("name="))
        }
        val code = f.Browser()
        code.post("/manage/code", mapOf("next" to "/"))
        code.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
        assertTrue(firstSubmit(code.get("/camera?edit=Sign-in").text()).startsWith("<button class=\"dflt\""))
    }

    @Test fun showingASignInsPasswordNeedsAHomeAssistantSignIn() {
        val secret = "made-up-secret-7"
        val camera = SettingsSection("camera", "Camera", listOf(SettingsItem.SignIn("Sign-in", { true }, { "viewer" }, { secret }) { _, d -> d(null) }))
        f.statusFn = { f.status.copy(sections = listOf(camera)) }
        val b = f.Browser()
        b.post("/manage/code", mapOf("next" to "/"))
        b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
        val dialog = b.get("/camera?edit=Sign-in").text()
        assertTrue("Show is there, greyed", dialog.contains("type=\"button\" disabled title=\"${esc(ManagePages.NEEDS_HA_REVEAL)}\">Show</button>"))
        val r = b.post("/settings/reveal", form(b, "camera", "Sign-in"))
        assertEquals(403, r.status)
        assertFalse(r.text().contains(secret))
        assertFalse(f.events.any { it.contains(secret) })
    }

    @Test fun anItemThePageNoLongerHasChangesNothing() {
        val b = signedIn()
        assertEquals("/screen?done=gone", b.post("/settings", form(b, "screen", "Brightness", "value" to "on")).headers["Location"])
        assertEquals("/", b.post("/settings", form(b, "nope", "x", "value" to "on")).headers["Location"])
    }

    @Test fun aChangeThatTakesLongerSaysItIsStillApplying() {
        val slow = ManageFixture(saveWaitMs = 50)
        val held = SettingsSection("screen", "Screen", listOf(SettingsItem.Switch("Brightness", "", { false }) { _, _ -> }))
        slow.statusFn = { slow.status.copy(sections = listOf(held)) }
        val b = slow.Browser().also { it.signInWithHa() }
        val r = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to "screen", "key" to "Brightness", "value" to "on"))
        assertEquals("/screen?done=pending", r.headers["Location"])
    }

    @Test fun editsOpenAsTheirSheetAndEscapeWhatTheyShow() {
        address = "http://\"homeassistant\".local:8123/<x>"
        val b = signedIn()
        val page = b.get("/screen?edit=Home+Assistant").text()
        assertTrue(page.contains("class=\"dlg\""))
        assertTrue(page.contains("value=\"http://&quot;homeassistant&quot;.local:8123/&lt;x&gt;\""))
        assertFalse(page.contains("<x>"))
        val chips = b.get("/screen?edit=Turn+off+when+idle").text()
        assertTrue("the picked one is lit", chips.contains("class=\"pc on\" type=\"submit\" name=\"value\" value=\"120\""))
        assertFalse("a switch has no sheet", b.get("/screen?edit=Full+screen").text().contains("class=\"dlg\""))
    }

    @Test fun theFootEscapesAQuoteInTheName() {
        f.user = HaUser("Alex \"Al\" <b>", isAdmin = true)
        val page = signedIn().get("/").text()
        assertTrue(page.contains("title=\"Alex &quot;Al&quot; &lt;b&gt;\""))
        assertFalse(page.contains("<b>\""))
    }

    /** #253 verifier: the nav's Panel group is every core section in the model, not a fixed list. */
    @Test fun theNavListsTheModelsCoreSections() {
        val frame = SettingsSection("frame", "Frame", listOf(SettingsItem.Note { "The panel this app runs on." }))
        val radio = SettingsSection("radio", "Radio", listOf(SettingsItem.Note { "r" }))
        f.statusFn = { f.status.copy(panes = listOf(paneRow("radio", radio)), sections = listOf(frame, radio, SettingsSection("about", "About", listOf(SettingsItem.Note { "a" })))) }
        val nav = signedIn().get("/").text().substringAfter("<aside class=\"side\">").substringBefore("</aside>")
        val panel = nav.substringAfter("<h3>Panel</h3>").substringBefore("<h3>")
        assertTrue("a core section the model has", panel.contains("href=\"/frame\""))
        assertFalse("a pane's section sits under Panes", panel.contains("href=\"/radio\""))
        assertFalse("About has its own page under System", panel.contains("href=\"/about\""))
    }

    @Test fun everyPaneHasItsChipAfterItsNameAndOffPanesFold() {
        f.statusFn = {
            f.status.copy(panes = listOf(
                PaneRow("assist", "Voice", PaneState(PaneState.Kind.RUNNING, "Wake word"), null),
                PaneRow("sendspin", "Music", PaneState(PaneState.Kind.OFF, "Turned off"), null),
                PaneRow("radio", "Radio", PaneState(PaneState.Kind.PROBLEM, "Failed"), null),
            ))
        }
        val page = signedIn().get("/").text()
        val nav = page.substringAfter("<aside class=\"side\">").substringBefore("</aside>")
        assertTrue(nav.contains("<span class=\"nt\">Voice</span><span class=\"chip\" aria-label=\"status Running\"><i class=\"dot ok\"></i>Running</span>"))
        assertTrue(nav.contains("<span class=\"nt\">Radio</span><span class=\"chip\" aria-label=\"status Problem\"><i class=\"dot bad\"></i>Problem</span>"))
        val fold = nav.substringAfter("<details class=\"off\">")
        assertTrue(fold.startsWith("<summary>Off · 1</summary>"))
        assertTrue(fold.contains("<span class=\"nt\">Music</span><span class=\"chip\" aria-label=\"status Off\"><i class=\"dot off\"></i>Off</span>"))
    }

    private fun esc(s: String) = dev.glassworks.web.PageShell.esc(s)
}
