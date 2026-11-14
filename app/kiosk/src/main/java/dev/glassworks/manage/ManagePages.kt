/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.NOT_RUNNING
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
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

    /** The signed-in browser, for the nav's foot and the forms' CSRF field. */
    class Viewer(val who: String, val csrf: String)

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
        fun item(path: String, title: String, dot: Boolean = false, grey: Boolean = false) =
            """<a class="ni${if (path == current) " on" else ""}${if (grey) " grey" else ""}" href="${esc(path)}"${if (path == current) " aria-current=\"page\"" else ""}>${esc(title)}${if (dot) "<i class=\"dot ok\"></i>" else ""}</a>"""
        val core = s.sections.filter { it.id in CORE_PANEL }
        val panes = s.panes.joinToString("") { p ->
            item("/" + p.id, p.title, dot = p.state.kind == PaneState.Kind.RUNNING, grey = p.state.kind == PaneState.Kind.CANT_RUN)
        }
        return """<div class="brand">${PageShell.mark(Look.DONE, "bm")}<div><b>${esc(s.name)}</b><small>${esc(if (s.name == "Glassworks Panel") s.frame else "Glassworks Panel · ${s.frame}")}</small></div></div>
<nav>${item("/", "Overview")}""" +
            (if (core.isNotEmpty()) """<h3>Panel</h3>${core.joinToString("") { item("/" + it.id, it.title) }}""" else "") +
            (if (s.panes.isNotEmpty()) "<h3>Panes</h3>$panes" else "") +
            """<h3>System</h3>${item("/log", "Log")}${item("/security", "Security")}${item("/about", "About")}</nav>
<form class="who" method="post" action="/manage/signout">${csrf(viewer)}<span><i class="dot ok"></i>Signed in · ${esc(viewer.who)}</span><button type="submit">Sign out</button></form>"""
    }

    private fun frame(s: PanelStatus, current: String, title: String, viewer: Viewer, main: String, notice: String? = null): String {
        val n = nav(s, current, viewer)
        val note = notice?.let { """<div class="notice" role="status"><i class="dot ok"></i>${esc(it)}</div>""" }.orEmpty()
        val body = """<aside class="side">$n</aside>
<header class="top">${PageShell.mark(Look.DONE, "m")}<span>${esc(if (current == "/") s.name else title)}</span><details class="menu"><summary aria-label="Menu"><span></span><span></span></summary><div class="drop">$n</div></details></header>
<main class="main">$note$main</main>"""
        return PageShell.document("$title · ${s.name}", MANAGE_CSS, body)
    }

    private fun csrf(v: Viewer) = """<input type="hidden" name="csrf" value="${esc(v.csrf)}">"""

    private fun action(v: Viewer, path: String, label: String, cls: String = "pill") =
        """<form method="post" action="${esc(path)}">${csrf(v)}<button class="$cls" type="submit">${esc(label)}</button></form>"""

    private fun chip(state: PaneState): String {
        val (tone, label) = when (state.kind) {
            PaneState.Kind.RUNNING -> "ok" to (state.chip ?: "Running")
            PaneState.Kind.OFF -> "off" to (state.chip ?: "Off")
            PaneState.Kind.CANT_RUN -> "off" to (state.chip ?: "Can't run")
            PaneState.Kind.PROBLEM -> "bad" to (state.chip ?: "Problem")
            PaneState.Kind.UNKNOWN -> return ""
        }
        return """<span class="chip"><i class="dot $tone"></i>${esc(label)}</span>"""
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
     * A settings section, or a pane with its sections, read-only for now (phase 2 makes them editable). A
     * pane that is off ([PaneState.Kind.OFF], from its section's on/off) shows its state and how to turn
     * it on, and none of its settings or links: they do nothing while it is off.
     */
    fun section(s: PanelStatus, v: Viewer, path: String, title: String, intro: String?, state: PaneState?, sections: List<SettingsSection>,
                links: List<Pair<String, String>> = emptyList()): String {
        val off = state?.kind == PaneState.Kind.OFF
        val shown = if (off) emptyList() else sections.filter { it.hasSettings() }
        val cards = shown.joinToString("") { sec ->
            val heading = if (shown.size > 1 || sec.title != title) """<div class="lh"><b>${esc(sec.title)}</b></div>""" else ""
            """<section class="card list">$heading${items(sec.items)}</section>"""
        }
        val stateCard = state?.takeIf { it.kind != PaneState.Kind.UNKNOWN }?.let {
            """<section class="card list"><div class="row static"><div><b>${esc(title)}</b><span>${esc(it.detail)}</span></div>${chip(it)}</div></section>"""
        }.orEmpty()
        val note = when {
            off -> """<p class="foot">${esc(TURNED_OFF)}</p>"""
            shown.isNotEmpty() -> """<p class="foot">Change these on the panel for now. The Manage page will change them too.</p>"""
            else -> """<p class="foot">${esc(NOT_RUNNING)}</p>"""
        }
        val more = if (off || links.isEmpty()) "" else """<section class="card list">${links.joinToString("") { (label, href) ->
            """<a class="row" href="${esc(href)}"><div><b>${esc(label)}</b></div><i class="chev" aria-hidden="true">›</i></a>""" }}</section>"""
        val main = """<h1 class="ph">${esc(title)}</h1>${intro?.let { """<p class="sub">${esc(it)}</p>""" }.orEmpty()}$stateCard$cards$more$note"""
        return frame(s, path, title, v, main)
    }

    private fun items(items: List<SettingsItem>): String = items.joinToString("") { item ->
        when (item) {
            is SettingsItem.Text -> {
                val v = safe { item.value() }
                // As the panel's row shows it ("After 2 minutes" for 120); the hint names an empty one.
                row(item.title, item.hint.takeIf { it.isNotEmpty() && it != v }, if (v.isEmpty()) "Not set" else safe { item.shown(v) }.ifEmpty { v })
            }
            is SettingsItem.Switch -> row(item.title, item.detail.ifEmpty { null }, if (safe { item.value() } == "true") "On" else "Off")
            is SettingsItem.Slider -> row(item.title, null, safe { item.shown(item.value()) })
            is SettingsItem.Duration -> row(item.title, null, safe { item.shown(item.value()) })
            is SettingsItem.TimeRange -> row(item.title, null, safe { item.shown(item.value()) })
            is SettingsItem.Info -> row(safe { item.title() }, null, safe { item.detail() })
            is SettingsItem.Note -> """<div class="row static"><div><span>${esc(safe { item.text() })}</span></div></div>"""
            is SettingsItem.Link -> row(item.title, item.detail.ifEmpty { null }, "On the panel")
            is SettingsItem.Group -> """<div class="grp"><div class="lh"><b>${esc(item.title)}</b></div>${items(item.items)}</div>"""
            // A view only the panel can draw: say so, rather than drop the row.
            is SettingsItem.Native -> row(nativeTitle(item.key), "Shown on the panel's own settings", "On the panel")
            // #212's items, as rows until the Manage page draws them as the panel does (#242).
            is SettingsItem.Choice -> row(item.title, null, safe { item.value().let { v -> item.options().firstOrNull { it.first == v }?.second ?: v } })
            is SettingsItem.Status -> """<div class="row static"><div><span>${esc(safe { item.text() })}</span></div></div>"""
            is SettingsItem.States -> """<div class="grp"><div class="lh"><b>${esc(item.title)}</b></div>${safeRows(item).joinToString("") { row(it.name, null, it.value) }}</div>"""
            is SettingsItem.Tiles -> safeTiles(item).joinToString("") { t -> row(t.name, t.detail.ifEmpty { null }, listOf(t.value, t.unit).filter { it.isNotEmpty() }.joinToString(" ")) }
        }
    }

    /** Not only the core's "isn't running yet" note, which a pane gets while the panel's device doesn't run ([dev.glassworks.pane.paneSections]). */
    private fun SettingsSection.hasSettings() = items.any { it !is SettingsItem.Note || safe { it.text() } != NOT_RUNNING }

    private fun safeRows(i: SettingsItem.States) = runCatching { i.rows() }.getOrDefault(emptyList())
    private fun safeTiles(i: SettingsItem.Tiles) = runCatching { i.tiles() }.getOrDefault(emptyList())

    private fun nativeTitle(key: String) = key.split('-', '_', '.').joinToString(" ").replaceFirstChar { it.uppercase() }

    private fun safe(f: () -> Any?): String = try { f()?.toString().orEmpty() } catch (_: Throwable) { "" }

    private fun row(title: String, detail: String?, value: String) =
        """<div class="row static"><div><b>${esc(title)}</b>${detail?.let { "<span>${esc(it)}</span>" }.orEmpty()}</div><em>${esc(value)}</em></div>"""

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
<section class="card logs">$rows</section>"""
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
        val rows = s.about.joinToString("") { (k, value) -> row(k, null, value) }
        val main = """<h1 class="ph">About</h1><p class="sub">This panel and the software it runs.</p><section class="card list">$rows</section>"""
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
        /** The core's own sections that sit under "Panel" in the nav. */
        val CORE_PANEL = setOf("dashboard", "screen", "network")

        /** The foot of an off pane's page. */
        const val TURNED_OFF = "This pane is off. Turn it on at the top of its page in the panel's settings."

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

        const val MANAGE_CSS = """body{display:block}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;flex:none;background:var(--faint)}.dot.ok{background:var(--green)}.dot.work{background:var(--amber)}.dot.bad{background:var(--rose)}.dot.off{background:var(--faint)}
.side{position:fixed;inset:0 auto 0 0;width:280px;padding:28px 18px;border-right:1px solid var(--edge);display:flex;flex-direction:column;overflow:auto}
.brand{display:flex;gap:14px;align-items:center;padding:0 12px 26px}.brand .bm{width:34px;height:34px;flex:none}.brand b{display:block;font-size:18px;font-weight:500}.brand small{color:var(--muted);font-size:14px}
nav{display:flex;flex-direction:column;gap:2px;flex:1}nav h3{margin:20px 12px 6px;font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:var(--faint);font-weight:600}
.ni{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-radius:12px;text-decoration:none;font-size:16px;border:1px solid transparent}
.ni:hover{background:var(--hover)}.ni.on{background:var(--glass);border-color:var(--edge)}.ni.grey{color:var(--faint)}
.who{display:flex;align-items:center;justify-content:space-between;gap:8px;border-top:1px solid var(--edge);margin-top:18px;padding:16px 12px 0;font-size:14px;color:var(--muted)}
.who span{display:flex;align-items:center;gap:8px}.who button{background:none;border:0;color:var(--muted);font:inherit;cursor:pointer;padding:0}.who button:hover{color:var(--fg)}
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
.chip{display:inline-flex;align-items:center;gap:8px;padding:5px 14px;border-radius:999px;border:1px solid var(--edge);background:var(--glass);font-size:14px;color:var(--muted);white-space:nowrap}
.row .chip{display:inline-flex;font-size:14px;color:var(--muted)}
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
.list{padding:4px 20px}.row{min-height:56px}.row.static{flex-wrap:wrap;row-gap:4px}.row em{flex-basis:100%;max-width:none;text-align:left}.row b{font-size:16px}.row span{font-size:14px}.chev{display:none}
.ph{font-size:32px}.sub{font-size:15px;margin-bottom:18px}.lt{flex-direction:column;gap:0}.dl{text-align:left;margin:0 0 14px}.search{flex-basis:100%}
.logs{padding:12px 14px;font-size:12.5px}.lr{grid-template-columns:auto 1fr;gap:4px 12px}.lr span{grid-column:1/-1;padding-bottom:6px}}"""

        private const val DIALOG_CSS = """.dim{position:fixed;inset:0;filter:brightness(.45);pointer-events:none;overflow:hidden}
.scrim{position:fixed;inset:0;display:grid;place-items:center;padding:16px;background:rgba(0,0,0,.25)}
.dlg{width:100%;max-width:560px;background:var(--bg-2);border:1px solid var(--edge);border-radius:26px;padding:36px 36px 32px;box-shadow:0 30px 80px rgba(0,0,0,.5)}
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
