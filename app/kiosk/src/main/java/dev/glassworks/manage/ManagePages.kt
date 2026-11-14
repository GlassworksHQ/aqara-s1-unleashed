/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.NOT_RUNNING
import dev.glassworks.pane.ChipTone
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.chipTone
import dev.glassworks.pane.chipWord
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.brand.Tokens
import dev.glassworks.web.PageShell
import dev.glassworks.web.PageShell.Look
import dev.glassworks.web.PageShell.esc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Manage page's HTML (#33's approved design), on the shell the QR pages use ([PageShell]). No
 * scripts: the nav folds into a `<details>` menu on a phone, filters are links, actions are forms.
 * Everything from outside (names, log lines, settings values) is escaped here.
 */
class ManagePages(private val zone: TimeZone = TimeZone.getDefault(), private val clock: () -> Long = System::currentTimeMillis) {

    /** Where a page sits in the nav: a fixed page or a settings section / pane by id. */
    data class Place(val path: String, val title: String)

    /**
     * The signed-in browser, for the nav's foot and the forms' CSRF field. [homeAssistant]: it signed in with
     * Home Assistant, which a pane's on/off needs ([SettingsChange.needsHomeAssistant]).
     */
    class Viewer(val who: String, val csrf: String, val homeAssistant: Boolean = true)

    // ------------------------------------------------------------------ sign in

    /**
     * "Manage this panel." One form: HA's button posts to `/manage/ha`, the code link to `/manage/code`, so
     * the remember switch counts for both. [haOk]: HA's address is known and local, so HA's button shows.
     */
    fun signIn(name: String, next: String, haOk: Boolean, problem: String? = null): String {
        val remember = """<div class="rem"><label class="sw"><input type="checkbox" name="remember" value="1" checked> Remember this browser for 30 days</label></div>"""
        val form = """<form method="post" action="/manage/ha"><input type="hidden" name="next" value="${esc(next)}">""" +
            (if (haOk) """<button class="btn" type="submit">Continue with Home Assistant <span aria-hidden="true">↗</span></button>$remember
<div class="rule"></div><p class="alt">Home Assistant down? <button class="lk" type="submit" formaction="/manage/code">Use the code on the panel</button></p>"""
            else """<button class="btn" type="submit" formaction="/manage/code">Show a code on the panel</button>$remember""") + "</form>"
        val text = when {
            problem != null -> esc(problem)
            haOk -> "Sign in with a Home Assistant administrator."
            else -> "Use the code the panel shows. The panel has no Home Assistant on your network to sign in with."
        }
        return PageShell.qrPage(name, "Manage this panel", text, if (problem != null) Look.PROBLEM else Look.START, button = form, extraCss = SIGN_IN_CSS)
    }

    /** The code dialog over [behind] (#33's drawing 07): six boxes, Cancel and the action. */
    fun codeDialog(name: String, title: String, text: String, action: String, actionLabel: String, cancel: String,
                   hidden: Map<String, String>, problem: String?, danger: Boolean, behindHtml: String = ""): String {
        val fields = hidden.entries.joinToString("") { (k, v) -> """<input type="hidden" name="${esc(k)}" value="${esc(v)}">""" }
        val body = """<div class="dim">$behindHtml</div><div class="scrim"><form class="dlg" method="post" action="${esc(action)}">$fields
<h2>${esc(title)}</h2><p>${esc(text)}</p>
<div class="code"><span></span><span></span><span></span><span></span><span></span><span></span>
<input name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required autofocus aria-label="The 6-digit code"></div>
${if (problem != null) """<p class="err">${esc(problem)}</p>""" else ""}
<div class="dbtns"><a class="pill" href="${esc(cancel)}">Cancel</a><button class="pill${if (danger) " danger" else " primary"}" type="submit">${esc(actionLabel)}</button></div></form></div>"""
        return PageShell.document(name, MANAGE_CSS + DIALOG_CSS, body)
    }

    /** A page that only loads [to] from here, so a link from another site (HA's "Visit") arrives with the SameSite=Strict cookie. */
    fun bounce(name: String, to: String): String =
        PageShell.document(name, "", """<meta http-equiv="refresh" content="0;url=${esc(to)}"><noscript></noscript>""")

    // ------------------------------------------------------------------ the signed-in frame

    private fun nav(s: PanelStatus, current: String, viewer: Viewer): String {
        fun item(path: String, title: String, chip: String = "", grey: Boolean = false) =
            """<a class="ni${if (path == current) " on" else ""}${if (grey) " grey" else ""}" href="${esc(path)}"${if (path == current) " aria-current=\"page\"" else ""}><span class="nt">${esc(title)}</span>$chip</a>"""
        fun pane(p: PaneRow) = item("/" + p.id, p.title, chip(p.state), grey = p.state.kind == PaneState.Kind.CANT_RUN)
        // The core's own sections: every one that isn't a pane's or a page of its own (Log, About).
        val core = s.sections.filter { sec -> s.panes.none { it.sectionId == sec.id } && sec.id !in OWN_PAGES }
        // As the panel's rail (#243): each pane's chip after its name; panes that are off fold under "Off · N".
        val (off, on) = s.panes.partition { it.state.kind == PaneState.Kind.OFF }
        val panes = on.joinToString("") { pane(it) } + if (off.isEmpty()) "" else
            """<details class="off"${if (off.any { "/" + it.id == current }) " open" else ""}><summary>Off · ${off.size}</summary>${off.joinToString("") { pane(it) }}</details>"""
        return """<div class="brand">${PageShell.mark(Look.DONE, "bm")}<div><b>${esc(s.name)}</b><small>${esc(if (s.name == "Glassworks Panel") s.frame else "Glassworks Panel · ${s.frame}")}</small></div></div>
<nav>${item("/", "Overview")}""" +
            (if (core.isNotEmpty()) """<h3>Panel</h3>${core.joinToString("") { item("/" + it.id, it.title) }}""" else "") +
            (if (s.panes.isNotEmpty()) "<h3>Panes</h3>$panes" else "") +
            """<h3>System</h3>${item("/log", "Log")}${item("/security", "Security")}${item("/about", "About")}</nav>
<form class="who" method="post" action="/manage/signout">${csrf(viewer)}<span><i class="dot ok"></i><span class="nm" title="${esc(viewer.who)}">Signed in · ${esc(viewer.who)}</span></span><button type="submit">Sign out</button></form>"""
    }

