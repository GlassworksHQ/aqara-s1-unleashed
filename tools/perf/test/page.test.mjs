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

const T = require("../page/tiers.js");

test("the three reference dashboards are fixed: light, typical, heavy", () => {
  assert.deepEqual(T.ORDER, ["light", "typical", "heavy"]);
  assert.deepEqual(T.TIERS.light, { tiles: 12, entities: 5, rows: 5, longLists: 0, longRows: 0, sensors: 2, histories: 0, pictures: 0 });
  assert.deepEqual(T.TIERS.typical, { tiles: 26, entities: 13, rows: 6, longLists: 0, longRows: 0, sensors: 6, histories: 2, pictures: 2 });
  assert.deepEqual(T.TIERS.heavy, { tiles: 50, entities: 26, rows: 8, longLists: 4, longRows: 20, sensors: 16, histories: 8, pictures: 15 });
  assert.deepEqual(T.LIVE, { updatesPerS: 5, flipEveryMs: 2000, clockEveryMs: 1000, tileTransitionMs: 180 });
});

test("every page has the same first screen, so every page gets the same update stream", () => {
  assert.deepEqual(T.HEAD, { sensorTiles: 4, switchTiles: 3, rows: 5, tiles: 7 });
  for (const t of T.ORDER) {
    assert.ok(T.TIERS[t].tiles >= 1 + T.HEAD.tiles, `${t} has the toggle and the head's tiles`);
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

test("generated hues stay between the brand's sky and violet (no status colours, no ember)", () => {
  for (const r of [0, 0.25, 0.5, 0.999]) {
    const h = S.skyToViolet(r);
    assert.ok(h >= 199 && h <= 258, `${r} -> ${h}`);
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
