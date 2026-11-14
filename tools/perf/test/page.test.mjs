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
// A view without its cameras (PLAIN, rubric v10) has its base view's counts, a still picture per camera.
const base = (t) => (T.PLAIN || {})[t] || t;
T.cardCount = (t) => { const s = T.TIERS[base(t)]; return s.tiles + s.entities + s.glances + s.longLists + s.sensors + s.histories + s.pictures + 1 + T.CAMERAS[base(t)].length; };

test("the reference dashboards are fixed: light, typical without and with its camera, heavy", () => {
  assert.deepEqual(T.ORDER, ["light", "typical-nocam", "typical", "heavy"]);
  assert.deepEqual(T.PLAIN, { "typical-nocam": "typical" });
  assert.deepEqual(T.TIERS.light, { tiles: 12, entities: 4, glances: 1, rows: 5, longLists: 0, longRows: 0, sensors: 2, histories: 0, pictures: 0 });
  assert.deepEqual(T.TIERS.typical, { tiles: 26, entities: 11, glances: 2, rows: 6, longLists: 0, longRows: 0, sensors: 6, histories: 2, pictures: 1 });
  assert.deepEqual(T.TIERS.heavy, { tiles: 50, entities: 22, glances: 4, rows: 8, longLists: 4, longRows: 20, sensors: 16, histories: 8, pictures: 13 });
  // #174: the camera cards take pictures' places. Light none; typical one 720p; heavy one 720p and a
  // grid of three 360p (a main stream and sub-streams), at 15 fps.
  assert.deepEqual(T.CAMERAS, { start: ["360p"], light: [], typical: ["720p"], "typical-nocam": [], heavy: ["720p", ["360p", "360p", "360p"]] });
  assert.equal(T.CAMERA.fps, 15);
  assert.equal(T.GLANCE_ENTITIES, 5);
  assert.deepEqual(T.LIVE, { updatesPerS: 5, flipEveryMs: 2000, clockEveryMs: 1000, tileTransitionMs: 180 });
});

test("every page has the same first screen, so every page gets the same update stream", () => {
  assert.deepEqual(T.HEAD, { sensorTiles: 4, switchTiles: 3, rows: 5 });
  for (const t of T.ORDER) {
    assert.ok(T.TIERS[base(t)].tiles >= 1 + T.HEAD.sensorTiles + T.HEAD.switchTiles, `${t} has the toggle and the head's tiles`);
    assert.ok(T.TIERS[base(t)].entities >= 1, `${t} has the head's entities card`);
  }
});

test("about 20, 50 and 120 cards", () => {
  assert.deepEqual(T.ORDER.map(T.cardCount), [20, 50, 50, 120]);
});

test("each reference dashboard has at least everything the one below it has", () => {
  for (const [lo, hi] of [["light", "typical"], ["typical", "heavy"]]) {
    for (const [k, v] of Object.entries(T.TIERS[lo])) assert.ok(T.TIERS[hi][k] >= v, `${hi}.${k} >= ${lo}.${k}`);
  }
});