    /** The page: the nav, the phone's header, [main], and [overlay] over it all (a dialog). */
    private fun frame(s: PanelStatus, current: String, title: String, viewer: Viewer, main: String, notice: String? = null, overlay: String = ""): String {
        val n = nav(s, current, viewer)
        val note = notice?.let { """<div class="notice" role="status"><i class="dot ok"></i>${esc(it)}</div>""" }.orEmpty()
        // Under a dialog the page is inert: Tab stays in the dialog, as in the panel's sheet.
        val inert = if (overlay.isEmpty()) "" else " inert"
        val body = """<aside class="side"$inert>$n</aside>
<header class="top"$inert>${PageShell.mark(Look.DONE, "m")}<span>${esc(if (current == "/") s.name else title)}</span><details class="menu"><summary aria-label="Menu"><span></span><span></span></summary><div class="drop">$n</div></details></header>
<main class="main"$inert>$note$main</main>$overlay"""
        return PageShell.document("$title · ${s.name}", MANAGE_CSS + DIALOG_CSS, body)
    }

    private fun csrf(v: Viewer) = """<input type="hidden" name="csrf" value="${esc(v.csrf)}">"""

    private fun action(v: Viewer, path: String, label: String, cls: String = "pill") =
        """<form method="post" action="${esc(path)}">${csrf(v)}<button class="$cls" type="submit">${esc(label)}</button></form>"""

    /** The pane's chip: its word ([chipWord]) and dot ([chipTone]), the same as the panel's rail (#243). */
    private fun chip(state: PaneState): String {
        val label = state.chipWord ?: return ""
        val tone = when (state.chipTone) { ChipTone.OK -> "ok"; ChipTone.PROBLEM -> "bad"; ChipTone.QUIET -> "off" }
        return """<span class="chip" aria-label="status ${esc(label)}"><i class="dot $tone"></i>${esc(label)}</span>"""
    }

    // ------------------------------------------------------------------ pages

    fun overview(s: PanelStatus, v: Viewer, actions: PanelActions, notice: String?): String {
        val tiles = s.tiles.joinToString("") { t ->
            val dot = t.tone?.let { """<i class="dot ${toneClass(it)}"></i>""" }.orEmpty()
            val inner = """<small>${esc(t.label)}</small><b>$dot${esc(t.value)}</b><span>${esc(t.detail)}</span>"""
            if (t.href != null) """<a class="tile" href="${esc(t.href)}">$inner</a>""" else """<div class="tile">$inner</div>"""
        }
        val panes = s.panes.joinToString("") { p ->
            """<a class="row" href="/${esc(p.id)}"><div><b>${esc(p.title)}</b><span>${esc(p.state.detail)}</span></div>${chip(p.state)}<i class="chev" aria-hidden="true">›</i></a>"""
        }
        val buttons = action(v, "/action/reload", "Reload dashboard") + action(v, "/action/restart", "Restart app") +
            (if (actions.canReboot) action(v, "/action/reboot", "Reboot") else "") + action(v, "/action/update", "Check for updates")
        val main = """<section class="card hero">${PageShell.mark(Look.DONE, "hm")}<div class="ht"><h1><i class="dot ok"></i>Running</h1>
<p class="meta">Glassworks ${esc(s.version)} · up ${esc(duration(s.uptimeMs))}</p></div><div class="acts">$buttons</div></section>
<section class="tiles">$tiles</section>""" +
            (if (s.panes.isNotEmpty()) """<section class="card list"><div class="lh"><b>Panes</b><span>${s.panes.size} in this frame</span></div>$panes</section>""" else "")
        return frame(s, "/", "Overview", v, main, notice)
    }

    /**
     * A settings page, drawn as the panel's menu draws it (#242): the heading with "Panes ›" for a pane,
     * its on/off at top right, why it can't run, then its items, each changeable here as on the panel.
     * A pane's [state] puts its chip after the heading on a phone, where the nav is folded away.
     * [dialog]: an item's edit or a question over the page. [problem]: what went wrong with the last change.
     * [restart]: a change waits for the app to start again.
     */
    fun section(s: PanelStatus, v: Viewer, path: String, title: String, state: PaneState?, sec: SettingsSection?,
                links: List<Pair<String, String>> = emptyList(), dialog: Dialog? = null, problem: String? = null, restart: Boolean = false,
                notice: String? = null): String {
        val heading = sec?.heading ?: title
        val why = sec?.let { runCatching { it.unavailable() }.getOrNull() }
        val master = sec?.master?.takeIf { why == null }?.let { m -> toggle(v, sec.id, SettingsChange.MASTER, m, heading, locked = !v.homeAssistant) }.orEmpty()
        val head = """<div class="phr">${if (state != null) """<span class="crumb">Panes ›</span>""" else ""}<h1 class="ph">${esc(heading)}</h1>""" +
            """${state?.let { """<span class="pchip">${chip(it)}</span>""" }.orEmpty()}$master</div>"""
        val lines = listOfNotNull(
            why?.let { """<p class="sub">${esc("This pane can't run on this panel: $it.")}</p>""" },
            if (master.isNotEmpty() && !v.homeAssistant) """<p class="sub">${esc(NEEDS_HA)}</p>""" else null,
            problem?.let { """<p class="err" role="alert">${esc(it)}</p>""" },
            if (restart) """<div class="banner"><i class="dot work"></i><span>${esc(SettingsChange.RESTART_TEXT)}</span>${action(v, "/action/restart", "Restart now", "pill primary")}</div>""" else null,
        ).joinToString("")
        val card = if (sec == null) """<section class="card list pg"><div class="row static note"><span>${esc(NOT_RUNNING)}</span></div></section>"""
            else """<section class="card list pg">${items(v, path, sec.id, sec.items)}</section>"""
        val off = state?.kind == PaneState.Kind.OFF
        val more = if (off || links.isEmpty()) "" else """<section class="card list">${links.joinToString("") { (label, href) ->
            """<a class="row" href="${esc(href)}"><div><b>${esc(label)}</b></div><i class="chev" aria-hidden="true">›</i></a>""" }}</section>"""
        val overlay = if (dialog != null && sec != null) dialog(v, path, sec, dialog) else ""
        return frame(s, path, title, v, head + lines + card + more, notice, overlay)
    }

