// node --test tools/perf/test/  (no dependencies)
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const S = require("../page/stats.js");
const W = require("../page/workload.js");
const cases = JSON.parse(readFileSync(new URL("./stats-cases.json", import.meta.url)));
const bandCases = JSON.parse(readFileSync(new URL("./band-cases.json", import.meta.url)));
const rubric = JSON.parse(readFileSync(new URL("../page/rubric.json", import.meta.url)));

for (const c of cases) {
  test(`frameStats matches perflib.frame_stats: ${c.name}`, () => {
    assert.deepEqual(S.frameStats(c.intervals, c.vsync_ms), c.expect);
  });
}

test("a frame that missed one vsync counts as jank; jitter around one vsync does not", () => {
  assert.equal(S.frameStats([17.4, 15.9, 16.2, 17.1], 1000 / 60).jank_pct, 0);
  assert.equal(S.frameStats([16.7, 16.7, 33.4, 16.7], 1000 / 60).jank_pct, 25);
});

test("intervals are differences of consecutive timestamps", () => {
  assert.deepEqual(S.intervals([0, 16, 33, 66]), [16, 17, 33]);
});

test("the seeded states payload depends only on the seed and the tick", () => {
  const a = W.makeStates(89, 150, 3);
  assert.equal(a, W.makeStates(89, 150, 3));
  assert.notEqual(a, W.makeStates(89, 150, 4));
  assert.equal(JSON.parse(a).length, 150);
});

test("a burst reports only entities whose text changed", () => {
  const first = W.burst(W.makeStates(89, 150, 0), new Map());
  assert.equal(first.changed.length, 150);
  const again = W.burst(W.makeStates(89, 150, 0), first.next);
  assert.equal(again.changed.length, 0);
  const next = W.burst(W.makeStates(89, 150, 1), first.next);
  assert.ok(next.changed.length > 0 && next.changed.length < 150);
});

test("the JS workload checksum is pinned (the page reports it; a mismatch means the work changed)", () => {
  assert.equal(W.expectedChecksum(W.JS_SPEC), 4197945554);
});

for (const c of bandCases) {
  test(`the ladder stops on the grader's rule (grade.frame_band): ${c.name}`, () => {
    assert.equal(S.frameBand(c.stats, rubric), c.band);
  });
}

test("the measured window leaves SurfaceFlinger's history room for the read after it", () => {
  assert.equal(S.measureMs(1000 / 60), 1666);
  assert.equal(S.measureMs(1000 / 90), 1111);
  assert.equal(S.measureMs(1000 / 120), 833);
  for (const hz of [50, 60, 75, 90, 120, 144]) {
    assert.ok(127 - S.measureMs(1000 / hz) / (1000 / hz) >= 26, `${hz} Hz`);
  }
});

// page/tiers.json: the one source for the fake HA (dashboards.py) and the harness.
const T = JSON.parse(readFileSync(new URL("../page/tiers.json", import.meta.url)));
// A camera card, or a grid of them, is one card (#174).
T.cardCount = (t) => { const s = T.TIERS[t]; return s.tiles + s.entities + s.glances + s.longLists + s.sensors + s.histories + s.pictures + 1 + T.CAMERAS[t].length; };

test("the three reference dashboards are fixed: light, typical, heavy", () => {
  assert.deepEqual(T.ORDER, ["light", "typical", "heavy"]);
  assert.deepEqual(T.TIERS.light, { tiles: 12, entities: 4, glances: 1, rows: 5, longLists: 0, longRows: 0, sensors: 2, histories: 0, pictures: 0 });
  assert.deepEqual(T.TIERS.typical, { tiles: 26, entities: 11, glances: 2, rows: 6, longLists: 0, longRows: 0, sensors: 6, histories: 2, pictures: 1 });
  assert.deepEqual(T.TIERS.heavy, { tiles: 50, entities: 22, glances: 4, rows: 8, longLists: 4, longRows: 20, sensors: 16, histories: 8, pictures: 13 });
  // #174: the camera cards take pictures' places. Light none; typical one 720p; heavy one 720p and a
  // grid of three 360p (a main stream and sub-streams), at 15 fps.
  assert.deepEqual(T.CAMERAS, { start: ["360p"], light: [], typical: ["720p"], heavy: ["720p", ["360p", "360p", "360p"]] });
  assert.equal(T.CAMERA.fps, 15);
  assert.equal(T.GLANCE_ENTITIES, 5);
  assert.deepEqual(T.LIVE, { updatesPerS: 5, flipEveryMs: 2000, clockEveryMs: 1000, tileTransitionMs: 180 });
});

