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
 */
(() => {
  "use strict";
  const APP = "s1kiosk";
  const params = new URLSearchParams(location.search);
  const disabledByUrl = params.get("kiosk") === "0" || params.has("edit");

  let cfg = { hideHeader: true, hideSidebar: true };

  const HUI_CSS = `
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
    };
    // Messages that expect an answer (EMOutgoingMessageWithAnswer); everything else is fire-and-forget.
    const NEEDS_ANSWER = ["entity/add_to/get_actions", "matter/share_device", "webrtc/stream/start"];
    exportFunction(function (raw) {
      const msg = parsePayload(raw);
      if (!msg || typeof msg.id !== "number" || msg.type === "result") return;
      let reply = null;
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

  // HA renders lazily and re-renders on navigation; re-apply cheaply.
  setInterval(apply, 1000);
  document.addEventListener("DOMContentLoaded", apply);
})();