    /** A question or an item's edit over a settings page, as the panel's sheet ([Dialog]). */
    sealed interface Dialog {
        val key: String

        /**
         * Change the item at [key]; [problem]: why the last try didn't save, and [typed]: what was sent then,
         * shown again. [revealed]: a sign-in's password, after the person asked to see it.
         */
        class Edit(override val key: String, val problem: String? = null, val typed: String? = null, val revealed: String? = null) : Dialog

        /** Ask [ask] before setting the switch at [key] to [value] (`on`/`off`). */
        class Confirm(override val key: String, val value: String, val ask: SettingsItem.Confirm) : Dialog
    }

    private fun fields(v: Viewer, section: String, key: String) =
        csrf(v) + """<input type="hidden" name="section" value="${esc(section)}"><input type="hidden" name="key" value="${esc(key)}">"""

    /** The on/off: a switch that posts the other value. [locked]: shown, but this browser can't change it ([NEEDS_HA]). */
    private fun toggle(v: Viewer, section: String, key: String, item: SettingsItem.Switch, label: String, locked: Boolean = false): String {
        val on = runCatching { item.value() }.getOrDefault(false)
        val lock = if (locked) """ disabled title="${esc(NEEDS_HA)}"""" else ""
        return """<form class="tgf" method="post" action="/settings">${fields(v, section, key)}<button class="tg${if (on) " on" else ""}" type="submit" name="value" value="${if (on) "off" else "on"}" role="switch" aria-checked="$on" aria-label="${esc(label)}"$lock><i></i></button></form>"""
    }

    private fun items(v: Viewer, path: String, section: String, items: List<SettingsItem>, prefix: String = ""): String = items.joinToString("") { item ->
        val key = prefix + SettingsChange.titleOf(item)
        fun edit(title: String, value: String) =
            """<a class="row" href="${esc(path)}?edit=${esc(enc(key))}"><div><b>${esc(title)}</b><span>${esc(value)}</span></div><i class="chev" aria-hidden="true">›</i></a>"""
        when (item) {
            is SettingsItem.Text -> if (SettingsChange.canChange(item)) edit(item.title, safe { SettingsChange.shown(item) })
                else row(item.title, safe { SettingsChange.shown(item) }, dim = true)
            is SettingsItem.Switch -> """<div class="row"><div><b>${esc(item.title)}</b>${if (item.detail.isNotEmpty()) "<span>${esc(item.detail)}</span>" else ""}</div>${toggle(v, section, key, item, item.title)}</div>"""
            is SettingsItem.Slider -> {
                val now = runCatching { item.value() }.getOrDefault(item.min)
                """<form class="row sl" method="post" action="/settings">${fields(v, section, key)}<div class="slh"><b>${esc(item.title)}</b><em>${esc(safe { item.shown(now) })}</em></div>""" +
                    """<div class="slr"><input type="range" name="value" min="${num(item.min)}" max="${num(item.max)}" step="${num(item.step)}" value="${num(now)}" aria-label="${esc(item.title)}"><button class="pill sm" type="submit">Save</button></div></form>"""
            }
            is SettingsItem.Duration -> edit(item.title, safe { item.shown(item.value()) })
            is SettingsItem.TimeRange -> edit(item.title, safe { item.shown(item.value()) })
            is SettingsItem.Choice -> edit(item.title, safe { SettingsChange.shown(item) })
            is SettingsItem.SignIn -> edit(item.title, safe { item.shown(item.isSet()) })
            is SettingsItem.Info -> row(safe { item.title() }, safe { item.detail() })
            is SettingsItem.Note -> """<div class="row static note"><span>${esc(safe { item.text() })}</span></div>"""
            // Opens something on the panel itself (another app, the licences): say so, rather than drop the row.
            is SettingsItem.Link -> row(item.title, item.detail, right = ON_THE_PANEL, dim = item.detail.isEmpty())
            is SettingsItem.Group -> """<div class="grp"><div class="gl">${esc(item.title)}</div>${items(v, path, section, item.items, "$prefix${item.title}/")}</div>"""
            // A view only the panel can draw: say so, rather than drop the row.
            is SettingsItem.Native -> row(item.title.ifEmpty { nativeTitle(section) }, "Shown on the panel's own settings", right = ON_THE_PANEL)
            is SettingsItem.Status -> """<div class="st"><i class="dot ${if (runCatching { item.ok() }.getOrDefault(false)) "ok" else "off"}"></i><span>${esc(safe { item.text() })}</span></div>"""
            is SettingsItem.States -> {
                val list = runCatching { item.rows() }.getOrDefault(emptyList())
                """<div class="grp"><div class="gl">${esc(item.title)}</div>${list.joinToString("") { r ->
                    """<div class="sr${if (r.offByDefault) " quiet" else ""}"><span>${esc(r.name)}</span><em>${esc(r.value)}</em></div>""" }}""" +
                    (if (list.any { it.offByDefault }) """<p class="foot">${esc(SettingsChange.OFF_BY_DEFAULT)}</p>""" else "") + "</div>"
            }
            is SettingsItem.Tiles -> tiles(item)
        }
    }

    /** Readings as tiles, [SettingsItem.Tiles.columns] to a row, each with its last hour or how full it is. */
    private fun tiles(item: SettingsItem.Tiles): String {
        val list = runCatching { item.tiles() }.getOrDefault(emptyList())
        return """<div class="ptl" style="--cols:${item.columns.coerceIn(1, 6)}">""" + list.joinToString("") { t ->
            val unit = if (t.unit.isEmpty()) "" else """<u>${esc(t.unit)}</u>"""
            val under = when {
                t.meter != null -> """<i class="mt"><i style="width:${(t.meter.coerceIn(0f, 1f) * 100).toInt()}%"></i></i>"""
                (t.history?.size ?: 0) >= 2 -> spark(t.history!!)
                else -> ""
            }
            """<div class="pt"><small>${esc(t.name)}</small><b>${esc(t.value)}$unit</b>${if (t.detail.isNotEmpty()) "<span>${esc(t.detail)}</span>" else ""}$under</div>"""
        } + "</div>"
    }