test("every page has the same first screen, so every page gets the same update stream", () => {
  assert.deepEqual(T.HEAD, { sensorTiles: 4, switchTiles: 3, rows: 5 });
  for (const t of T.ORDER) {
    assert.ok(T.TIERS[t].tiles >= 1 + T.HEAD.sensorTiles + T.HEAD.switchTiles, `${t} has the toggle and the head's tiles`);
    assert.ok(T.TIERS[t].entities >= 1, `${t} has the head's entities card`);
  }
});

test("about 20, 50 and 120 cards", () => {
  assert.deepEqual(T.ORDER.map(T.cardCount), [20, 50, 120]);
});

test("each reference dashboard has at least everything the one below it has", () => {
  for (const [lo, hi] of [["light", "typical"], ["typical", "heavy"]]) {
    for (const [k, v] of Object.entries(T.TIERS[lo])) assert.ok(T.TIERS[hi][k] >= v, `${hi}.${k} >= ${lo}.${k}`);
  }
});

test("the pages' protocol is the one rubric.json's rules are for", () => {
  assert.equal(T.PROTOCOL, rubric.protocol_version);
});

test("page lines fit the 1023 bytes the S1 keeps of a console line (smoke7)", () => {
  assert.deepEqual(S.consoleLines("GWPERF", "n:1", '{"t":"done"}'), ['GWPERF {"t":"done"}']);
  const text = JSON.stringify({ t: "step", changes: Array.from({ length: 60 }, (_, k) => [k * 20, k * 20 + 0.4, 0, 0, k * 20 + 9]) });
  const lines = S.consoleLines("GWPERF", "abcdef12:42", text);
  assert.ok(text.length > 1023 && lines.length >= 2);
  for (const l of lines) assert.ok(Buffer.byteLength(l) <= 1023, `${Buffer.byteLength(l)} bytes`);
  const parts = lines.map((l) => l.match(/^GWPERF\+ abcdef12:42 (\d+)\/(\d+) (.*)$/s));
  assert.ok(parts.every((m, k) => m && Number(m[1]) === k && Number(m[2]) === lines.length));
  assert.equal(parts.map((m) => m[3]).join(""), text);
});

function hue(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const max = Math.max(r, g, b), d = max - Math.min(r, g, b);
  const h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return Math.round(((h * 60 + 360) % 360) * 10) / 10;
}

test("generated hues stay between the brand's sky and violet (no status colours, no ember)", () => {
  // glassworks.dev src/app/globals.css: --sky #38bdf8, --violet #a78bfa.
  const sky = hue("#38bdf8"), violet = hue("#a78bfa");
  assert.deepEqual([sky, violet], [198.4, 255.1]);
  assert.equal(S.skyToViolet(0), sky);
  for (const r of [0.25, 0.5, 0.999, 0.99999]) {
    const h = S.skyToViolet(r);
    assert.ok(h >= sky && h <= violet, `${r} -> ${h}`);
  }
});

test("non-ASCII lines split on bytes, never inside a character, and still fit 1023 bytes", () => {
  const text = JSON.stringify({ names: Array.from({ length: 200 }, (_, i) => `Küche ${i} 🌡 Wohnzimmer`) });
  assert.ok(text.length < 7000 && Buffer.byteLength(text) > text.length);   // multi-byte
  const lines = S.consoleLines("GWPERF", "k:1", text);
  for (const l of lines) assert.ok(Buffer.byteLength(l) <= 1023, `${Buffer.byteLength(l)} bytes`);
  const chunks = lines.map((l) => l.replace(/^GWPERF\+ k:1 \d+\/\d+ /, ""));
  for (const c of chunks) assert.ok(!c.includes("�"), "a character was cut");
  assert.equal(chunks.join(""), text);
});

