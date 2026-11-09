/* S1 Kiosk helper content script (MIT).
 *
 * - Kiosk mode for Home Assistant: hides the dashboard header (toolbar) and the sidebar.
 *   Disable per page with ?kiosk=0 (or ?edit=1), or globally in the app settings.
 * - Exposes panel state to the page as `window.s1kiosk` and a `s1kiosk-state` DOM event.
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

  hideSidebar();

  // Ask the app for settings (falls back to defaults if the app hasn't wired up messaging yet).
  browser.runtime.sendNativeMessage(APP, { type: "config" }).then((r) => {
    if (r && typeof r === "object") {
      cfg = Object.assign(cfg, r);
      hideSidebar();
      apply();
    }
  }).catch(() => {});

  // Live state push from the app (presence, lux, screen) over a native port.
  try {
    const port = browser.runtime.connectNative(APP);
    port.onMessage.addListener((m) => {
      if (m && m.type === "state") publishState(m.state);
      if (m && m.type === "config") { cfg = Object.assign(cfg, m.config); apply(); }
    });
  } catch (e) { /* not fatal */ }

  // HA renders lazily and re-renders on navigation; re-apply cheaply.
  setInterval(apply, 1000);
  document.addEventListener("DOMContentLoaded", apply);
})();
