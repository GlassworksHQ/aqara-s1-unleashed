/* Glassworks perf test page (MIT). Runs the stages in order and logs one `GWPERF {json}` console
 * line per event, which GeckoView writes to logcat (tag GeckoConsole). tools/perf/perfrun.py reads
 * them; the line types and fields are documented in docs/perf.md ("Page log lines").
 *
 * Query: run=<k> seed=<n> vsync=<ms> nonce=<id> (echoed in every line) taps=<n> (the runner will
 * tap n times; 0 skips the input stage) quick=1 (short ladder, for a smoke test) families=a,b.
 */
(function () {
  "use strict";
  const S = window.GWStats, W = window.GWWorkload;
  const q = new URLSearchParams(location.search);
  const RUN = Number(q.get("run") || 0);
  const SEED = Number(q.get("seed") || 1);
  const VSYNC = Number(q.get("vsync") || 1000 / 60);
  const QUICK = q.get("quick") === "1";
  const TAPS = Math.max(0, Number(q.get("taps") || 0));

  const WARMUP_MS = 1000, MEASURE_MS = S.measureMs(VSYNC), COOLDOWN_MS = 1200;
  const STEPS = QUICK ? [1, 16, 128] : [1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024];

  const stage = document.getElementById("stage");
  const status = document.getElementById("status");
  const hero = document.getElementById("hero");

  const NONCE = q.get("nonce") || "";

  function log(obj) {
    console.log("GWPERF " + JSON.stringify(Object.assign({ run: RUN, nonce: NONCE }, obj)));
  }
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const nextFrame = () => new Promise((r) => requestAnimationFrame(r));

  // ---- the animated families. Each build(n) fills #stage and returns a per-frame tick (or null).

  function place(rnd, el) {
    el.style.left = Math.floor(rnd() * (innerWidth - 60)) + "px";
    el.style.top = Math.floor(rnd() * (innerHeight - 60)) + "px";
    el.style.animationDelay = (-rnd()).toFixed(3) + "s";
  }

  function cssBoxes(cls) {
    return (n, rnd) => {
      for (let i = 0; i < n; i++) {
        const b = document.createElement("div");
        b.className = "box " + cls;
        b.style.background = `hsl(${Math.floor(rnd() * 360)} 70% 55%)`;
        place(rnd, b);
        stage.appendChild(b);
      }
      return null;
    };
  }

  function svgDots(n, rnd) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("class", "full");
    const dots = [];
    for (let i = 0; i < n; i++) {
      const c = document.createElementNS(ns, "circle");
      c.setAttribute("r", "10");
      c.setAttribute("fill", `hsl(${Math.floor(rnd() * 360)} 70% 55%)`);
      svg.appendChild(c);
      dots.push({ c, x: rnd() * innerWidth, y: rnd() * innerHeight, p: rnd() * 6.28 });
    }
    stage.appendChild(svg);
    return (t) => {
      for (const d of dots) {
        d.c.setAttribute("cx", (d.x + 30 * Math.cos(t / 300 + d.p)).toFixed(1));
        d.c.setAttribute("cy", (d.y + 30 * Math.sin(t / 300 + d.p)).toFixed(1));
      }
    };
  }

  function canvasParticles(n, rnd) {
    const cv = document.createElement("canvas");
    cv.width = Math.round(innerWidth * devicePixelRatio);
    cv.height = Math.round(innerHeight * devicePixelRatio);
    stage.appendChild(cv);
    const g = cv.getContext("2d");
    const ps = [];
    for (let i = 0; i < n; i++) ps.push({ x: rnd() * cv.width, y: rnd() * cv.height, p: rnd() * 6.28, h: Math.floor(rnd() * 360) });
    return (t) => {
      g.clearRect(0, 0, cv.width, cv.height);
      for (const s of ps) {
        g.fillStyle = `hsl(${s.h} 70% 55%)`;
        g.beginPath();
        g.arc(s.x + 40 * Math.cos(t / 400 + s.p), s.y + 40 * Math.sin(t / 400 + s.p), 12, 0, 6.2832);
        g.fill();
      }
    };
  }

  // Home Assistant-like tiles: a tenth of them get a new number and bar width every frame,
  // so each tile updates about six times a second at 60 fps.
  function haCards(n, rnd, parent) {
    const grid = document.createElement("div");
    grid.className = "grid";
    const cards = [];
    for (let i = 0; i < n; i++) {
      const c = document.createElement("div");
      c.className = "card";
      c.innerHTML = `<div class="name">Sensor ${i} living room</div><div class="value">0</div><div class="bar"></div>`;
      grid.appendChild(c);
      cards.push({ v: c.children[1], b: c.children[2], base: rnd() * 100 });
    }
    (parent || stage).appendChild(grid);
    let k = 0;
    const per = Math.max(1, Math.ceil(n / 10));
    return () => {
      for (let j = 0; j < per; j++) {
        const c = cards[k++ % n];
        const x = (c.base + k * 0.37) % 100;
        c.v.textContent = x.toFixed(1) + " W";
        c.b.style.transform = `scaleX(${(x / 100).toFixed(3)})`;
      }
    };
  }

  const FAMILIES = {
    "css-transform": cssBoxes("spin"),
    "css-opacity": cssBoxes("pulse"),
    "svg": svgDots,
    "canvas2d": canvasParticles,
    "ha-cards": haCards,
  };

  // The reference load the headline grade uses: a busy wall dashboard. 24 updating tiles,
  // 6 spinning icons, a 64-point sparkline redrawn every frame. A project choice (docs/perf.md).
  function referenceLoad(_n, rnd) {
    const ticks = [haCards(24, rnd), cssBoxes("spin")(6, rnd), canvasParticles(0, rnd)];
    const cv = stage.querySelector("canvas");
    cv.style.cssText = "inset:auto 12px 28px auto;width:360px;height:120px";
    cv.width = Math.round(360 * devicePixelRatio);
    cv.height = Math.round(120 * devicePixelRatio);
    const g = cv.getContext("2d");
    ticks[2] = (t) => {
      g.clearRect(0, 0, cv.width, cv.height);
      g.strokeStyle = "#03a9f4";
      g.lineWidth = 2 * devicePixelRatio;
      g.beginPath();
      for (let i = 0; i < 64; i++) {
        const y = cv.height / 2 + (cv.height / 3) * Math.sin(t / 500 + i / 5);
        const x = (i / 63) * cv.width;
        if (i) g.lineTo(x, y); else g.moveTo(x, y);
      }
      g.stroke();
    };
    return (t) => { for (const f of ticks) if (f) f(t); };
  }

  // ---- measuring

  async function runAnimated(build, n, label) {
    const rnd = W.mulberry32(SEED * 1000003 + n);
    stage.textContent = "";
    hero.style.display = "none";
    status.textContent = `${label} n=${n}`;
    log({ t: "warmup", stage: label, n });
    const tick = build(n, rnd);
    let measuring = false, stamps = [];
    let running = true;
    const loop = (ts) => {
      if (!running) return;
      if (tick) tick(ts);
      if (measuring) stamps.push(ts);
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
    await sleep(WARMUP_MS);
    log({ t: "measure", stage: label, n });
    measuring = true;
    await sleep(MEASURE_MS);
    measuring = false;
    running = false;
    const raf = S.frameStats(S.intervals(stamps), VSYNC);
    log({ t: "step", stage: label, n, raf });
    stage.textContent = "";
    await sleep(COOLDOWN_MS);
    return raf;
  }

  let RUBRIC = null;

  async function ladder() {
    const only = (q.get("families") || "").split(",").filter(Boolean);
    for (const [name, build] of Object.entries(FAMILIES)) {
      if (only.length && !only.includes(name)) continue;
      for (const n of STEPS) {
        const st = await runAnimated(build, n, name);
        if ((S.frameBand(st, RUBRIC) || "poor") === "poor") break;
      }
    }
  }

  // The page itself can't scroll (html is overflow: hidden), so the list gets its own scroller.
  // scrolled_px shows the scroll happened; a stage that moved nothing paints nothing.
  async function scroll() {
    stage.textContent = "";
    const sc = document.createElement("div");
    sc.id = "scroller";
    stage.appendChild(sc);
    haCards(240, W.mulberry32(SEED), sc);
    log({ t: "warmup", stage: "scroll", n: 240 });
    await sleep(WARMUP_MS);
    const stamps = [];
    const top0 = sc.scrollTop;
    log({ t: "measure", stage: "scroll", n: 240 });
    const end = performance.now() + MEASURE_MS;
    while (performance.now() < end) {
      const ts = await nextFrame();
      sc.scrollTop += 6;
      stamps.push(ts);
    }
    log({ t: "step", stage: "scroll", n: 240, scrolled_px: Math.round(sc.scrollTop - top0), raf: S.frameStats(S.intervals(stamps), VSYNC) });
    stage.textContent = "";
    await sleep(COOLDOWN_MS);
  }

  // Tap-to-paint latency under the reference load. The runner taps the target with `input tap`;
  // Event Timing gives input delay + processing + presentation per interaction (what INP uses).
  async function input() {
    const WAIT_MS = 30000;
    stage.textContent = "";
    const tick = referenceLoad(0, W.mulberry32(SEED + 7));
    let running = true;
    const loop = (ts) => { if (running) { tick(ts); requestAnimationFrame(loop); } };
    requestAnimationFrame(loop);
    const tap = document.createElement("div");
    tap.id = "tap";
    tap.textContent = "off";
    document.body.appendChild(tap);
    const durations = new Map();
    let po = null;
    const hasET = typeof PerformanceEventTiming !== "undefined";
    if (hasET) {
      po = new PerformanceObserver((list) => {
        for (const e of list.getEntries()) {
          if (!e.interactionId) continue;
          durations.set(e.interactionId, Math.max(durations.get(e.interactionId) || 0, e.duration));
        }
      });
      po.observe({ type: "event", durationThreshold: 16, buffered: true });
    }
    const approx = [];
    let taps = 0;
    tap.addEventListener("pointerdown", (ev) => {
      taps++;
      tap.classList.toggle("on");
      tap.textContent = tap.classList.contains("on") ? "on" : "off";
      for (const v of stage.querySelectorAll(".card .value")) v.textContent = tap.textContent;
      const t0 = ev.timeStamp;
      requestAnimationFrame(() => requestAnimationFrame((t2) => approx.push(t2 - t0)));
    });
    await sleep(WARMUP_MS);
    const r = tap.getBoundingClientRect();
    const dpr = devicePixelRatio;
    log({ t: "input-ready", taps: TAPS, x: Math.round((r.left + r.width / 2) * dpr), y: Math.round((r.top + r.height / 2) * dpr) });
    const end = performance.now() + WAIT_MS;
    while (taps < TAPS && performance.now() < end) await sleep(100);
    await sleep(500);
    running = false;
    if (po) { for (const e of po.takeRecords()) if (e.interactionId) durations.set(e.interactionId, Math.max(durations.get(e.interactionId) || 0, e.duration)); po.disconnect(); }
    // Event Timing skips interactions under its 16 ms threshold; count those as 16 ms (an upper bound).
    const values = hasET ? Array.from(durations.values()) : approx.slice();
    if (hasET) while (values.length < taps) values.push(16);
    values.sort((a, b) => a - b);
    log({ t: "input", method: hasET ? "event-timing" : "raf2", taps, values: values.map((v) => Math.round(v)), inp_ms: values.length ? Math.round(values[values.length - 1]) : null });
    tap.remove();
    stage.textContent = "";
    await sleep(COOLDOWN_MS);
  }

  // The JS workload: WARMUP + RUNS state bursts (pure part from workload.js, then the DOM update
  // and a forced layout), payloads built before the clock starts.
  async function js() {
    const spec = W.JS_SPEC;
    status.textContent = "js";
    log({ t: "warmup", stage: "js", n: spec.runs });
    const payloads = [];
    for (let t = 0; t < spec.warmup + spec.runs; t++) payloads.push(W.makeStates(spec.seed, spec.entities, t));
    stage.textContent = "";
    const grid = document.createElement("div");
    grid.className = "grid";
    const nodes = new Map();
    stage.appendChild(grid);
    let prev = new Map(), sum = 0;
    const times = [];
    for (let t = 0; t < payloads.length; t++) {
      const t0 = performance.now();
      const r = W.burst(payloads[t], prev);
      for (const c of r.changed) {
        let el = nodes.get(c.id);
        if (!el) { el = document.createElement("div"); el.className = "card"; grid.appendChild(el); nodes.set(c.id, el); }
        el.textContent = c.text;
      }
      sum = (sum ^ r.checksum) >>> 0;
      void grid.offsetHeight;
      const dt = performance.now() - t0;
      if (t >= spec.warmup) times.push(dt);
      prev = r.next;
      await sleep(0);
    }
    times.sort((a, b) => a - b);
    const med = S.nearestRank(times, 0.5), p95 = S.nearestRank(times, 0.95);
    log({ t: "js", runs: times.length, median_ms: Math.round(med * 100) / 100, p95_ms: Math.round(p95 * 100) / 100, checksum: sum, expected: W.expectedChecksum(spec) });
    stage.textContent = "";
    await sleep(COOLDOWN_MS);
  }

  function pageLoad() {
    return new Promise((resolve) => {
      let lcp = null;
      try {
        new PerformanceObserver((l) => { for (const e of l.getEntries()) lcp = e.renderTime || e.startTime; })
          .observe({ type: "largest-contentful-paint", buffered: true });
      } catch (e) { lcp = null; }
      // After the load event, wait for the first paint to be reported (up to 3 s), then 300 ms
      // more so the largest paint is in too.
      const paintEntry = () => performance.getEntriesByName("first-contentful-paint")[0];
      const done = async () => {
        const end = performance.now() + 3000;
        while (!paintEntry() && lcp === null && performance.now() < end) await sleep(100);
        await sleep(300);
        const nav = performance.getEntriesByType("navigation")[0] || {};
        const fcp = (paintEntry() || {}).startTime;
        resolve({
          lcp_from: lcp === null ? (fcp === undefined ? null : "fcp") : "lcp",
          fetch_ms: Math.round(nav.responseEnd || 0),
          dcl_ms: Math.round(nav.domContentLoadedEventEnd || 0),
          load_ms: Math.round(nav.loadEventEnd || 0),
          fcp_ms: fcp === undefined ? null : Math.round(fcp),
          lcp_ms: lcp === null ? null : Math.round(lcp),
        });
      };
      if (document.readyState === "complete") done(); else addEventListener("load", done);
    });
  }

  async function main() {
    const load = await pageLoad();
    RUBRIC = await (await fetch("rubric.json")).json();
    log({
      t: "hello", seed: SEED, vsync_ms: VSYNC, ua: navigator.userAgent, dpr: devicePixelRatio,
      css: [innerWidth, innerHeight], cores: navigator.hardwareConcurrency || null, measure_ms: MEASURE_MS, load,
    });
    await js();
    await ladder();
    await runAnimated(referenceLoad, 1, "reference");
    await scroll();
    if (TAPS) await input();
    status.textContent = "done";
    hero.style.display = "";
    hero.textContent = "done";
    log({ t: "done" });
  }

  main().catch((e) => log({ t: "error", message: String(e && e.stack || e) }));
})();
