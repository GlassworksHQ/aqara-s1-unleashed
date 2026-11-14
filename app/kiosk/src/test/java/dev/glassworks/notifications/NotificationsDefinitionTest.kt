/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import android.app.Application
import android.content.Context
import dev.glassworks.pane.SettingsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationsDefinitionTest {
    private val file = RuntimeEnvironment.getApplication().getSharedPreferences("notifications-def-test", Context.MODE_PRIVATE)
    private val hub = NotificationsHub()
    private val d = NotificationsDefinition(file, hub, "esphome.s1_panel_notify", now = { 600_000L })

    private fun texts(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        when (i) {
            is SettingsItem.Group -> listOf(i.title) + texts(i.items)
            is SettingsItem.Note -> listOf(i.text())
            is SettingsItem.Info -> listOf("${i.title()}: ${i.detail()}")
            is SettingsItem.Link -> listOf("${i.title}: ${i.detail}")
            is SettingsItem.Switch -> listOf(i.title)
            else -> emptyList()
        }
    }

    @Test
    fun offByDefaultWithASetupCard() {
        assertFalse(d.switch.on)
        assertEquals("Notifications" to "On-screen alerts", d.setupChoice!!.let { it.title to it.detail })
        val t = texts(d.settings().single().items)
        assertTrue(t.first(), t.first().contains("esphome.s1_panel_notify stays in Home Assistant"))
        assertTrue("its state from HA", "Action: esphome.s1_panel_notify" in t)
    }

    @Test
    fun onThePageShowsWhatIsOnThePanel() {
        d.switch.on = true
        assertEquals("Ready · nothing on the panel", texts(d.settings().single().items).first())
        val n = Notification("Washer", "")
        hub.publish(NotificationsHub.State(list = listOf(
            NotificationStore.Entry(1, n, 480_000, Delivery.of(Priority.NORMAL, false)),
            NotificationStore.Entry(2, n, 300_000, Delivery.of(Priority.NORMAL, true)),
        )))
        val t = texts(d.settings().single().items)
        assertEquals("2 on the panel · 1 held · last one 2 min ago", t.first())
        assertTrue("Notifications on the panel: 2 · open the list" in t)
        assertTrue("Do not disturb: Off" in t)
        assertTrue("Button taps: event esphome.notification_action" in t)
    }

    @Test
    fun doNotDisturbOnThePageIsTheSameSetting() {
        d.switch.on = true
        val dnd = (d.settings().single().items.filterIsInstance<SettingsItem.Group>().first().items[0] as SettingsItem.Switch)
        dnd.save(true) {}
        assertTrue(NotificationsPrefs(file).doNotDisturb)
        assertTrue(dnd.value())
    }
}