    /** The last hour as a line, oldest first. */
    private fun spark(h: List<Float>): String {
        val lo = h.min(); val hi = h.max(); val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val pts = h.mapIndexed { i, y -> "${num(i * 100f / (h.size - 1))},${num(22f - (y - lo) / span * 20f)}" }.joinToString(" ")
        return """<svg class="sp" viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden="true"><polyline points="$pts"/></svg>"""
    }

    /** The edit for [d.key] in [sec], or the question before a switch: the panel's sheet as a dialog. */
    private fun dialog(v: Viewer, path: String, sec: SettingsSection, d: Dialog): String {
        val item = SettingsChange.find(sec, d.key) ?: return ""
        fun sheet(title: String, hint: String, body: String, action: String?, problem: String?, more: String = "") =
            """<div class="scrim"><form class="dlg" method="post" action="/settings" role="dialog" aria-modal="true" aria-labelledby="dlg-t">${fields(v, sec.id, d.key)}""" +
                // Enter submits the form's first button: make that the action, not Show or Clear.
                (if (action != null) """<button class="dflt" type="submit" tabindex="-1" aria-hidden="true">${esc(action)}</button>""" else "") +
                """<h2 id="dlg-t">${esc(title)}</h2>""" +
                (if (hint.isNotEmpty()) "<p>${esc(hint)}</p>" else "") + body + (problem?.let { """<p class="err" role="alert">${esc(it)}</p>""" }.orEmpty()) +
                """<div class="dbtns"><a class="pill" href="${esc(path)}">Cancel</a>$more${action?.let { """<button class="pill primary" type="submit">${esc(it)}</button>""" }.orEmpty()}</div></form></div>"""
        fun chips(now: String, options: List<Pair<String, String>>) = """<div class="pcs">""" + options.joinToString("") { (value, name) ->
            """<button class="pc${if (value == now) " on" else ""}" type="submit" name="value" value="${esc(value)}"${if (value == now) " aria-current=\"true\" autofocus" else ""}>${esc(name)}</button>"""
        } + "</div>"
        return when (d) {
            is Dialog.Confirm -> sheet(d.ask.title, d.ask.detail,
                """<input type="hidden" name="value" value="${esc(d.value)}"><input type="hidden" name="confirmed" value="1">""", d.ask.action, null)
            is Dialog.Edit -> when (item) {
                is SettingsItem.Text -> {
                    val mode = when (item.kind) { SettingsItem.TextKind.NUMBER -> "numeric"; SettingsItem.TextKind.DECIMAL -> "decimal"; SettingsItem.TextKind.ADDRESS -> "url"; else -> "text" }
                    sheet(item.title, item.hint, """<input class="fld" name="value" value="${esc(d.typed ?: safe { item.value() })}" placeholder="${esc(item.hint)}" inputmode="$mode" autocomplete="off" spellcheck="false" autofocus aria-label="${esc(item.title)}">""",
                        "Save", d.problem)
                }
                is SettingsItem.Duration -> {
                    val now = runCatching { item.value() }.getOrDefault(0)
                    val all = (item.choices + now).distinct().sortedWith(compareBy<Int> { it == 0 }.thenBy { it })
                    sheet(item.title, "", chips(now.toString(), all.map { it.toString() to safe { item.shown(it) }.removePrefix("After ") }), null, d.problem)
                }
                // The password is on the page only after Show ([Dialog.Edit.revealed]); otherwise its field starts empty.
                is SettingsItem.SignIn -> {
                    val set = runCatching { item.isSet() }.getOrDefault(false)
                    val pass = d.revealed?.let { """<input class="fld" type="text" name="password" value="${esc(it)}" autocomplete="off" spellcheck="false" aria-label="Password">""" }
                        ?: """<input class="fld" type="password" name="password" value="" placeholder="${if (set) "Password · leave empty to keep it" else "Password"}" autocomplete="new-password" aria-label="Password">"""
                    val show = when {
                        !set || d.revealed != null -> ""
                        v.homeAssistant -> """<button class="pill sm" type="submit" formaction="/settings/reveal">Show</button>"""
                        else -> """<button class="pill sm" type="button" disabled title="${esc(NEEDS_HA_REVEAL)}">Show</button>"""
                    }
                    sheet(item.title, if (set && !v.homeAssistant) NEEDS_HA_REVEAL else "",
                        """<div class="flds"><input class="fld" name="user" value="${esc(safe { item.user() })}" placeholder="User name" autocomplete="username" autocapitalize="none" spellcheck="false" autofocus aria-label="User name">""" +
                            """<div class="pw">$pass$show</div></div>""",
                        "Save", d.problem, more = if (set) """<button class="pill" type="submit" name="clear" value="1">Clear</button>""" else "")
                }
                is SettingsItem.Choice -> sheet(item.title, "", chips(safe { item.value() }, runCatching { item.options() }.getOrDefault(emptyList())), null, d.problem)
                is SettingsItem.TimeRange -> {
                    val now = safe { item.value() }
                    val (from, to) = SettingsChange.parseRange(now) ?: (22 * 60 to 7 * 60)
                    fun time(name: String, label: String, min: Int) =
                        """<label class="tm"><span>$label</span><input type="time" name="$name" value="${SettingsChange.hhmm(min)}" step="${item.stepMin * 60}" required></label>"""
                    sheet(item.title, "", """<label class="sw"><input type="checkbox" name="on" value="1"${if (now.isNotBlank()) " checked" else ""} autofocus> On</label>""" +
                        """<div class="tms">${time("from", "From", from)}${time("to", "To", to)}</div>""", "Save", d.problem)
                }
                else -> ""
            }
        }
    }

    private fun nativeTitle(section: String) = section.split('-', '_', '.').joinToString(" ").replaceFirstChar { it.uppercase() }

    private fun safe(f: () -> Any?): String = try { f()?.toString().orEmpty() } catch (_: Throwable) { "" }

