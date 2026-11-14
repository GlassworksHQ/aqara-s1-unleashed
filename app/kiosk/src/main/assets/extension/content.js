/* S1 Kiosk helper content script (MIT).
 *
 * - Kiosk mode for Home Assistant: hides the dashboard header (toolbar) and the sidebar.
 *   Disable per page with ?kiosk=0 (or ?edit=1), or globally in the app settings.
 * - Exposes panel state to the page as `window.s1kiosk` and a `s1kiosk-state` DOM event.
 * - External auth bridge (only on pages the app loaded with ?external_auth=1, i.e. once the panel
 *   is signed in with the QR code): `window.externalApp` with getExternalAuth / revokeExternalAuth /
 *   externalBus, the "V1" interface the HA frontend uses for the Android Companion app
 *   (frontend src/external_app/external_auth.ts, external_messaging.ts; src/data/external.ts).
 *   Tokens come from the app over native messaging; the app checks the page origin.
 * - Reports the Lovelace dashboard list (`lovelace/dashboards/list` over the page's own HA
 *   connection) to the app on load and every 10 min, for the ESPHome "Dashboard" select.
 */
(() => {
  "use strict";
  const APP = "s1kiosk";
  const params = new URLSearchParams(location.search);
  const disabledByUrl = params.get("kiosk") === "0" || params.has("edit");

  let cfg = { hideHeader: true, hideSidebar: true };

  // ---- Home Assistant's launch screen, at night (#63 round 5) ----------------------------------------
  // HA's index.html starts with its own launch screen on #fafafa: the logo (.ha-lockup), an info box
  // (#ha-launch-screen-info-box, empty during a normal load; the frontend writes why it couldn't start
  // there) and the Open Home Foundation attribution (.ohf-logo). However the app times the end of its
  // connecting screen, on the S1 that splash could still be what Gecko last painted (round 5 panel run:
  // one white frame in two cold starts). So it draws at night: the background is the night colour, the
  // logo and the attribution are hidden, and the info box stays readable in a light colour, so a failed
  // start still says why. Every rule is scoped to the launch screen being there (:has), and the style is
  // removed once the frontend has removed it. Not on a page the person opened with ?kiosk=0 or ?edit=1.
  const LAUNCH_CSS = `
    html:has(#ha-launch-screen), html:has(#ha-launch-screen) body { background: #0a0b0f !important; }
    #ha-launch-screen { background: #0a0b0f !important; }
    #ha-launch-screen .ha-lockup, #ha-launch-screen .ohf-logo { visibility: hidden !important; }
    #ha-launch-screen #ha-launch-screen-info-box { color: #f4f4f5 !important; }
  `;
  let launchStyle = null;
  if (!disabledByUrl) {
    try {
      launchStyle = document.createElement("style");
      launchStyle.id = "s1kiosk-launch";
      launchStyle.textContent = LAUNCH_CSS;
      (document.head || document.documentElement).appendChild(launchStyle);
    } catch (e) { launchStyle = null; /* not fatal: the connecting screen still covers it */ }
  }
  function dropLaunchStyle() {
    if (!launchStyle) return;
    try { launchStyle.remove(); } catch (e) { /* gone with the page */ }
    launchStyle = null;
  }

  // With the header hidden, its height is 0 for everything under hui-root too: cards that size
  // themselves as `calc(100vh - var(--header-height))` otherwise end 56 CSS px short and leave a
  // blank strip at the bottom of the screen (issue #7).
  const HUI_CSS = `
    :host { --header-height: 0px !important; }
    .header { display: none !important; }
    #view { padding-top: 0 !important; min-height: 100vh !important; }
  `;

  function sr(el) { return el && el.shadowRoot; }

  function addStyle(root, id, css) {
    if (!root || root.querySelector("style#" + id)) return;
    const s = document.createElement("style");
    s.id = id;
    s.textContent = css;
    root.appendChild(s);
  }

  function removeStyle(root, id) {
    const s = root && root.querySelector("style#" + id);
    if (s) s.remove();
  }

  function apply() {
    const ha = document.querySelector("home-assistant");
    if (!ha) return;
    const main = sr(ha) && sr(ha).querySelector("home-assistant-main");
    const panel = sr(main) && sr(main).querySelector("ha-panel-lovelace");
    const hui = sr(panel) && sr(panel).querySelector("hui-root");
    const huiRoot = sr(hui);
    // Don't hide the toolbar while the dashboard is being edited.
    const editing = huiRoot && huiRoot.querySelector(".edit-mode, hui-editor, .header.edit-mode");
    if (cfg.hideHeader && !disabledByUrl && !editing) addStyle(huiRoot, "s1kiosk-hui", HUI_CSS);
    else removeStyle(huiRoot, "s1kiosk-hui");
  }

  function hideSidebar() {
    if (!cfg.hideSidebar || disabledByUrl) return;
    try {
      // HA stores UI prefs as JSON strings in localStorage; it reads this at startup.
      if (localStorage.getItem("dockedSidebar") !== '"always_hidden"') {
        localStorage.setItem("dockedSidebar", '"always_hidden"');
      }
    } catch (e) { /* storage unavailable */ }
  }

  function publishState(state) {
    try {
      const detail = cloneInto(state, window);
      window.wrappedJSObject.s1kiosk = detail;
      window.dispatchEvent(new CustomEvent("s1kiosk-state", { detail }));
    } catch (e) { /* not fatal */ }
  }

  // ---- native port: request/response ------------------------------------------------------------
  // Replies to browser.runtime.sendNativeMessage don't reach the page in this GeckoView build (the
  // parent logs "Invalid event data for callback" and the promise rejects with undefined), so every
  // app <-> page exchange goes over one long-lived native port: requests carry an id, the app answers
  // with {type: "reply", id, ...} on the same port. The app also pushes state/config on it.
  let port = null;
  let nextId = 1;
  const pending = new Map();
  const portListeners = [];
  try {
    port = browser.runtime.connectNative(APP);
    port.onMessage.addListener((m) => {
      if (m && m.type === "reply" && pending.has(m.id)) {
        const done = pending.get(m.id);
        pending.delete(m.id);
        done(m);
        return;
      }
      portListeners.forEach((l) => { try { l(m); } catch (e) { /* ignore */ } });
    });
    port.onDisconnect.addListener(() => {
      port = null;
      pending.forEach((done) => done(null));
      pending.clear();
    });
  } catch (e) { port = null; }

  /** Sends {type, ...extra} to the app; resolves with the reply object, or null on failure/timeout. */
  function request(type, extra, timeoutMs) {
    return new Promise((resolve) => {
      if (!port) { resolve(null); return; }
      const id = nextId++;
      const t = setTimeout(() => { if (pending.delete(id)) resolve(null); }, timeoutMs || 15000);
      pending.set(id, (m) => { clearTimeout(t); resolve(m); });
      try { port.postMessage(Object.assign({ type, id }, extra || {})); }
      catch (e) { pending.delete(id); clearTimeout(t); resolve(null); }
    });
  }

  // ---- external auth bridge ------------------------------------------------------------------
  // The frontend computes `isExternal` once at startup from window.externalApp / ?external_auth=1,
  // so the object must exist before its module scripts run: install it synchronously here
  // (document_start), never after an async round trip.
  // Callbacks the frontend installs on window and asks us to call (external_auth.ts constants).
  const AUTH_CALLBACKS = ["externalAuthSetToken", "externalAuthRevokeToken"];

  function callPage(name, ...args) {
    try {
      const fn = window.wrappedJSObject[name];
      if (typeof fn !== "function") return;
      fn(...args.map((a) => (a !== null && typeof a === "object" ? cloneInto(a, window) : a)));
    } catch (e) { console.warn("s1kiosk: page callback " + name + " failed", e); }
  }

  function parsePayload(payload) {
    try { return typeof payload === "string" ? JSON.parse(payload) : null; } catch (e) { return null; }
  }

  function installExternalApp() {
    const ext = createObjectIn(window, { defineAs: "externalApp" });

    // getExternalAuth('{"callback":"externalAuthSetToken","force":true}')
    // -> window.externalAuthSetToken(true, {access_token, expires_in}) or (false)
    exportFunction(function (payload) {
      const p = parsePayload(payload);
      if (!p || !AUTH_CALLBACKS.includes(p.callback)) return;
      request("getExternalAuth", { force: !!p.force }).then((r) => {
        if (r && r.success) callPage(p.callback, true, { access_token: r.access_token, expires_in: r.expires_in });
        else callPage(p.callback, false);
      });
    }, ext, { defineAs: "getExternalAuth" });

    // revokeExternalAuth('{"callback":"externalAuthRevokeToken"}'): the user logged out in HA.
    exportFunction(function (payload) {
      const p = parsePayload(payload);
      if (!p || !AUTH_CALLBACKS.includes(p.callback)) return;
      request("revokeExternalAuth").then((r) => callPage(p.callback, !!(r && r.success)));
    }, ext, { defineAs: "revokeExternalAuth" });

    // externalBus(JSON): with this method present the frontend creates ExternalMessaging and
    // *awaits* `config/get` before it finishes loading, so that one must be answered. Replies go to
    // window.externalBus({id, type: "result", success, result|error}) and must be async: the
    // frontend registers the pending id right after calling us.
    const CONFIG = {
      hasSettingsScreen: false, hasSidebar: false, canWriteTag: false, hasExoPlayer: false,
      canCommissionMatter: false, canImportThreadCredentials: false, hasAssist: false,
      hasBarCodeScanner: 0, canSetupImprov: false, hasEntityAddTo: false, appVersion: "S1 Kiosk",
      // The app covers the frontend with its connecting screen until it has drawn: the frontend then
      // drops its own launch screen at once and sends frontend/loaded (partial-panel-resolver.ts).
      hasSplashscreen: true,
    };
    // Messages that expect an answer (EMOutgoingMessageWithAnswer); everything else is fire-and-forget.
    const NEEDS_ANSWER = ["entity/add_to/get_actions", "matter/share_device", "webrtc/stream/start"];
    exportFunction(function (raw) {
      const msg = parsePayload(raw);
      if (!msg || typeof msg.id !== "number" || msg.type === "result") return;
      let reply = null;
      if (msg.type === "frontend/loaded") readyAfterPaint("frontend/loaded"); // fire-and-forget
      if (msg.type === "config/get") {
        reply = { id: msg.id, type: "result", success: true, result: CONFIG };
      } else if (NEEDS_ANSWER.includes(msg.type)) {
        reply = { id: msg.id, type: "result", success: false,
          error: { code: "not_supported", message: "Not supported by S1 Kiosk" } };
      }
      if (reply) setTimeout(() => callPage("externalBus", reply), 0);
    }, ext, { defineAs: "externalBus" });
  }

  if (params.get("external_auth") === "1") {
    try { installExternalApp(); } catch (e) { console.warn("s1kiosk: external auth bridge failed", e); }
  }

  hideSidebar();

  // Ask the app for settings (falls back to defaults if the app hasn't wired up messaging yet).
  request("config", null, 5000).then((r) => {
    if (r && r.config && typeof r.config === "object") {
      cfg = Object.assign(cfg, r.config);
      hideSidebar();
      apply();
    }
  });

  // Live pushes from the app (presence, lux, screen state; config changes) on the same port.
  portListeners.push((m) => {
    if (m && m.type === "state") publishState(m.state);
    if (m && m.type === "config") { cfg = Object.assign(cfg, m.config); apply(); }
  });

  // ---- Notifications (#118): open a Home Assistant view in place ---------------------------------
  // As the frontend's own navigate() does (history.pushState, then "location-changed"), so the S1
  // doesn't load the whole frontend again for a doorbell view and again for the way back. Only on this
  // page's own origin; the app checked the URL already (HaUrls). The app opens a notification's view
  // and goes back with `replace`, so the view doesn't stay in the history for Back to find later.
  portListeners.push((m) => {
    if (!m || m.type !== "navigate" || window !== window.top) return;
    let target;
    try { target = new URL(m.url, location.href); } catch (e) { return; }
    if (target.origin !== location.origin) return;
    const path = target.pathname + target.search + target.hash;
    if (document.querySelector("home-assistant")) {
      if (m.replace) history.replaceState(null, "", path); else history.pushState(null, "", path);
      window.dispatchEvent(new CustomEvent("location-changed"));
    } else if (m.replace) {
      location.replace(path);
    } else {
      location.assign(path);
    }
  });

  // ---- Lovelace dashboards for the panel's ESPHome "Dashboard" select ------------------------------
  // Asked through the page's own HA connection (`hass.callWS`), so it works with the cookie session
  // and with external auth alike. Sent fire-and-forget on the port as a JSON string (no GeckoBundle
  // typing issues); the app only believes the top-level page on the HA it uses.
  const DASH_RETRY_MS = 5000, DASH_REFRESH_MS = 10 * 60 * 1000, DASH_MAX_TRIES = 36;
  let dashTries = 0;

  function reportDashboards() {
    let hass = null;
    try {
      const ha = document.querySelector("home-assistant");
      hass = ha && ha.wrappedJSObject && ha.wrappedJSObject.hass;
    } catch (e) { hass = null; }
    if (!port || !hass || !hass.connected || typeof hass.callWS !== "function") return false;
    let isAdmin = false;
    try { isAdmin = !!(hass.user && hass.user.is_admin); } catch (e) { /* ignore */ }
    const onResult = (res) => {
      let list;
      try { list = JSON.parse(window.wrappedJSObject.JSON.stringify(res)); } catch (e) { return; }
      if (!Array.isArray(list)) return;
      const out = list
        .filter((d) => d && typeof d.url_path === "string" && d.url_path && (isAdmin || !d.require_admin))
        .map((d) => ({ url_path: d.url_path, title: typeof d.title === "string" ? d.title : "" }));
      try { port.postMessage({ type: "dashboards", json: JSON.stringify(out) }); } catch (e) { /* port gone */ }
    };
    try {
      hass.callWS(cloneInto({ type: "lovelace/dashboards/list" }, window))
        .then(exportFunction(onResult, window), exportFunction(() => {}, window));
    } catch (e) { return false; }
    return true;
  }

  function scheduleDashboards() {
    // Not a Home Assistant page (its index.html has <home-assistant> from the start): stop.
    if (!document.querySelector("home-assistant") && document.readyState === "complete") return;
    const ok = reportDashboards();
    dashTries++;
    if (ok) setTimeout(scheduleDashboards, DASH_REFRESH_MS);
    else if (dashTries < DASH_MAX_TRIES) setTimeout(scheduleDashboards, DASH_RETRY_MS);
  }
  setTimeout(scheduleDashboards, DASH_RETRY_MS);

  // ---- ready: the page is drawn (#63 round 2) ----------------------------------------------------
  // Home Assistant's index.html starts with its own launch screen (#ha-launch-screen: the logo, an info
  // box and the attribution) and the frontend removes it once it has drawn. The app keeps its connecting
  // screen up until then and cross-fades into the dashboard. A page
  // without that launch screen (the sign-in page, any other page) is ready once its document is.
  // The app fades its screen out over what Gecko last painted, and on the S1 the paint without the
  // launch screen can come well after the DOM change (round 4 panel run: the white splash showed
  // through the fade). So "ready" waits for two frames to be drawn, and a little more.
  const PAINT_MARGIN_MS = 150;
  let readySent = false;
  let readyPending = false; // one chain of frames at a time
  function sendReady(why) {
    if (readySent || !port) return;
    readySent = true;
    try { port.postMessage({ type: "ready", why }); } catch (e) { /* port gone */ }
  }
  function readyAfterPaint(why) {
    if (readySent || readyPending) return;
    readyPending = true;
    try {
      requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(() => sendReady(why), PAINT_MARGIN_MS)));
    } catch (e) { sendReady(why); }
  }
  function watchLaunchScreen() {
    const ls = document.getElementById("ha-launch-screen");
    if (!ls || !ls.parentNode) { dropLaunchStyle(); readyAfterPaint("page"); return; }
    // Home Assistant's frontend is loading: the app waits longer for it before giving up (a cold load
    // on the S1 can take well over 15 s).
    try { if (port) port.postMessage({ type: "launch" }); } catch (e) { /* port gone */ }
    const obs = new MutationObserver(() => {
      if (!document.getElementById("ha-launch-screen")) { obs.disconnect(); dropLaunchStyle(); readyAfterPaint("removed"); }
    });
    obs.observe(ls.parentNode, { childList: true });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", watchLaunchScreen);
  else watchLaunchScreen();

  // HA renders lazily and re-renders on navigation; re-apply cheaply.
  setInterval(apply, 1000);
  document.addEventListener("DOMContentLoaded", apply);
})();
