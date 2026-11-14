/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.NO_SETTINGS
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Manage page's panes come from the same sections as the panel's menu, with their on/off (#33, #67). */
class ManagePanesTest {
    private var on = true
    private val master = SettingsItem.Switch("Music", "A Sendspin speaker", { on }) { v, done -> on = v; done(null) }
    private val link = listOf("Share it" to "/thread")

    private fun section(unavailable: String? = null) = SettingsSection(
        "sendspin", "Music", listOf(SettingsItem.Switch("Lossless (FLAC)", "", { true }) { _, d -> d(null) }),
        master = master, unavailable = { unavailable },
    )

    @Test fun aPaneThatIsOffReadsOffAndOffersNothing() {
        on = false
        val row = paneRow("sendspin", section(), link) { PaneState(PaneState.Kind.RUNNING, "Playing") }
        assertEquals("Music", row.title)
        assertEquals(PaneState.Kind.OFF, row.state.kind)
        assertTrue("no links while it is off", row.links.isEmpty())
        assertEquals("sendspin", row.sectionId)
    }

    @Test fun aPaneThatIsOnSaysWhatItDoesOrOn() {
        val running = paneRow("sendspin", section(), link) { PaneState(PaneState.Kind.RUNNING, "Playing") }
        assertEquals("Playing", running.state.detail)
        assertEquals(link, running.links)
        val plain = paneRow("sendspin", section())
        assertEquals(PaneState.Kind.RUNNING, plain.state.kind)
        assertEquals("On", plain.state.chip)
        assertEquals("A Sendspin speaker", plain.state.detail)
    }

    @Test fun aPaneThatCantRunSaysWhy() {
        val row = paneRow("sendspin", section(unavailable = "Needs the wall base attached"))
        assertEquals(PaneState(PaneState.Kind.CANT_RUN, "Needs the wall base attached"), row.state)
    }

    @Test fun withoutASectionTheIdInWords() {
        val row = paneRow("wall-base", null)
        assertEquals("Wall base", row.title)
        assertEquals(PaneState.UNKNOWN, row.state)
    }

    @Test fun aPaneWithOnlyItsSwitchHasNoSettingsToList() {
        val f = ManageFixture()
        f.status = f.status.copy(
            panes = f.status.panes + PaneRow("time-zone", "Time zone", PaneState(PaneState.Kind.RUNNING, "Keeps the zone", "On"), "time-zone"),
            sections = f.status.sections + SettingsSection("time-zone", "Time zone", listOf(SettingsItem.Note { NO_SETTINGS }), master = master),
        )
        val b = f.Browser()
        b.signInWithHa()
        val page = b.get("/time-zone").text()
        assertEquals("the no-settings line once", 1, Regex(Regex.escape(NO_SETTINGS)).findAll(page).count())
        assertFalse(page.contains("Change these on the panel"))
    }
}
