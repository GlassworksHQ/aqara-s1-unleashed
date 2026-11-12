/* Glassworks reference dashboards (MIT): the three fixed, seeded, Home Assistant-like pages the grade
 * is measured on (?tier=light|typical|heavy; tiers.js defines them). No network. Built from HA's own
 * card types and laid out the way HA's masonry view does (docs/perf.md, "The reference dashboards").
 *
 * Phases, one log line each: render still -> `refdash` (load times, on the page's clock) -> stay still
 * -> go live (state updates and a ticking clock) -> `warmup` -> `measure` (with the live check) ->
 * `step` (frames during the updates) -> `input-ready` / `input` (taps, during the updates) -> for each
 * swipe: `swipe-ready` -> `measure` -> `step` (frames while it scrolled) -> `done`.
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
  const SWIPES = q.has("swipes") ? Math.max(0, Number(q.get("swipes"))) : T.PHASES.swipes;
  const SEED = 89;

  // Simple icons drawn for this page (not copied from an icon set): name -> SVG path, 24x24.
  const ICONS = {
    bulb: "M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2zM9 19h6v2H9z",
    plug: "M7 2h2v5h6V2h2v5h1v5a6 6 0 0 1-5 5.9V22h-2v-4.1A6 6 0 0 1 6 12V7h1z",
    therm: "M10 3a2 2 0 0 1 4 0v10.3a4 4 0 1 1-4 0zM11 6v8h2V6z",
    door: "M5 2h14v20H5zM15 11h2v2h-2z",
    fan: "M12 11a1 1 0 1 1 0 2 1 1 0 0 1 0-2zM12 2c3 0 4 3 2 6l-2 2zM22 12c0 3-3 4-6 2l-2-2zM12 22c-3 0-4-3-2-6l2-2zM2 12c0-3 3-4 6-2l2 2z",
    tv: "M2 5h20v13H2zM8 20h8v2H8z",
  };
  const ICON_FOR = { sensor: "therm", light: "bulb", switch: "plug", binary_sensor: "door", climate: "fan", media_player: "tv" };

  // HA renders an icon as ha-state-icon > ha-svg-icon > svg, each a custom element with its own
  // shadow root; the same nesting here, coloured through `color` as HA does.
  class GwSvgIcon extends HTMLElement {
    connectedCallback() {
      if (this.shadowRoot) return;
      this.attachShadow({ mode: "open" }).innerHTML =
        `<style>:host{display:inline-flex;width:24px;height:24px}svg{fill:currentColor}</style>` +
        `<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="${ICONS[this.getAttribute("icon")]}"/></svg>`;
    }
  }
  class GwStateIcon extends HTMLElement {
    connectedCallback() {
      if (this.shadowRoot) return;
      this.attachShadow({ mode: "open" }).innerHTML =
        `<style>:host{display:inline-flex}</style><gw-svg-icon icon="${this.getAttribute("icon")}"></gw-svg-icon>`;
    }
  }
  customElements.define("gw-svg-icon", GwSvgIcon);
  customElements.define("gw-state-icon", GwStateIcon);

  function icon(name) {
    return `<gw-state-icon icon="${name}"></gw-state-icon>`;
  }

  // The 180 ms transitions are HA's: ha-tile-icon (colour, background, opacity) and the tile's
  // background (hui-tile-card).
  const CARD_CSS = `
    :host { display: block; background: var(--card); border-radius: 12px; box-shadow: 0 2px 4px rgba(0,0,0,.35); overflow: hidden; }
    h1 { margin: 0; padding: 16px 16px 4px; font-size: 18px; font-weight: 400; }
    .states { padding: 0 16px 12px; }
    .row { display: flex; align-items: center; height: 40px; }
    .badge { flex: none; width: 40px; height: 40px; display: grid; place-items: center; }
    .badge gw-state-icon { color: var(--muted); }
    .info { flex: 1; min-width: 0; margin-left: 16px; }
    .info .primary { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .info .secondary { color: var(--muted); font-size: 12px; }
    .value { color: var(--text); font-variant-numeric: tabular-nums; }
    .tile { position: relative; display: flex; align-items: center; gap: 10px; padding: 10px; }
    .tile .background { position: absolute; inset: 0; background: transparent; transition: background-color 180ms ease-in-out; }
    .tile.on .background { background: rgba(255,193,7,.08); }
    .tile .icon { position: relative; width: 36px; height: 36px; border-radius: 18px; display: grid; place-items: center; }
    .tile .icon .shape { position: absolute; inset: 0; border-radius: 18px; background: var(--muted);
      opacity: .2; transition: background-color 180ms ease-in-out, opacity 180ms ease-in-out; }
    .tile.on .icon .shape { background: var(--on); opacity: .3; }
    .tile .icon gw-state-icon { position: relative; color: var(--muted); transition: color 180ms ease-in-out; }
    .tile.on .icon gw-state-icon { color: var(--on); }
    .tile .info { margin-left: 0; position: relative; }
    .sensor { padding: 16px 16px 0; }
    .sensor .name { color: var(--muted); }
    .sensor .big { font-size: 28px; font-variant-numeric: tabular-nums; }
    svg.graph { display: block; width: 100%; height: 80px; }
    canvas.history { display: block; width: 100%; height: 180px; }
    img.picture { display: block; width: 100%; aspect-ratio: 16 / 9; }
    .clock { padding: 18px 16px; font-size: 40px; font-weight: 300; text-align: center; font-variant-numeric: tabular-nums; }
  `;

  class GwCard extends HTMLElement {
    set config(html) {
      const root = this.attachShadow({ mode: "open" });
      root.innerHTML = `<style>${CARD_CSS}</style>${html}`;
    }
  }
  customElements.define("gw-card", GwCard);

  function card(html, size) {
    const el = document.createElement("gw-card");
    el.config = html;
    el.cardSize = size;
    return el;
  }

  function stateText(s) {
    const unit = s.entity_id.startsWith("sensor.") ? s.attributes.unit_of_measurement : "";
    return unit ? `${s.state} ${unit}` : s.state;
  }

  // hui-tile-card: ha-card > background + container > ha-tile-icon (shape + icon) + ha-tile-info.
  function tileCard(s) {
    const d = s.entity_id.split(".")[0];
    return card(`<div class="tile${s.state === "on" ? " on" : ""}"><div class="background"></div>
      <div class="icon"><div class="shape"></div>${icon(ICON_FOR[d] || "plug")}</div>
      <div class="info"><div class="primary">${s.attributes.friendly_name}</div><div class="secondary value">${stateText(s)}</div></div></div>`, 1);
  }

  // hui-entities-card: ha-card > header + #states > rows (state-badge, info with secondary, state).
  function entitiesCard(title, states) {
    const rows = states.map((s) => `<div class="row"><div class="badge">${icon(ICON_FOR[s.entity_id.split(".")[0]] || "plug")}</div>
      <div class="info"><div class="primary">${s.attributes.friendly_name}</div><div class="secondary">${s.last_changed.slice(11, 16)}</div></div>
      <div class="value">${stateText(s)}</div></div>`).join("");
    return card(`<h1>${title}</h1><div class="states">${rows}</div>`, states.length + 1);
  }

  // hui-sensor-card's graph is hui-graph-base: an SVG with a filled area and a line path.
  function sensorCard(s, rnd) {
    const pts = [];
    for (let i = 0; i <= 48; i++) pts.push([(i * 500) / 48, 20 + 60 * rnd()]);
    const line = "M" + pts.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" L");
    return card(`<div class="sensor"><div class="name">${s.attributes.friendly_name}</div><div class="big">${stateText(s)}</div></div>
      <svg class="graph" viewBox="0 0 500 100" preserveAspectRatio="none">
        <path d="${line} L500,100 L0,100 z" fill="var(--accent)" fill-opacity=".15"/>
        <path d="${line}" fill="none" stroke="var(--accent)" stroke-width="3"/></svg>`, 2);
  }

  // hui-history-graph-card draws with ECharts on a canvas; drawn once here, as a settled chart.
  function historyCard(title, rnd) {
    const el = card(`<h1>${title}</h1><canvas class="history" width="600" height="360"></canvas>`, 4);
    const g = el.shadowRoot.querySelector("canvas").getContext("2d");
    g.strokeStyle = "rgba(255,255,255,.08)";
    for (let y = 40; y < 360; y += 64) { g.beginPath(); g.moveTo(0, y); g.lineTo(600, y); g.stroke(); }
    for (const colour of ["#03a9f4", "#ffc107"]) {
      g.strokeStyle = colour;
      g.lineWidth = 3;
      g.beginPath();
      for (let i = 0; i <= 96; i++) {
        const x = (i / 96) * 600, y = 60 + 240 * rnd();
        if (i) g.lineTo(x, y); else g.moveTo(x, y);
      }
      g.stroke();
    }
    return el;
  }

  // hui-picture-card: ha-card > hui-image > img. The images are generated here (no network), as JPEGs
  // the engine has to decode, like a camera snapshot.
  function picture(rnd) {
    const c = document.createElement("canvas");
    c.width = 480;
    c.height = 270;
    const g = c.getContext("2d");
    const grad = g.createLinearGradient(0, 0, 480, 270);
    grad.addColorStop(0, `hsl(${Math.floor(rnd() * 360)} 45% 35%)`);
    grad.addColorStop(1, `hsl(${Math.floor(rnd() * 360)} 45% 15%)`);
    g.fillStyle = grad;
    g.fillRect(0, 0, 480, 270);
    for (let i = 0; i < 40; i++) {
      g.fillStyle = `hsla(${Math.floor(rnd() * 360)} 60% 60% / .35)`;
      g.beginPath();
      g.arc(rnd() * 480, rnd() * 270, 8 + rnd() * 40, 0, 6.2832);
      g.fill();
    }
    return c.toDataURL("image/jpeg", 0.85);
  }

  function pictureCard(rnd) {
    return card(`<img class="picture" alt="" src="${picture(rnd)}">`, 3);
  }

  const live = { targets: [], clock: null, toggle: null };

  // HA's masonry view: one column per matched breakpoint (300, 600, 900, 1200 px); each card goes to
  // the first column whose size is under 5, else to the smallest (hui-masonry-view.ts).
  function render() {
    const rnd = W.mulberry32(SEED);
    const states = JSON.parse(W.makeStates(SEED, 400, 0));
    const view = document.getElementById("view");
    const n = Math.max(1, [300, 600, 900, 1200].filter((w) => innerWidth >= w).length);
    const cols = [], sizes = [];
    for (let i = 0; i < n; i++) { const c = document.createElement("div"); c.className = "col"; view.appendChild(c); cols.push(c); sizes.push(0); }
    const put = (el) => {
      let i = sizes.findIndex((s) => s < 5);
      if (i < 0) i = sizes.indexOf(Math.min(...sizes));
      cols[i].appendChild(el);
      sizes[i] += el.cardSize;
      return el;
    };

    // The cards in a fixed, seeded order: clock and toggle first, then the rest shuffled.
    const deck = [];
    let e = 0;
    const next = (k) => { const out = states.slice(e, e + k); e += k; return out; };
    for (let i = 1; i < SPEC.tiles; i++) deck.push(() => tileCard(next(1)[0]));
    for (let i = 0; i < SPEC.entities; i++) deck.push(() => entitiesCard(`Room ${i + 1}`, next(SPEC.rows)));
    for (let i = 0; i < SPEC.longLists; i++) deck.push(() => entitiesCard(`All lights ${i + 1}`, next(SPEC.longRows)));
    for (let i = 0; i < SPEC.sensors; i++) deck.push(() => sensorCard(next(1)[0], rnd));
    for (let i = 0; i < SPEC.histories; i++) deck.push(() => historyCard(`History ${i + 1}`, rnd));
    for (let i = 0; i < SPEC.pictures; i++) deck.push(() => pictureCard(rnd));
    for (let i = deck.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [deck[i], deck[j]] = [deck[j], deck[i]]; }

    live.clock = put(card(`<div class="clock">12:00:00</div>`, 2));
    live.toggle = put(tileCard({ entity_id: "light.living_room", state: "off", attributes: { friendly_name: "Living room" } }));
    for (const make of deck) put(make());
  }

  // Elements in the document and in every shadow root under it.
  function nodeCount(root = document) {
    let n = 0;
    for (const el of root.querySelectorAll("*")) {
      n++;
      if (el.shadowRoot) n += nodeCount(el.shadowRoot);
    }
    return n;
  }

  function inView(el) {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.top >= 0 && r.left >= 0 && r.bottom <= innerHeight && r.right <= innerWidth;
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
      css: [innerWidth, innerHeight], dpr: devicePixelRatio, scroll_height: document.documentElement.scrollHeight,
      fcp_ms: fcp ? Math.round(fcp.startTime) : null, lcp_ms: lcp === null ? null : Math.round(lcp),
      load_ms: nav.loadEventEnd ? Math.round(nav.loadEventEnd) : null,
      // performance times are relative to timeOrigin (epoch ms); the runner maps them to logcat time.
      origin_epoch_ms: Math.round(performance.timeOrigin), epoch_ms: Date.now(),
    });
  }

  // The live updates: `updatesPerS` state changes a second over the tiles and rows on screen (seeded
  // order), and the clock. Every run shows the same sequence.
  function goLive() {
    for (const c of document.querySelectorAll("gw-card")) {
      if (c === live.toggle || c === live.clock || !inView(c)) continue;
      for (const v of c.shadowRoot.querySelectorAll(".value")) live.targets.push({ card: c, v });
    }
    let tick = 0;
    setInterval(() => {
      tick++;
      if (!live.targets.length) return;
      const t = live.targets[(tick * 7) % live.targets.length];
      const states = JSON.parse(W.makeStates(SEED, 400, tick));
      t.v.textContent = stateText(states[(tick * 13) % states.length]);
      const tile = t.card.shadowRoot.querySelector(".tile");
      if (tile) tile.classList.toggle("on", tick % 2 === 0);
    }, Math.round(1000 / T.LIVE.updatesPerS));
    let sec = 0;
    const clock = live.clock.shadowRoot.querySelector(".clock");
    setInterval(() => { sec++; clock.textContent = `12:${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`; },
      T.LIVE.clockEveryMs);
  }

  const frameStamps = { on: false, list: [] };
  requestAnimationFrame(function loop(ts) { if (frameStamps.on) frameStamps.list.push(ts); requestAnimationFrame(loop); });

  // Frames while the updates run. The screen is still between changes, so only rAF (the main thread
  // keeping time) is graded here; the live check says whether the changes were on screen.
  async function updatesWindow() {
    log({ t: "warmup", stage: "updates", n: 1 });
    await sleep(T.PHASES.warmupMs);
    const watched = [live.toggle, live.clock].concat(live.targets.map((t) => t.card));
    log({ t: "measure", stage: "updates", n: 1, moving: watched.length, offscreen: watched.filter((el) => !inView(el)).length,
          targets: live.targets.length, animations: 0, animations_expected: 0 });
    const t0 = performance.now();
    frameStamps.list = [];
    frameStamps.on = true;
    await sleep(S.measureMs(VSYNC));
    frameStamps.on = false;
    log({ t: "step", stage: "updates", n: 1, window_ms: Math.round(performance.now() - t0),
          raf: S.frameStats(S.intervals(frameStamps.list), VSYNC) });
  }

  // Tap-to-paint on the toggle while the updates run. Event Timing gives input delay + processing +
  // presentation per interaction (what INP measures).
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
      tile.querySelector(".value").textContent = on ? "on" : "off";
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

  // Real touch scrolling: the runner swipes (`input swipe`) where the page asks. A swipe's window runs
  // from its first scroll event to its last (the fling included), capped at the SurfaceFlinger-sized
  // window. It logs the frames, how far the page moved, and the time from touch to the first scroll.
  let up = false;
  async function swipe(i) {
    const max = document.documentElement.scrollHeight - innerHeight;
    if (scrollY >= max - 4) up = true;           // at the bottom: swipe back up
    else if (scrollY <= 4) up = false;           // at the top: swipe down the page
    const x = Math.round((innerWidth / 2) * devicePixelRatio);
    const lo = Math.round(innerHeight * 0.85 * devicePixelRatio), hi = Math.round(innerHeight * 0.15 * devicePixelRatio);
    const y0 = scrollY;
    let downAt = null, first = null, last = null;
    const onDown = (ev) => { if (downAt === null) downAt = ev.timeStamp; };
    const onScroll = (ev) => {
      if (first === null) {
        first = ev.timeStamp;
        log({ t: "measure", stage: "scroll", n: i });
        frameStamps.list = [];
        frameStamps.on = true;
      }
      last = ev.timeStamp;
    };
    addEventListener("pointerdown", onDown, true);
    addEventListener("scroll", onScroll, { passive: true });
    // Finger from near the bottom to near the top scrolls down the page; the reverse scrolls up.
    log({ t: "swipe-ready", n: i, x, y1: up ? hi : lo, y2: up ? lo : hi, ms: T.PHASES.swipeMs });
    const cap = S.measureMs(VSYNC);
    const end = performance.now() + T.PHASES.swipeWaitMs;
    while (performance.now() < end) {
      await sleep(50);
      if (first !== null && (performance.now() - last > T.PHASES.scrollQuietMs || performance.now() - first > cap)) break;
    }
    frameStamps.on = false;
    removeEventListener("pointerdown", onDown, true);
    removeEventListener("scroll", onScroll);
    if (first === null) {
      log({ t: "step", stage: "scroll", n: i, window_ms: 0, scrolled_px: 0, raf: { n: 0 } });
      return;
    }
    const windowMs = Math.min(cap, last - first + VSYNC);
    const stamps = frameStamps.list.filter((t) => t <= first + windowMs);
    log({ t: "step", stage: "scroll", n: i, window_ms: Math.round(windowMs), scrolled_px: Math.round(Math.abs(scrollY - y0)),
          first_scroll_ms: downAt === null ? null : Math.round(first - downAt), raf: S.frameStats(S.intervals(stamps), VSYNC),
          // raw intervals, so the runner can pool all of a run's swipes into one set of frames
          raf_intervals: S.intervals(stamps).map((x) => Math.round(x * 10) / 10) });
    await sleep(300);
  }

  async function main() {
    await reportLoad();
    const loaded = (performance.getEntriesByType("navigation")[0] || {}).loadEventEnd || performance.now();
    await sleep(Math.max(0, loaded + T.PHASES.stillAfterLoadEventMs - performance.now()));
    goLive();
    await updatesWindow();
    if (TAPS) await input();
    for (let i = 0; i < SWIPES; i++) await swipe(i);
    log({ t: "done", stage: TIER });
  }

  addEventListener("DOMContentLoaded", render);
  addEventListener("load", () => { main().catch((e) => log({ t: "error", message: String(e && e.stack || e) })); });
})();