    private fun num(f: Float): String = SettingsChange.plain(f)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** A name with its value under it, as the panel's rows; [right]: a word at the row's end. */
    private fun row(title: String, value: String, right: String? = null, dim: Boolean = false) =
        """<div class="row static${if (dim) " quiet" else ""}"><div><b>${esc(title)}</b>${if (value.isNotEmpty()) "<span>${esc(value)}</span>" else ""}</div>${right?.let { "<em>${esc(it)}</em>" }.orEmpty()}</div>"""

    /** The core's own section with [id] (Log's, About's) as a card, or nothing. */
    private fun ownCard(v: Viewer, s: PanelStatus, id: String): String =
        s.sections.firstOrNull { it.id == id }?.let { """<section class="card list">${items(v, "/$id", it.id, it.items)}</section>""" }.orEmpty()

    fun log(s: PanelStatus, v: Viewer, entries: List<LogEntry>, level: LogEntry.Level, search: String): String {
        fun chipLink(l: LogEntry.Level, label: String): String {
            val q = buildList { if (l != LogEntry.Level.INFO) add("level=" + l.name.lowercase()); if (search.isNotEmpty()) add("q=" + java.net.URLEncoder.encode(search, "UTF-8")) }
            return """<a class="fc${if (l == level) " on" else ""}" href="/log${if (q.isEmpty()) "" else "?" + q.joinToString("&")}">$label</a>"""
        }
        val rows = if (entries.isEmpty()) """<p class="empty">Nothing here${if (search.isNotEmpty() || level != LogEntry.Level.INFO) " that matches" else " yet"}.</p>"""
        else entries.joinToString("") { e ->
            val cls = when (e.level) { LogEntry.Level.WARNING -> "w"; LogEntry.Level.ERROR -> "e"; else -> "" }
            """<div class="lr"><time>${esc(time(e.timeMs))}</time><b class="$cls">${esc(e.source)}</b><span>${esc(e.text)}</span></div>"""
        }
        val main = """<div class="lt"><div><h1 class="ph">Log</h1><p class="sub">Kept across reboots, the last 7 days.</p></div>
<div class="dl"><a class="pill" href="/log/diagnostics.txt" download>Download diagnostics</a><small>Tokens and keys are removed</small></div></div>
<div class="filters">${chipLink(LogEntry.Level.INFO, "All")}${chipLink(LogEntry.Level.WARNING, "Warnings")}${chipLink(LogEntry.Level.ERROR, "Errors")}
<form class="search" method="get" action="/log">${if (level != LogEntry.Level.INFO) """<input type="hidden" name="level" value="${level.name.lowercase()}">""" else ""}<input type="search" name="q" value="${esc(search)}" placeholder="Search" aria-label="Search the log"></form></div>
${ownCard(v, s, "log")}<section class="card logs">$rows</section>"""
        return frame(s, "/log", "Log", v, main)
    }

    fun security(s: PanelStatus, v: Viewer, sessions: List<Session>, mine: String, notice: String?): String {
        val rows = sessions.joinToString("") { x ->
            val me = x.id == mine
            val detail = if (x.remember) "Remembered until ${day(x.expiresMs)}" else "Last used ${ago(x.lastUsedMs)}"
            val who = when (x.with) { SignedInWith.HOME_ASSISTANT -> x.user?.let { " · $it" }.orEmpty(); SignedInWith.PANEL_CODE -> " · with the panel's code" }
            """<div class="row static"><div><b>${esc(x.browser)}${if (me) """ · <span class="me">this browser</span>""" else ""}</b><span>${esc(detail + who)}</span></div>
<form method="post" action="/security/signout">${csrf(v)}<input type="hidden" name="id" value="${esc(x.id)}"><button class="pill" type="submit">Sign out</button></form></div>"""
        }
        val main = """<h1 class="ph">Security</h1><p class="sub">Who can manage this panel, and the actions that need you there.</p>
<section class="card list"><div class="lh"><b>Signed-in browsers</b></div>$rows</section>"""
        return frame(s, "/security", "Security", v, main, notice)
    }

    fun about(s: PanelStatus, v: Viewer): String {
        val rows = s.about.joinToString("") { (k, value) -> row(k, "", right = value) }
        val main = """<h1 class="ph">About</h1><p class="sub">This panel and the software it runs.</p>${ownCard(v, s, "about")}<section class="card list">$rows</section>"""
        return frame(s, "/about", "About", v, main)
    }

    fun notFound(s: PanelStatus, v: Viewer): String =
        frame(s, "", "Not found", v, """<h1 class="ph">Not found</h1><p class="sub">This panel has no such page. <a href="/">Back to the overview</a>.</p>""")

    // ------------------------------------------------------------------ words

    private fun toneClass(t: Tile.Tone) = when (t) { Tile.Tone.OK -> "ok"; Tile.Tone.WORKING -> "work"; Tile.Tone.PROBLEM -> "bad"; Tile.Tone.OFF -> "off" }

    private fun fmt(p: String) = SimpleDateFormat(p, Locale.ENGLISH).apply { timeZone = zone }

    /** Today: `09:41:07`. Earlier: `11 Nov 09:41`. */
    fun time(ms: Long): String {
        val today = fmt("yyyyMMdd").format(Date(clock()))
        return if (fmt("yyyyMMdd").format(Date(ms)) == today) fmt("HH:mm:ss").format(Date(ms)) else fmt("d MMM HH:mm").format(Date(ms))
    }

    private fun day(ms: Long) = fmt("d MMM").format(Date(ms))

