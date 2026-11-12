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
    // Typical, plus the test page's old reference load: 24 chips of which 3 update every frame (so
    // each one every 8 frames), more spinners, a line chart redrawn every frame.
    busy: { updateEveryMs: 2000, updatesPerTick: 5, spinners: 10, pulses: 2, ring: true, clock: true,
            sparkline: true, frameTiles: 24, motion: true },
  };

  const ORDER = ["light", "typical", "busy"];

  // The page's phases, in ms. It stays still after its load event so its load can settle (750 ms of
  // no frames) and the kiosk's loading overlay is gone: the kiosk hides it at first paint, or 1.5 s
  // after the load if that was missed, with a 300 ms fade. Then it goes live: warm-up, one measured
  // window, then the taps.
  const PHASES = { stillAfterLoadEventMs: 2500, warmupMs: 1000, tapWaitMinMs: 30000 };

  const api = { TIERS, ORDER, PHASES };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWTiers = api;
})(this);
