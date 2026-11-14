/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

/**
 * How much a notification may interrupt (#118). What each one does is [Delivery.of].
 *
 * The names are the panel's own. [parse] also takes the HA Companion app's words, so a script
 * written for a phone keeps its meaning: Android's `priority` (`high`, `normal`) and iOS's
 * `interruption-level` (`passive`, `active`, `time-sensitive`, `critical`).
 */
enum class Priority(val id: String) {
    LOW("low"), NORMAL("normal"), HIGH("high"), CRITICAL("critical");

    companion object {
        fun parse(s: String): Priority? = when (s.trim().lowercase()) {
            "low", "passive", "min" -> LOW
            "normal", "default", "active" -> NORMAL
            "high", "time-sensitive", "max" -> HIGH
            "critical" -> CRITICAL
            else -> null
        }
    }
}

/** The sounds the panel has built in. [NONE] is silence. */
enum class Sound(val id: String) {
    NONE("none"), CHIME("chime"), DOORBELL("doorbell"), ALERT("alert");

    companion object {
        fun parse(s: String): Sound? = entries.firstOrNull { it.id == s.trim().lowercase() }

        /** The sound a notification gets when it names none. */
        fun default(p: Priority) = when (p) {
            Priority.LOW -> NONE
            Priority.NORMAL, Priority.HIGH -> CHIME
            Priority.CRITICAL -> ALERT
        }
    }
}

/**
 * One button on a notification. Tapping it reports [id] to Home Assistant (Companion's `action`).
 * [uri], if set, is a page on the panel's own Home Assistant that the tap also opens, already checked
 * by [HaUrls].
 */
data class NotificationAction(val id: String, val title: String, val uri: String? = null)

/**
 * A notification as the panel keeps it. Every URL in it ([image], [url], an action's `uri`) is an
 * absolute URL on the panel's own Home Assistant: [NotifyRequest] checked it.
 */
data class Notification(
    val title: String,
    val message: String,
    /** Companion's `tag`: a new notification with the same tag replaces this one, and `clear_notification` closes it. */
    val tag: String? = null,
    val priority: Priority = Priority.NORMAL,
    /** A picture to show, fetched with the panel's sign-in (a `camera_proxy` URL, `/local/…`, `/media/…`). */
    val image: String? = null,
    val actions: List<NotificationAction> = emptyList(),
    val sound: Sound = Sound.default(priority),
    /** Seconds until it closes by itself. Null: it stays until it is dismissed or cleared. */
    val timeoutS: Int? = null,
    /** A dashboard view to open full screen instead of a card, e.g. `/dashboard-door/0`. */
    val url: String? = null,
    /** Companion's `sticky`: tapping an action does not close it. */
    val sticky: Boolean = false,
)

/** What one call of the `notify` action asks for. */
sealed interface NotifyCommand {
    data class Post(val notification: Notification) : NotifyCommand

    /** Companion's `message: clear_notification`: close the notification with [tag], on this panel. */
    data class Clear(val tag: String) : NotifyCommand
}
