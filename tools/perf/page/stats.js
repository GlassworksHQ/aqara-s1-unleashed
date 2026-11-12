/* Frame-time statistics for the Glassworks perf test (MIT).
 * The same definitions are in tools/perf/perflib.py (frame_stats); both are tested against
 * tools/perf/test/stats-cases.json, so the page and the runner agree to the millisecond.
 */
(function (root) {
  "use strict";

  const BUDGET_MS = 1000 / 60;
  const FROZEN_MS = 700;

  function nearestRank(sorted, p) {
    if (!sorted.length) return null;
    const i = Math.max(0, Math.ceil(p * sorted.length) - 1);
    return sorted[Math.min(i, sorted.length - 1)];
  }

  function round2(x) {
    return x === null ? null : Math.round(x * 100) / 100;
  }

  // Frame intervals are snapped to whole vsync periods before the percentile, so timer jitter on
  // a frame that made its vsync (16.9 ms on a 60 Hz display) does not count as over budget.
  function frameStats(intervalsMs, vsyncMs) {
    const period = vsyncMs > 0 ? vsyncMs : BUDGET_MS;
    const xs = intervalsMs.filter((x) => Number.isFinite(x) && x > 0);
    const n = xs.length;
    if (!n) return { n: 0 };
    const snapped = xs.map((x) => Math.max(1, Math.round(x / period)) * period);
    const raw = xs.slice().sort((a, b) => a - b);
    const q = snapped.slice().sort((a, b) => a - b);
    const sum = xs.reduce((a, b) => a + b, 0);
    const over = snapped.filter((x) => x > BUDGET_MS + 0.5).length;
    const frozen = xs.filter((x) => x > FROZEN_MS).length;
    return {
      n,
      median_ms: round2(nearestRank(raw, 0.5)),
      p95_ms: round2(nearestRank(q, 0.95)),
      p95_raw_ms: round2(nearestRank(raw, 0.95)),
      max_ms: round2(raw[n - 1]),
      fps: round2(1000 * n / sum),
      jank_pct: round2(100 * over / n),
      frozen_pct: round2(100 * frozen / n),
    };
  }

  function intervals(timestamps) {
    const out = [];
    for (let i = 1; i < timestamps.length; i++) out.push(timestamps[i] - timestamps[i - 1]);
    return out;
  }

  const api = { BUDGET_MS, FROZEN_MS, frameStats, intervals, nearestRank };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWStats = api;
})(this);