test("the pages' protocol is the one rubric.json's rules are for", () => {
  assert.equal(T.PROTOCOL, rubric.protocol_version);
  // Clarity v1 (2026-11-14): the pages and the rules carry the same version marker.
  assert.equal(T.CLARITY, rubric.clarity);
  assert.deepEqual([T.CLARITY, T.PROTOCOL, rubric.version], [2, 2, 2]);
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

// ---- a WebRTC camera's receiver counts (#202): getStats samples over the camera window, every receiver

const rx = (id, ssrc, n, extra = {}) => ({ id, ssrc, type: "inbound-rtp", kind: "video", framesReceived: n + 1, framesDecoded: n,
                                           framesDropped: Math.floor(n / 50), freezeCount: Math.floor(n / 100), ...extra });
const at = (t, ...pcs) => ({ t, pcs: pcs.map(([pc, reports]) => ({ pc, reports })) });

test("webrtcWindow: one receiver over the window, its decoder, codec and candidate pair", () => {
  const end = [
    rx("IT1", 7, 128, { codecId: "C1", jitterBufferDelay: 7, jitterBufferEmittedCount: 129, freezeCount: 1, totalFreezesDuration: 0.4,
                        decoderImplementation: "ExternalDecoder", powerEfficientDecoder: true }),
    { id: "IA1", type: "inbound-rtp", kind: "audio", framesDecoded: 999 },
    { id: "C1", type: "codec", mimeType: "video/H264", sdpFmtpLine: "packetization-mode=1;profile-level-id=42e01f" },
    { id: "T1", type: "transport", selectedCandidatePairId: "P1" },
    { id: "P1", type: "candidate-pair", localCandidateId: "L1", state: "succeeded" },
    { id: "L1", type: "local-candidate", candidateType: "relay", relayProtocol: "tcp", protocol: "udp" },
  ];
  const w = S.webrtcWindow([at(0, ["a", [rx("IT1", 7, 8, { jitterBufferDelay: 1, jitterBufferEmittedCount: 9, freezeCount: 0, totalFreezesDuration: 0 })]]),
                            at(8000, ["a", end])]);
  assert.deepEqual([w.frames_received, w.frames_decoded, w.frames_dropped, w.freezes, w.freeze_s], [120, 120, 2, 1, 0.4]);
  assert.equal(w.jitter_buffer_ms, 50);                    // 6 s over 120 frames
  assert.deepEqual([w.decoder, w.power_efficient, w.codec, w.sessions, w.receivers, w.no_video_ms],
                   ["ExternalDecoder", true, "video/H264", 1, 1, 0]);
  assert.deepEqual(w.candidate, { local: "relay", relay: "tcp", protocol: "udp" });
  // Fields an engine leaves out: null, never 0.
  const bare = S.webrtcWindow([at(0, ["a", [{ id: "I", type: "inbound-rtp", mediaType: "video", framesDecoded: 5 }]]),
                               at(1000, ["a", [{ id: "I", type: "inbound-rtp", mediaType: "video", framesDecoded: 20 }]])]);
  assert.deepEqual([bare.frames_decoded, bare.freezes, bare.decoder, bare.power_efficient, bare.candidate], [15, null, null, null, null]);
  assert.equal(S.webrtcWindow([]), null);
  assert.deepEqual(S.webrtcWindow([at(0, ["a", [{ id: "A", type: "inbound-rtp", kind: "audio" }]])]), { inbound: false, sessions: 0, receivers: 0 });
});

test("webrtcWindow: a second session mid-window counts from 0, and two receivers are never subtracted", () => {
  // The S1's heavy view (smoke3): the first connection's ICE failed, HA's player made a new session;
  // in Gecko the new receiver's stats can even reuse the old id. The old diff read -189 frames.
  const w = S.webrtcWindow([
    at(0, ["pc1", [rx("IT01V", 11, 300)]]),
    at(1000, ["pc1", [rx("IT01V", 11, 315)]]),
    at(2000, ["pc1", [rx("IT01V", 11, 315)]]),          // ICE failed: nothing decoded
    at(3000),                                          // no connection carries the video
    at(4000, ["pc2", [rx("IT01V", 22, 6)]]),           // the new session, same stats id, from 0
    at(5000, ["pc2", [rx("IT01V", 22, 21)]]),
  ]);
  assert.equal(w.frames_decoded, 15 + 21);
  assert.equal(w.sessions, 2);
  assert.equal(w.receivers, 2);
  assert.equal(w.no_video_ms, 2000);                   // 1 to 2 s and 2 to 3 s; 3 to 4 s decoded 6
  // The same connection renegotiated (restartIce): same key, counters back to low values.
  const reneg = S.webrtcWindow([at(0, ["pc1", [rx("IT01V", 11, 300)]]), at(1000, ["pc1", [rx("IT01V", 11, 330)]]),
                                at(2000, ["pc1", [rx("IT01V", 11, 12)]])]);
  assert.deepEqual([reneg.frames_decoded, reneg.sessions], [30 + 12, 1]);
});

test("webrtcWindow: a receiver gone by the window's end keeps what it counted", () => {
  const w = S.webrtcWindow([at(0, ["pc1", [rx("IT1", 1, 10)]]), at(1000, ["pc1", [rx("IT1", 1, 25)]]), at(2000), at(3000)]);
  assert.deepEqual([w.inbound, w.frames_decoded, w.no_video_ms], [true, 15, 2000]);
});

test("webrtcWindow: no count is ever negative, whatever the receivers do", () => {
  let seed = 7;
  const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed % n; };
  for (let trial = 0; trial < 200; trial++) {
    const samples = [];
    for (let i = 0; i < 12; i++) {
      const pcs = [];
      for (const pc of ["pc1", "pc2", "pc3"]) if (rnd(3)) pcs.push([pc, [rx(rnd(2) ? "X" : "Y", rnd(3), rnd(500))]]);
      samples.push(at(i * 1000, ...pcs));
    }
    const w = S.webrtcWindow(samples);
    for (const k of ["frames_received", "frames_decoded", "frames_dropped", "freezes", "no_video_ms"]) {
      assert.ok(w[k] === null || w[k] === undefined || w[k] >= 0, `${k} ${w[k]} in trial ${trial}`);
    }
  }
});

// ---- frames shown (#202's verifier): Gecko's getVideoPlaybackQuality reads 0 for a MediaStream video

test("shownFrames: WebRTC's own counts first; a quality that reads 0 while frames came is not reported", () => {
  const gecko = { quality: { decoded: 0, dropped: 0 }, callbacks: 70, rvfc: true };
  // GeckoView with the receiver's counts: frames decoded (its dropped ones never were), never the zeroed quality.
  assert.deepEqual(S.shownFrames({ ...gecko, webrtc: { frames_decoded: 75, frames_dropped: 2 } }), { frames: 75, source: "webrtc" });
  // Without them: the 0 is "not reported", and rVFC's callbacks count instead.
  assert.deepEqual(S.shownFrames({ ...gecko, webrtc: null }), { frames: 70, source: "rvfc" });
  assert.deepEqual(S.shownFrames({ ...gecko, webrtc: { inbound: false } }), { frames: 70, source: "rvfc" });
  // The S1's heavy, smoke 4: 1090 decoded and 690 dropped are 1090 shown, not 400.
  assert.deepEqual(S.shownFrames({ quality: null, webrtc: { frames_decoded: 1090, frames_dropped: 690 }, callbacks: 0, rvfc: true }),
                   { frames: 1090, source: "webrtc" });
  // HLS: the decoder's count, as before; a real 0 (nothing came either) stays 0, never rVFC's.
  assert.deepEqual(S.shownFrames({ quality: { decoded: 77, dropped: 1 }, webrtc: null, callbacks: 60, rvfc: true }), { frames: 76, source: "quality" });
  assert.deepEqual(S.shownFrames({ quality: { decoded: 0, dropped: 0 }, webrtc: null, callbacks: 0, rvfc: true }), { frames: 0, source: "quality" });
  assert.deepEqual(S.shownFrames({ quality: null, webrtc: null, callbacks: 0, rvfc: false }), { frames: null, source: "none" });
  // More dropped than decoded (a stream that hardly decoded): the decoded ones, never fewer than 0.
  assert.deepEqual(S.shownFrames({ quality: null, webrtc: { frames_decoded: 10, frames_dropped: 40 }, callbacks: 3, rvfc: true }),
                   { frames: 10, source: "webrtc" });
});

// ---- protocol 8 (#202, s1rtc4): a WebRTC camera's gaps and stalls are the receiver's own

const steady = (t, n, freezes = 0, frozen = 0, extra = {}) =>
  at(t, ["pc1", [{ id: "IT1", ssrc: 1, type: "inbound-rtp", kind: "video", framesDecoded: n, freezeCount: freezes,
                   totalFreezesDuration: frozen, ...extra }]]);

test("webrtcWindow: steady decoding with no freeze has no stall, and its longest gap is the freeze rule's bound", () => {
  // 30 s at 15 fps, sampled each second.
  const w = S.webrtcWindow(Array.from({ length: 31 }, (_, k) => steady(k * 1000, 15 * k)));
  assert.equal(w.stall_ms, 0);
  assert.equal(w.max_gap_bound, true);
  assert.equal(w.max_gap_ms, 216.67);              // max(3 x 66.7, 66.7 + 150): every gap was under it
  assert.equal(w.span_ms, 30000);
});

test("webrtcWindow: a freeze is a stall at its full length, and the longest gap", () => {
  const smp = [steady(0, 0), steady(1000, 15), steady(2000, 25, 1, 0.8), steady(3000, 40, 1, 0.8)];
  const w = S.webrtcWindow(smp);
  assert.equal(w.stall_ms, 800);
  assert.equal(w.max_gap_ms, 800);
  assert.equal(w.max_gap_bound, false);
  // A pause (the stream inactive) counts the same way.
  const p = S.webrtcWindow([steady(0, 0, 0, 0, { pauseCount: 0, totalPausesDuration: 0 }),
                            steady(1000, 5, 0, 0, { pauseCount: 1, totalPausesDuration: 0.6 })]);
  assert.equal(p.stall_ms, 600);
});

test("webrtcWindow: time with nothing decoded is a stall, and a freeze ending it isn't counted twice", () => {
  // Nothing from 1 s to 3 s, then the first frame ends a 2.2 s freeze.
  const w = S.webrtcWindow([steady(0, 0), steady(1000, 15), steady(2000, 15), steady(3000, 15), steady(4000, 30, 1, 2.2)]);
  assert.equal(w.no_video_ms, 2000);
  assert.equal(w.stall_ms, 2200);                  // 2000 with nothing, and the 200 ms of the freeze before them
  assert.equal(w.max_gap_ms, 2200);
});

test("s1rtc4's heavy: frame callbacks starved by a busy page, the receiver decoding steadily: graded as playing", async () => {
  // The page's frame callbacks: one every 1.2 s for 147 s (127 of them, as on the S1), while the
  // compositor presented every frame (presentedFrames moved by 18 each time) ...
  const times = Array.from({ length: 123 }, (_, k) => 1000 + k * 1200), pfs = times.map((_, k) => 18 * k);
  const cb = S.videoGaps(times, pfs, 0, 147500);
  assert.ok(cb.stall_total_ms > 140000, "the callbacks alone read as a 2-minute stall");
  assert.ok(cb.stall_presented > 2000, "but the compositor presented frames inside it");
  // ... and the receiver decoded 15 a second, with 2 freezes, 0.49 s in all (s1rtc4 run 2, camera 0).
  const smp = Array.from({ length: 31 }, (_, k) => steady(k * 1000, 15 * k, k >= 20 ? 2 : k >= 10 ? 1 : 0, k >= 20 ? 0.49 : k >= 10 ? 0.25 : 0));
  const w = S.webrtcWindow(smp);
  assert.equal(w.stall_ms, 490);
  assert.equal(w.max_gap_ms, 250);
  const shown = S.shownFrames({ quality: { decoded: 0, dropped: 0 }, webrtc: w, callbacks: times.length, rvfc: true });
  assert.equal(shown.source, "webrtc");
  // The camera bars (rubric.json camera_levels): playing, not stalled.
  const run = { first_frame_ms: 2281, shown_pct: Math.min(100, (100 * shown.frames) / (15 * w.span_ms / 1000)),
                max_gap_ms: w.max_gap_ms, stall_total_ms: w.stall_ms };
  const G = await import("../grade.ts");
  assert.notEqual(G.cameraLevel(run, rubric), rubric.grade.below);
  assert.equal(G.cameraLevel(Object.assign({}, run, { max_gap_ms: cb.max_gap_ms, stall_total_ms: cb.stall_total_ms }), rubric),
               rubric.grade.below, "graded on the callbacks it would have failed");
});

test("webrtcWindow: two samples a moment apart (the window's end) aren't a gap", () => {
  const w = S.webrtcWindow([steady(0, 0), steady(1000, 15), steady(1000.4, 15)]);
  assert.equal(w.stall_ms, 0);
  assert.equal(w.max_gap_bound, true);
});

test("pacedSleep: a wait ends on the first frame past its time when the timers are held back", async () => {
  // s1rtc4's heavy: the page's timers queued behind the frontend's work, its frames kept coming.
  let t = 0;
  const frames = [];
  const env = { now: () => t, setTimeout: () => {}, requestAnimationFrame: (f) => frames.push(f) };
  let done = false;
  S.pacedSleep(1000, env).then(() => { done = true; });
  for (let k = 0; k < 8 && !done; k++) {         // a frame every 200 ms; the timer never fires
    t += 200;
    frames.splice(0).forEach((f) => f());
    await Promise.resolve();
  }
  assert.equal(done, true);
  assert.equal(t, 1000);
  // No frames (a hidden page): the timer ends it.
  let fired = null;
  const quiet = { now: () => 0, setTimeout: (f, ms) => { fired = ms; f(); }, requestAnimationFrame: () => {} };
  await S.pacedSleep(500, quiet);
  assert.equal(fired, 500);
});

// ---- #205's verifier: freezes and pauses, late answers, receivers without freeze counts

test("webrtcWindow: one outage reported as both a freeze and a pause counts once", () => {
  const w = S.webrtcWindow([steady(0, 0, 0, 0, { pauseCount: 0, totalPausesDuration: 0 }),
                            steady(1000, 15, 0, 0, { pauseCount: 0, totalPausesDuration: 0 }),
                            steady(3000, 30, 1, 2.0, { pauseCount: 1, totalPausesDuration: 2.0 })]);
  assert.equal(w.stall_ms, 2000);                  // was 4000: the sum of the two counters
  assert.equal(w.max_gap_ms, 2000);
  assert.equal(w.gaps, 1);
  // Two receivers' outages still add up.
  const two = (t, n, f, fd) => at(t, ["pc1", [
    { id: "A", ssrc: 1, type: "inbound-rtp", kind: "video", framesDecoded: n, freezeCount: f, totalFreezesDuration: fd },
    { id: "B", ssrc: 2, type: "inbound-rtp", kind: "video", framesDecoded: n, freezeCount: f, totalFreezesDuration: fd }]]);
  assert.equal(S.webrtcWindow([two(0, 0, 0, 0), two(1000, 15, 1, 0.5)]).stall_ms, 1000);
});

test("webrtcWindow: intervals run on the reports' own timestamps, so a late answer counts nothing past them", () => {
  // Asked at 0 and 1000 ms; the second answer came late, but its counts were taken at 1000 ms
  // (Gecko reads them on its call thread). The ask times say so, and so do the reports.
  const stamped = (t, ts, n) => at(t, ["pc1", [{ id: "IT1", ssrc: 1, type: "inbound-rtp", kind: "video", framesDecoded: n,
                                                  freezeCount: 0, totalFreezesDuration: 0, timestamp: ts }]]);
  const w = S.webrtcWindow([stamped(0, 5_000_000, 0), stamped(1000, 5_002_000, 30)]);
  assert.equal(w.clock, "stats");
  assert.equal(w.span_ms, 2000);                   // the reports' span, not the asks'
  const shown = S.shownFrames({ quality: null, webrtc: w, callbacks: 0, rvfc: true });
  assert.equal(Math.round((100 * shown.frames) / (15 * w.span_ms / 1000)), 100);   // 30 frames in 2 s at 15 fps
  // Without timestamps on every sample: the ask times.
  assert.equal(S.webrtcWindow([steady(0, 0), steady(1000, 15)]).clock, "asked");
});

test("webrtcWindow: a receiver without freeze counts has stalls n/a, not 0", () => {
  const bare = (t, n) => at(t, ["pc1", [{ id: "IT1", ssrc: 1, type: "inbound-rtp", kind: "video", framesDecoded: n }]]);
  const w = S.webrtcWindow([bare(0, 0), bare(1000, 15), bare(2000, 30)]);
  assert.equal(w.stalls_reported, false);
  assert.equal(w.stall_ms, null);
  assert.equal(w.max_gap_ms, null);
  assert.equal(w.max_gap_bound, false);
});

// ---- rubric 2 (Clarity v2): the main thread's blocking from the first screen drawn to usable

test("frameBlocking: each frame interval past 50 ms counts, as TBT counts a long task's", () => {
  // Drawn at 1000, usable at 1480: frames 16 ms apart, but one 300 ms late (250 blocking) and one
  // 120 ms late (70 blocking).
  const b = S.frameBlocking([1016, 1032, 1332, 1348, 1468], 1000, 1480);
  assert.deepEqual(b, { blocking_ms: 320, long_frames: 2, longest_ms: 300 });
  // Short frames only: none.
  assert.equal(S.frameBlocking([1016.7, 1033.4, 1050.1], 1000, 1060).blocking_ms, 0);
  // The edges count: nothing drawn between draw and usable for 400 ms is 350 ms of blocking.
  assert.equal(S.frameBlocking([], 1000, 1400).blocking_ms, 350);
  assert.equal(S.frameBlocking([1], null, 2), null);
});
