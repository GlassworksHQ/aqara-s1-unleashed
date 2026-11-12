/* The three reference dashboards the grade is measured on (MIT). Pure data, so node can pin it.
 * They differ in how much is on them, not in animation: mostly static, like Home Assistant, built
 * from HA's own card types (docs/perf.md, "The reference dashboards"). The counts and the update
 * rate are project choices, fixed before any frame was graded under them. Change one only on
 * purpose, with the docs and test/page.test.mjs, never to move a particular frame's letter.
 */
(function (root) {
  "use strict";

  // Cards per page. Every page also has a clock card, and a "Living room" toggle tile (one of the
  // tiles). entities: cards of `rows` rows; longLists: cards of `longRows` rows; sensors: sensor
  // cards with an SVG line graph; histories: history-graph cards (canvas); pictures: picture cards.
  const TIERS = {
    light: { tiles: 12, entities: 5, rows: 5, longLists: 0, longRows: 0, sensors: 2, histories: 0, pictures: 0 },
    typical: { tiles: 26, entities: 13, rows: 6, longLists: 0, longRows: 0, sensors: 6, histories: 2, pictures: 2 },
    heavy: { tiles: 50, entities: 26, rows: 8, longLists: 4, longRows: 20, sensors: 16, histories: 8, pictures: 15 },
  };

  const ORDER = ["light", "typical", "heavy"];

  // The measurement protocol: bump it whenever how something is measured changes (the pages, the
  // head, the swipes, the update rate, the windows, or how the runner reads them). rubric.json's
  // protocol_version says which protocol its rules apply to; a summary from another one is
  // re-measured, not graded. test/page.test.mjs pins the two equal.
  const PROTOCOL = 1;

  // The first screen is the same on every page: the clock, the toggle, `tiles` more tiles and one
  // entities card of `rows` rows, taken from the page's own counts. Only these change while the page
  // is live, so every page gets the same update stream; the pages differ in what is around and below.
  const HEAD = { tiles: 7, rows: 5 };

  // While live, the page changes `updatesPerS` entity states per second over the head's tiles and
  // rows (a seeded order; a tile flips on or off with HA's 180 ms transitions, a row changes its
  // text), and its clock ticks every second.
  const LIVE = { updatesPerS: 5, clockEveryMs: 1000, tileTransitionMs: 180 };

  // Phases, in ms. The page stays still after its load event so its load can settle and the
  // kiosk's loading overlay is gone (hidden at first paint, or 1.5 s after the load, 300 ms fade).
  // Then it goes live: warm-up, one window of frames during the updates, the taps, then the swipes.
  const PHASES = { stillAfterLoadEventMs: 2500, warmupMs: 1000, tapWaitMinMs: 30000,
                   swipes: 6, swipeMs: 300, scrollQuietMs: 400, swipeWaitMs: 4000 };

  function cardCount(t) {
    const s = TIERS[t];
    return s.tiles + s.entities + s.longLists + s.sensors + s.histories + s.pictures + 1;   // + the clock
  }

  const api = { TIERS, ORDER, HEAD, LIVE, PHASES, PROTOCOL, cardCount };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWTiers = api;
})(this);