// ---- "usable" (F1): each clause has a test that fails without it

function usableAfter(frames, headCards = 2) {
  let st = { last: "", stable: 0, done: false };
  for (const f of frames) {
    st = S.usableStep(f, st, headCards);
    if (st.done) return true;
  }
  return false;
}
const drawn = (top) => ({ rendered: true, top });
const ok = { launch: false, viewIndex: 2, wantIndex: 2, head: [{ rendered: true }, { rendered: true }], onScreen: [drawn(0), drawn(120)] };

test("usable: two frames in a row with everything drawn and nothing moving", () => {
  assert.equal(usableAfter([ok]), false);                 // one frame isn't enough
  assert.equal(usableAfter([ok, ok]), true);
});

test("usable: not while HA's launch screen is up", () => {
  const f = { ...ok, launch: true };
  assert.equal(usableAfter([f, f, f]), false);
});

test("usable: not before hui-root shows the view that was asked for", () => {
  const f = { ...ok, viewIndex: 1 };
  assert.equal(usableAfter([f, f, f]), false);
});

test("usable: not while a head card is missing or not drawn, even if every card on screen is", () => {
  const missing = { ...ok, head: [{ rendered: true }] };
  assert.equal(usableAfter([missing, missing, missing]), false);
  const blank = { ...ok, head: [{ rendered: true }, { rendered: false }] };
  assert.equal(usableAfter([blank, blank, blank]), false);
});

test("usable: not while a card on the screen isn't drawn, or there are none", () => {
  const blank = { ...ok, onScreen: [drawn(0), { rendered: false, top: 120 }] };
  assert.equal(usableAfter([blank, blank, blank]), false);
  const none = { ...ok, onScreen: [] };
  assert.equal(usableAfter([none, none, none]), false);
});

test("usable: not while the layout moves", () => {
  const moved = { ...ok, onScreen: [drawn(0), drawn(160)] };
  assert.equal(usableAfter([ok, moved, ok, moved]), false);
  const more = { ...ok, onScreen: [drawn(0), drawn(120), drawn(240)] };
  assert.equal(usableAfter([ok, more, more]), true);      // stable again from the second `more`
});

test("free: every probe within 50 ms for the whole window, from when the stretch began", () => {
  // A long probe (80 ms), a short one that starts a stretch, a long one that ends it, then short
  // probes of 40 ms from 305 on: free from 305, done once 500 ms of them have run.
  const probes = [[0, 80], [80, 85], [85, 300], [300, 305]];
  for (let t = 305; t < 900; t += 40) probes.push([t, t + 40]);
  let st = { start: null, done: false };
  const seen = [];
  for (const [p, r] of probes) {
    st = S.freeStep(p, r, st.start, 500);
    seen.push(st.start);
    if (st.done) break;
  }
  assert.deepEqual(seen.slice(0, 5), [null, 85, null, 305, 305]);
  assert.equal(st.done, true);
  assert.equal(st.start, 305);
  assert.ok(probes.findIndex(([, r]) => r - 305 >= 500) === seen.length - 1);   // done at the first probe 500 ms on
  assert.equal(S.freeStep(0, 51, 0, 1).done, false);       // a probe over 50 ms is never free
});

// ---- the camera streams' HLS fetches (smoke-cam1): where a late segment comes from

