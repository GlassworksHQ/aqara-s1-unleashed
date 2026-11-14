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
  // The harness's own waits end at the first of a timer and an animation frame past the time.
  // Protocol 8 (#202, s1rtc4): on the S1's heavy view a 30 s tap wait, checked by a timer, ended
  // after 140 s: the page's timers wait behind the frontend's queue, while the refresh driver's
  // frames come first (5 to 8 a second there). The timer still ends a wait when no frame comes (a
  // hidden page draws none).
  const sleep = (ms) => new Promise((r) => {
    const end = performance.now() + ms;
    let over = false;
    const finish = () => { if (!over) { over = true; r(); } };
    setTimeout(finish, ms);
    const tick = () => { if (over) return; if (performance.now() >= end) finish(); else requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  });
  // A promise, or after `ms` (the harness's own clock) undefined: for answers a busy page may hold.
  const within = (p, ms) => Promise.race([p, sleep(ms).then(() => undefined)]);
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

  // The page's WebRTC connections (HA's ha-web-rtc-player makes one per camera), so the camera task
  // can read each stream's getStats. The connection is the browser's own; only the list is ours.
  // Per connection, what HA's player did with it: when it was made, its ICE states and how many
  // offers it made (restartIce makes another on the same connection). Detail for the camera task.
  const peers = [], peerInfo = new WeakMap();
  let peerCount = 0;
  const NativePC = window.RTCPeerConnection;
  if (NativePC) {
    const ClarityPC = function (config) {
      const pc = new NativePC(config);
      const info = { n: ++peerCount, born: performance.now(), ice: [], offers: 0 };
      peerInfo.set(pc, info);
      pc.addEventListener("iceconnectionstatechange", () => info.ice.push([performance.now(), pc.iceConnectionState]));
      pc.addEventListener("signalingstatechange", () => { if (pc.signalingState === "have-local-offer") info.offers++; });
      peers.push(pc);
      for (let i = peers.length - 1; i >= 0; i--) if (peers[i].signalingState === "closed") peers.splice(i, 1);
      return pc;
    };
    ClarityPC.prototype = NativePC.prototype;
    if (NativePC.generateCertificate) ClarityPC.generateCertificate = NativePC.generateCertificate.bind(NativePC);
    window.RTCPeerConnection = ClarityPC;
  }

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

  // A card is drawn when its ha-card is laid out; a card made of cards when it and every card in it
  // is (stats.js cardRendered, tested there).
  const rendered = (card) => S.cardRendered(card);

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
  // `busy` (detail, #202): each second of the wait, the longest a probe waited, in ms, so a long
  // wait says whether the page was busy throughout or in bursts.
  async function freeFrom() {
    let st = { start: null, done: false };
    const t0 = performance.now(), busy = [];
    while (!st.done) {
      const posted = performance.now();
      const ran = await new Promise((r) => setTimeout(() => r(performance.now()), 0));
      const k = Math.floor((posted - t0) / 1000);
      if (k < 600) {
        while (busy.length <= k) busy.push(0);
        busy[k] = Math.max(busy[k], Math.round(ran - posted));
      }
      st = S.freeStep(posted, ran, st.start, C.PHASES.freeWindowMs);
    }
    return { start: st.start, busy };
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
    const free = await freeFrom();
    return { rendered_tick_ms: r1(ts), first_screen_cards: onScreen.length, head_cards: C.HEAD_CARDS,
             interactive_ms: r1(free.start), busy_ms: free.busy };
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

  // ---- live cameras (#174, #202): the view's video elements, their first frame and their playback

  // What this engine offers HA's camera players: hls.js on MSE when H.264 is supported there, else
  // native HLS (ha-hls-player.ts); WebRTC needs RTCPeerConnection (ha-web-rtc-player.ts). Logged with
  // every refdash, so a panel run says which paths could run.
  function videoCaps() {
    const v = document.createElement("video");
    return {
      mse_h264: !!(window.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="avc1.4d401f"')),
      native_hls: v.canPlayType("application/vnd.apple.mpegurl"),
      rvfc: "requestVideoFrameCallback" in HTMLVideoElement.prototype,
      webrtc: !!NativePC,
    };
  }

  // One entry per camera (HA's ha-camera-stream), with a record per player it got: with go2rtc HA
  // offers WebRTC and HLS, and the frontend starts both, shows HLS until WebRTC has video, then keeps
  // WebRTC only (ha-camera-stream.ts _streams). The graded stream is the view's path's.
  const cams = { list: [], expected: 0, nav: 0, t0: null, path: null };
  const camCount = (tier) => (C.CAMERAS[(C.FALLBACK || {})[tier] || tier] || []).reduce((n, x) => n + (Array.isArray(x) ? x.length : 1), 0);
  const pathOf = (tier) => ((C.FALLBACK || {})[tier] ? "hls" : C.CAMERA.path);
  const PLAYERS = { "ha-web-rtc-player": "web_rtc", "ha-hls-player": "hls" };
  const hostOf = (el) => { const r = el.getRootNode(); return r && r.host ? r.host : null; };

  function watchVideo(el) {
    const rec = { el, first: null, frames: [], pfs: [], ticks: [], counting: false, waiting: 0, q0: null, s0: null };
    const frame = (t, pf) => {
      if (rec.first === null) rec.first = t;
      if (rec.counting) {
        rec.frames.push(t);
        rec.pfs.push(pf === undefined ? null : pf);
      }
    };
    if (el.requestVideoFrameCallback) {
      const cb = (now, md) => {
        frame((md && md.presentationTime) || now, md && md.presentedFrames);
        if (el.isConnected || rec.first === null) el.requestVideoFrameCallback(cb);
      };
      el.requestVideoFrameCallback(cb);
    } else {
      // Without requestVideoFrameCallback: the first frame at loadeddata, gaps from timeupdate. A
      // video that already has a frame when it is found (it loaded before the 100 ms discovery poll)
      // counts from now: a little late, never missed. GeckoView 159 has rVFC, so the S1 never takes
      // this path (video_caps.rvfc).
      if (el.readyState >= 2) rec.first = performance.now();
      el.addEventListener("loadeddata", () => { if (rec.first === null) rec.first = performance.now(); });
      let last = -1;
      el.addEventListener("timeupdate", () => { if (el.currentTime !== last) { last = el.currentTime; if (rec.counting) rec.ticks.push(performance.now()); } });
    }
    el.addEventListener("waiting", () => { if (rec.counting) rec.waiting++; });
    return rec;
  }

  // The connection that carries a WebRTC player's video, by its track.
  // The connections that carry a WebRTC player's video now, by its tracks.
  function peersOf(el) {
    const st = el.srcObject, ids = new Set(((st && st.getVideoTracks && st.getVideoTracks()) || []).map((t) => t.id));
    if (!ids.size) return [];
    return peers.filter((pc) => pc.signalingState !== "closed" && pc.getReceivers().some((r) => r.track && ids.has(r.track.id)));
  }

  // One getStats() sample of every connection carrying the camera's video (stats.js webrtcWindow),
  // timed when it is asked for. HA's player may change connections mid-window, so the window is
  // sampled every second, not diffed between two snapshots.
  // One getStats per receiver at a time (protocol 8): on a busy page answers come late, and one a
  // second each piled up (s1rtc4's heavy: the camera line came 93 s after its window, waiting for
  // all of them). A sample's time is when it was asked; the counts are cumulative, so a late or
  // skipped one loses no frame, only resolution. `force`: the window's ends, always asked.
  function sample(rec, force) {
    if (rec.inflight && !force) return;
    const t = performance.now(), pcs = peersOf(rec.el);
    for (const pc of pcs) rec.pcsSeen.add(pc);
    const p = Promise.all(pcs.map((pc) => pc.getStats().then((stats) => {
      const reports = [];
      stats.forEach((x) => reports.push(x));
      return { pc: peerInfo.get(pc).n, reports };
    }, () => null))).then((got) => { rec.samples.push({ t, pcs: got.filter(Boolean) }); });
    rec.inflight = p.then(() => { if (rec.inflight === q) rec.inflight = null; });
    const q = rec.inflight;
    rec.pending.push(p);
  }

  // Finds the view's cameras and their videos as HA builds them (inside the camera cards' shadow
  // roots), for up to 20 s, until each has a frame on the graded path.
  async function trackCameras(nav) {
    cams.list = [];
    cams.expected = camCount(V.tier);
    cams.path = pathOf(V.tier);
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
            if (seen.has(v)) continue;
            seen.add(v);
            const player = hostOf(v), stream = player && hostOf(player);
            const kind = (player && PLAYERS[player.localName]) || "other";
            let cam = cams.list.find((c) => c.stream === stream);
            if (!cam) cams.list.push((cam = { stream, recs: {} }));
            if (!cam.recs[kind]) cam.recs[kind] = watchVideo(v);
          }
        }
      }
      if (cams.list.length >= cams.expected && cams.list.every((c) => c.recs[cams.path] && c.recs[cams.path].first !== null)) return;
      await sleep(100);
    }
  }

  function camerasStart() {
    // One observer at a time, and none on a view without cameras: camerasEnd returns early there, so
    // an observer started for light would stay and add every later view's fetches a second time (the
    // fetch detail of s1cam3's runs 1 and 3 counted twice; their timings stand).
    if (cams.obs) cams.obs.disconnect();
    cams.obs = null;
    if (!cams.expected) return;
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
    for (const c of cams.list) {
      for (const r of Object.values(c.recs)) {
        r.counting = true;
        r.frames = [];
        r.pfs = [];
        r.ticks = [];
        r.q0 = r.el.getVideoPlaybackQuality ? r.el.getVideoPlaybackQuality() : null;
        r.samples = [];
        r.pending = [];
        r.inflight = null;
        r.pcsSeen = new Set();
      }
    }
    if (cams.path === "web_rtc") {
      const graded = () => cams.list.map((c) => c.recs.web_rtc).filter(Boolean);
      for (const r of graded()) sample(r, true);
      cams.sampler = setInterval(() => { for (const r of graded()) sample(r); }, 1000);
    }
  }

  // Playback over a fixed window from the view going live (protocol 8: CAMERA.windowMs, 30 s, while
  // the updates and the taps run; before, it ran to the end of the taps, 147 s on s1rtc4's heavy),
  // always before the first swipe (the swipes scroll the cameras away, and an engine may rightly
  // stop decoding a video no one can see). Each camera is graded on its view's path (CAMERA path;
  // HLS on a fallback view). Over WebRTC the frames shown, the gaps and the stalls are the
  // receiver's (getStats, stats.js webrtcWindow); the frame callbacks' gaps are detail, as how busy
  // the page was (`callback_gaps`). Over HLS, frames presented against fps x the time, and stalls
  // are the callbacks' gaps over 500 ms.
  async function camerasEnd() {
    if (!cams.expected) return;
    const t1 = performance.now(), fps = C.CAMERA.fps;
    const dur = t1 - cams.t0;
    clearInterval(cams.sampler);
    // Everything at t1 that can be read at once; the receiver's getStats is asked for now too, and
    // read after the loop (an await here would let the other players count on).
    const stream = (r) => {
      r.counting = false;
      const times = r.el.requestVideoFrameCallback ? r.frames : r.ticks;
      const q1 = r.el.getVideoPlaybackQuality ? r.el.getVideoPlaybackQuality() : null;
      const decoded = q1 && r.q0 ? q1.totalVideoFrames - r.q0.totalVideoFrames : null;
      const dropped = q1 && r.q0 ? q1.droppedVideoFrames - r.q0.droppedVideoFrames : null;
      if (cams.path === "web_rtc") sample(r, true);
      const g = S.videoGaps(times, r.el.requestVideoFrameCallback ? r.pfs : null, cams.t0, t1);
      return {
        found: true, w: r.el.videoWidth, h: r.el.videoHeight,
        first_frame_ms: r.first === null ? null : r1(r.first - cams.nav),
        max_gap_ms: r.first === null ? null : r1(g.max_gap_ms),
        stall_total_ms: r.first === null ? null : r1(g.stall_total_ms),
        stall_presented: r.first === null ? null : g.stall_presented,
        stalls_at: g.stalls_at,
        gaps_source: r.el.requestVideoFrameCallback ? "rvfc" : "timeupdate",
        callbacks: r.frames.length, decoded, dropped, waiting: r.waiting,
      };
    };
    const missing = { found: false, first_frame_ms: null, shown_pct: null, max_gap_ms: null, stall_total_ms: null };
    // Every player's counts first, at t1; only then the awaits for getStats, during which the
    // others would go on counting.
    const streams = [], webrtcRecs = [];
    for (let i = 0; i < Math.max(cams.expected, cams.list.length); i++) {
      const c = cams.list[i], r = c && c.recs[cams.path];
      const out = Object.assign({ path: cams.path }, r ? stream(r) : missing);
      // The camera's other player, if it had one (HLS while WebRTC started): its first frame, and
      // whether the frontend had removed it by the window's end. Detail.
      out.others = c ? Object.keys(c.recs).filter((k) => k !== cams.path).map((k) => {
        const o = c.recs[k];
        o.counting = false;
        return { path: k, first_frame_ms: o.first === null ? null : r1(o.first - cams.nav), connected: o.el.isConnected };
      }) : [];
      streams.push(out);
      webrtcRecs.push(r && cams.path === "web_rtc" ? r : null);
    }
    // The WebRTC receiver's own counts over the window (getStats): frames decoded, dropped and
    // frozen, the decoder it used, the codec, and the candidate pair (relay over TCP). Then each
    // stream's frames shown, from the best count the engine gives (stats.js shownFrames).
    for (let i = 0; i < streams.length; i++) {
      const out = streams[i], r = cams.list[i] && cams.list[i].recs[cams.path];
      if (!r) continue;
      if (webrtcRecs[i]) {
        // The samples that answered within statsWaitMs of the window's end (a busy page holds them):
        // the counts run from the first to the last of those, and so does the time they're shown over.
        await within(Promise.all(r.pending), C.PHASES.statsWaitMs);
        const smp = r.samples.filter((x) => x.t <= t1 + 1).sort((a, b) => a.t - b.t);
        out.webrtc = S.webrtcWindow(smp);
        if (out.webrtc && out.webrtc.inbound && out.webrtc.span_ms > 0) {
          // The graded gaps and stalls: the receiver's. The callbacks' stay beside them.
          out.callback_gaps = { max_gap_ms: out.max_gap_ms, stall_total_ms: out.stall_total_ms,
                                presented: out.stall_presented, callbacks: out.callbacks, source: out.gaps_source,
                                stalls_at: out.stalls_at };
          out.stalls_at = out.webrtc.stalls_at;
          if (out.first_frame_ms !== null) {
            out.max_gap_ms = out.webrtc.max_gap_ms;
            out.stall_total_ms = out.webrtc.stall_ms;
          }
          out.gaps_source = out.webrtc.max_gap_bound ? "webrtc (bound)" : "webrtc";
          out.span_ms = out.webrtc.span_ms;
        }
        // Each connection the camera's video came over in the window: when HA's player made it, its
        // offers (a restartIce is a second one) and its ICE states, after the navigation.
        if (out.webrtc) {
          out.webrtc.connections = [...r.pcsSeen].map((pc) => {
            const info = peerInfo.get(pc);
            return { n: info.n, made_ms: r1(info.born - cams.nav), offers: info.offers,
                     ice: info.ice.map(([t, st]) => [r1(t - cams.nav), st]) };
          });
        }
      }
      const s = S.shownFrames({ quality: out.decoded === null ? null : { decoded: out.decoded, dropped: out.dropped },
                                webrtc: out.webrtc || null, callbacks: out.callbacks, rvfc: !!r.el.requestVideoFrameCallback });
      out.frames = s.frames;
      // Over the receiver's samples, shown is over their span (the counts' own time).
      const over = s.source === "webrtc" && out.span_ms ? out.span_ms : dur;
      out.shown_pct = s.frames === null || r.first === null ? null : r1(Math.min(100, (100 * s.frames) / ((fps * over) / 1000)));
      out.source = s.source + (r.el.requestVideoFrameCallback ? "+rvfc" : "+timeupdate");
    }
    if (cams.obs) {
      for (const e of cams.obs.takeRecords()) if (e.name.includes("/api/hls/")) cams.fetches.push(e.toJSON());
      cams.obs.disconnect();
    }
    const fetches = cams.obs ? S.hlsFetches(cams.fetches.filter((e) => e.startTime >= cams.t0 && e.startTime <= t1)) : null;
    // t0_ms, nav_ms: the window's start and the navigation on the page's clock (detail, to find moments).
    log({ t: "camera", stage: V.tier, path: cams.path, window_ms: Math.round(dur), t0_ms: r1(cams.t0), nav_ms: r1(cams.nav),
          fps, expected: cams.expected,
          found: cams.list.filter((c) => c.recs[cams.path]).length, streams, fetches });
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
    // Each tap goes over `adb shell input`, which took 2.4 to 4.7 s a tap on s1rtc4's heavy; the
    // budget allows tapEachMs a tap. INP is the page's own Event Timing: from when the tap reached
    // the page (the event's timestamp), never from when the runner sent it.
    const end = performance.now() + Math.max(C.PHASES.tapWaitMinMs, V.taps * C.PHASES.tapEachMs + 5000);
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
    // Each task its own budget (protocol 8): the cameras' fixed window runs beside the updates and
    // the taps, and the swipes come after both, so a slow page still gets every task tried.
    const camerasDone = cams.expected ? sleep(C.CAMERA.windowMs).then(camerasEnd) : Promise.resolve();
    await updatesWindow(head);
    if (V.taps) await input();
    await camerasDone;
    for (let i = 0; i < V.swipes; i++) await swipe(i);
    // The fake stops the stream when told; the runner tells it too once the view is done.
    await within(stream(false), C.PHASES.endStreamWaitMs);
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
