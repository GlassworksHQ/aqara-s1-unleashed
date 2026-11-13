/* Clarity's harness (MIT): measures Home Assistant's real frontend on a reference dashboard.
 *
 * The fake Home Assistant (tools/perf/fakeha.py) serves HA's own index.html with this script first,
 * after stats.js and the page config (window.GW_CLARITY, from page/tiers.json). It changes nothing
 * HA does, except the room to flick below the cards (see "Room to flick" in docs/perf.md). It logs
 * one line per event, as protocol 3's pages did, so tools/perf/perfrun.py and report.py read it:
 *
 *   refdash (load times, usable) -> warmup -> measure (live check) -> step (live updates) ->
 *   input-ready / input (taps) -> per swipe: swipe-ready -> measure -> step -> done
 *
 * Without a nonce in the URL (the kiosk's own page after a restart), it logs only refdash and done:
 * that is the restart's end.
 */
(function () {
  "use strict";
  const S = window.GWStats, C = window.GW_CLARITY;
  const q = new URLSearchParams(location.search);
  const NONCE = q.get("nonce") || "";
  const RUN = Number(q.get("run") || 0);
  const TAPS = Math.max(0, Number(q.get("taps") || 0));
  const SWIPES = q.has("swipes") ? Math.max(0, Number(q.get("swipes"))) : C.PHASES.swipes;
  const VSYNC = Number(q.get("vsync") || 1000 / 60);
  const TIER = ((location.pathname.match(/^\/clarity-([a-z]+)/) || [])[1]) || "light";
  const GRADED = !!NONCE;

  let logSeq = 0;
  function log(obj) {
    logSeq++;
    for (const l of S.consoleLines("GWPERF", `${NONCE || "restart"}:${logSeq}`,
      JSON.stringify(Object.assign({ run: RUN, nonce: NONCE }, obj)))) {
      console.log(l);
    }
  }
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const r1 = (x) => Math.round(x * 10) / 10;
  const sr = (e) => e && e.shadowRoot;

  // ---- the websocket: see each state change arrive, before HA's own listener runs

  const live = { watch: new Map(), changes: [], on: false, applied: 0, seen: new Set() };
  const Native = window.WebSocket;
  function Clarity(url, protocols) {
    const ws = protocols === undefined ? new Native(url) : new Native(url, protocols);
    ws.addEventListener("message", onMessage);       // registered first, so it runs before HA's
    return ws;
  }
  Clarity.prototype = Native.prototype;
  for (const k of ["CONNECTING", "OPEN", "CLOSING", "CLOSED"]) Clarity[k] = Native[k];
  window.WebSocket = Clarity;

  function onMessage(ev) {
    if (!live.watch.size || typeof ev.data !== "string" || ev.data.indexOf('"c":{') < 0) return;
    const at = performance.now();
    let msgs;
    try { msgs = JSON.parse(ev.data); } catch (e) { return; }
    for (const m of Array.isArray(msgs) ? msgs : [msgs]) {
      const c = m && m.event && m.event.c;
      if (!c) continue;
      for (const id of Object.keys(c)) {
        const w = live.watch.get(id);
        if (!w) continue;
        // The frontend can hold two connections for a moment (a reconnect); the same change then
        // arrives twice. Its context id says it's the same change.
        const ctx = c[id] && c[id]["+"] && c[id]["+"].c;
        const key = id + "|" + (typeof ctx === "string" ? ctx : JSON.stringify(ctx));
        if (live.seen.has(key)) continue;
        live.seen.add(key);
        const onOff = !id.startsWith("sensor.");
        live.applied++;
        // [due, ran, transition ms, kind, tick]: due = ran = arrival; HA's tile fades 180 ms on an
        // on/off change (ha-tile-icon.ts); a sensor value changes text only.
        const change = { at, d: onOff ? C.LIVE.tileTransitionMs : 0, kind: onOff ? 2 : 0, dom: null, tick: null };
        w.pending.push(change);
        if (live.on) live.changes.push(change);
      }
    }
  }

  // ---- finding HA's cards (once, not every frame)

  function find(root, sel, depth) {
    if (!root || depth > 40) return null;
    const hit = root.querySelector(sel);
    if (hit) return hit;
    for (const el of root.querySelectorAll("*")) {
      if (el.shadowRoot) {
        const r = find(el.shadowRoot, sel, depth + 1);
        if (r) return r;
      }
    }
    return null;
  }

  let viewEl = null;
  function cards() {
    if (!viewEl || !viewEl.isConnected) viewEl = find(document, "hui-masonry-view", 0);
    return viewEl ? Array.from(sr(viewEl).querySelectorAll("hui-card")) : [];
  }

  function rendered(card) {
    const inner = card.firstElementChild;
    const ha = inner && sr(inner) && sr(inner).querySelector("ha-card");
    return !!(ha && ha.offsetHeight > 0);
  }

  function inView(el) {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.top >= 0 && r.left >= 0 && r.bottom <= innerHeight && r.right <= innerWidth;
  }

  function shadowRoots(el, out) {
    if (sr(el)) {
      out.push(sr(el));
      for (const c of sr(el).querySelectorAll("*")) shadowRoots(c, out);
    }
    for (const c of el.children) shadowRoots(c, out);
    return out;
  }

  // Watch the cards that show the head's entities: any DOM change in one after a state change for
  // it arrived is HA drawing that change.
  function watch(entityIds) {
    const byEntity = new Map();
    for (const card of cards()) {
      const cfg = card.config || {};
      const ids = cfg.entity ? [cfg.entity] : (cfg.entities || []).map((e) => (typeof e === "string" ? e : e.entity));
      for (const id of ids) if (entityIds.includes(id)) byEntity.set(id, card);
    }
    for (const [id, card] of byEntity) {
      const w = { card, pending: [] };
      const mo = new MutationObserver(() => {
        const now = performance.now();
        for (const c of w.pending) if (c.dom === null && c.at <= now) c.dom = now;
      });
      for (const root of shadowRoots(card, [])) mo.observe(root, { subtree: true, childList: true, characterData: true, attributes: true });
      live.watch.set(id, w);
    }
    return byEntity;
  }

  // ---- frames: rAF times, and the frame each change was drawn in

  const frames = { on: false, list: [] };
  requestAnimationFrame(function loop(ts) {
    if (frames.on) frames.list.push(ts);
    for (const w of live.watch.values()) {
      for (const c of w.pending) if (c.dom !== null && c.tick === null) c.tick = ts;
      w.pending = w.pending.filter((c) => c.tick === null);
    }
    requestAnimationFrame(loop);
  });

  // ---- usable: the first screen's cards rendered, and the main thread free

  async function usable() {
    let last = "", stable = 0, renderedTick = null;
    while (renderedTick === null) {
      const ts = await new Promise((r) => requestAnimationFrame(r));
      if (document.getElementById("ha-launch-screen")) continue;
      const first = cards().filter((c) => { const r = c.getBoundingClientRect(); return r.height > 0 && r.top < innerHeight && r.bottom > 0; });
      if (!first.length || !first.every(rendered)) { stable = 0; continue; }
      const key = first.length + ":" + first.map((c) => Math.round(c.getBoundingClientRect().top)).join(",");
      stable = key === last ? stable + 1 : 0;
      last = key;
      if (stable >= 1) renderedTick = { ts, first: first.length };
    }
    // Interactive: a task posted now runs within 50 ms (RAIL's input budget).
    let interactive = null;
    while (interactive === null) {
      const posted = performance.now();
      const ran = await new Promise((r) => setTimeout(() => r(performance.now()), 0));
      if (ran - posted <= 50) interactive = ran;
      else await sleep(10);
    }
    return { rendered_tick_ms: r1(renderedTick.ts), first_screen_cards: renderedTick.first, interactive_ms: r1(interactive) };
  }

  function nodeCount(root) {
    let n = 0;
    for (const el of root.querySelectorAll("*")) {
      n++;
      if (el.shadowRoot) n += nodeCount(el.shadowRoot);
    }
    return n;
  }

  let lcp = null;
  try {
    new PerformanceObserver((l) => { for (const e of l.getEntries()) lcp = e.renderTime || e.startTime; })
      .observe({ type: "largest-contentful-paint", buffered: true });
  } catch (e) { lcp = null; }

  async function reportLoad() {
    const u = await usable();
    const nav = performance.getEntriesByType("navigation")[0] || {};
    const fcp = performance.getEntriesByName("first-contentful-paint")[0];
    log({
      t: "refdash", tier: TIER, protocol: C.PROTOCOL, frontend: C.FRONTEND, nodes: nodeCount(document), cards: cards().length,
      css: [innerWidth, innerHeight], dpr: devicePixelRatio, scroll_height: document.documentElement.scrollHeight,
      fcp_ms: fcp ? Math.round(fcp.startTime) : null, lcp_ms: lcp === null ? null : Math.round(lcp),
      load_ms: nav.loadEventEnd ? Math.round(nav.loadEventEnd) : null,
      usable: u,
      origin_epoch_ms: Math.round(performance.timeOrigin), epoch_ms: Date.now(),
    });
  }

  // ---- room to flick: as protocol 3, every page leaves scrollRoomScreens screens below its top

  function roomToFlick() {
    const root = find(document, "hui-root", 0);
    if (!root || !sr(root)) return;
    const s = document.createElement("style");
    s.textContent = `#view { min-height: ${Math.ceil(innerHeight * (1 + C.PHASES.scrollRoomScreens))}px !important; }`;
    sr(root).appendChild(s);
  }

  // ---- live updates: the fake HA streams them while the page asks

  async function stream(on) {
    try { await fetch(`/clarity/${on ? "live" : "still"}?nonce=${encodeURIComponent(NONCE)}`, { cache: "no-store" }); } catch (e) { /* the fake is gone */ }
  }

  async function updatesWindow(head) {
    log({ t: "warmup", stage: "updates", n: 1 });
    live.changes = [];
    live.on = true;
    await stream(true);
    await sleep(C.PHASES.warmupMs);
    const watched = Array.from(new Set(Array.from(head.values())));
    log({ t: "measure", stage: "updates", n: 1, moving: watched.length, offscreen: watched.filter((el) => !inView(el)).length,
          targets: head.size, update_every_ms: Math.round(1000 / C.LIVE.updatesPerS), animations: 0, animations_expected: 0 });
    const t0 = performance.now();
    frames.list = [];
    frames.on = true;
    await sleep(S.measureMs(VSYNC));
    frames.on = false;
    await sleep(Math.max(0, t0 + C.PHASES.updatesWindowMs - performance.now()));
    live.on = false;
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r())));
    const t1 = t0 + C.PHASES.updatesWindowMs;
    const seen = live.changes.filter((c) => c.at <= t1 && c.at + c.d >= t0);
    log({ t: "step", stage: "updates", n: 1, window_ms: Math.round(t1 - t0), t0: r1(t0), pacing_ms: S.measureMs(VSYNC),
          raf: S.frameStats(S.intervals(frames.list), VSYNC), raf_times: frames.list.map((x) => r1(x - t0)),
          changes: seen.map((c) => [r1(c.at - t0), r1(c.at - t0), c.d, c.kind, c.tick === null ? null : r1(c.tick - t0)]) });
  }

  // ---- tap: HA's own toggle tile, Event Timing for INP

  async function input() {
    const toggle = cards().find((c) => (c.config || {}).entity === "light.living_room");
    if (!toggle) return log({ t: "input", stage: TIER, method: "none", taps: 0, values: [], inp_ms: null });
    const durations = new Map();
    const hasET = typeof PerformanceEventTiming !== "undefined";
    let po = null, taps = 0;
    if (hasET) {
      po = new PerformanceObserver((l) => {
        for (const e of l.getEntries()) if (e.interactionId) durations.set(e.interactionId, Math.max(durations.get(e.interactionId) || 0, e.duration));
      });
      po.observe({ type: "event", durationThreshold: 16, buffered: true });
    }
    const onDown = () => { taps++; };
    addEventListener("pointerdown", onDown, true);
    const r = toggle.getBoundingClientRect();
    log({ t: "input-ready", taps: TAPS, x: Math.round((r.left + r.width / 2) * devicePixelRatio), y: Math.round((r.top + r.height / 2) * devicePixelRatio) });
    const end = performance.now() + Math.max(C.PHASES.tapWaitMinMs, TAPS * 2000 + 5000);
    while (taps < TAPS && performance.now() < end) await sleep(100);
    await sleep(500);
    removeEventListener("pointerdown", onDown, true);
    if (po) po.disconnect();
    const values = Array.from(durations.values());
    if (hasET) while (values.length < taps) values.push(16);
    values.sort((a, b) => a - b);
    log({ t: "input", stage: TIER, method: hasET ? "event-timing" : "none", taps, values: values.map((v) => Math.round(v)),
          inp_ms: values.length ? Math.round(values[values.length - 1]) : null });
  }

  // ---- scroll: the same flick down from the top, during the live updates

  async function swipe(i) {
    scrollTo({ top: 0, behavior: "instant" });
    await sleep(C.PHASES.repositionStillMs);
    const max = document.documentElement.scrollHeight - innerHeight;
    const x = Math.round((innerWidth / 2) * devicePixelRatio);
    const lo = Math.round(innerHeight * 0.85 * devicePixelRatio), hi = Math.round(innerHeight * 0.15 * devicePixelRatio);
    const y0 = scrollY;
    let downAt = null, first = null, last = null, appliedAt = null;
    const onDown = (ev) => { if (downAt === null) downAt = ev.timeStamp; };
    const onScroll = (ev) => {
      if (first === null) {
        first = ev.timeStamp;
        appliedAt = live.applied;
        log({ t: "measure", stage: "scroll", n: i });
        frames.list = [];
        frames.on = true;
      }
      last = ev.timeStamp;
    };
    addEventListener("pointerdown", onDown, true);
    addEventListener("scroll", onScroll, { passive: true });
    log({ t: "swipe-ready", n: i, x, y1: lo, y2: hi, ms: C.PHASES.swipeMs });
    const cap = S.measureMs(VSYNC);
    const end = performance.now() + C.PHASES.swipeWaitMs;
    while (performance.now() < end) {
      await sleep(50);
      if (first !== null && (performance.now() - last > C.PHASES.scrollQuietMs || performance.now() - first > cap)) break;
    }
    frames.on = false;
    removeEventListener("pointerdown", onDown, true);
    removeEventListener("scroll", onScroll);
    if (first === null) {
      log({ t: "step", stage: "scroll", n: i, window_ms: 0, scrolled_px: 0, raf: { n: 0 } });
      return;
    }
    const windowMs = Math.min(cap, last - first + VSYNC);
    const stamps = frames.list.filter((t) => t <= first + windowMs);
    log({ t: "step", stage: "scroll", n: i, window_ms: Math.round(windowMs), scrolled_px: Math.round(Math.abs(scrollY - y0)),
          hit_edge: scrollY >= max - 1, changes_during: live.applied - appliedAt,
          first_scroll_ms: downAt === null ? null : Math.round(first - downAt), raf: S.frameStats(S.intervals(stamps), VSYNC),
          raf_intervals: S.intervals(stamps).map((x) => r1(x)) });
    await sleep(300);
  }

  async function main() {
    await new Promise((r) => (document.readyState === "complete" ? r() : addEventListener("load", r, { once: true })));
    await reportLoad();
    if (!GRADED) return log({ t: "done", stage: "restart" });
    const loaded = (performance.getEntriesByType("navigation")[0] || {}).loadEventEnd || performance.now();
    await sleep(Math.max(0, loaded + C.PHASES.stillAfterLoadEventMs - performance.now()));
    roomToFlick();
    const H = C.HEAD_ENTITIES;
    const head = watch(H.sensors.concat(H.rows, H.switches));
    await updatesWindow(head);
    if (TAPS) await input();
    for (let i = 0; i < SWIPES; i++) await swipe(i);
    await stream(false);
    log({ t: "done", stage: TIER });
  }

  main().catch((e) => log({ t: "error", message: String((e && e.stack) || e) }));
})();
