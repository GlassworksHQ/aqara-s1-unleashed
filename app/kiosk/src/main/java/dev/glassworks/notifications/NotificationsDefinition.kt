/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import android.content.SharedPreferences
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection

/**
 * The notifications pane ([NotificationsPane]) for the whole process: its on/off, its card on setup's
 * Panes step and its settings page (#118, the approved design). [file] is its settings file, [hub] the
 * running pane's state, [actionName] what Home Assistant calls the action (`esphome.s1_panel_notify`).
 */
class NotificationsDefinition(
    file: SharedPreferences,
    private val hub: NotificationsHub,
    private val actionName: String,
    private val now: () -> Long,
) : PaneDefinition {
    override val id = NotificationsPane.ID

    private val prefs = NotificationsPrefs(file)

    override val switch = PaneSwitch(
        file, NotificationsPrefs.KEY_ENABLED, default = false, title = "Notifications", detail = "On-screen alerts",
        objectId = "notifications", entityName = "Notifications", icon = "mdi:bell-outline", inSetup = true,
        // The core shows this on the page while the pane is off (#212), then the pane's entities.
        offNote = offNote(actionName),
    )

    override fun settings(): List<SettingsSection> = listOf(
        SettingsSection(id, "Notifications", if (switch.on) onItems() else offItems()),
    )

    private fun onItems(): List<SettingsItem> {
        val s = hub.state
        return listOf(
            SettingsItem.Note { status(hub.state) },
            SettingsItem.Group("Interruptions", listOf(
                SettingsItem.Switch(
                    "Do not disturb", "Holds everything but critical in the list, silent", { prefs.doNotDisturb },
                ) { on, done -> prefs.doNotDisturb = on; done(null) },
                SettingsItem.Link("Notifications on the panel", listLine(s)) { hub.openList() },
            )),
            SettingsItem.Group("Try it", listOf(
                SettingsItem.Link("Send a test notification", "A normal one, with a chime and one button") { hub.sendTest() },
            )),
            inHomeAssistant(),
        )
    }

    private fun offItems(): List<SettingsItem> = listOf(SettingsItem.Note { offNote(actionName) }, inHomeAssistant())

    private fun inHomeAssistant() = SettingsItem.Group("In Home Assistant", listOf(
        SettingsItem.Info({ "Do not disturb" }, { if (prefs.doNotDisturb) "On" else "Off" }),
        SettingsItem.Info({ "Action" }, { actionName }),
        SettingsItem.Info({ "Button taps" }, { "event ${ActionReport.EVENT}" }),
        SettingsItem.Note {
            "Send one from any script or automation. The fields are the Companion app's: title, message, " +
                "and data with tag, image, actions, priority, sound, timeout and url."
        },
    ))

    private fun status(s: NotificationsHub.State): String {
        if (s.list.isEmpty()) return "Ready · nothing on the panel"
        val held = s.list.count { it.held }
        val parts = mutableListOf("${s.list.size} on the panel")
        if (held > 0) parts += "$held held"
        parts += "last one ${ago(now() - s.list.maxOf { it.postedAt })}"
        return parts.joinToString(" · ")
    }

    private fun listLine(s: NotificationsHub.State) = if (s.list.isEmpty()) "None now" else "${s.list.size} · open the list"

    companion object {
        /** What being off means: the action stays, and says so (#67 keeps an off pane's entities). */
        fun offNote(actionName: String) =
            "Notifications are off. $actionName stays in Home Assistant and answers “Notifications are off on this panel” until you turn them on."

        /** "now", "2 min ago", "3 h ago". */
        fun ago(ms: Long): String = when {
            ms < 60_000 -> "now"
            ms < 3_600_000 -> "${ms / 60_000} min ago"
            else -> "${ms / 3_600_000} h ago"
        }
    }
}