test("hlsFetches: segments and playlists apart, queueing, the longest gap per stream", () => {
  const e = (path, start, req, first, end, size = 0) =>
    ({ name: `http://127.0.0.1:8089${path}`, startTime: start, fetchStart: start, requestStart: req, responseStart: first, responseEnd: end, transferSize: size });
  const out = S.hlsFetches([
    e("/api/hls/clarity000001/segment/3.m4s", 0, 5, 20, 40, 2048),
    e("/api/hls/clarity000001/segment/4.m4s", 1000, 1900, 1910, 1950, 2048),   // waited 900 ms for a connection
    e("/api/hls/clarity000002/segment/9.m4s", 500, 501, 510, 520, 1024),
    e("/api/hls/clarity000001/playlist.m3u8", 900, 901, 905, 906, 300),
    e("/api/websocket", 0, 1, 2, 3),                                            // not a stream's
  ]);
  assert.equal(out.streams, 2);
  assert.equal(out.segment.n, 3);
  assert.equal(out.playlist.n, 1);
  assert.deepEqual(out.segment.queued_ms, { median: 5, p95: 900, max: 900 });
  assert.equal(out.segment.arrival_gap_max_ms, 1910);   // stream 1: 40 -> 1950; stream 2 has one segment
  assert.equal(out.segment.kb, 5);
  // requestStart 0 (no detailed timing): not counted as 0 ms of queueing.
  assert.equal(S.hlsFetches([e("/api/hls/a/segment/1.m4s", 0, 0, 0, 10)]).segment.queued_ms, null);
});

// ---- a card made of cards (#174's grid of camera sub-streams): drawn when it and every card in it is

function el({ height = 0, shadow = null } = {}) {
  return { offsetHeight: height, shadowRoot: shadow, firstElementChild: null };
}
function root(children) {   // a shadow root holding these elements
  return {
    querySelectorAll: (sel) => children.filter((c) => c.tag === sel),
    querySelector: (sel) => children.find((c) => c.tag === sel) || null,
  };
}
function card(inner) { return { tag: "hui-card", firstElementChild: inner }; }
function plain(drawn) { return card(el({ height: 50, shadow: root([{ tag: "ha-card", offsetHeight: drawn ? 40 : 0 }]) })); }
function grid(height, kids) { return card(el({ height, shadow: root(kids) })); }

test("cardRendered: a card is drawn when its ha-card is laid out", () => {
  assert.equal(S.cardRendered(plain(true)), true);
  assert.equal(S.cardRendered(plain(false)), false);
  assert.equal(S.cardRendered(card(el({ height: 50 }))), false);           // no shadow root yet
  assert.equal(S.cardRendered(card(null)), false);
});

test("cardRendered: a card of cards needs itself laid out and every card in it drawn", () => {
  assert.equal(S.cardRendered(grid(120, [plain(true), plain(true), plain(true)])), true);
  assert.equal(S.cardRendered(grid(120, [plain(true), plain(false), plain(true)])), false);   // one not drawn
  assert.equal(S.cardRendered(grid(0, [plain(true), plain(true)])), false);                   // the grid not laid out
  assert.equal(S.cardRendered(grid(120, [grid(60, [plain(true)]), plain(true)])), true);       // nested grids
  assert.equal(S.cardRendered(grid(120, [grid(60, [plain(false)])])), false);
});

// ---- a stream's gaps (#174), and presentedFrames as a cross-check on the stalls (#182's verifier)

test("videoGaps: the longest gap and the stalls, the window's edges included", () => {
  const times = [100, 166, 233, 1300, 1366];                 // a 1067 ms gap between 233 and 1300
  const g = S.videoGaps(times, null, 0, 1500);
  assert.equal(g.max_gap_ms, 1067);
  assert.equal(g.stall_total_ms, 1067);
  assert.equal(g.stall_presented, null);                       // without rVFC's counts, no cross-check
  assert.equal(S.videoGaps([50, 900], null, 0, 1000).stall_total_ms, 850);   // the gap from 50 to 900
});

test("videoGaps: frames presented inside a stall say the callbacks stalled, not the stream", () => {
  const times = [100, 166, 233, 1300, 1366];
  // The compositor went on: 16 frames presented between the callbacks at 233 and 1300.
  assert.equal(S.videoGaps(times, [10, 11, 12, 28, 29], 0, 1500).stall_presented, 15);
  // The stream really stopped: presentedFrames moved by one.
  assert.equal(S.videoGaps(times, [10, 11, 12, 13, 14], 0, 1500).stall_presented, 0);
  // The graded numbers don't change with the cross-check.
  assert.equal(S.videoGaps(times, [10, 11, 12, 28, 29], 0, 1500).stall_total_ms, 1067);
});
