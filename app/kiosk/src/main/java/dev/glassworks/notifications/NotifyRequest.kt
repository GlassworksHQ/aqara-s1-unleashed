/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import org.json.JSONException
import org.json.JSONObject

/**
 * Reads one call of the `notify` action (#118): `message`, `title` and `data`, the shape of the HA
 * Companion app's `notify.mobile_app_*`.
 *
 * Home Assistant makes every ESPHome action argument required (2026.11, `esphome/manager.py`
 * `_async_register_service`), so the optional fields travel in one argument, `data`, as a JSON
 * object. Empty `data` and `{}` both mean none. The keys are Companion's:
 *
 * | Key | Meaning |
 * |---|---|
 * | `tag` | Replaces the notification with the same tag. With `message: clear_notification`, closes it |
 * | `priority` | `low`, `normal`, `high`, `critical` (also Android's `high`/`normal`). iOS's `push.interruption-level` when there is no `priority` |
 * | `image` | A path or URL on the panel's own Home Assistant (`/api/camera_proxy/camera.porch`) |
 * | `entity_id` | A camera (`camera.porch`), when there is no `image` (Companion's iOS camera key) |
 * | `actions` | Up to 3 of `{"action": id, "title": words, "uri": path}` |
 * | `sound` | `chime`, `doorbell`, `alert` or `none`. Default: from the priority |
 * | `timeout` | Seconds until it closes by itself |
 * | `url` | A dashboard view on the panel's own Home Assistant, opened full screen |
 * | `sticky` | Tapping an action leaves it open |
 *
 * Other keys are ignored, so a script written for a phone (`channel`, `color`, `ttl`, …) still
 * works. A mistake throws [IllegalArgumentException] in plain words; the action's handler lets it
 * through, and Home Assistant shows it to whoever ran the script.
 */
object NotifyRequest {
    /** Companion's word for "close the notification with this tag". */
    const val CLEAR = "clear_notification"
    const val MAX_ACTIONS = 3
    const val MAX_TITLE = 200
    const val MAX_MESSAGE = 2000
    /** A day: longer than that is a mistake, not a timeout. */
    const val MAX_TIMEOUT_S = 86_400

    /** [haBase] is the panel's Home Assistant origin, which every link must be on. */
    fun parse(message: String, title: String, data: String, haBase: String?): NotifyCommand {
        val d = parseData(data)
        val tag = d.optString("tag", "").trim().takeIf { it.isNotEmpty() }
        if (message.trim() == CLEAR) {
            requireNotNull(tag) { "clear_notification needs a tag in data" }
            return NotifyCommand.Clear(tag)
        }
        val t = title.trim()
        val m = message.trim()
        require(t.isNotEmpty() || m.isNotEmpty()) { "a notification needs a title or a message" }

        val priority = priority(d)
        val sound = d.optString("sound", "").trim().takeIf { it.isNotEmpty() }?.let {
            Sound.parse(it) ?: throw IllegalArgumentException(
                "unknown sound '$it'; the panel has ${Sound.entries.joinToString { s -> s.id }}",
            )
        } ?: Sound.default(priority)

        return NotifyCommand.Post(
            Notification(
                title = clip(t, MAX_TITLE),
                message = clip(m, MAX_MESSAGE),
                tag = tag,
                priority = priority,
                image = image(d, haBase),
                actions = actions(d, haBase),
                sound = sound,
                timeoutS = timeout(d),
                url = link(d, "url", haBase),
                sticky = d.optBoolean("sticky", false),
            ),
        )
    }

    private fun parseData(data: String): JSONObject {
        val s = data.trim()
        if (s.isEmpty()) return JSONObject()
        return try {
            JSONObject(s)
        } catch (e: JSONException) {
            throw IllegalArgumentException("data must be a JSON object, like {\"tag\": \"door\"}")
        }
    }

    private fun priority(d: JSONObject): Priority {
        val raw = d.optString("priority", "").trim().takeIf { it.isNotEmpty() }
            ?: d.optJSONObject("push")?.optString("interruption-level", "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Priority.NORMAL
        return Priority.parse(raw) ?: throw IllegalArgumentException(
            "unknown priority '$raw'; use low, normal, high or critical",
        )
    }

    private fun image(d: JSONObject, haBase: String?): String? {
        val image = d.optString("image", "").trim()
        if (image.isNotEmpty()) return link(d, "image", haBase)
        val entity = d.optString("entity_id", "").trim().takeIf { it.isNotEmpty() } ?: return null
        val path = HaUrls.cameraProxy(entity)
            ?: throw IllegalArgumentException("entity_id must be a camera, like camera.porch")
        return HaUrls.resolve(haBase, path) ?: throw noHa()
    }

    private fun actions(d: JSONObject, haBase: String?): List<NotificationAction> {
        if (!d.has("actions")) return emptyList()
        val a = d.optJSONArray("actions") ?: throw IllegalArgumentException("actions must be a list")
        require(a.length() <= MAX_ACTIONS) { "at most $MAX_ACTIONS actions, got ${a.length()}" }
        val out = ArrayList<NotificationAction>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: throw IllegalArgumentException("each action needs an action and a title")
            val id = o.optString("action", "").trim()
            val words = o.optString("title", "").trim()
            require(id.isNotEmpty() && words.isNotEmpty()) { "each action needs an action and a title" }
            require(id != "REPLY") { "the panel has no keyboard for REPLY" }
            require(out.none { it.id == id }) { "two actions are both '$id'" }
            out += NotificationAction(id, clip(words, 40), link(o, "uri", haBase))
        }
        return out
    }

    private fun timeout(d: JSONObject): Int? {
        if (!d.has("timeout") || d.isNull("timeout")) return null
        val v = d.opt("timeout")
        val s = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        } ?: throw IllegalArgumentException("timeout must be a number of seconds")
        require(s > 0 && s <= MAX_TIMEOUT_S) { "timeout must be between 1 and $MAX_TIMEOUT_S seconds" }
        return Math.ceil(s).toInt()
    }

    /** [key] of [o] as a URL on the panel's Home Assistant, null when absent; throws when it points elsewhere. */
    private fun link(o: JSONObject, key: String, haBase: String?): String? {
        val raw = o.optString(key, "").trim().takeIf { it.isNotEmpty() } ?: return null
        if (haBase == null) throw noHa()
        return HaUrls.resolve(haBase, raw) ?: throw IllegalArgumentException(
            "$key must be a path on this panel's Home Assistant, like /lovelace/0",
        )
    }

    private fun noHa() = IllegalArgumentException("the panel doesn't know its Home Assistant's address yet")

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
