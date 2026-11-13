/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

/**
 * The one look of every page the panel serves: the QR-code pages (sign in, Thread join, their results)
 * and the Manage page. glassworks.dev's tokens, night by default and daylight when the computer or phone
 * is in light mode, the system font, the inline stack, no scripts and no outside files.
 *
 * Change the look here, not in the servers: both the QR pages (`SignInServer`) and the Manage page draw
 * through this file, so they stay one experience (#33, #63).
 */
object PageShell {

    /** The stack tells the state, as on the panel: outlined to start, lit when done, rose and dashed for a problem. */
    enum class Look { START, DONE, PROBLEM }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Headers every page carries. No scripts at all and nothing loaded from outside: only the page's own
     * inline style. [formAction]: where its forms may post (`'none'` on the QR pages, which have no forms;
     * `'self'` on the Manage page, plus HA's origin on the sign-in page, whose form redirects there). No
     * framing, no sniffing, no caching. No referrer to other sites (the OAuth callback's URL holds a code);
     * `same-origin`, not `no-referrer`, because with `no-referrer` Chromium sends `Origin: null` on the
     * page's own forms and the Manage page's origin check refuses them (seen 2026-11-12).
     */
    fun securityHeaders(formAction: String = "'self'"): Map<String, String> = mapOf(
        "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; " +
            "frame-ancestors 'none'; form-action $formAction",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "same-origin",
    )

    /** glassworks.dev's tokens (kiln/packages/brand/src/tokens.css): night, then daylight. */
    const val TOKENS = """:root{--bg:#0a0b0f;--bg-2:#111319;--glass:rgba(255,255,255,.045);--edge:rgba(255,255,255,.08);--fg:#f4f4f5;--muted:#a1a1aa;--faint:#6b6b74;
--ink-sky:#7dd3fc;--ok-ink:#34d399;--work-ink:#fbbf24;--sky:#38bdf8;--violet:#a78bfa;--green:#34d399;--amber:#fbbf24;--rose:#fb7185;
--lit:linear-gradient(135deg,var(--sky),var(--violet));--btn:#f4f4f5;--btx:#0a0b0f;--pl:#f4f4f5;--g1:rgba(56,189,248,.13);--g2:rgba(167,139,250,.14);
--field:rgba(255,255,255,.06);--hover:rgba(255,255,255,.06);color-scheme:dark}
@media (prefers-color-scheme:light){:root{--bg:#eceef2;--bg-2:#e2e5eb;--glass:rgba(255,255,255,.66);--edge:rgba(16,20,32,.1);--fg:#0c0e13;--muted:#4f525c;--faint:#7d808b;
--ink-sky:#0369a1;--ok-ink:#047857;--work-ink:#b45309;--btn:#0c0e13;--btx:#f4f4f5;--pl:#0c0e13;--g1:rgba(56,189,248,.22);--g2:rgba(167,139,250,.22);
--field:rgba(255,255,255,.8);--hover:rgba(16,20,32,.05);color-scheme:light}}
*{box-sizing:border-box}html,body{margin:0;min-height:100%}
body{min-height:100vh;color:var(--fg);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
background:radial-gradient(900px 600px at -10% -20%,var(--g1),transparent 60%),radial-gradient(800px 600px at 110% 120%,var(--g2),transparent 60%),var(--bg);background-attachment:fixed}
a{color:inherit}
svg.mk rect{stroke:var(--pl);stroke-width:2.4}.p0{stroke-opacity:.7}.p1{stroke-opacity:.45}.p2{stroke-opacity:.2}
.bad{stroke:var(--rose)!important;stroke-dasharray:5 4}svg.mk rect.lit{stroke:none}
.hd{display:flex;align-items:center;gap:10px;font-size:13px;font-weight:600}.hd .m{width:20px;height:20px}.hd .m rect{stroke-width:1.4}
.hd .sp{flex:1}.hd a.to{font-weight:500;color:var(--muted);text-decoration:none}.hd a.to:hover{color:var(--fg)}"""

    /** The stack as inline SVG: plates are isometric rounded squares (branding/boot/stack.py), top plate last. */
    fun mark(look: Look, cls: String): String {
        fun plate(i: Int, attrs: String) =
            """<rect width="60" height="60" rx="9" transform="matrix(.866 .5 -.866 .5 60 ${11 + i * 19})" vector-effect="non-scaling-stroke" $attrs/>"""
        val top = when (look) {
            Look.START -> plate(0, """fill="none" class="p0"""")
            Look.DONE -> plate(0, """fill="url(#lit)" class="lit"""")
            Look.PROBLEM -> plate(0, """fill="none" class="bad"""")
        }
        return """<svg class="mk $cls" viewBox="2 6 116 108" aria-hidden="true"><defs><linearGradient id="lit" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#38bdf8"/><stop offset=".55" stop-color="#818cf8"/><stop offset="1" stop-color="#a78bfa"/></linearGradient></defs>""" +
            plate(2, """fill="none" class="p2"""") + plate(1, """fill="none" class="p1"""") + top + "</svg>"
    }

    /** The small header every page starts with: the stack and the panel's name, and on the right [right] (HTML). */
    fun header(name: String, look: Look, right: String = "") =
        """<header class="hd">${mark(if (look == Look.DONE) Look.DONE else Look.START, "m")}<span>${esc(name)}</span><span class="sp"></span>$right</header>"""

    /**
     * Where a QR page's header has room on its right. The QR pages don't know about the Manage session
     * (they work without one); the Manage server fills the slot with its way back for a signed-in browser
     * ([fillHeaderSlot]) and empties it for everyone else.
     */
    const val HEADER_SLOT = "<!--gw-header-right-->"

    fun fillHeaderSlot(html: ByteArray, right: String): ByteArray {
        val s = String(html, Charsets.UTF_8)
        return if (HEADER_SLOT in s) s.replace(HEADER_SLOT, right).toByteArray(Charsets.UTF_8) else html
    }

    /** A whole page: [css] after the tokens, [body] as given. */
    fun document(title: String, css: String, body: String, bodyClass: String = ""): String =
        """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark light">
<title>${esc(title)}</title><style>$TOKENS
$css</style></head><body${if (bodyClass.isNotEmpty()) " class=\"$bodyClass\"" else ""}>$body</body></html>"""

    // ------------------------------------------------------------------ the QR-code pages (#63)

    private const val QR_CSS = """body{display:flex;flex-direction:column;padding:28px 24px}
main{flex:1;display:flex;flex-direction:column;max-width:960px;width:100%;margin:0 auto}
.big{width:52px;height:52px;margin:auto 0 18px}
h1{font-size:30px;font-weight:300;letter-spacing:-.02em;line-height:1.15;margin:0 0 10px}
p{color:var(--muted);margin:0}p b,.txt b{color:var(--fg);font-weight:500}
.tile{display:flex;align-items:center;gap:14px;margin-top:22px;padding:14px 16px;border-radius:16px;background:var(--glass);border:1px solid var(--edge);font-size:15px}
.tile>div{flex:1}.tile small{display:block;font-size:12px;color:var(--faint);margin-bottom:2px}
.ic{display:grid;place-items:center;width:30px;height:30px;border-radius:9px;color:#38bdf8;background:rgba(56,189,248,.14)}
.info{align-items:flex-start;color:var(--muted);font-size:14px}.ii{display:grid;place-items:center;width:18px;height:18px;border:1.5px solid var(--muted);border-radius:50%;font-size:11px;font-weight:700;flex:none;margin-top:2px}
i.ok{width:8px;height:8px;border-radius:50%;background:var(--green);flex:none}
.act{margin-top:auto;padding-top:28px;min-height:1px}.btn{display:block;text-align:center;padding:15px 20px;border-radius:999px;background:var(--btn);color:var(--btx);text-decoration:none;font-weight:500}
.foot{text-align:center;font-size:12.5px;color:var(--faint);margin-top:12px}.foot a{color:var(--muted)}
@media (min-width:720px){body{padding:28px 40px}main{display:grid;grid-template-columns:1fr 1.1fr;grid-template-rows:1fr auto auto 1fr;column-gap:56px}
.big{grid-column:1;grid-row:2/4;width:200px;height:190px;justify-self:end;align-self:center;margin:0}.copy,.act{grid-column:2;max-width:380px}.copy{grid-row:2}.act{grid-row:3;margin:0;padding-top:20px}
.btn{display:inline-block}.foot{text-align:left}}
button.btn,button.lk{border:0;font:inherit;cursor:pointer}button.lk{background:none;padding:0;color:var(--fg);text-decoration:underline;text-underline-offset:3px}
.sw{display:inline-flex;align-items:center;gap:12px;color:var(--muted);font-size:15px;cursor:pointer}
.sw input{appearance:none;-webkit-appearance:none;margin:0;width:44px;height:26px;border-radius:999px;background:var(--field);border:1px solid var(--edge);position:relative;cursor:pointer;flex:none}
.sw input::after{content:"";position:absolute;top:2px;left:2px;width:20px;height:20px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.3);transition:left .15s}
.sw input:checked{background:var(--lit);border-color:transparent}.sw input:checked::after{left:20px}
.sw input:focus-visible,.btn:focus-visible,button.lk:focus-visible{outline:2px solid var(--sky);outline-offset:3px}
.rule{height:1px;background:var(--edge);margin:22px 0 18px}"""

    /**
     * One QR-code page (#63): [text] is HTML (escape what comes from outside). Done pages have no button;
     * errors say the one fix on the panel. [headerRight] is HTML for the header's right side (the way back
     * to Manage for a signed-in browser). [footHtml] is HTML, [foot] plain text.
     */
    fun qrPage(
        name: String, title: String, text: String, look: Look,
        tile: String = "", button: String = "", foot: String = "", footHtml: String = "", headerRight: String = "", extraCss: String = "",
    ): String {
        val heading = esc(title).let { if (it.endsWith(".")) it else "$it." }
        val footer = when {
            footHtml.isNotEmpty() -> "<div class=\"foot\">$footHtml</div>"
            foot.isNotEmpty() -> "<div class=\"foot\">${esc(foot)}</div>"
            else -> ""
        }
        return document(name, QR_CSS + extraCss, header(name, look, headerRight) +
            """<main>${mark(look, "big")}<div class="copy"><h1>$heading</h1><p>$text</p>$tile</div><div class="act">$button$footer</div></main>""")
    }
}
