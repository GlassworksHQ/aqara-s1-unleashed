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

test("the measured window fits SurfaceFlinger's 127-frame history at any refresh rate", () => {
  assert.equal(S.measureMs(1000 / 60), 2000);
  assert.equal(S.measureMs(1000 / 90), 1333);
  assert.equal(S.measureMs(1000 / 120), 1000);
  for (const hz of [50, 60, 75, 90, 120, 144]) {
    assert.ok(S.measureMs(1000 / hz) / (1000 / hz) <= 121, `${hz} Hz`);
  }
});
