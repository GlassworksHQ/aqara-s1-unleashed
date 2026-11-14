/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #242: one check before every save, so the Manage page can't send what the panel's controls wouldn't. */
class SettingsChangeTest {
    private val saved = ArrayList<Any>()

    /** [SettingsChange.change]'s answer, which comes at once for these items. */
    private fun change(i: SettingsItem, raw: String): String? {
        var answer: String? = "no answer"
        SettingsChange.change(i, raw) { answer = it }
        return answer
    }

    private fun text(kind: SettingsItem.TextKind, enabled: Boolean = true) =
        SettingsItem.Text("Zoom", "Auto", kind, { "" }, enabled = { enabled }) { v, d -> saved += v; d(null) }

    @Test fun aNumberTakesDigitsOnlyAndADecimalANumber() {
        assertEquals("Use digits only.", change(text(SettingsItem.TextKind.NUMBER), "12a"))
        assertNull(change(text(SettingsItem.TextKind.NUMBER), " 120 "))
        assertEquals("Use a number, like 1.5.", change(text(SettingsItem.TextKind.DECIMAL), "1,5"))
        assertNull(change(text(SettingsItem.TextKind.DECIMAL), "1.5"))
        assertNull("empty is Auto", change(text(SettingsItem.TextKind.DECIMAL), ""))
        assertEquals(listOf("120", "1.5", ""), saved)
    }

    @Test fun aTextThePanelGreysOutCantChange() {
        assertEquals(SettingsChange.CANT_NOW, change(text(SettingsItem.TextKind.ADDRESS, enabled = false), "192.0.2.20"))
        assertTrue(saved.isEmpty())
    }

    @Test fun controlCharactersAndLongValuesNeverReachASave() {
        assertEquals("That has characters a setting can't take.", change(text(SettingsItem.TextKind.PLAIN), "a\u0000b"))
        assertEquals("That's too long.", change(text(SettingsItem.TextKind.PLAIN), "x".repeat(2001)))
        assertTrue(saved.isEmpty())
    }

    @Test fun aSliderTakesItsRangeOnItsSteps() {
        val s = SettingsItem.Slider("Presence range", { 0f }, 0f, 2.5f, 0.1f, { "$it m" }) { v, d -> saved += v; d(null) }
        assertEquals("Pick from 0 to 2.5.", change(s, "3"))
        assertEquals("Pick from 0 to 2.5.", change(s, "-0.1"))
        assertEquals("That isn't a number.", change(s, "NaN"))
        assertNull(change(s, "1.23"))
        assertEquals(1.2f, saved.single() as Float, 1e-5f)
    }

    @Test fun aDurationTakesOneOfItsChoicesOrTheValueItHas() {
        val d = SettingsItem.Duration("Turn off when idle", { 45 }, listOf(30, 60, 0), { "After $it s" }) { v, done -> saved += v; done(null) }
        assertEquals(SettingsChange.PICK, change(d, "90"))
        assertNull(change(d, "60"))
        assertNull("the value set elsewhere stays pickable, as on the panel", change(d, "45"))
        assertEquals(listOf(60, 45), saved)
    }

    @Test fun aChoiceTakesAnOptionsId() {
        val c = SettingsItem.Choice("Wake word", { "okay_nabu" }, { listOf("okay_nabu" to "Okay Nabu", "" to "None") }) { v, d -> saved += v; d(null) }
        assertEquals(SettingsChange.PICK, change(c, "Okay Nabu"))
        assertNull(change(c, ""))
        assertEquals(listOf(""), saved)
    }

    @Test fun aSwitchTakesOnOrOff() {
        val s = SettingsItem.Switch("Full screen", "", { false }) { v, d -> saved += v; d(null) }
        assertEquals("That isn't on or off.", change(s, "true"))
        assertNull(change(s, "on"))
        assertNull(change(s, "off"))
        assertEquals(listOf(true, false), saved)
    }

