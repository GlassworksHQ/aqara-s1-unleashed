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
  const PROTOCOL = 3;

  // The first screen is the same on every page: the clock, the toggle, `sensorTiles` sensor tiles
  // and `switchTiles` on/off tiles (a light, a switch, a media player), and one entities card of
  // `rows` sensor rows, taken from the page's own counts. Only these change while the page is live,
  // so every page gets the same update stream; the pages differ in what is around and below.
  const HEAD = { sensorTiles: 4, switchTiles: 3, rows: 5 };
  HEAD.tiles = HEAD.sensorTiles + HEAD.switchTiles;

  // While live, as Home Assistant does it (docs/perf.md, "Live updates"): `updatesPerS` sensor value
  // changes a second over the head's sensor tiles and rows (text only: HA's tile colour doesn't
  // change with a sensor's value), one on/off change every `flipEveryMs` on the head's on/off tiles
  // (HA's 180 ms colour transitions), and the clock every second.
  const LIVE = { updatesPerS: 5, flipEveryMs: 2000, clockEveryMs: 1000, tileTransitionMs: 180 };

  // The updates window is 4 s, so each run's change-to-screen p95 is over about 26 changes. A still
  // screen presents only when something changes (about 16 frames a second here), so SurfaceFlinger's
  // 127-frame history still covers it; a change it doesn't cover isn't counted.
  // Phases, in ms. The page stays still after its load event so its load can settle and the
  // kiosk's loading overlay is gone (hidden at first paint, or 1.5 s after the load, 300 ms fade).
  // Then it goes live: warm-up, one window of frames during the updates, the taps, then the swipes.
  // Each swipe is a flick down the page from its top (the page jumps back and waits still first), and
  // every page leaves at least `scrollRoomScreens` screens of scrolling below the top, so no flick
  // reaches the end: a short page gets empty view background below its cards.
  const PHASES = { stillAfterLoadEventMs: 2500, warmupMs: 1000, tapWaitMinMs: 30000,
                   swipes: 6, swipeMs: 300, scrollQuietMs: 400, swipeWaitMs: 4000,
                   scrollRoomScreens: 3, repositionStillMs: 500, updatesWindowMs: 4000 };

  function cardCount(t) {
    const s = TIERS[t];
    return s.tiles + s.entities + s.longLists + s.sensors + s.histories + s.pictures + 1;   // + the clock
  }

  const api = { TIERS, ORDER, HEAD, LIVE, PHASES, PROTOCOL, cardCount };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWTiers = api;
})(this);