    private fun ago(ms: Long): String {
        val m = (clock() - ms).coerceAtLeast(0) / 60_000
        return when {
            m < 2 -> "just now"
            m < 60 -> "$m minutes ago"
            m < 120 -> "an hour ago"
            m < 24 * 60 -> "${m / 60} hours ago"
            m < 48 * 60 -> "yesterday"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    companion object {
        /** Sections drawn on a page of their own under "System" in the nav, not under "Panel". */
        val OWN_PAGES = setOf("log", "security", "about")

        /** A pane's on/off on the page of a browser signed in with the panel's code. */
        const val NEEDS_HA = "Turning a pane on or off needs a Home Assistant sign-in. Sign out, then continue with Home Assistant."

        /** A sign-in's Show on the page of a browser signed in with the panel's code. */
        const val NEEDS_HA_REVEAL = "Showing the password needs a Home Assistant sign-in. It shows on the panel too."

        /** A row that opens something only the panel has. */
        const val ON_THE_PANEL = "On the panel"

        /** `up 3 days, 4 h`, `up 5 h, 12 min`, `up 3 min`. */
        fun duration(ms: Long): String {
            val min = ms / 60_000
            val h = min / 60
            val d = h / 24
            return when {
                d >= 1 -> "$d day${if (d == 1L) "" else "s"}, ${h % 24} h"
                h >= 1 -> "$h h, ${min % 60} min"
                else -> "$min min"
            }
        }

        private const val SIGN_IN_CSS = """h1{font-size:30px}@media (min-width:720px){h1{font-size:52px;letter-spacing:-.03em}.copy p{font-size:18px}}
.rem{margin-top:18px}.alt{font-size:15px}.act form{margin:0}"""

        val MANAGE_CSS = """body{display:block}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;flex:none;background:var(--faint)}.dot.ok{background:var(--green)}.dot.work{background:var(--amber)}.dot.bad{background:var(--rose)}.dot.off{background:var(--faint)}
.side{position:fixed;inset:0 auto 0 0;width:280px;padding:28px 18px;border-right:1px solid var(--edge);display:flex;flex-direction:column;overflow:auto}
.brand{display:flex;gap:14px;align-items:center;padding:0 12px 26px}.brand .bm{width:34px;height:34px;flex:none}.brand b{display:block;font-size:18px;font-weight:500}.brand small{color:var(--muted);font-size:14px}
nav{display:flex;flex-direction:column;gap:2px;flex:1}nav h3{margin:20px 12px 6px;font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:var(--faint);font-weight:600}
.ni{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-radius:12px;text-decoration:none;font-size:16px;border:1px solid transparent}
.ni:hover{background:var(--hover)}.ni.on{background:var(--glass);border-color:var(--edge)}.ni.grey{color:var(--faint)}
.who{display:flex;align-items:center;justify-content:space-between;gap:8px;border-top:1px solid var(--edge);margin-top:18px;padding:16px 12px 0;font-size:14px;color:var(--muted)}
.who>span{display:flex;align-items:center;gap:8px;min-width:0}.who .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.who button{background:none;border:0;color:var(--muted);font:inherit;cursor:pointer;padding:0;white-space:nowrap;flex:none}.who button:hover{color:var(--fg)}
.top{display:none}
.main{margin-left:280px;padding:40px 56px 64px;max-width:1336px}
.card{background:var(--glass);border:1px solid var(--edge);border-radius:22px;margin-bottom:18px}
.hero{display:grid;grid-template-columns:120px 1fr;grid-template-rows:auto auto;column-gap:40px;align-items:center;padding:32px 40px}
.hm{width:120px;height:112px;grid-row:1/3}.ht{align-self:end}.acts{grid-column:2;align-self:start}
.hero h1{display:flex;align-items:center;gap:14px;font-size:32px;font-weight:300;margin:0 0 4px}.meta{color:var(--muted);margin:0 0 20px;font-size:17px}
.acts{display:flex;flex-wrap:wrap;gap:10px}.acts form{margin:0}
.pill{display:inline-block;padding:11px 22px;border-radius:999px;background:var(--glass);border:1px solid var(--edge);color:var(--fg);font:inherit;font-size:16px;cursor:pointer;text-decoration:none}
.pill:hover{background:var(--hover)}.pill.danger{color:var(--rose);border-color:rgba(251,113,133,.5)}.pill.primary{background:var(--btn);color:var(--btx);border-color:transparent}
.tiles{display:grid;grid-template-columns:repeat(4,1fr);gap:18px;margin-bottom:18px}
.tile{background:var(--glass);border:1px solid var(--edge);border-radius:22px;padding:22px 24px;display:flex;flex-direction:column;gap:4px;min-width:0}
a.tile{text-decoration:none}a.tile:hover{background:var(--hover)}.tile small{color:var(--muted);font-size:14px}.tile b{display:flex;align-items:center;gap:10px;font-weight:400;font-size:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.tile span{color:var(--muted);font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.list{padding:8px 28px}.lh{display:flex;justify-content:space-between;align-items:baseline;padding:16px 0 12px;border-bottom:1px solid var(--edge)}.lh b{font-weight:500;font-size:17px}.lh span{color:var(--muted);font-size:14px}
.row{display:flex;align-items:center;gap:16px;padding:14px 0;border-bottom:1px solid var(--edge);text-decoration:none;min-height:64px}.row:last-child{border-bottom:0}
.row>div{flex:1;min-width:0}.row b{display:block;font-weight:400;font-size:17px}.row span{display:block;color:var(--muted);font-size:15px}.row em{font-style:normal;color:var(--muted);text-align:right;overflow-wrap:anywhere;max-width:55%}
.row form{margin:0}.me{display:inline!important;color:var(--ok-ink)!important;font-size:inherit!important}
.grp .lh{border-bottom:1px solid var(--edge)}.grp:not(:first-child) .lh{padding-top:22px}
.chip{display:inline-flex;align-items:center;gap:${Tokens.Chip.GAP}px;padding:${Tokens.Chip.PAD_V}px ${Tokens.Chip.PAD_END}px ${Tokens.Chip.PAD_V}px ${Tokens.Chip.PAD_START}px;border-radius:999px;border:1px solid var(--edge);background:var(--glass);font-size:${Tokens.Chip.TEXT}px;line-height:1.35;color:var(--muted);white-space:nowrap;flex:none}
.chip .dot{width:${Tokens.Chip.DOT}px;height:${Tokens.Chip.DOT}px}
.ni .nt{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.ni .chip{margin-left:8px}
details.off summary{list-style:none;cursor:pointer;display:flex;justify-content:space-between;align-items:center;padding:8px 12px;font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:var(--faint);font-weight:600}
details.off summary::-webkit-details-marker{display:none}details.off summary::after{content:"›";font-size:18px;letter-spacing:0;transition:transform .15s}details.off[open] summary::after{transform:rotate(90deg)}
.phr{display:flex;align-items:center;gap:14px;margin:0 0 6px}.phr .ph{flex:1;min-width:0;margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.crumb{color:var(--faint);font-size:28px;font-weight:300;white-space:nowrap}.pchip{display:none}.pg{margin-top:18px}
.tgf{margin:0;flex:none}.tg{position:relative;display:block;width:${Tokens.Toggle.W}px;height:${Tokens.Toggle.H}px;border-radius:999px;border:1px solid var(--edge);background:var(--field);cursor:pointer;padding:0}
.tg i{position:absolute;top:${Tokens.Toggle.KNOB_INSET - 1}px;left:${Tokens.Toggle.KNOB_INSET - 1}px;width:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;height:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;border-radius:50%;background:var(--muted)}
.tg.on{background:linear-gradient(90deg,var(--sky),var(--violet));border-color:transparent}.tg.on i{left:auto;right:${Tokens.Toggle.KNOB_INSET - 1}px;background:#fff}
.tg:disabled{opacity:.45;cursor:not-allowed}.tg:focus-visible,.pc:focus-visible,.pill:focus-visible{outline:2px solid var(--sky);outline-offset:3px}
.row.note span{color:var(--muted);font-size:15px;line-height:1.35}.row.quiet b{color:var(--muted)}
.row.sl{flex-direction:column;align-items:stretch;gap:8px}.slh{display:flex;align-items:baseline;gap:16px}.slh b{flex:1;min-width:0}.row.sl .slh em{flex:none;max-width:60%;text-align:right;font-style:normal;color:var(--muted);font-size:15px}
.slr{display:flex;align-items:center;gap:14px}.slr input{flex:1;min-width:0;accent-color:var(--sky)}.pill.sm{padding:7px 16px;font-size:14px}
.gl{margin:24px 0 4px;font-size:12px;letter-spacing:.2em;text-transform:uppercase;color:var(--faint);font-weight:600}
.st{display:flex;align-items:center;gap:10px;padding:16px 0 8px;color:var(--muted);font-size:15px}
.sr{display:flex;align-items:center;gap:16px;min-height:46px;border-top:1px solid var(--edge);font-size:15.5px}.sr span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sr em{font-style:normal;color:var(--muted);text-align:right}.sr.quiet span{color:var(--muted)}.sr.quiet em{color:var(--faint)}
.ptl{display:grid;grid-template-columns:repeat(var(--cols),minmax(0,1fr));gap:12px;padding:10px 0 14px}
.pt{background:var(--glass);border:1px solid var(--edge);border-radius:var(--r-tile);padding:14px 16px;display:flex;flex-direction:column;gap:2px;min-width:0}
.pt small{color:var(--faint);font-size:11px;font-weight:600;letter-spacing:.16em;text-transform:uppercase}.pt b{font-weight:300;font-size:26px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.pt b u{text-decoration:none;font-size:14px;color:var(--muted);margin-left:4px}
.pt span{color:var(--faint);font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mt{display:block;height:4px;border-radius:2px;background:var(--edge);margin-top:10px;overflow:hidden}.mt i{display:block;height:100%;background:linear-gradient(90deg,var(--sky),var(--violet))}
.sp{display:block;width:100%;height:24px;margin-top:8px}.sp polyline{fill:none;stroke:var(--sky);stroke-width:1.5;vector-effect:non-scaling-stroke}
.banner{display:flex;align-items:center;gap:13px;padding:10px 10px 10px 18px;border-radius:20px;background:rgba(251,191,36,.08);border:1px solid rgba(251,191,36,.35);margin:14px 0}.banner span{flex:1}.banner form{margin:0}
.main>.err{color:var(--rose);margin:10px 0}
.chev{font-style:normal;color:var(--faint);font-size:22px;line-height:1}
.ph{font-size:48px;font-weight:300;letter-spacing:-.02em;margin:0 0 6px}.sub{color:var(--muted);font-size:18px;margin:0 0 26px}
.foot{color:var(--faint);font-size:14px;margin-top:6px}
.notice{display:flex;align-items:center;gap:12px;padding:14px 20px;border-radius:16px;background:var(--glass);border:1px solid var(--edge);margin-bottom:18px;font-size:15px}
.lt{display:flex;justify-content:space-between;align-items:flex-start;gap:20px}.dl{text-align:right;margin-top:10px}.dl small{display:block;color:var(--muted);font-size:14px;margin-top:8px}
.filters{display:flex;gap:10px;align-items:center;margin:4px 0 18px;flex-wrap:wrap}.fc{padding:8px 18px;border-radius:999px;border:1px solid var(--edge);text-decoration:none;font-size:15px}
.fc.on{background:var(--lit);border-color:transparent;color:#fff}.search{margin-left:auto;flex:0 1 380px}
.search input{width:100%;padding:11px 18px;border-radius:14px;border:1px solid var(--edge);background:var(--glass);color:var(--fg);font:inherit;font-size:15px}
.logs{padding:18px 24px;font:14px/1.4 ui-monospace,"SF Mono",Menlo,Consolas,monospace}.lr{display:grid;grid-template-columns:auto 130px 1fr;gap:20px;padding:5px 4px}
.lr time{color:var(--faint);white-space:nowrap}.lr b{font-weight:400;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.lr b.w{color:var(--amber)}.lr b.e{color:var(--rose)}.lr span{color:var(--muted);overflow-wrap:anywhere}
.empty{color:var(--muted);font-family:system-ui,-apple-system,sans-serif;margin:8px 4px}
@media (prefers-color-scheme:light){.lr b.w{color:var(--work-ink)}.lr b.e{color:#be123c}}
@media (max-width:900px){.side{display:none}.main{margin:0;padding:18px 16px 40px}
.top{display:flex;align-items:center;gap:12px;padding:18px 20px;border-bottom:1px solid var(--edge);font-size:17px;position:relative}.top .m{width:26px;height:26px}.top>span{flex:1}
.menu summary{list-style:none;cursor:pointer;width:28px;height:22px;display:flex;flex-direction:column;justify-content:center;gap:6px}.menu summary::-webkit-details-marker{display:none}
.drop .brand{display:none}.menu summary span{display:block;height:2px;border-radius:2px;background:var(--fg)}
.menu .drop{position:absolute;left:0;right:0;top:100%;z-index:5;background:var(--bg);border-bottom:1px solid var(--edge);padding:16px;max-height:80vh;overflow:auto;box-shadow:0 20px 40px rgba(0,0,0,.35)}
.hero{grid-template-columns:64px 1fr;column-gap:18px;padding:18px 20px;margin-bottom:12px}.hm{width:64px;height:60px;grid-row:1}.ht{align-self:center}
.hero h1{font-size:24px}.meta{font-size:14px;margin-bottom:0}
.acts{grid-column:1/-1;margin-top:16px;gap:8px}.acts .pill{padding:9px 16px;font-size:15px}
.tiles{grid-template-columns:1fr;gap:0;background:var(--glass);border:1px solid var(--edge);border-radius:22px;padding:4px 20px}
.tile{flex-direction:row;align-items:center;justify-content:space-between;border:0;border-bottom:1px solid var(--edge);border-radius:0;background:none;padding:14px 0}
.tile:last-child{border-bottom:0}.tile small{color:var(--fg);font-size:16px}.tile b{font-size:14px;color:var(--muted);gap:8px}.tile span{display:none}
.crumb{font-size:20px}.pchip{display:inline-flex}.ptl{grid-template-columns:repeat(2,minmax(0,1fr))}.banner{flex-wrap:wrap}
.list{padding:4px 20px}.row{min-height:56px}.row.static{flex-wrap:wrap;row-gap:4px}.row em{flex-basis:100%;max-width:none;text-align:left}.row b{font-size:16px}.row span{font-size:14px}.chev{display:none}
.ph{font-size:32px}.sub{font-size:15px;margin-bottom:18px}.lt{flex-direction:column;gap:0}.dl{text-align:left;margin:0 0 14px}.search{flex-basis:100%}
.logs{padding:12px 14px;font-size:12.5px}.lr{grid-template-columns:auto 1fr;gap:4px 12px}.lr span{grid-column:1/-1;padding-bottom:6px}}"""

        val DIALOG_CSS = """.dim{position:fixed;inset:0;filter:brightness(.45);pointer-events:none;overflow:hidden}
.scrim{position:fixed;inset:0;display:grid;place-items:center;padding:16px;background:rgba(0,0,0,.25)}
.dlg{width:100%;max-width:${Tokens.DIALOG_WIDTH}px;background:var(--bg-2);border:1px solid var(--edge);border-radius:${Tokens.R_DIALOG}px;padding:36px 36px 32px;box-shadow:0 30px 80px rgba(0,0,0,.5)}
.scrim .dlg{max-height:calc(100vh - 32px);overflow:auto}
.pcs{display:flex;flex-wrap:wrap;gap:10px;margin:4px 0}.pc{padding:10px 17px;border-radius:999px;border:1px solid var(--edge);background:var(--glass);color:var(--muted);font:inherit;font-size:15.5px;font-weight:500;cursor:pointer}
.pc:hover{background:var(--hover)}.pc.on{background:linear-gradient(90deg,var(--sky),var(--violet));color:#0a0b0f;border-color:transparent}
.dflt{position:absolute;width:1px;height:1px;padding:0;border:0;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}.flds{display:flex;flex-direction:column;gap:12px}.pw{display:flex;gap:10px;align-items:center}.pw .fld{flex:1;min-width:0}.fld{width:100%;padding:14px 18px;border-radius:16px;border:1px solid var(--edge);background:var(--field);color:var(--fg);font:inherit;font-size:17px}.fld:focus{outline:none;border-color:rgba(56,189,248,.55)}
.sw{display:flex;justify-content:space-between;align-items:center;gap:12px;color:var(--fg);font-size:16.5px;cursor:pointer}
.sw input{appearance:none;-webkit-appearance:none;margin:0;width:${Tokens.Toggle.W}px;height:${Tokens.Toggle.H}px;border-radius:999px;background:var(--field);border:1px solid var(--edge);position:relative;cursor:pointer;flex:none;order:2}
.sw input::after{content:"";position:absolute;top:${Tokens.Toggle.KNOB_INSET - 1}px;left:${Tokens.Toggle.KNOB_INSET - 1}px;width:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;height:${Tokens.Toggle.H - 2 * Tokens.Toggle.KNOB_INSET}px;border-radius:50%;background:var(--muted)}
.sw input:checked{background:linear-gradient(90deg,var(--sky),var(--violet));border-color:transparent}.sw input:checked::after{left:auto;right:${Tokens.Toggle.KNOB_INSET - 1}px;background:#fff}
.tms{display:flex;flex-direction:column;gap:10px;margin-top:16px}.tm{display:flex;align-items:center;gap:16px;color:var(--muted);font-size:16.5px}.tm span{flex:1}
.tm input{padding:9px 14px;border-radius:12px;border:1px solid var(--edge);background:var(--field);color:var(--fg);font:inherit;font-size:20px}
.dlg h2{font-size:32px;font-weight:300;letter-spacing:-.02em;margin:0 0 10px}.dlg>p{color:var(--muted);margin:0 0 26px;font-size:17px}
.code{position:relative;display:flex;gap:14px;width:max-content;max-width:100%}.code span{width:62px;height:72px;border-radius:14px;background:var(--glass);border:1px solid var(--edge)}
.code input{position:absolute;inset:0;width:100%;border:0;background:transparent;color:var(--fg);font:30px/72px ui-monospace,"SF Mono",Menlo,monospace;
padding:0 0 0 calc(31px - .5ch);letter-spacing:calc(76px - 1ch);caret-color:var(--sky);outline:none}
.code:focus-within span{border-color:rgba(56,189,248,.45)}
.err{color:var(--rose)!important;margin:14px 0 0!important;font-size:15px!important}
.dbtns{display:flex;justify-content:flex-end;gap:12px;margin-top:30px}
@media (max-width:600px){.code{gap:8px}.code span{width:42px;height:56px}.code input{font-size:22px;line-height:56px;padding-left:calc(21px - .5ch);letter-spacing:calc(50px - 1ch)}.dlg{padding:26px 20px}.dlg h2{font-size:26px}}"""
    }
}