    @Test fun aTimeWindowTakesTwoTimesOrNone() {
        val t = SettingsItem.TimeRange("At night", { "" }, { it }) { v, d -> saved += v; d(null) }
        assertEquals("Use times like 22:00, from 00:00 to 23:59.", change(t, "24:00-07:00"))
        assertEquals("Use times like 22:00, from 00:00 to 23:59.", change(t, "22:60-07:00"))
        assertNull(change(t, "9:30-7:00"))
        assertNull(change(t, ""))
        assertEquals(listOf("09:30-07:00", ""), saved)
    }

    @Test fun whatOnlyShowsCantBeChangedAndASaveThatThrowsIsAnAnswer() {
        assertEquals(SettingsChange.CANT_HERE, change(SettingsItem.Note { "x" }, "x"))
        val broken = SettingsItem.Switch("Mute microphone", "", { false }) { _, _ -> error("no microphone") }
        assertEquals("That didn't save: no microphone.", change(broken, "on"))
    }

    @Test fun aSignInTakesANameWithoutAColonAndAPasswordWithinTheirLimits() {
        var stored: Pair<String, String>? = "viewer" to "made-up-old"
        val i = SettingsItem.SignIn("Sign-in", { stored != null }, { stored?.first }, { stored?.second }) { v, d -> stored = v; d(null) }
        fun sign(u: String, p: String): String? { var a: String? = "no answer"; SettingsChange.signIn(i, u, p) { a = it }; return a }
        assertEquals("A user name can't have a colon or spaces.", sign("a:b", "made-up-pass"))
        assertEquals("A user name can't have a colon or spaces.", sign("a b", "made-up-pass"))
        assertEquals("Type a user name.", sign(" ", "made-up-pass"))
        assertNull("an empty password keeps the stored one", sign("viewer", ""))
        assertEquals("viewer" to "made-up-old", stored)
        assertEquals("Use at most 64 characters for the user name.", sign("u".repeat(65), "made-up-pass"))
        assertEquals("Use at most 128 characters for the password.", sign("viewer", "p".repeat(129)))
        assertEquals("viewer" to "made-up-old", stored)
        assertNull(sign(" viewer2 ", "made up: pass"))
        assertEquals("the name trimmed, the password as typed", "viewer2" to "made up: pass", stored)
        var a: String? = "no answer"
        SettingsChange.clear(i) { a = it }
        assertNull(a)
        assertNull("Clear removes it", stored)
        assertEquals("with none stored, a password is needed", "Type a password.", sign("viewer", ""))
    }

    @Test fun aSignInSaveThatThrowsNeverRepeatsWhatWasTyped() {
        val i = SettingsItem.SignIn("Sign-in", { false }) { v, _ -> error("bad ${v?.second}") }
        var a: String? = null
        SettingsChange.signIn(i, "viewer", "made-up-secret-7", ) { a = it }
        assertEquals(SettingsChange.COULD_NOT_SIGN_IN, a)
    }

    @Test fun keysAreTheTitlesDownFromTheSectionWithTheOnOffFirst() {
        val allow = SettingsItem.Text("IP addresses", "", SettingsItem.TextKind.ADDRESS, { "" }) { _, d -> d(null) }
        val off = SettingsItem.Text("Greyed", "", SettingsItem.TextKind.ADDRESS, { "" }, enabled = { false }) { _, d -> d(null) }
        val master = SettingsItem.Switch("Radio", "", { true }) { _, d -> d(null) }
        val s = SettingsSection("radio", "Radio", listOf(SettingsItem.Note { "n" }, SettingsItem.Group("Who may connect", listOf(allow, off))), master = master)
        assertEquals(listOf("on", "Who may connect/IP addresses"), SettingsChange.changeable(s).map { it.first })
        assertTrue(SettingsChange.find(s, "Who may connect/IP addresses") === allow)
        assertNull(SettingsChange.find(s, "IP addresses"))
        assertTrue(SettingsChange.needsHomeAssistant("on"))
    }
}
