/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Changing a setting, the same on every settings surface (#242). The panel's menu and the Manage page
 * both change an item through [change]: it checks the value as the panel's control limits it (a number
 * in the slider's range, one of the duration's choices, an option's id) and then calls the item's own
 * `save`. So the checks live here once, and a browser can't send what the panel wouldn't take.
 */
object SettingsChange {

    /** The key of a section's on/off ([SettingsSection.master]). */
    const val MASTER = "on"

    /**
     * The items of [section] a person can change, in page order, each with its key: [MASTER] for its
     * on/off, else the titles from the section down, `/`-joined ("Who may connect/IP addresses …").
     */
    fun changeable(section: SettingsSection): List<Pair<String, SettingsItem>> {
        fun walk(items: List<SettingsItem>, prefix: String): List<Pair<String, SettingsItem>> = items.flatMap { i ->
            when {
                i is SettingsItem.Group -> walk(i.items, prefix + i.title + "/")
                canChange(i) -> listOf(prefix + titleOf(i) to i)
                else -> emptyList()
            }
        }
        return listOfNotNull(section.master?.let { MASTER to it }) + walk(section.items, "")
    }

    /** The item at [key] in [section], or null when the page has no such item now. */
    fun find(section: SettingsSection, key: String): SettingsItem? = changeable(section).firstOrNull { it.first == key }?.second

    /** A kind of item a person changes (a Text only while [SettingsItem.Text.enabled]). */
    fun canChange(i: SettingsItem): Boolean = when (i) {
        is SettingsItem.Text -> runCatching { i.enabled() }.getOrDefault(false)
        is SettingsItem.Switch, is SettingsItem.Slider, is SettingsItem.Duration, is SettingsItem.TimeRange, is SettingsItem.Choice,
        is SettingsItem.SignIn -> true
        else -> false
    }

    /**
     * A pane's on/off needs a Home Assistant sign-in on the Manage page: in Home Assistant it is an
     * `authOnly` switch ([PaneSwitch.entity]), which only a connection with the panel's key may change.
     */
    fun needsHomeAssistant(key: String): Boolean = key == MASTER

    fun titleOf(i: SettingsItem): String = when (i) {
        is SettingsItem.Text -> i.title
        is SettingsItem.Switch -> i.title
        is SettingsItem.Slider -> i.title
        is SettingsItem.Duration -> i.title
        is SettingsItem.TimeRange -> i.title
        is SettingsItem.Choice -> i.title
        is SettingsItem.SignIn -> i.title
        is SettingsItem.Link -> i.title
        is SettingsItem.Group -> i.title
        is SettingsItem.States -> i.title
        is SettingsItem.Info -> runCatching { i.title() }.getOrDefault("")
        is SettingsItem.Native -> i.key
        is SettingsItem.Note, is SettingsItem.Status, is SettingsItem.Tiles -> ""
    }

    /**
     * Applies [raw], the value as text, to [item]: checked, then the item's `save`. [done] gets null when
     * it saved, else what went wrong, in words for the person. A switch takes `on` or `off`; a time range
     * `HH:MM-HH:MM` or empty for none. A save that throws is an answer too (#63 F8).
     */
    fun change(item: SettingsItem, raw: String, done: (String?) -> Unit) {
        val v = raw.trim()
        if (v.length > MAX_LENGTH) return done("That's too long.")
        if (v.any { it.code < 0x20 }) return done("That has characters a setting can't take.")
        try {
            when (item) {
                is SettingsItem.Text -> {
                    if (!canChange(item)) return done(CANT_NOW)
                    val ok = when (item.kind) {
                        SettingsItem.TextKind.NUMBER -> v.all(Char::isDigit)
                        SettingsItem.TextKind.DECIMAL -> v.isEmpty() || DECIMAL.matches(v)
                        else -> true
                    }
                    if (!ok) return done(if (item.kind == SettingsItem.TextKind.NUMBER) "Use digits only." else "Use a number, like 1.5.")
                    item.save(v, done)
                }
                is SettingsItem.Switch -> when (v) {
                    "on" -> item.save(true, done)
                    "off" -> item.save(false, done)
                    else -> done("That isn't on or off.")
                }
                is SettingsItem.Slider -> {
                    val f = v.toFloatOrNull()?.takeIf { it.isFinite() } ?: return done("That isn't a number.")
                    if (f < item.min - EPS || f > item.max + EPS) return done("Pick from ${plain(item.min)} to ${plain(item.max)}.")
                    item.save(snap(f, item.min, item.max, item.step), done)
                }
                is SettingsItem.Duration -> {
                    val s = v.toIntOrNull()
                    if (s == null || (s !in item.choices && s != item.value())) return done(PICK)
                    item.save(s, done)
                }
                is SettingsItem.TimeRange -> {
                    if (v.isNotEmpty() && parseRange(v) == null) return done("Use times like 22:00, from 00:00 to 23:59.")
                    item.save(parseRange(v)?.let { (a, b) -> "${hhmm(a)}-${hhmm(b)}" }.orEmpty(), done)
                }
                is SettingsItem.Choice -> {
                    if (item.options().none { it.first == v }) return done(PICK)
                    item.save(v, done)
                }
                else -> done(CANT_HERE)
            }
        } catch (e: Throwable) {
            done(couldNot(e))
        }
    }

    /**
     * Saves a sign-in's [user] and [password] after the checks a stream's sign-in needs (RTSP and ONVIF
     * put them in `user:password`): a name with no colon or spaces, a password, both within their length.
     * An empty [password] keeps the one stored, so the name can change alone. The password is never in
     * what [done] says.
     */
    fun signIn(item: SettingsItem.SignIn, user: String, password: String, done: (String?) -> Unit) {
        val u = user.trim()
        val pass = password.ifEmpty { runCatching { item.reveal() }.getOrNull().orEmpty() }
        val problem = when {
            u.isEmpty() -> "Type a user name."
            u.length > USER_MAX -> "Use at most $USER_MAX characters for the user name."
            u.any { it == ':' || it.isWhitespace() || it.code < 0x20 } -> "A user name can't have a colon or spaces."
            pass.isEmpty() -> "Type a password."
            pass.length > PASSWORD_MAX -> "Use at most $PASSWORD_MAX characters for the password."
            pass.any { it.code < 0x20 || it.code == 0x7f } -> "That password has characters a sign-in can't take."
            else -> null
        }
        if (problem != null) return done(problem)
        try { item.save(u to pass, done) } catch (e: Throwable) { done(COULD_NOT_SIGN_IN) }
    }

    /** Clear: the sign-in is removed. */
    fun clear(item: SettingsItem.SignIn, done: (String?) -> Unit) {
        try { item.save(null, done) } catch (e: Throwable) { done(COULD_NOT_SIGN_IN) }
    }

    /** A typed value as its row shows it: [SettingsItem.Text.shown], else its hint while it's empty. */
    fun shown(i: SettingsItem.Text): String {
        val v = i.value()
        return if (v.isBlank()) i.shown(v).ifBlank { i.hint } else i.shown(v)
    }

    /** The picked option's name, or its id when it has none. */
    fun shown(i: SettingsItem.Choice): String { val v = i.value(); return i.options().firstOrNull { it.first == v }?.second ?: v }

    /** The slider's value on its steps, as the panel's slider snaps it. */
    fun snap(v: Float, min: Float, max: Float, step: Float): Float {
        if (step <= 0f) return v.coerceIn(min, max)
        val n = ((v.coerceIn(min, max) - min) / step).roundToInt()
        return (min + n * step).coerceIn(min, max)
    }

    /** 2.5, 0.1, 100: as typed. */
    fun plain(f: Float): String = java.math.BigDecimal(f.toString()).stripTrailingZeros().toPlainString()

    /** What a save that threw says. */
    fun couldNot(e: Throwable): String = "That didn't save: ${e.message ?: e.javaClass.simpleName}."

    fun hhmm(min: Int): String = String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60)

    /** `22:00-07:00` as minutes after midnight, or null. */
    fun parseRange(w: String): Pair<Int, Int>? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""").find(w) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        if (m1.toInt() > 59 || m2.toInt() > 59) return null
        val a = h1.toInt() * 60 + m1.toInt(); val b = h2.toInt() * 60 + m2.toInt()
        return if (a < 24 * 60 && b < 24 * 60) a to b else null
    }

    /** A change that takes effect when the app starts again ([SettingsItem.Text.restartsApp]): the banner's words on both surfaces. */
    const val RESTART_TEXT = "This takes effect after the app restarts. It takes about 5 seconds."

    /** Under "In Home Assistant", when a row is dimmed. */
    const val OFF_BY_DEFAULT = "Dimmed: off by default in Home Assistant. Turn them on there to keep their history."

    /** Whether a saved change waits for the app to start again. */
    fun restarts(i: SettingsItem): Boolean = (i as? SettingsItem.Text)?.restartsApp == true || (i as? SettingsItem.Switch)?.restartsApp == true

    const val USER_MAX = 64
    const val PASSWORD_MAX = 128

    /** A sign-in's save that threw: no exception text, which could hold what was typed. */
    const val COULD_NOT_SIGN_IN = "That sign-in didn't save."

    const val CANT_NOW = "This can't change now."
    const val CANT_HERE = "This can't be changed here."
    const val PICK = "Pick one of the choices."

    private const val MAX_LENGTH = 2000
    private const val EPS = 1e-4f
    private val DECIMAL = Regex("""^\d+(\.\d+)?$""")
}
