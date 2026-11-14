/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.PageShell
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The panel's Manage page at `http://<panel>:8099/` (#33). It shares the port with the QR-code pages:
 * [qrPaths] and, until the panel is set up ([setUp]), `/` go to [qr] untouched and need no session.
 * Everything else needs a signed-in browser.
 *
 * Signing in: with Home Assistant ([HaSignIn]: local HA only, admins only, HA's token revoked at once), or
 * with the code on the panel's screen ([PanelCode]). Either gives the browser this page's own session
 * ([SessionStore]): an HttpOnly, SameSite=Strict cookie, for 30 days when remembered.
 *
 * Every POST must come from this page: its `Origin` is the panel's own, and a signed-in one carries the
 * session's CSRF token. A dangerous action (reboot) also needs the code the panel shows at that moment.
 * Pure JVM; tests drive [handle].
 */
class ManageServer(
    private val host: ManageHost,
    /** The HA address the panel uses, or null. Sessions are tied to it. */
    private val haBase: () -> String?,
    /** Setup is done: `/` is the Manage page, not the QR sign-in. */
    private val setUp: () -> Boolean,
    private val sessions: SessionStore,
    private val code: PanelCode,
    private val signIn: HaSignIn,
    private val log: ManageLog,
    private val qrPaths: Set<String>,
    private val qr: (HttpRequest) -> HttpResponse,
    /**
     * The panel's own addresses and names, lowercase, without a port or brackets: its IP addresses, its
     * mDNS name. The page answers only to these (DNS rebinding, including a spoofed `.local` answer).
     */
    private val ownHosts: () -> Set<String>,
    private val pages: ManagePages = ManagePages(),
    private val event: (String) -> Unit = {},
    /** How long a settings change may take before the page answers "still applying" (the radio's off waits on the radio). */
    private val saveWaitMs: Long = 20_000,
) {
    private val random = SecureRandom()

    fun handle(req: HttpRequest): HttpResponse {
        if (req.path in qrPaths) return qrPage(req)
        // Before setup, / is the QR sign-in, except for a browser already signed in to Manage (with the code).
        if (req.path == "/" && !setUp() && sessions.lookup(req.cookies[SESSION], haBase()) == null) return qrPage(req)
        if (!hostAllowed(req.headers["host"], ownHosts())) return HttpResponse.text(400, "Open this page with the panel's address.")
        if (req.method != "GET" && req.method != "HEAD" && req.method != "POST") return HttpResponse.text(405, "Not allowed.")
        if (req.method == "POST" && !sameOrigin(req)) return HttpResponse.text(403, "This form didn't come from the panel's own page.")
        val session = sessions.lookup(req.cookies[SESSION], haBase())
        // Back from the reload in signedOut() with the cookie: the same page, without the mark.
        // safeNext: a path on this panel only (`//host/` would leave it).
        if (session != null && req.method == "GET" && req.query[RELOADED] != null) return HttpResponse.seeOther(safeNext(withoutMark(req)))
        return when (req.path) {
            "/favicon.ico" -> HttpResponse.text(404, "")
            "/manage/ha" -> post(req) { startHa(req) }
            HaSignIn.CALLBACK -> haCallback(req)
            "/manage/code" -> if (req.method == "POST") codeSignIn(req) else codeDialog(req, null)
            "/manage/signout" -> post(req) { signOut(req, session) }
            else -> if (session == null) signedOut(req) else signedIn(req, session)
        }
    }

    // ------------------------------------------------------------------ the QR pages

    /** The QR pages as they are, with the way back to Manage in their header for a signed-in browser. */
    private fun qrPage(req: HttpRequest): HttpResponse {
        val r = qr(req)
        val signed = sessions.lookup(req.cookies[SESSION], haBase()) != null
        val right = if (signed) """<a class="to" href="/">Manage this panel <span aria-hidden="true">›</span></a>""" else ""
        return HttpResponse(r.status, r.headers, PageShell.fillHeaderSlot(r.body, right))
    }

    // ------------------------------------------------------------------ signing in

    private fun signedOut(req: HttpRequest): HttpResponse {
        if (req.method == "POST") return HttpResponse.seeOther("/")
        // Arrived from another site (HA's "Visit"): the SameSite=Strict cookie stayed behind. Load the page again
        // from here, which is same-site, so the cookie comes. Browsers send Sec-Fetch-Site only to https and
        // loopback, never to the panel's http:// address on the LAN, so no header counts too, when no cookie came
        // at all. The mark keeps it to one reload: back without a session, it's the sign-in page.
        val site = req.headers["sec-fetch-site"]
        if (req.query[RELOADED] == null && req.cookies[SESSION] == null && (site == "cross-site" || site == null)) {
            val to = safeNext(withoutMark(req))
            return html(200, pages.bounce(name(), to + (if ('?' in to) "&" else "?") + "$RELOADED=1"))
        }
        return signInPage(req, safeNext(withoutMark(req)), null)
    }

    private fun signInPage(req: HttpRequest, next: String, problem: String?, status: Int = 200): HttpResponse {
        val ha = haBase()
        val haOk = ha != null && signIn.isLocal(ha)
        val extra = preCookie(req)
        return html(status, pages.signIn(name(), next, haOk, problem), formTargets = listOfNotNull(ha?.let(::origin)), extra = extra)
    }

    private fun startHa(req: HttpRequest): HttpResponse {
        val next = safeNext(req.form["next"])
        val ha = haBase() ?: return signInPage(req, next, "The panel doesn't know Home Assistant's address yet. Use the code on the panel.", 409)
        val hostHeader = req.headers["host"]!!
        val (pre, cookie) = pre(req)
        val (url, why) = signIn.start(ha, hostHeader.lowercase(), pre, req.form["remember"] == "1", next)
        if (url == null) return signInPage(req, next, why, 409)
        return HttpResponse.seeOther(url, cookie)
    }

    private fun haCallback(req: HttpRequest): HttpResponse {
        val q = req.query
        return when (val r = signIn.finish(q["state"], q["code"], q["error"], req.cookies[PRE])) {
            is HaSignIn.Result.Failed -> {
                event("Sign-in through Home Assistant refused: ${r.title}")
                html(403, PageShell.qrPage(name(), r.title, PageShell.esc(r.text), PageShell.Look.PROBLEM,
                    button = """<a class="btn" href="/">Back to sign in</a>"""))
            }
            is HaSignIn.Result.Ok -> {
                // Authenticated against the address the sign-in started with: if the panel's has changed since, start
                // again. Read once, so the session is bound to the address that was checked.
                val ha = haBase()
                if (r.haBase != ha?.trim()?.trimEnd('/')) {
                    return html(409, PageShell.qrPage(name(), "Home Assistant's address changed", "Sign in again.", PageShell.Look.PROBLEM,
                        button = """<a class="btn" href="/">Back to sign in</a>"""))
                }
                val (token, s) = sessions.create(r.user.name, SignedInWith.HOME_ASSISTANT, SessionStore.browserName(req.headers["user-agent"]), r.remember, ha)
                event("${r.user.name} signed in with Home Assistant (${s.browser})")
                // HA sent the browser here from another site: a redirect would still be cross-site, and the
                // new Strict cookie wouldn't go with it. A page that reloads to the target is same-site.
                html(200, PageShell.document(name(), "", """<meta http-equiv="refresh" content="0;url=${PageShell.esc(r.next)}">"""),
                    extra = mapOf("Set-Cookie" to sessionCookie(token, r.remember) + "\n" + clearCookie(PRE)))
            }
        }
    }

    /** `/manage/code`, POST: show a code on the panel, or check the one typed. */
    private fun codeSignIn(req: HttpRequest): HttpResponse {
        val next = safeNext(req.form["next"])
        val remember = req.form["remember"] == "1"
        val (pre, cookie) = pre(req)
        val typed = req.form["code"]
        if (typed == null) {
            return when (val shown = code.show(PURPOSE_SIGN_IN, "Sign in to manage this panel", pre)) {
                is PanelCode.Show.Ok -> HttpResponse.seeOther("/manage/code?next=" + enc(next) + if (remember) "&remember=1" else "", cookie)
                is PanelCode.Show.Locked -> codeLocked(req, next, remember)
                is PanelCode.Show.Busy -> { capped(req, shown); codeDialog(req, busyText(shown), next, remember, 429) }
                PanelCode.Show.Unavailable -> {
                    event("The panel can't show a code (it may not draw over other apps)")
                    signInPage(req, next, NO_CODE + if (haBase() != null) " Sign in with Home Assistant instead." else "", 503)
                }
            }
        }
        return when (val c = code.check(PURPOSE_SIGN_IN, pre, typed)) {
            PanelCode.Check.Ok -> {
                val (token, s) = sessions.create(null, SignedInWith.PANEL_CODE, SessionStore.browserName(req.headers["user-agent"]), remember, haBase())
                event("A browser signed in with the panel's code (${s.browser})")
                HttpResponse.seeOther(next, mapOf("Set-Cookie" to sessionCookie(token, remember) + "\n" + clearCookie(PRE)))
            }
            is PanelCode.Check.Wrong -> {
                event("Wrong code typed to sign in")
                codeDialog(req, if (c.triesLeft > 0) "That's not the code. ${c.triesLeft} ${if (c.triesLeft == 1) "try" else "tries"} left."
                    else "That's not the code, and that was the last try. Show a new one.", next, remember, 401)
            }
            PanelCode.Check.NoCode -> codeDialog(req, "That code has run out. Go back and show a new one.", next, remember, 401)
            is PanelCode.Check.Locked -> codeLocked(req, next, remember)
        }
    }

    private fun codeLocked(req: HttpRequest, next: String, remember: Boolean) =
        codeDialog(req, "Too many wrong codes. Try again after ${hhmm(code.lockedUntil() ?: 0)}.", next, remember, 429)

    private fun codeDialog(req: HttpRequest, problem: String?, next: String = safeNext(req.query["next"]),
                           remember: Boolean = req.query["remember"] == "1", status: Int = 200): HttpResponse {
        val html = pages.codeDialog(
            name(), "Enter the code on the panel.", "The panel is showing a 6-digit code now.", "/manage/code", "Sign in",
            "/", mapOf("next" to next) + if (remember) mapOf("remember" to "1") else emptyMap(), problem, danger = false,
        )
        return html(status, html, extra = preCookie(req))
    }

    private fun signOut(req: HttpRequest, session: Session?): HttpResponse {
        if (session == null) return HttpResponse.seeOther("/")
        if (!csrfOk(req, session)) return HttpResponse.text(403, "This form has expired. Go back and try again.")
        if (sessions.revoke(session.id) == SessionStore.Revoked.NOT_SAVED) return notSaved()
        event("${who(session)} signed out (${session.browser})")
        return HttpResponse.seeOther("/", mapOf("Set-Cookie" to clearCookie(SESSION)))
    }

    // ------------------------------------------------------------------ signed in

    private fun signedIn(req: HttpRequest, s: Session): HttpResponse {
        val viewer = ManagePages.Viewer(who(s), s.csrf, homeAssistant = s.with == SignedInWith.HOME_ASSISTANT)
        if (req.method == "POST") {
            if (!csrfOk(req, s)) return HttpResponse.text(403, "This form has expired. Go back and try again.")
            return posted(req, s, viewer)
        }
        val status = host.status()
        val path = req.path
        return when {
            path == "/" -> html(200, pages.overview(status, viewer, host.actions, notice(req.query["done"])))
            path == "/log" -> {
                val level = when (req.query["level"]) { "warning" -> LogEntry.Level.WARNING; "error" -> LogEntry.Level.ERROR; else -> LogEntry.Level.INFO }
                val q = req.query["q"].orEmpty().take(100)
                html(200, pages.log(status, viewer, log.read(level, q), level, q))
            }
            path == "/log/diagnostics.txt" -> diagnostics()
            path == "/security" -> html(200, pages.security(status, viewer, sessions.list(haBase()), s.id, notice(req.query["done"])))
            path == "/about" -> html(200, pages.about(status, viewer))
            path == "/confirm/reboot" -> html(200, rebootDialog(status, viewer, null))
            else -> {
                val edit = req.query["edit"]?.let { ManagePages.Dialog.Edit(it) }
                settingsPage(status, viewer, path.removePrefix("/"), dialog = edit, done = req.query["done"])
                    ?: html(404, pages.notFound(status, viewer))
            }
        }
    }

    /** A pane's or a core section's page by [id], or null when the panel has no such page. */
    private fun settingsPage(status: PanelStatus, viewer: ManagePages.Viewer, id: String, dialog: ManagePages.Dialog? = null,
                             problem: String? = null, done: String? = null, code: Int = 200): HttpResponse? {
        val pane = status.panes.firstOrNull { it.id == id }
        val section = status.sections.firstOrNull { it.id == (pane?.sectionId ?: id) }
        if (pane == null && section == null) return null
        // An edit link for an item the page doesn't have now, or one changed in its row (a switch, a slider), is the page itself.
        val shown = dialog?.takeIf { d ->
            val item = section?.let { SettingsChange.find(it, d.key) }
            d is ManagePages.Dialog.Confirm || (item != null && item !is SettingsItem.Switch && item !is SettingsItem.Slider)
        }
        return html(code, pages.section(status, viewer, "/$id", pane?.title ?: section!!.title, pane?.state, section, pane?.links.orEmpty(),
            dialog = shown, problem = problem, restart = done == "restart", notice = sectionNotice(done)))
    }

    private fun sectionNotice(done: String?): String? = when (done) {
        "gone" -> "That setting isn't on this page now. Here is the page as it is."
        "pending" -> "The panel is still applying that. Reload in a moment to see it."
        else -> null
    }

    /**
     * `/settings`, POST: change one item, as the panel's menu does (#242). The form names the section, the
     * item's key ([SettingsChange.changeable]) and the value; [SettingsChange.change] checks it and calls the
     * item's own save, on the panel's main thread ([ManageHost.onMain]). A switch that asks first on the panel
     * asks here too, then comes back with `confirmed`. A pane's on/off needs a Home Assistant sign-in.
     */
    private fun changeSetting(req: HttpRequest, s: Session, viewer: ManagePages.Viewer): HttpResponse {
        val status = host.status()
        val id = req.form["section"].orEmpty()
        val section = status.sections.firstOrNull { it.id == id } ?: return HttpResponse.seeOther("/")
        val page = "/" + (status.panes.firstOrNull { it.sectionId == id }?.id ?: id)
        val key = req.form["key"].orEmpty()
        val item = SettingsChange.find(section, key) ?: return HttpResponse.seeOther("$page?done=gone")
        val what = "${section.title} › " + if (key == SettingsChange.MASTER) "on/off" else key.replace("/", " › ")
        if (SettingsChange.needsHomeAssistant(key) && s.with != SignedInWith.HOME_ASSISTANT) {
            event("${who(s)}: refused to change $what: needs a Home Assistant sign-in")
            return settingsPage(status, viewer, page.removePrefix("/"), problem = ManagePages.NEEDS_HA, code = 403)!!
        }
        // The time window's dialog sends On, From and To; the rest send their value as text.
        val raw = if (item is SettingsItem.TimeRange) (if (req.form["on"] == "1") "${req.form["from"].orEmpty()}-${req.form["to"].orEmpty()}" else "")
            else req.form["value"].orEmpty()
        if (item is SettingsItem.Switch && req.form["confirmed"] != "1" && (raw == "on" || raw == "off")) {
            val ask = runCatching { item.confirm(raw == "on") }.getOrNull()
            if (ask != null) return settingsPage(status, viewer, page.removePrefix("/"), ManagePages.Dialog.Confirm(key, raw, ask))!!
        }
        val problem = apply(item, raw)
        if (problem == PENDING) {
            event("${who(s)} changed $what (still applying)")
            return HttpResponse.seeOther("$page?done=pending")
        }
        event(if (problem == null) "${who(s)} changed $what" else "${who(s)} couldn't change $what: $problem")
        if (problem == null) return HttpResponse.seeOther(if (SettingsChange.restarts(item)) "$page?done=restart" else page)
        // As the panel: a typed value, a duration, a choice or a window keeps its sheet open with why; the rest say it on the page.
        val dialog = if (item is SettingsItem.Switch || item is SettingsItem.Slider) null else ManagePages.Dialog.Edit(key, problem)
        return settingsPage(host.status(), viewer, page.removePrefix("/"), dialog, problem = if (dialog == null) problem else null, code = 422)!!
    }

    /** [SettingsChange.change] on the panel's main thread; its answer, or [PENDING] when it takes longer than a page should wait. */
    private fun apply(item: SettingsItem, raw: String): String? {
        val answered = CountDownLatch(1)
        val answer = AtomicReference<String?>()
        host.onMain { SettingsChange.change(item, raw) { why -> if (answered.count > 0) { answer.set(why); answered.countDown() } } }
        return if (answered.await(saveWaitMs, TimeUnit.MILLISECONDS)) answer.get() else PENDING
    }

    private fun posted(req: HttpRequest, s: Session, viewer: ManagePages.Viewer): HttpResponse {
        val a = host.actions
        fun run(name: String, what: String, f: () -> String?): HttpResponse {
            val problem = f()
            event(if (problem == null) "${who(s)}: $what" else "${who(s)}: $what failed: $problem")
            return HttpResponse.seeOther(if (problem == null) "/?done=$name" else "/?done=failed")
        }
        return when (req.path) {
            "/action/reload" -> run("reload", "reload the dashboard", a::reloadDashboard)
            "/action/restart" -> run("restart", "restart the app", a::restartApp)
            "/action/update" -> run("update", "check for updates", a::checkForUpdates)
            "/action/reboot" -> {
                if (!a.canReboot) return HttpResponse.seeOther("/")
                // Rebooting takes the radio's network down for everyone: someone must be at the panel.
                when (val shown = code.show(PURPOSE_REBOOT, "Reboot the panel", s.id)) {
                    is PanelCode.Show.Ok -> HttpResponse.seeOther("/confirm/reboot")
                    is PanelCode.Show.Locked -> html(429, rebootDialog(host.status(), viewer, lockedText()))
                    is PanelCode.Show.Busy -> { capped(req, shown); html(429, rebootDialog(host.status(), viewer, busyText(shown))) }
                    PanelCode.Show.Unavailable -> {
                        event("${who(s)}: reboot refused, the panel can't show a code")
                        HttpResponse.seeOther("/?done=nocode")
                    }
                }
            }
            "/confirm/reboot" -> when (val c = code.check(PURPOSE_REBOOT, s.id, req.form["code"].orEmpty())) {
                PanelCode.Check.Ok -> run("reboot", "reboot the panel", a::reboot)
                is PanelCode.Check.Wrong -> html(401, rebootDialog(host.status(), viewer,
                    if (c.triesLeft > 0) "That's not the code. ${c.triesLeft} ${if (c.triesLeft == 1) "try" else "tries"} left." else "That was the last try. Cancel and start again."))
                PanelCode.Check.NoCode -> html(401, rebootDialog(host.status(), viewer, "That code has run out. Cancel and start again."))
                is PanelCode.Check.Locked -> html(429, rebootDialog(host.status(), viewer, lockedText()))
            }
            "/settings" -> changeSetting(req, s, viewer)
            "/security/signout" -> {
                val id = req.form["id"].orEmpty()
                val target = sessions.list(haBase()).firstOrNull { it.id == id }
                if (sessions.revoke(id) == SessionStore.Revoked.NOT_SAVED) return notSaved()
                target?.let { event("${who(s)} signed out ${it.browser}") }
                if (id == s.id) HttpResponse.seeOther("/", mapOf("Set-Cookie" to clearCookie(SESSION)))
                else HttpResponse.seeOther("/security?done=signedout")
            }
            else -> HttpResponse.text(404, "Not found.")
        }
    }

    /** The panel couldn't store a sign-out: it holds until the app restarts, and the person should know. */
    private fun notSaved(): HttpResponse {
        event("A sign-out couldn't be saved; it holds until the app restarts")
        return HttpResponse.text(500, "Signed out until the app restarts: the panel couldn't save it. Try again.")
    }

    private fun rebootDialog(status: PanelStatus, viewer: ManagePages.Viewer, problem: String?): String =
        pages.codeDialog(name(), "Enter the code on the panel.", "Rebooting needs someone at the panel. It's showing a 6-digit code now.",
            "/confirm/reboot", "Reboot the panel", "/", mapOf("csrf" to viewer.csrf), problem, danger = true,
            // The overview the reboot was asked from, dimmed behind the dialog.
            behindHtml = pages.overview(status, viewer, host.actions, null).substringAfter("<body>").substringBeforeLast("</body>"))

    private fun busyText(b: PanelCode.Show.Busy): String = when (b.why) {
        PanelCode.Why.SHOWING -> {
            val s = ((b.untilMs - System.currentTimeMillis() + 999) / 1000).coerceIn(1, 30)
            "The panel is showing another code. Try again in $s ${if (s == 1L) "second" else "seconds"}."
        }
        PanelCode.Why.TOO_MANY -> "The panel has shown too many codes in the last ${code.showWindowMs / 60_000} minutes. Try again after ${hhmm(b.untilMs)}."
    }

    /** The cap on new codes was hit: worth a line in the Log, with who asked. */
    private fun capped(req: HttpRequest, b: PanelCode.Show.Busy) {
        if (b.why == PanelCode.Why.TOO_MANY) event("Too many code requests: refused one from ${req.remote ?: "an unknown address"}")
    }

    private fun lockedText() = "Too many wrong codes. Try again after ${hhmm(code.lockedUntil() ?: 0)}."

    private fun diagnostics(): HttpResponse {
        val now = System.currentTimeMillis()
        val text = buildString {
            append("Glassworks Panel diagnostics, ").append(Date(now)).append("\n\n")
            append(host.diagnostics()).append("\n\nLog, the last 7 days, newest first:\n")
            for (e in log.read(limit = 5000)) append(pages.time(e.timeMs)).append(' ').append(e.level.name[0]).append(' ').append(e.source).append(": ").append(e.text).append('\n')
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date(now))
        return HttpResponse(200, mapOf(
            "Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store",
            "Content-Disposition" to "attachment; filename=\"glassworks-diagnostics-$stamp.txt\"",
        ) + PageShell.securityHeaders(), Redact.text(text).toByteArray(Charsets.UTF_8))
    }

    private fun notice(done: String?): String? = when (done) {
        "reload" -> "Reloading the dashboard."
        "restart" -> "The app is restarting. The panel shows the dashboard again in a few seconds."
        "update" -> "Checking for updates. Home Assistant's update card shows what it finds."
        "reboot" -> "The panel is rebooting. This page comes back in about a minute."
        "signedout" -> "That browser is signed out."
        "failed" -> "That didn't work. The Log says why."
        "nocode" -> "$NO_CODE Rebooting needs it."
        else -> null
    }

    // ------------------------------------------------------------------ checks and cookies

    /** POSTs only: the form came from this page (its Origin is the panel's own address). */
    private fun sameOrigin(req: HttpRequest): Boolean {
        val origin = req.headers["origin"]
        val hostHeader = req.headers["host"] ?: return false
        if (origin == null) return req.headers["sec-fetch-site"] == "same-origin"
        return origin.equals("http://$hostHeader", ignoreCase = true)
    }

    private fun csrfOk(req: HttpRequest, s: Session): Boolean {
        val sent = req.form["csrf"] ?: return false
        return MessageDigest.isEqual(sent.toByteArray(), s.csrf.toByteArray())
    }

    private inline fun post(req: HttpRequest, f: () -> HttpResponse): HttpResponse =
        if (req.method == "POST") f() else HttpResponse.seeOther("/")

    /** The browser's pre-sign-in id (binds an HA sign-in or a code to it), and the cookie to set if it's new. */
    private fun pre(req: HttpRequest): Pair<String, Map<String, String>> {
        req.cookies[PRE]?.takeIf { it.length in 20..64 }?.let { return it to emptyMap() }
        val b = ByteArray(24).also(random::nextBytes)
        val v = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        return v to mapOf("Set-Cookie" to preCookieValue(v))
    }

    private fun preCookie(req: HttpRequest): Map<String, String> = pre(req).second

    // Lax: HA's redirect back is a top-level GET from another site, and it must carry this cookie.
    private fun preCookieValue(v: String) = "$PRE=$v; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800"

    private fun sessionCookie(token: String, remember: Boolean) =
        "$SESSION=$token; Path=/; HttpOnly; SameSite=Strict" + if (remember) "; Max-Age=${30L * 24 * 3600}" else ""

    private fun clearCookie(name: String) = "$name=; Path=/; HttpOnly; SameSite=${if (name == SESSION) "Strict" else "Lax"}; Max-Age=0"

    private fun html(status: Int, html: String, formTargets: List<String> = emptyList(), extra: Map<String, String> = emptyMap()) =
        HttpResponse.html(status, html, PageShell.securityHeaders("'self'" + formTargets.joinToString("") { " $it" }) + extra)

    private fun who(s: Session) = s.user ?: "Code sign-in"

    private fun name() = host.name

    private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(ms))

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        const val SESSION = "gw_session"

        /** The query mark of the one reload a signed-out page from another site gets ([signedOut]). */
        const val RELOADED = "gw_reload"

        /** [req]'s path and query without the [RELOADED] mark. */
        fun withoutMark(req: HttpRequest): String {
            val q = req.query.filterKeys { it != RELOADED }
            return if (q.isEmpty()) req.path else req.path + "?" + q.entries.joinToString("&") { (k, v) -> dev.glassworks.web.HttpServer.enc(k) + "=" + dev.glassworks.web.HttpServer.enc(v) }
        }
        const val PRE = "gw_pre"

        /** [apply]'s answer while a change is still running. */
        private const val PENDING = "\u0000pending"
        const val PURPOSE_SIGN_IN = "signin"
        const val PURPOSE_REBOOT = "reboot"

        private const val NO_CODE = "The panel can't show a code right now."

        /**
         * True when the `Host` header names the panel itself: one of [own], with or without a port. Any other
         * name is refused, even one that resolves here (DNS rebinding) or a `.local` name someone else answers.
         */
        fun hostAllowed(host: String?, own: Set<String>): Boolean {
            val h = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 255 } ?: return false
            val name = when {
                h.startsWith("[") -> h.substringAfter('[').substringBefore(']').takeIf { h.indexOf(']') > 0 } ?: return false
                h.count { it == ':' } == 1 -> h.substringBefore(':').takeIf { h.substringAfter(':').all(Char::isDigit) } ?: return false
                h.contains(':') -> return false
                else -> h
            }
            return name in own
        }

        /** Where to go after signing in: a path on this panel, never another site. */
        fun safeNext(next: String?): String {
            val n = next?.trim().orEmpty()
            if (!n.startsWith("/") || n.startsWith("//") || n.startsWith("/\\") || n.startsWith("/manage/") || n.length > 300) return "/"
            if (n.any { it.isWhitespace() || it.code < 0x20 }) return "/"
            return n
        }

        fun origin(url: String): String? = try {
            val u = URI(url.trim())
            if (u.scheme == null || u.rawAuthority == null) null else u.scheme.lowercase() + "://" + u.rawAuthority.lowercase()
        } catch (_: Exception) { null }
    }
}
