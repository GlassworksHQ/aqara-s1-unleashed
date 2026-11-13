/* Clarity's harness (MIT): measures Home Assistant's real frontend on the reference dashboard.
 *
 * The fake Home Assistant (tools/perf/fakeha.py) serves HA's own index.html with this script first,
 * after stats.js and the page config (window.GW_CLARITY, from page/tiers.json). It changes nothing
 * HA does, except the room to flick below the cards (see "Room to flick" in docs/perf.md).
 *
 * The frontend loads once: the kiosk opens the dashboard's start view (after a restart, or for an
 * idle sample). The harness reports when that view was usable (refdash and done, with no nonce:
 * the restart's end), then waits for the runner's commands (/clarity/next). Each command opens one
 * reference view the way HA's view tabs and navigate actions do (navigate(): pushState, then
 * location-changed), and the harness measures it, one line per event, as protocol 3's pages did:
 *
 *   refdash (open the view: navigation to usable) -> warmup -> measure (live check) -> step (live
 *   updates) -> input-ready / input (taps) -> per swipe: swipe-ready -> measure -> step -> done
 */
(function () {
  "use strict";
  const S = window.GWStats, C = window.GW_CLARITY;
  const EVERY = 1000 / C.LIVE.updatesPerS, FLIP = C.LIVE.flipEveryMs;
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const frame = () => new Promise((r) => requestAnimationFrame(r));
  const r1 = (x) => Math.round(x * 10) / 10;
  const sr = (e) => e && e.shadowRoot;

  // The view being measured: from the runner's command. Before the first, the landing view (no nonce).
  const landing = ((location.pathname.split("/")[2]) || "start");
  let V = { nonce: "", run: 0, taps: 0, swipes: 0, vsync: 1000 / 60, tier: landing };

  let logSeq = 0;
  function log(obj) {
    logSeq++;
    for (const l of S.consoleLines("GWPERF", `${V.nonce || "restart"}:${logSeq}`,
      JSON.stringify(Object.assign({ run: V.run, nonce: V.nonce }, obj)))) {
      console.log(l);
    }
  }

  // ---- the websocket: see each state change arrive, before HA's own listener runs

  const live = { watch: new Map(), changes: [], on: false, applied: 0, seen: new Set(), gen: null, liveAt: null,
                 offset: null, rtt: null, askedAt: null };
  const Native = window.WebSocket;
  function Clarity(url, protocols) {
    const ws = protocols === undefined ? new Native(url) : new Native(url, protocols);
    ws.addEventListener("message", onMessage);       // registered first, so it runs before HA's
    return ws;
  }
  Clarity.prototype = Native.prototype;
  for (const k of ["CONNECTING", "OPEN", "CLOSING", "CLOSED"]) Clarity[k] = Native[k];
  window.WebSocket = Clarity;

  // The live stream's context ids carry its slot (dashboards.py, stream_context): the stream's
  // generation and the change's number. The fake sends change k at a fixed time after the stream
  // started, so the harness knows when each one was sent, even if the main thread was too busy to
  // read it then.
  const SLOT = /^01JC1ARITY([SF])(\d{5})(\d{6})(\d{4})$/;

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
        const slot = typeof ctx === "string" ? SLOT.exec(ctx) : null;
        // HA's tile fades 180 ms on an on/off change (ha-tile-icon.ts); a sensor value changes text only.
        const change = { at, d: onOff ? C.LIVE.tileTransitionMs : 0, kind: onOff ? 2 : 0, dom: null, tick: null,
                         slot: slot ? slot[1] + Number(slot[3]) : null, gen: slot ? Number(slot[2]) : null,
                         late: slot ? Number(slot[4]) : 0 };
        w.pending.push(change);
        if (live.on) live.changes.push(change);
      }
    }
  }

  // ---- finding HA's cards: hui-root > #view > the current hui-view > hui-masonry-view > hui-card

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

  let rootEl = null;
  function huiRoot() {
    if (!rootEl || !rootEl.isConnected) rootEl = find(document, "hui-root", 0);
    return rootEl;
  }

  // The view hui-root shows now (hui-root.ts _selectView: the last child of #view).
  function currentView() {
    const root = huiRoot();
    const box = root && sr(root) && sr(root).getElementById("view");
    const view = box && box.lastElementChild;
    return view && view.localName === "hui-view" ? view : null;
  }

  function cards(view) {
    view = view || currentView();
    const layout = view && view.firstElementChild;
    return layout && sr(layout) ? Array.from(sr(layout).querySelectorAll("hui-card")) : [];
  }

  // A card is drawn when its ha-card is laid out. A card made of cards (HA's grid, the heavy view's
  // camera sub-streams) has no ha-card of its own: it is drawn when it is laid out and every card
  // in it is.
  function rendered(card) {
    const inner = card.firstElementChild;
    const root = inner && sr(inner);
    if (!root) return false;
    const nested = Array.from(root.querySelectorAll("hui-card"));
    if (nested.length) return inner.offsetHeight > 0 && nested.every(rendered);
    const ha = root.querySelector("ha-card");
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
    unwatch();
    const byEntity = new Map();
    for (const card of cards()) {
      const cfg = card.config || {};
      const ids = cfg.entity ? [cfg.entity] : (cfg.entities || []).map((e) => (typeof e === "string" ? e : e.entity));
      for (const id of ids) if (entityIds.includes(id)) byEntity.set(id, card);
    }
    for (const [id, card] of byEntity) {
      const w = { card, pending: [] };
      w.mo = new MutationObserver(() => {
        const now = performance.now();
        for (const c of w.pending) if (c.dom === null && c.at <= now) c.dom = now;
      });
      for (const root of shadowRoots(card, [])) w.mo.observe(root, { subtree: true, childList: true, characterData: true, attributes: true });
      live.watch.set(id, w);
    }
    return byEntity;
  }

  function unwatch() {
    for (const w of live.watch.values()) w.mo.disconnect();
    live.watch.clear();
  }

  // ---- frames: rAF times, and the frame each change was drawn in

  // Only while a view is measured: between views and at idle, the harness asks for no frames (F10).
  const frames = { on: false, list: [], ticking: false };
  function tick(ts) {
    if (!frames.ticking) return;
    if (frames.on) frames.list.push(ts);
    for (const w of live.watch.values()) {
      for (const c of w.pending) if (c.dom !== null && c.tick === null) c.tick = ts;
      w.pending = w.pending.filter((c) => c.tick === null);
    }
    requestAnimationFrame(tick);
  }
  function startTicks() {
    if (!frames.ticking) {
      frames.ticking = true;
      requestAnimationFrame(tick);
    }
  }

  // ---- usable: the view's first-screen cards rendered, and the main thread free

  // Free: every task posted back to back runs within 50 ms (RAIL's input budget) for freeWindowMs.
  // One quick task isn't enough: HA keeps building the cards below the first screen in many tasks,
  // and a single probe can land in a gap between two long ones (smoke9's heavy did, 47 ms after
  // its render, while typical's main thread stayed busy for 3 s). Returns when that stretch began.
  async function freeFrom() {
    let st = { start: null, done: false };
    while (!st.done) {
      const posted = performance.now();
      const ran = await new Promise((r) => setTimeout(() => r(performance.now()), 0));
      st = S.freeStep(posted, ran, st.start, C.PHASES.freeWindowMs);
    }
    return st.start;
  }

  // The view's head: its first HEAD_CARDS configured cards (the same on every view), from the
  // layout's own list, so a card HA hasn't put on the page yet still counts as not drawn (F1).
  function headCards(view) {
    const layout = view && view.firstElementChild;
    return Array.from((layout && layout.cards) || []).slice(0, C.HEAD_CARDS).map((c) => ({ rendered: rendered(c) }));
  }

  async function usable(index) {
    let st = { last: "", stable: 0, done: false }, ts = 0, onScreen = [];
    while (!st.done) {
      ts = await frame();
      const view = currentView();
      onScreen = cards(view).map((c) => c.getBoundingClientRect()).map((r, i) => [r, i])
        .filter(([r]) => r.height > 0 && r.top < innerHeight && r.bottom > 0);
      const all = cards(view);
      st = S.usableStep({
        launch: !!document.getElementById("ha-launch-screen"),
        viewIndex: view ? view.index : null, wantIndex: index,
        head: headCards(view),
        onScreen: onScreen.map(([r, i]) => ({ rendered: rendered(all[i]), top: r.top })),
      }, st, C.HEAD_CARDS);
    }
    const interactive = await freeFrom();
    return { rendered_tick_ms: r1(ts), first_screen_cards: onScreen.length, head_cards: C.HEAD_CARDS, interactive_ms: r1(interactive) };
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

  // nav: the navigation's start on the page's clock (0 for the frontend's own load).
  async function reportUsable(nav, first) {
    const u = await usable(C.VIEWS.indexOf(V.tier));
    const navEntry = performance.getEntriesByType("navigation")[0] || {};
    const fcp = performance.getEntriesByName("first-contentful-paint")[0];
    log({
      t: "refdash", tier: V.tier, protocol: C.PROTOCOL, frontend: C.FRONTEND, nodes: nodeCount(document),
      // The view's cards (dashboards.py) and how many HA had built when it was usable.
      cards: C.CARDS[V.tier], cards_rendered: cards().length,
      css: [innerWidth, innerHeight], dpr: devicePixelRatio, scroll_height: document.documentElement.scrollHeight,
      // The frontend's own load: only meaningful for the first view (the restart's).
      fcp_ms: first && fcp ? Math.round(fcp.startTime) : null, lcp_ms: first && lcp !== null ? Math.round(lcp) : null,
      load_ms: first && navEntry.loadEventEnd ? Math.round(navEntry.loadEventEnd) : null,
      nav_ms: r1(nav), usable: u, video_caps: videoCaps(),
      origin_epoch_ms: Math.round(performance.timeOrigin), epoch_ms: Date.now(),
    });
    return u;
  }

  // ---- room to flick: as protocol 3, every view leaves scrollRoomScreens screens below its top

  function roomToFlick() {
    const root = huiRoot();
    if (!root || !sr(root) || sr(root).getElementById("clarity-room")) return;
    const s = document.createElement("style");
    s.id = "clarity-room";
    s.textContent = `#view { min-height: ${Math.ceil(innerHeight * (1 + C.PHASES.scrollRoomScreens))}px !important; }`;
    sr(root).appendChild(s);
  }

  // ---- live cameras (#174): the view's video elements, their first frame and their playback

  // What this engine offers HA's camera player (ha-hls-player.ts: hls.js on MSE when H.264 is
  // supported there, else native HLS). Logged with every refdash, so a panel run says which path ran.
  function videoCaps() {
    const v = document.createElement("video");
    return {
      mse_h264: !!(window.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="avc1.4d401f"')),
      native_hls: v.canPlayType("application/vnd.apple.mpegurl"),
      rvfc: "requestVideoFrameCallback" in HTMLVideoElement.prototype,
    };
  }

  const cams = { recs: [], expected: 0, nav: 0, until: 0, t0: null };
  const camCount = (tier) => (C.CAMERAS[tier] || []).reduce((n, x) => n + (Array.isArray(x) ? x.length : 1), 0);

  function watchVideo(el) {
    const rec = { el, first: null, frames: [], ticks: [], counting: false, waiting: 0, q0: null };
    const frame = (t) => {
      if (rec.first === null) rec.first = t;
      if (rec.counting) rec.frames.push(t);
    };
    if (el.requestVideoFrameCallback) {
      const cb = (now, md) => {
        frame((md && md.presentationTime) || now);
        if (cams.recs.includes(rec)) el.requestVideoFrameCallback(cb);
      };
      el.requestVideoFrameCallback(cb);
    } else {
      // Without requestVideoFrameCallback: the first frame at loadeddata, gaps from timeupdate.
      el.addEventListener("loadeddata", () => { if (rec.first === null) rec.first = performance.now(); });
      let last = -1;
      el.addEventListener("timeupdate", () => { if (el.currentTime !== last) { last = el.currentTime; if (rec.counting) rec.ticks.push(performance.now()); } });
    }
    el.addEventListener("waiting", () => { if (rec.counting) rec.waiting++; });
    return rec;
  }

  // Finds the view's videos as HA builds them (inside the camera cards' shadow roots), for up to 20 s.
  async function trackCameras(nav) {
    cams.recs = [];
    cams.expected = camCount(V.tier);
    cams.nav = nav;
    if (!cams.expected) return;
    const seen = new Set(), end = nav + 20000, index = C.VIEWS.indexOf(V.tier);
    while (performance.now() < end) {
      const view = currentView();
      // Until hui-root has switched, the view on screen is the one before (its cameras included).
      if (!view || view.index !== index) { await sleep(50); continue; }
      for (const card of cards(view)) {
        for (const r of shadowRoots(card, [])) {
          for (const v of r.querySelectorAll("video")) {
            if (!seen.has(v)) { seen.add(v); cams.recs.push(watchVideo(v)); }
          }
        }
      }
      if (cams.recs.length >= cams.expected && cams.recs.every((r) => r.first !== null)) return;
      await sleep(100);
    }
  }

  function camerasStart() {
    cams.t0 = performance.now();
    // The streams' HLS fetches during the window (S.hlsFetches). An observer, not the timeline: HA's
    // frontend has likely filled the resource buffer by now, and an observer still gets new entries.
    cams.fetches = [];
    try {
      cams.obs = new PerformanceObserver((list) => {
        for (const e of list.getEntries()) if (e.name.includes("/api/hls/")) cams.fetches.push(e.toJSON());
      });
      cams.obs.observe({ type: "resource" });
    } catch (e) {
      cams.obs = null;
    }
    for (const r of cams.recs) {
      r.counting = true;
      r.frames = [];
      r.ticks = [];
      r.q0 = r.el.getVideoPlaybackQuality ? r.el.getVideoPlaybackQuality() : null;
    }
  }

  // Playback from the view going live to its first swipe: the cameras on screen, the updates and the
  // taps running (the swipes scroll them away, and an engine may rightly stop decoding a video no
  // one can see). Shown: frames presented against fps x the time; stalls: gaps over 500 ms.
  function camerasEnd() {
    if (!cams.expected) return;
    const t1 = performance.now(), dur = t1 - cams.t0, fps = C.CAMERA.fps;
    const streams = [];
    for (let i = 0; i < Math.max(cams.expected, cams.recs.length); i++) {
      const r = cams.recs[i];
      if (!r) { streams.push({ found: false, first_frame_ms: null, shown_pct: null, max_gap_ms: null, stall_total_ms: null }); continue; }
      r.counting = false;
      const times = r.el.requestVideoFrameCallback ? r.frames : r.ticks;
      const q1 = r.el.getVideoPlaybackQuality ? r.el.getVideoPlaybackQuality() : null;
      const decoded = q1 && r.q0 ? q1.totalVideoFrames - r.q0.totalVideoFrames : null;
      const dropped = q1 && r.q0 ? q1.droppedVideoFrames - r.q0.droppedVideoFrames : null;
      // Shown: the decoder's own count of frames it presented (totalVideoFrames less dropped).
      // requestVideoFrameCallback is for the first frame and the gaps: it may fire once for two
      // frames that land in one rendering update (Chrome, 2026-11-13: 60 callbacks for 77 frames).
      const shown = decoded !== null ? decoded - dropped : (r.el.requestVideoFrameCallback ? r.frames.length : null);
      const pts = [cams.t0].concat(times.filter((t) => t >= cams.t0 && t <= t1), [t1]);
      const gaps = pts.slice(1).map((t, k) => t - pts[k]);
      streams.push({
        found: true, w: r.el.videoWidth, h: r.el.videoHeight,
        first_frame_ms: r.first === null ? null : r1(r.first - cams.nav),
        shown_pct: shown === null || r.first === null ? null : r1(Math.min(100, (100 * shown) / ((fps * dur) / 1000))),
        max_gap_ms: r.first === null ? null : r1(Math.max(...gaps)),
        stall_total_ms: r.first === null ? null : r1(gaps.filter((g) => g > 500).reduce((a, b) => a + b, 0)),
        frames: shown, callbacks: r.frames.length, decoded, dropped, waiting: r.waiting,
        source: (decoded !== null ? "quality" : "rvfc") + (r.el.requestVideoFrameCallback ? "+rvfc" : "+timeupdate"),
      });
    }
    if (cams.obs) {
      for (const e of cams.obs.takeRecords()) if (e.name.includes("/api/hls/")) cams.fetches.push(e.toJSON());
      cams.obs.disconnect();
    }
    const fetches = cams.obs ? S.hlsFetches(cams.fetches.filter((e) => e.startTime >= cams.t0 && e.startTime <= t1)) : null;
    log({ t: "camera", stage: V.tier, window_ms: Math.round(dur), fps, expected: cams.expected, found: cams.recs.length, streams, fetches });
  }

  // ---- opening a view: HA's own navigate()

  // src/common/navigate.ts performNavigation: history.pushState, then "location-changed" on the
  // window; hui-root reads the route and swaps in the view (hui-root.ts _selectView).
  function navigate(path) {
    history.pushState(Object.assign({}, history.state, { from: location.pathname + location.search }), "", path);
    const ev = new Event("location-changed", { bubbles: true, composed: true, cancelable: false });
    ev.detail = { replace: false };
    window.dispatchEvent(ev);
  }

  // ---- live updates: the fake HA streams them while the page asks

  // The page's clock against the fake's: round trips to /clarity/time while the view is still, the
  // shortest kept (offset = fake - page, at the trip's middle; uncertain by half its length). A busy
  // main thread can hold a request back before it leaves (smoke on heavy: 650 ms), so the moment the
  // harness asks for the stream isn't when the fake starts it; the fake's own clock says that.
  async function syncClock() {
    let best = null;
    for (let i = 0; i < 12; i++) {
      const a = performance.now();
      try {
        const r = await fetch("/clarity/time", { cache: "no-store" });
        const ms = (await r.json()).ms;
        const b = performance.now();
        if (!best || b - a < best.rtt) best = { rtt: b - a, offset: ms - (a + b) / 2 };
      } catch (e) { /* the fake is gone */ }
    }
    live.offset = best ? best.offset : null;
    live.rtt = best ? best.rtt : null;
  }

  async function stream(on) {
    if (on) live.askedAt = performance.now();
    try {
      const r = await fetch(`/clarity/${on ? "live" : "still"}?nonce=${encodeURIComponent(V.nonce)}`, { cache: "no-store" });
      if (on) {
        const j = r.ok ? await r.json() : {};
        // No stream, no measure: a schedule that never ran must not read as a slow panel.
        if (j.gen === undefined) throw new Error(`the live stream didn't start (HTTP ${r.status})`);
        live.gen = j.gen;
        // When the fake started the schedule, on the page's clock; without a clock tie, when the
        // harness asked (earlier than the fake started, so it counts against the panel).
        live.liveAt = live.offset !== null && j.start_ms !== undefined ? j.start_ms - live.offset : live.askedAt;
      }
    } catch (e) {
      if (on) throw e;                    // the view logs an error and isn't graded
    }
  }

  async function updatesWindow(head) {
    log({ t: "warmup", stage: "updates", n: 1 });
    live.changes = [];
    live.on = true;
    await stream(true);
    await sleep(C.PHASES.warmupMs);
    const watched = Array.from(new Set(Array.from(head.values())));
    log({ t: "measure", stage: "updates", n: 1, moving: watched.length, offscreen: watched.filter((el) => !inView(el)).length,
          targets: head.size, update_every_ms: Math.round(EVERY), animations: 0, animations_expected: 0 });
    const t0 = performance.now();
    frames.list = [];
    frames.on = true;
    await sleep(S.measureMs(V.vsync));
    frames.on = false;
    await sleep(Math.max(0, t0 + C.PHASES.updatesWindowMs - performance.now()));
    live.on = false;
    await frame();
    await frame();
    const t1 = t0 + C.PHASES.updatesWindowMs;
    // Every change the fake sent up to the window's end, from its schedule: [sent, arrived, transition
    // ms, kind, tick], ms after t0. One that hadn't arrived when this is logged has no arrival and no
    // tick; the report counts it as not shown by the window's end.
    const got = new Map(live.changes.filter((c) => c.slot && c.gen === live.gen).map((c) => [c.slot, c]));
    const sched = [];
    for (let k = 1; live.liveAt + k * EVERY <= t1; k++) sched.push(["S" + k, live.liveAt + k * EVERY, 0, 0]);
    for (let f = 1; live.liveAt + f * FLIP <= t1; f++) sched.push(["F" + f, live.liveAt + f * FLIP, C.LIVE.tileTransitionMs, 2]);
    sched.sort((a, b) => a[1] - b[1]);
    // A change that arrived says how late the fake sent it; one that didn't counts from its fixed
    // time (earlier, so against the panel, by the fake's few ms).
    const changes = sched.filter((s) => s[1] + s[2] >= t0 - EVERY).map(([slot, due, d, kind]) => {
      const c = got.get(slot);
      const sent = due + (c ? c.late : 0);
      return [r1(sent - t0), c ? r1(c.at - t0) : null, d, kind, c && c.tick !== null ? r1(c.tick - t0) : null];
    });
    log({ t: "step", stage: "updates", n: 1, window_ms: Math.round(t1 - t0), t0: r1(t0), pacing_ms: S.measureMs(V.vsync),
          from: "sent", stream_at_ms: r1(live.liveAt - t0), stream_asked_ms: r1(live.askedAt - t0),
          clock_rtt_ms: live.rtt === null ? null : r1(live.rtt),
          raf: S.frameStats(S.intervals(frames.list), V.vsync), raf_times: frames.list.map((x) => r1(x - t0)), changes });
  }

  // ---- tap: HA's own toggle tile, Event Timing for INP

  async function input() {
    const toggle = cards().find((c) => (c.config || {}).entity === "light.living_room");
    if (!toggle) return log({ t: "input", stage: V.tier, method: "none", taps: 0, values: [], inp_ms: null });
    const durations = new Map();
    const hasET = typeof PerformanceEventTiming !== "undefined";
    const since = performance.now();       // the frontend stays loaded: earlier views' taps are buffered too
    let po = null, taps = 0;
    if (hasET) {
      po = new PerformanceObserver((l) => {
        for (const e of l.getEntries()) {
          if (e.interactionId && e.startTime >= since) durations.set(e.interactionId, Math.max(durations.get(e.interactionId) || 0, e.duration));
        }
      });
      po.observe({ type: "event", durationThreshold: 16, buffered: true });
    }
    const onDown = () => { taps++; };
    addEventListener("pointerdown", onDown, true);
    const r = toggle.getBoundingClientRect();
    log({ t: "input-ready", taps: V.taps, x: Math.round((r.left + r.width / 2) * devicePixelRatio), y: Math.round((r.top + r.height / 2) * devicePixelRatio) });
    const end = performance.now() + Math.max(C.PHASES.tapWaitMinMs, V.taps * 2000 + 5000);
    while (taps < V.taps && performance.now() < end) await sleep(100);
    await sleep(500);
    removeEventListener("pointerdown", onDown, true);
    if (po) po.disconnect();
    const values = Array.from(durations.values());
    if (hasET) while (values.length < taps) values.push(16);
    values.sort((a, b) => a - b);
    log({ t: "input", stage: V.tier, method: hasET ? "event-timing" : "none", taps, values: values.map((v) => Math.round(v)),
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
    const cap = S.measureMs(V.vsync);
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
    const windowMs = Math.min(cap, last - first + V.vsync);
    const stamps = frames.list.filter((t) => t <= first + windowMs);
    log({ t: "step", stage: "scroll", n: i, window_ms: Math.round(windowMs), scrolled_px: Math.round(Math.abs(scrollY - y0)),
          hit_edge: scrollY >= max - 1, changes_during: live.applied - appliedAt,
          first_scroll_ms: downAt === null ? null : Math.round(first - downAt), raf: S.frameStats(S.intervals(stamps), V.vsync),
          raf_intervals: S.intervals(stamps).map((x) => r1(x)) });
    await sleep(300);
  }

  // ---- one reference view, as the runner asked

  async function openView(cmd) {
    V = { nonce: String(cmd.nonce), run: Number(cmd.run || 0), taps: Math.max(0, Number(cmd.taps || 0)),
          swipes: cmd.swipes !== undefined ? Math.max(0, Number(cmd.swipes)) : C.PHASES.swipes,
          vsync: Number(cmd.vsync || 1000 / 60), tier: String(cmd.view) };
    unwatch();
    startTicks();
    const nav = performance.now();
    navigate(`/${C.DASHBOARD}/${V.tier}`);
    const camerasFound = trackCameras(nav);
    const u = await reportUsable(nav, false);
    // Still for a moment after it is usable, as a person looks before they touch it.
    await sleep(Math.max(0, u.interactive_ms + C.PHASES.stillAfterUsableMs - performance.now()));
    roomToFlick();
    await syncClock();
    const H = C.HEAD_ENTITIES;
    const head = watch(H.sensors.concat(H.rows, H.switches));
    await camerasFound;
    camerasStart();
    await updatesWindow(head);
    if (V.taps) await input();
    camerasEnd();
    for (let i = 0; i < V.swipes; i++) await swipe(i);
    await stream(false);
    unwatch();
    frames.ticking = false;
    log({ t: "done", stage: V.tier });
  }

  async function main() {
    await new Promise((r) => (document.readyState === "complete" ? r() : addEventListener("load", r, { once: true })));
    // The frontend's own load: the restart's end (or an idle sample's page).
    await reportUsable(0, true);
    log({ t: "done", stage: "restart" });
    let last = "";
    for (;;) {
      let cmd = null;
      try {
        const r = await fetch(`/clarity/next?after=${encodeURIComponent(last)}`, { cache: "no-store" });
        if (r.status === 200) cmd = await r.json();
      } catch (e) {
        await sleep(1000);
        continue;
      }
      if (!cmd) continue;
      last = String(cmd.nonce);
      try {
        await openView(cmd);
      } catch (e) {
        log({ t: "error", message: String((e && e.stack) || e) });
        try { await stream(false); } catch (e2) { /* the fake is gone */ }
        unwatch();
        frames.ticking = false;
      }
    }
  }

  main().catch((e) => log({ t: "error", message: String((e && e.stack) || e) }));
})();
