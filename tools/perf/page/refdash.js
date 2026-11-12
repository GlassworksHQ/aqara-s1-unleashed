/* Glassworks reference dashboards (MIT): the three fixed, seeded, Home Assistant-like pages the grade
 * is measured on (?tier=light|typical|busy; tiers.js defines them). No network. Like the HA
 * frontend, the page renders from a states payload after it loads, each card a custom element with
 * its own shadow root. The card mix and DOM size are project choices (docs/perf.md).
 *
 * Phases: render still, so the load can settle -> log `refdash` (load times, on the page's clock)
 * -> stay still -> go live (the tier's updates and animations) -> `warmup` -> `measure` -> `step`
 * (frame stats, and the window's own length) -> `input-ready` / `input` (taps) -> `done`.
 * tools/perf/perfrun.py (refstart) reads these lines; docs/perf.md lists their fields.
 */
(function () {
  "use strict";
  const W = window.GWWorkload, S = window.GWStats, T = window.GWTiers;
  const q = new URLSearchParams(location.search);
  const NONCE = q.get("nonce") || "";
  const RUN = Number(q.get("run") || 0);
  const TIER = T.TIERS[q.get("tier")] ? q.get("tier") : "light";
  const SPEC = T.TIERS[TIER];
  const VSYNC = Number(q.get("vsync") || 1000 / 60);
  const TAPS = Math.max(0, Number(q.get("taps") || 0));
  const SEED = 89;

  // Simple icons drawn for this page (not copied from an icon set): name -> SVG path, 24x24.
  const ICONS = {
    bulb: "M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2zM9 19h6v2H9z",
    plug: "M7 2h2v5h6V2h2v5h1v5a6 6 0 0 1-5 5.9V22h-2v-4.1A6 6 0 0 1 6 12V7h1z",
    therm: "M10 3a2 2 0 0 1 4 0v10.3a4 4 0 1 1-4 0zM11 6v8h2V6z",
    drop: "M12 2l6 8a6 6 0 1 1-12 0z",
    door: "M5 2h14v20H5zM15 11h2v2h-2z",
    fan: "M12 11a1 1 0 1 1 0 2 1 1 0 0 1 0-2zM12 2c3 0 4 3 2 6l-2 2zM22 12c0 3-3 4-6 2l-2-2zM12 22c-3 0-4-3-2-6l2-2zM2 12c0-3 3-4 6-2l2 2z",
    sun: "M12 7a5 5 0 1 1 0 10 5 5 0 0 1 0-10zM11 0h2v4h-2zM11 20h2v4h-2zM0 11h4v2H0zM20 11h4v2h-4z",
    bolt: "M13 2L4 14h7l-2 8 9-12h-7z",
    lock: "M6 10V7a6 6 0 0 1 12 0v3h1v12H5V10zM8 10h8V7a4 4 0 0 0-8 0z",
    tv: "M2 5h20v13H2zM8 20h8v2H8z",
  };
  const ICON_FOR = { sensor: "therm", light: "bulb", switch: "plug", binary_sensor: "door", climate: "fan", media_player: "tv" };

  function icon(name, on) {
    return `<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path fill="${on ? "var(--on)" : "var(--muted)"}" d="${ICONS[name]}"/></svg>`;
  }

  // Animations run only once the card is live, so the page is still while it loads.
  const CARD_CSS = `
    :host { display: block; background: var(--card); border-radius: 12px; box-shadow: 0 2px 4px rgba(0,0,0,.35); overflow: hidden; }
    h2 { margin: 0; padding: 12px 16px 4px; font-size: 16px; font-weight: 500; }
    .row { display: flex; align-items: center; gap: 12px; padding: 6px 16px; min-height: 36px; }
    .row .name { flex: 1; min-width: 0; }
    .row .primary { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .row .secondary { color: var(--muted); font-size: 12px; }
    .row .state { color: var(--muted); font-variant-numeric: tabular-nums; }
    .tile { display: grid; grid-template-columns: 40px 1fr; gap: 2px 10px; padding: 12px; }
    .tile .ic { grid-row: span 2; width: 40px; height: 40px; border-radius: 50%; background: rgba(255,255,255,.06); display: grid; place-items: center; }
    .tile .name { font-weight: 500; }
    .tile .state { color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
    .tile.on .ic { background: rgba(255,193,7,.2); }
    .big { font-size: 34px; font-weight: 300; padding: 4px 16px; font-variant-numeric: tabular-nums; }
    .fc { display: flex; justify-content: space-between; padding: 8px 16px 14px; font-size: 12px; color: var(--muted); }
    svg.chart, canvas.chart { display: block; width: 100%; height: 70px; }
    :host([live]) .spin svg { animation: spin 1.2s linear infinite; }
    :host([live]) .pulse svg { animation: pulse 0.9s ease-in-out infinite alternate; }
    :host([live]) .ring circle.arc { animation: ring 2s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    @keyframes pulse { to { opacity: 0.2; } }
    @keyframes ring { from { stroke-dashoffset: 251; } to { stroke-dashoffset: 0; } }
  `;

  class GwCard extends HTMLElement {
    set config(html) {
      const root = this.attachShadow({ mode: "open" });
      root.innerHTML = `<style>${CARD_CSS}</style>${html}`;
    }
  }
  customElements.define("gw-card", GwCard);

  function card(html) {
    const el = document.createElement("gw-card");
    el.config = html;
    return el;
  }

  function stateText(s) {
    const unit = s.entity_id.startsWith("sensor.") ? s.attributes.unit_of_measurement : "";
    return unit ? `${s.state} ${unit}` : s.state;
  }

  // Nested like HA's entity rows (state badge, icon, info, secondary line), which is most of its DOM.
  function row(s) {
    const d = s.entity_id.split(".")[0];
    return `<div class="row"><div class="badge"><span class="ic">${icon(ICON_FOR[d], s.state === "on")}</span></div>
      <div class="name"><div class="primary">${s.attributes.friendly_name}</div><div class="secondary"><span>${s.last_changed.slice(11, 16)}</span></div></div>
      <div class="state"><span class="value">${stateText(s)}</span></div></div>`;
  }

  function entitiesCard(title, states) {
    return card(`<h2>${title}</h2><div class="rows">${states.map(row).join("")}</div><div style="height:8px"></div>`);
  }

  function tileCard(s, iconName, motion) {
    const d = s.entity_id.split(".")[0];
    return card(`<div class="tile"><div class="ic ${motion || ""}">${icon(iconName || ICON_FOR[d], s.state === "on")}</div>
      <span class="name">${s.attributes.friendly_name}</span><span class="state">${stateText(s)}</span></div>`);
  }

  function sensorCard(s, rnd) {
    let d = "M0 50";
    for (let i = 1; i <= 48; i++) d += ` L${(i * 300 / 48).toFixed(1)} ${(20 + 40 * rnd()).toFixed(1)}`;
    return card(`<h2>${s.attributes.friendly_name}</h2><div class="big">${s.state} ${s.attributes.unit_of_measurement}</div>
      <svg class="chart" viewBox="0 0 300 70" preserveAspectRatio="none"><path d="${d}" fill="none" stroke="var(--accent)" stroke-width="2"/></svg>`);
  }

  function gaugeCard(title, value) {
    const a = Math.PI * (1 - value / 100);
    const x = 100 + 80 * Math.cos(a), y = 100 - 80 * Math.sin(a);
    return card(`<h2>${title}</h2><svg viewBox="0 0 200 110" style="display:block;width:100%;height:120px">
      <path d="M20 100 A80 80 0 0 1 180 100" fill="none" stroke="rgba(255,255,255,.1)" stroke-width="16"/>
      <path d="M20 100 A80 80 0 0 1 ${x.toFixed(1)} ${y.toFixed(1)}" fill="none" stroke="var(--accent)" stroke-width="16"/>
      <text x="100" y="96" text-anchor="middle" fill="var(--text)" font-size="28">${value}%</text></svg>`);
  }

  function weatherCard(rnd) {
    const days = ["Mon", "Tue", "Wed", "Thu", "Fri"].map((d) =>
      `<div>${d}<br>${icon("sun", true)}<br>${Math.round(12 + 10 * rnd())}°</div>`).join("");
    return card(`<h2>Forecast</h2><div class="big">${icon("sun", true)} 18 °C</div><div class="fc">${days}</div>`);
  }

  const live = { rows: [], tiles: [], clock: null, canvas: null, toggle: null };

  function render() {
    const rnd = W.mulberry32(SEED);
    const states = JSON.parse(W.makeStates(SEED, 400, 0));
    document.getElementById("tabs").innerHTML = "<b>Home</b><span>Energy</span><span>Climate</span><span>Media</span><span>Security</span>";
    const view = document.getElementById("view");
    const cols = [0, 1, 2, 3].map(() => { const c = document.createElement("div"); c.className = "col"; view.appendChild(c); return c; });
    let k = 0;
    const put = (el) => { cols[k++ % cols.length].appendChild(el); return el; };
    put(weatherCard(rnd));
    live.toggle = put(tileCard({ entity_id: "light.living_room", state: "off", attributes: { friendly_name: "Living room" } }));
    for (let i = 0; i < 12; i++) live.rows.push(put(entitiesCard(`Room ${i + 1}`, states.slice(i * 6, i * 6 + 6))));
    for (let i = 0; i < 24; i++) {
      const motion = i < SPEC.spinners ? "spin" : i < SPEC.spinners + SPEC.pulses ? "pulse" : "";
      live.tiles.push(put(tileCard(states[80 + i], motion === "spin" ? "fan" : null, motion)));
    }
    for (let i = 0; i < 4; i++) put(sensorCard(states[i * 6], rnd));
    put(gaugeCard("Battery", 72));
    put(gaugeCard("Humidity", 48));
    if (SPEC.ring) put(card(`<h2>Washing machine</h2><svg class="ring" viewBox="0 0 100 100" style="display:block;width:120px;height:120px;margin:4px auto 12px">
      <circle cx="50" cy="50" r="40" fill="none" stroke="rgba(255,255,255,.1)" stroke-width="10"/>
      <circle class="arc" cx="50" cy="50" r="40" fill="none" stroke="var(--accent)" stroke-width="10" stroke-dasharray="251" stroke-dashoffset="125"/></svg>`));
    if (SPEC.clock) live.clock = put(card(`<h2>Time</h2><div class="big">12:00:00</div>`));
    if (SPEC.sparkline) {
      const c = put(card(`<h2>Power</h2><canvas class="chart" width="600" height="140"></canvas>`));
      live.canvas = c.shadowRoot.querySelector("canvas");
    }
  }

  function nodeCount() {
    let n = document.getElementsByTagName("*").length;
    for (const c of document.querySelectorAll("gw-card")) n += c.shadowRoot.querySelectorAll("*").length;
    return n;
  }

  function log(obj) {
    console.log("GWPERF " + JSON.stringify(Object.assign({ run: RUN, nonce: NONCE }, obj)));
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  let lcp = null;
  try {
    new PerformanceObserver((l) => { for (const e of l.getEntries()) lcp = e.renderTime || e.startTime; })
      .observe({ type: "largest-contentful-paint", buffered: true });
  } catch (e) { lcp = null; }

  async function reportLoad() {
    const paint = () => performance.getEntriesByName("first-contentful-paint")[0];
    const end = performance.now() + 5000;
    while (!paint() && performance.now() < end) await sleep(100);
    await sleep(300);
    const nav = performance.getEntriesByType("navigation")[0] || {};
    const fcp = paint();
    log({
      t: "refdash", tier: TIER, nodes: nodeCount(), cards: document.querySelectorAll("gw-card").length,
      fcp_ms: fcp ? Math.round(fcp.startTime) : null, lcp_ms: lcp === null ? null : Math.round(lcp),
      load_ms: nav.loadEventEnd ? Math.round(nav.loadEventEnd) : null,
      // performance times are relative to timeOrigin (epoch ms); the runner maps them to logcat time.
      origin_epoch_ms: Math.round(performance.timeOrigin), epoch_ms: Date.now(),
    });
  }

  // The tier's live behaviour. State updates step through seeded payloads, so every run shows the same.
  function goLive(onFrame) {
    for (const c of document.querySelectorAll("gw-card")) c.setAttribute("live", "");
    let tick = 0;
    setInterval(() => {
      tick++;
      const states = JSON.parse(W.makeStates(SEED, 400, tick));
      for (let i = 0; i < SPEC.updatesPerTick; i++) {
        const cardEl = live.rows[(tick + i) % live.rows.length];
        const v = cardEl.shadowRoot.querySelectorAll(".value")[i % 6];
        v.textContent = stateText(states[(tick * 7 + i) % states.length]);
      }
    }, SPEC.updateEveryMs);
    if (live.clock) {
      let sec = 0;
      const big = live.clock.shadowRoot.querySelector(".big");
      setInterval(() => { sec++; big.textContent = `12:${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`; }, 1000);
    }
    const tileStates = live.tiles.slice(0, SPEC.frameTiles).map((t) => t.shadowRoot.querySelector(".state"));
    const g = live.canvas && live.canvas.getContext("2d");
    let k = 0;
    const per = Math.max(1, Math.ceil(SPEC.frameTiles / 10));
    const loop = (ts) => {
      for (let j = 0; j < (tileStates.length ? per : 0); j++) {
        const i = k++ % tileStates.length;
        tileStates[i].textContent = ((i * 37 + k * 0.37) % 100).toFixed(1) + " W";
      }
      if (g) {
        g.clearRect(0, 0, 600, 140);
        g.strokeStyle = "#03a9f4";
        g.lineWidth = 3;
        g.beginPath();
        for (let i = 0; i < 64; i++) {
          const x = (i / 63) * 600, y = 70 + 45 * Math.sin(ts / 500 + i / 5);
          if (i) g.lineTo(x, y); else g.moveTo(x, y);
        }
        g.stroke();
      }
      onFrame(ts);
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  }

  async function frames(state) {
    log({ t: "warmup", stage: TIER, n: 1 });
    await sleep(T.PHASES.warmupMs);
    log({ t: "measure", stage: TIER, n: 1 });
    const t0 = performance.now();
    state.stamps = [];
    state.measuring = true;
    await sleep(S.measureMs(VSYNC));
    state.measuring = false;
    const windowMs = performance.now() - t0;
    log({ t: "step", stage: TIER, n: 1, window_ms: Math.round(windowMs), raf: S.frameStats(S.intervals(state.stamps), VSYNC) });
  }

  // Tap-to-paint on the "Living room" toggle while the tier stays live. Event Timing gives input
  // delay + processing + presentation per interaction (what INP measures).
  async function input() {
    const target = live.toggle;
    const tile = target.shadowRoot.querySelector(".tile");
    const durations = new Map();
    let po = null;
    const hasET = typeof PerformanceEventTiming !== "undefined";
    const take = (list) => {
      for (const e of list) if (e.interactionId) durations.set(e.interactionId, Math.max(durations.get(e.interactionId) || 0, e.duration));
    };
    if (hasET) {
      po = new PerformanceObserver((l) => take(l.getEntries()));
      po.observe({ type: "event", durationThreshold: 16, buffered: true });
    }
    const approx = [];
    let taps = 0;
    target.addEventListener("pointerdown", (ev) => {
      taps++;
      const on = tile.classList.toggle("on");
      tile.querySelector(".state").textContent = on ? "on" : "off";
      for (const v of live.rows[0].shadowRoot.querySelectorAll(".value")) v.textContent = on ? "on" : "off";
      const t0 = ev.timeStamp;
      requestAnimationFrame(() => requestAnimationFrame((t2) => approx.push(t2 - t0)));
    });
    const r = target.getBoundingClientRect();
    log({ t: "input-ready", taps: TAPS, x: Math.round((r.left + r.width / 2) * devicePixelRatio), y: Math.round((r.top + r.height / 2) * devicePixelRatio) });
    const end = performance.now() + Math.max(T.PHASES.tapWaitMinMs, TAPS * 2000 + 5000);
    while (taps < TAPS && performance.now() < end) await sleep(100);
    await sleep(500);
    if (po) { take(po.takeRecords()); po.disconnect(); }
    // Event Timing skips interactions under its 16 ms threshold; count those as 16 ms (an upper bound).
    const values = hasET ? Array.from(durations.values()) : approx.slice();
    if (hasET) while (values.length < taps) values.push(16);
    values.sort((a, b) => a - b);
    log({ t: "input", stage: TIER, method: hasET ? "event-timing" : "raf2", taps, values: values.map((v) => Math.round(v)),
          inp_ms: values.length ? Math.round(values[values.length - 1]) : null });
  }

  async function main() {
    await reportLoad();
    await sleep(T.PHASES.stillAfterLoadMs);
    const state = { measuring: false, stamps: [] };
    goLive((ts) => { if (state.measuring) state.stamps.push(ts); });
    await frames(state);
    if (TAPS) await input();
    log({ t: "done", stage: TIER });
  }

  addEventListener("DOMContentLoaded", render);
  addEventListener("load", () => { main().catch((e) => log({ t: "error", message: String(e && e.stack || e) })); });
})();
