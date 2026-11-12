/* The three reference dashboards the grade is measured on (MIT). Pure data, so node can pin it.
 * Fixed before any frame was graded; docs/perf.md says why each is shaped this way. Change one only
 * on purpose, with the docs and test/page.test.mjs, and never to move a particular frame's letter.
 */
(function (root) {
  "use strict";

  const TIERS = {
    // Cards, icons and text. A few states change every 2 s. Nothing animates.
    light: { updateEveryMs: 2000, updatesPerTick: 5, spinners: 0, pulses: 0, ring: false, clock: false,
             sparkline: false, frameTiles: 0, motion: false },
    // Light, plus a handful of animated elements like Home Assistant's defaults.
    typical: { updateEveryMs: 2000, updatesPerTick: 5, spinners: 4, pulses: 2, ring: true, clock: true,
               sparkline: false, frameTiles: 0, motion: true },
    // Typical, plus the test page's old reference load: tiles updating every frame, more spinners,
    // a line chart redrawn every frame.
    busy: { updateEveryMs: 2000, updatesPerTick: 5, spinners: 10, pulses: 2, ring: true, clock: true,
            sparkline: true, frameTiles: 24, motion: true },
  };

  const ORDER = ["light", "typical", "busy"];

  // The page's phases, in ms. It stays still after loading so its load can settle (750 ms of no
  // frames), then goes live: warm-up, one measured window, then the taps.
  const PHASES = { stillAfterLoadMs: 2000, warmupMs: 1000, tapWaitMinMs: 30000 };

  const api = { TIERS, ORDER, PHASES };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWTiers = api;
})(this);
