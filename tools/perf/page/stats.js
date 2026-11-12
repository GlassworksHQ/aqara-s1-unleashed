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

  function band(value, rule) {
    if (value === null || value === undefined) return null;
    if ("poor_at" in rule && value >= rule.poor_at) return "poor";
    if ("poor_above" in rule && value > rule.poor_above) return "poor";
    return value <= rule.good_max ? "good" : "needs-improvement";
  }

  const ORDER = { good: 0, "needs-improvement": 1, poor: 2 };

  // The same rule as grade.frame_band: the worst band of p95, jank and frozen frames.
  function frameBand(stats, rubric) {
    if (!stats || !stats.n) return null;
    const m = rubric.metrics;
    const bands = [band(stats.p95_ms, m.frame_p95_ms), band(stats.jank_pct, m.frame_jank_pct), band(stats.frozen_pct, m.frame_frozen_pct)];
    return bands.reduce((a, b) => (ORDER[b] > ORDER[a] ? b : a));
  }

  // SurfaceFlinger keeps the last 127 presented frames, and the runner reads them after the window
  // ends. 100 refreshes leave 27 slots (450 ms at 60 Hz) for that read: 1.67 s at 60 Hz, 0.83 s at 120 Hz.
  function measureMs(vsyncMs) {
    return Math.floor(100 * (vsyncMs > 0 ? vsyncMs : BUDGET_MS));
  }

  function intervals(timestamps) {
    const out = [];
    for (let i = 1; i < timestamps.length; i++) out.push(timestamps[i] - timestamps[i - 1]);
    return out;
  }

  const api = { BUDGET_MS, FROZEN_MS, frameStats, intervals, nearestRank, band, frameBand, measureMs };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWStats = api;
})(this);
