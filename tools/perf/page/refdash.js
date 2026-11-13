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

  // The 180 ms transitions are HA's: on a state change only the tile's icon fades, its shape's
  // background colour and opacity and the icon's colour (ha-tile-icon.ts L125-L143). The tile card's
  // own transitions (box-shadow, border-color: tile-card-style.ts L10-L15) change only on keyboard
  // focus, so nothing else fades.
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

  // hui-tile-card (L302-L386): ha-card > ha-tile-container > ha-tile-icon (shape + icon) + ha-tile-info.
  function tileCard(s) {
    const d = s.entity_id.split(".")[0];
    return card(`<div class="tile${s.state === "on" ? " on" : ""}">
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
    for (const colour of ["#38bdf8", "#a78bfa"]) {     // sky and violet (glassworks.dev/brand)
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

  // hui-picture-card (L268): ha-card > img. The images are generated here (no network), as JPEGs
  // the engine has to decode, like a camera snapshot.
  function picture(rnd) {
    const c = document.createElement("canvas");
    c.width = 480;
    c.height = 270;
    const g = c.getContext("2d");
    const grad = g.createLinearGradient(0, 0, 480, 270);
    grad.addColorStop(0, `hsl(${S.skyToViolet(rnd())} 45% 35%)`);
    grad.addColorStop(1, `hsl(${S.skyToViolet(rnd())} 45% 15%)`);
    g.fillStyle = grad;
    g.fillRect(0, 0, 480, 270);
    for (let i = 0; i < 40; i++) {
      g.fillStyle = `hsla(${S.skyToViolet(rnd())} 60% 60% / .35)`;
      g.beginPath();
      g.arc(rnd() * 480, rnd() * 270, 8 + rnd() * 40, 0, 6.2832);
      g.fill();
    }
    return c.toDataURL("image/jpeg", 0.85);
  }

  function pictureCard(rnd) {
    return card(`<img class="picture" alt="" src="${picture(rnd)}">`, 3);
  }

  const live = { targets: [], clock: null, toggle: null, head: [], sensors: [], switches: [], timers: [] };

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

    // The cards in a fixed, seeded order: the head first (clock, toggle, tiles and one entities card;
    // the same on every page, and the only cards that change while live), then the rest shuffled.
    const deck = [];
    const used = new Set();
    // The head takes its entities by domain; the rest of the page takes the others in order.
    const take = (domains) => {
      const i = states.findIndex((s, j) => !used.has(j) && domains.includes(s.entity_id.split(".")[0]));
      used.add(i);
      return states[i];
    };
    let e = 0;
    const next = (k) => {
      const out = [];
      while (out.length < k) { if (!used.has(e)) { used.add(e); out.push(states[e]); } e++; }
      return out;
    };
    for (let i = 1 + T.HEAD.tiles; i < SPEC.tiles; i++) deck.push(() => tileCard(next(1)[0]));
    for (let i = 1; i < SPEC.entities; i++) deck.push(() => entitiesCard(`Room ${i + 1}`, next(SPEC.rows)));
    for (let i = 0; i < SPEC.longLists; i++) deck.push(() => entitiesCard(`All lights ${i + 1}`, next(SPEC.longRows)));
    for (let i = 0; i < SPEC.sensors; i++) deck.push(() => sensorCard(next(1)[0], rnd));
    for (let i = 0; i < SPEC.histories; i++) deck.push(() => historyCard(`History ${i + 1}`, rnd));
    for (let i = 0; i < SPEC.pictures; i++) deck.push(() => pictureCard(rnd));
    for (let i = deck.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [deck[i], deck[j]] = [deck[j], deck[i]]; }

    live.clock = put(card(`<div class="clock">12:00:00</div>`, 2));
    live.toggle = put(tileCard({ entity_id: "light.living_room", state: "off", attributes: { friendly_name: "Living room" } }));
    // Sensor and on/off tiles in turn, then the sensor rows.
    const kinds = [];
    for (let i = 0; i < T.HEAD.tiles; i++) kinds.push(i % 2 === 1 && kinds.filter((k) => k).length < T.HEAD.switchTiles);
    while (kinds.filter((k) => k).length < T.HEAD.switchTiles) kinds[kinds.lastIndexOf(false)] = true;
    for (const onOff of kinds) {
      const s = take(onOff ? ["light", "switch", "media_player"] : ["sensor"]);
      const el = put(tileCard(s));
      el.entity = s;
      (onOff ? live.switches : live.sensors).push(el);
      live.head.push(el);
    }
    const rows = Array.from({ length: T.HEAD.rows }, () => take(["sensor"]));
    const energy = put(entitiesCard("Energy", rows));
    energy.rows = rows;
    live.head.push(energy);
    for (const make of deck) put(make());
    // Room to flick: at least scrollRoomScreens screens of scrolling below the top on every page.
    view.style.minHeight = `${Math.ceil(innerHeight * (1 + T.PHASES.scrollRoomScreens))}px`;
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

  let logSeq = 0;
  function log(obj) {
    // Split when long: logcat keeps only 1023 bytes of a console line (stats.js, consoleLines).
    logSeq++;
    for (const l of S.consoleLines("GWPERF", `${NONCE}:${logSeq}`, JSON.stringify(Object.assign({ run: RUN, nonce: NONCE }, obj)))) {
      console.log(l);
    }
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
      t: "refdash", tier: TIER, protocol: T.PROTOCOL, nodes: nodeCount(), cards: document.querySelectorAll("gw-card").length,
      css: [innerWidth, innerHeight], dpr: devicePixelRatio, scroll_height: document.documentElement.scrollHeight,
      fcp_ms: fcp ? Math.round(fcp.startTime) : null, lcp_ms: lcp === null ? null : Math.round(lcp),
      load_ms: nav.loadEventEnd ? Math.round(nav.loadEventEnd) : null,
      // performance times are relative to timeOrigin (epoch ms); the runner maps them to logcat time.
      origin_epoch_ms: Math.round(performance.timeOrigin), epoch_ms: Date.now(),
    });
  }

  // The live updates: `updatesPerS` state changes a second over the head's tiles and rows (seeded
  // order), and the clock. Every page and every run shows the same sequence. Each change is noted
  // with the time it was due and the time it ran (a busy main thread runs it late), and how long its
  // transition lasts, so the frames that had a change to draw can be told from idle ones.
  // Each change: [due, ran, transition ms, kind, tick]. kind: 0 a sensor value, 1 the clock, 2 an
  // on/off change. tick is the next animation frame's time: the frame the change is drawn in. The
  // runner times from ran to the first frame on screen that can show that frame (report.py).
  const changes = { on: false, list: [], pending: [], applied: 0 };
  function note(due, d, kind) {
    if (kind !== 1) changes.applied++;        // state changes only: the clock doesn't count for "during updates"
    if (!changes.on) return;
    const c = [due, performance.now(), d, kind, null];
    changes.list.push(c);
    changes.pending.push(c);
  }

  // Timers aimed at fixed times, so a late change doesn't push the next ones back (no drift).
  function every(period, fn) {
    const start = performance.now();
    let k = 0;
    const step = () => {
      k++;
      fn(k, start + k * period);
      live.timers.push(setTimeout(step, Math.max(0, start + (k + 1) * period - performance.now())));
    };
    live.timers.push(setTimeout(step, period));
  }

  // Like Home Assistant (docs/perf.md, "Live updates"): a sensor's new value changes its text only
  // (its tile colour doesn't depend on the value: state_active.ts); an on/off change flips the tile's
  // colour with HA's 180 ms transitions (ha-tile-icon.ts). Only the changed entity's card re-renders
  // (has-changed.ts).
  function goLive() {
    for (const c of live.sensors) live.targets.push({ v: c.shadowRoot.querySelector(".value"), s: c.entity });
    const rowValues = live.head[live.head.length - 1].shadowRoot.querySelectorAll(".value");
    live.head[live.head.length - 1].rows.forEach((s, i) => live.targets.push({ v: rowValues[i], s }));
    every(Math.round(1000 / T.LIVE.updatesPerS), (tick, due) => {
      const t = live.targets[(tick * 7) % live.targets.length];
      const v = parseFloat(t.s.state) + (((tick * 13) % 7) - 3) / 10;
      t.s = Object.assign({}, t.s, { state: v.toFixed(1) });
      t.v.textContent = stateText(t.s);
      note(due, 0, 0);
    });
    every(T.LIVE.flipEveryMs, (k, due) => {
      const c = live.switches[k % live.switches.length];
      const tile = c.shadowRoot.querySelector(".tile");
      const on = tile.classList.toggle("on");
      tile.querySelector(".value").textContent = on ? "on" : "off";
      note(due, T.LIVE.tileTransitionMs, 2);
    });
    const clock = live.clock.shadowRoot.querySelector(".clock");
    every(T.LIVE.clockEveryMs, (sec, due) => {
      clock.textContent = `12:${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`;
      note(due, 0, 1);
    });
  }

  // Before the next page opens, this one goes still: no timers left running into its teardown.
  function stopLive() {
    for (const t of live.timers) clearTimeout(t);
    live.timers.length = 0;
  }

  const frameStamps = { on: false, list: [] };
  requestAnimationFrame(function loop(ts) {
    if (frameStamps.on) frameStamps.list.push(ts);
    for (const c of changes.pending) c[4] = ts;
    changes.pending.length = 0;
    requestAnimationFrame(loop);
  });
  const r1 = (x) => Math.round(x * 10) / 10;

  // Frames while the updates run. The screen is still between changes, so SurfaceFlinger has nothing
  // steady to count; the runner grades the rAF frames that had a change to draw (report.py,
  // change_frames). The live check says whether the changes were on screen.
  async function updatesWindow() {
    log({ t: "warmup", stage: "updates", n: 1 });
    changes.list = [];
    changes.on = true;
    await sleep(T.PHASES.warmupMs);
    const watched = [live.toggle, live.clock].concat(live.head);
    log({ t: "measure", stage: "updates", n: 1, moving: watched.length, offscreen: watched.filter((el) => !inView(el)).length,
          targets: live.targets.length, update_every_ms: Math.round(1000 / T.LIVE.updatesPerS),
          animations: 0, animations_expected: 0 });
    const t0 = performance.now();
    frameStamps.list = [];
    frameStamps.on = true;
    // Frame pacing (detail): the first SurfaceFlinger-sized stretch. Change-to-screen: the whole window.
    await sleep(S.measureMs(VSYNC));
    frameStamps.on = false;
    await sleep(Math.max(0, t0 + T.PHASES.updatesWindowMs - performance.now()));
    changes.on = false;
    await new Promise((r) => requestAnimationFrame(() => r()));   // the last changes get their frame
    const t1 = t0 + T.PHASES.updatesWindowMs;
    // Times are ms after t0 (itself ms after performance.timeOrigin). A change from before t0 whose
    // transition runs into the window is kept.
    const seen = changes.list.filter((c) => c[0] <= t1 && c[1] + c[2] >= t0);
    log({ t: "step", stage: "updates", n: 1, window_ms: Math.round(t1 - t0), t0: r1(t0),
          pacing_ms: S.measureMs(VSYNC),
          raf: S.frameStats(S.intervals(frameStamps.list), VSYNC),
          raf_times: frameStamps.list.map((x) => r1(x - t0)),
          changes: seen.map((c) => [r1(c[0] - t0), r1(c[1] - t0), c[2], c[3], c[4] === null ? null : r1(c[4] - t0)]) });
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
  async function swipe(i) {
    // Every swipe is the same flick down the page from its top, with room below it.
    scrollTo({ top: 0, behavior: "instant" });
    await sleep(T.PHASES.repositionStillMs);
    const max = document.documentElement.scrollHeight - innerHeight;
    const x = Math.round((innerWidth / 2) * devicePixelRatio);
    const lo = Math.round(innerHeight * 0.85 * devicePixelRatio), hi = Math.round(innerHeight * 0.15 * devicePixelRatio);
    const y0 = scrollY;
    let downAt = null, first = null, last = null;
    const onDown = (ev) => { if (downAt === null) downAt = ev.timeStamp; };
    let appliedAt = null;
    const onScroll = (ev) => {
      if (first === null) {
        first = ev.timeStamp;
        appliedAt = changes.applied;
        log({ t: "measure", stage: "scroll", n: i });
        frameStamps.list = [];
        frameStamps.on = true;
      }
      last = ev.timeStamp;
    };
    addEventListener("pointerdown", onDown, true);
    addEventListener("scroll", onScroll, { passive: true });
    // Finger from near the bottom to near the top scrolls down the page.
    log({ t: "swipe-ready", n: i, x, y1: lo, y2: hi, ms: T.PHASES.swipeMs });
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
          hit_edge: scrollY >= max - 1,
          // The live updates keep running while it scrolls: how many state changes (sensor values and
          // on/off changes, not the clock) ran during this swipe.
          changes_during: changes.applied - appliedAt,
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
    stopLive();
    log({ t: "done", stage: TIER });
  }

  addEventListener("DOMContentLoaded", render);
  addEventListener("load", () => { main().catch((e) => log({ t: "error", message: String(e && e.stack || e) })); });
})();
