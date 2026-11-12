# Performance test and grade

`tools/perf/run.sh` measures how good a wall panel a frame (a device definition, such as the S1's
`aqara-s1-plus`) makes, and gives it a grade from **A** to **E**. The letter says **the biggest
fixed dashboard on which every wall-panel task still feels good**:

| Grade | Meaning |
|---|---|
| A | A good wall panel even for large dashboards |
| B | A good wall panel for typical dashboards; large ones stay fluid |
| C | A good wall panel for typical dashboards |
| D | A good wall panel for light dashboards |
| E | Not a good wall panel, even for light dashboards |

The report opens with one line such as "Aqara S1 Plus: C, a good wall panel for typical dashboards.
Held back by large dashboards: scrolling misses frames (p95 50 ms, 30% late)." (an example of the
format, not a measured result). The second sentence names the tasks that keep the frame from the
next letter up. A table under it shows each page's tasks.

The letter is derived, so it isn't stored anywhere it is shown: a frame's
[summary](#the-summary-glassworksperf-summary1) holds the measurements, and the site computes the
letter at render time from the summary and `rubric.json` (see [Showing the grade](#showing-the-grade)).

The grade is measured on three fixed **reference dashboards** that this test ships: light, typical
and heavy. They are seeded and load nothing from the network, so the grade doesn't depend on your
Home Assistant, your dashboard or your network. The one way your panel's own setup reaches it is
background load: the panes, the radio and the device's heat keep running while the pages are
measured, as they do every day (see [What the grade does and doesn't tell
you](#what-the-grade-does-and-doesnt-tell-you)). The bands underneath come from published guidelines
(Core Web Vitals, RAIL, Android vitals), not from any frame's own numbers.

The test also measures the panel as you run it (your own dashboard, memory, CPU, the panes) and
animation capacity. Those are reported separately and never graded. It needs no app changes: every
page runs in the kiosk's own GeckoView.

## Run it

You need adb, Python 3 and the kiosk installed on the device. Let the device idle for 10 minutes
first, with the screen on, at normal room temperature, and the kiosk's zoom at its default ("auto").

```sh
tools/perf/run.sh --frame aqara-s1-plus --name "Aqara S1 Plus" --runs 3 --out perf-s1
```

| Option | Default | What |
|---|---|---|
| `--frame` | (required) | The frame id that goes in the report |
| `--name` | the frame id | The frame's name in the report's headline |
| `--serial` | `$ANDROID_SERIAL` | Which device |
| `--runs` | 3 | Repeats of each part. The report gives the median and range |
| `--taps` | 10 | Taps on each reference dashboard's toggle (0 skips them; the grade is then provisional) |
| `--skip-synthetic`, `--skip-reference`, `--skip-real-use` | | Skip the animation test page (detail), the reference dashboards (the grade), or the real-use part. Skipping the reference dashboards makes the grade provisional |
| `--quick` | | Short ladder on the animation test page (1, 16 and 128 elements), to check the setup works |
| `--apk FILE` | | Add the APK's size per ABI to the report (repeat for each ABI) |
| `--idle-only LABEL` | | Take one idle sample with this label and nothing else (for [panes](#memory-and-cpu-per-pane)) |
| `--max-start-temp` | 50 | Waits up to 10 minutes for the CPU to cool below this (°C) |

A full run with `--runs 3` should take about 30 minutes. That is an estimate from the step timings,
not yet timed on a device under this definition. A graded run is a restart, a warm-up page, then
the three reference dashboards with 10 taps and 6 swipes each.

For the site, export the frame's summary from the finished report:

```sh
tools/perf/summary.py perf-s1/report.json perf-s1/summary.json --report-url report.json
```

It refuses incomplete or not comparable measurements unless you pass `--allow-provisional`; then
its `complete` is `false` and its facts say why.

The output directory holds the raw files, `report.json` and `report.md`. A full run needs a new or
empty directory; `--idle-only` adds samples to an existing one. `python3
tools/perf/report.py <dir>` rebuilds the report from the raw files at any time, without the device.

### What it changes on the device

- **The pages** open with the kiosk's one-off `--es open <url>` extra. The saved dashboard URL is
  **never changed**. They are served from your computer through `adb reverse`
  (`http://127.0.0.1:8089` on the device) with caching off, so every run loads them the same way.
  The device's network plays no part.
- **The screen stays on** because the runner sends the kiosk's own wake intent (`--es screen on`)
  when each page goes live. No setting changes.
- **Taps and swipes**, only on our own pages and only when the page asks: each page logs where its
  "Living room" toggle is (`input tap`) and where to swipe (`input swipe`), tagged with that page
  load's nonce. A request with another page's nonce is ignored. Before anything reaches
  `adb shell`, the runner checks the request: whole pixels inside the screen (`wm size`), 1 to 50
  taps (and no more than `--taps`), a swipe of 50 to 2000 ms. A bad request isn't sent; that page
  gets an error and isn't graded. A gesture is sent only while its page is still the current one,
  and the next page opens only after the last page's gestures are done. Nothing else is touched.
- **Restarts** send the system `KEYCODE_WAKEUP` key (the kiosk's wake intent would start the app)
  and force-stop the app. A kiosk that is the device's home app, like the S1's, is started again by
  the system at once (0.2 s on the S1). Otherwise the runner starts it with `am start -W` after 3 s.
  If the run stops early, or the last restart doesn't reach the dashboard, the exit trap restarts
  the app. While the app restarts, Home Assistant sees the panel go offline for a few seconds.
- Everything else is read-only: `getprop`, `/proc`, `dumpsys meminfo`, `dumpsys SurfaceFlinger
  --latency`, logcat.

## The reference dashboards

`tools/perf/page/dashboard.html?tier=…` renders a fixed, seeded Home Assistant-like dashboard. The
three pages differ in **how much is on them**, not in animation. They are mostly still, like HA,
with a steady stream of state updates once they are live. The counts are **project choices**,
fixed before any frame was graded under them (`tools/perf/page/tiers.js`, pinned by
`test/page.test.mjs`):

| Page | Cards | What | Elements | Height at 960 CSS px wide |
|---|---|---|---|---|
| **Light** | 20 | 12 tiles, 5 entities cards of 5 rows, 2 sensor cards with a graph, a clock | 534 | 860 px, about 2 screens on the S1 |
| **Typical** | 50 | 26 tiles, 13 entities cards of 6 rows, 6 sensor cards, 2 history graphs, 2 pictures, a clock | 1446 | 2742 px, about 5.5 screens |
| **Heavy** | 120 | 50 tiles, 26 entities cards of 8 rows, 4 long lists of 20 rows, 16 sensor cards, 8 history graphs, 15 pictures, a clock | 4513 | 8358 px, about 17 screens |

**The first screen is the same on every page** (`HEAD` in `tiers.js`): the clock, the toggle, 7
more tiles and one entities card of 5 rows, taken from the page's own counts. The rest of the page
comes after it, shuffled. Only the first screen changes while the page is live, so every page gets
the same update stream; the pages differ in how much is around and below it. (Before 2026-11-12 the
updates went to whatever was on screen. On the S1's smoke5 that was tiles with 180 ms fades on
light and rows without fades on heavy, so light's updates looked worse than heavy's.)

Elements are counted through every shadow root, and heights are from headless Chrome at 960 CSS px
(2026-11-12). The S1's CSS viewport is 960 × 480 (1440 × 720 at a device pixel ratio of 1.5).

Each card copies the way Home Assistant's frontend builds it, read from its source at commit
[`77170b4`](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67) (2026-11-12):

| Part of the page | How HA does it | Source | On the page |
|---|---|---|---|
| Tile | `ha-card` with a background and a container holding `ha-tile-icon` (a shape and the icon) and `ha-tile-info` (name, state). On a state change the icon's colour, the shape's colour and opacity, and the background fade over 180 ms, ease-in-out | [`hui-tile-card.ts` L185](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/hui-tile-card.ts#L185), [`ha-tile-icon.ts` L133–L143](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/tile/ha-tile-icon.ts#L133-L143) | The same structure and the same 180 ms transitions |
| Icons | `ha-state-icon` > `ha-svg-icon` > `svg`, each a custom element with its own shadow root | [`state-badge.ts`](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/entity/state-badge.ts) | The same two nested custom elements; the icons are simple SVGs drawn for the page |
| Entities card | `ha-card` > `h1.card-header` + `#states` > one row per entity (badge, name with a secondary line, state) | [`hui-entities-card.ts` L228, L259](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/hui-entities-card.ts#L228) | The same |
| Sensor card graph | `hui-graph-base`: an SVG with a filled area path and a line path | [`hui-graph-base.ts` L74–L112](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/components/hui-graph-base.ts#L74-L112), [`hui-sensor-card.ts`](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/hui-sensor-card.ts) | An SVG with the same two paths over 49 points |
| History graph | `state-history-charts` on `ha-chart-base`: ECharts on a canvas | [`hui-history-graph-card.ts` L370](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/hui-history-graph-card.ts#L370), [`ha-chart-base.ts` L892](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/chart/ha-chart-base.ts#L892) | A canvas with two series over 97 points, drawn once (a chart at rest) |
| Picture card | `ha-card` > `hui-image` > `img` | [`hui-picture-card.ts` L268](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/hui-picture-card.ts#L268) | An `img` whose JPEG (480 × 270) is generated on the page, so the engine decodes it like a snapshot |
| Clock | The digital clock card re-renders its text every second (`setInterval(…, 1000)`) | [`hui-clock-card-digital.ts` L25, L117](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/clock/hui-clock-card-digital.ts#L117) | The same, with seconds |
| Layout | Masonry: one column per matched breakpoint (300, 600, 900, 1200 px); each card goes to the first column whose size is under 5, else to the smallest | [`hui-masonry-view.ts` L19–L32, L111–L119](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/views/hui-masonry-view.ts#L111-L119) | The same rule and breakpoints, with HA-like card sizes (tile 1, entities rows + 1, sensor 2, clock 2, history 4, picture 3) |

No header: HA's kiosk mode hides it. The page scrolls as a document, so a touch scroll goes through
GeckoView's compositor, as it does in HA.

**Live updates (a project choice).** Once live, the page changes **5 entity states a second**, in a
seeded order over the first screen's 7 tiles and 5 rows: a tile flips on or off with HA's 180 ms
transitions, a row changes its text. Its clock ticks every second. The timers aim at fixed times, so
a late change doesn't push the next ones back. When the page is done, it stops them, so the next
page never opens over a page that is still updating. A busy home is the reason for that rate: dozens of power and energy sensors that
report every 5 to 10 s add up to a few state changes a second on one view.

**The order (protocol 1).** Each graded run opens a **warm-up** page first: the light page, with no
taps or swipes, not graded. It takes the kiosk's own dashboard's teardown, the first JIT and the
cold caches. Then the three pages run in an order that turns by one page each run: run 1 light,
typical, heavy; run 2 typical, heavy, light; run 3 heavy, light, typical. So no page always comes
first, and the report gives each page's results at each place in the order. Each page opens only
once the screen has been still for 2 s (at most 10 s). (On the S1's smoke5, 2026-11-12, light
always came first, right after the kiosk's dashboard, and graded worse than heavy.)

Each page runs in phases, one log line per event:
1. **Open the view.** It renders and reports its load times.
2. It stays still until 2.5 s after its load event, so the load can settle and the kiosk's own
   loading screen is gone. (The kiosk covers every page it loads with "Connecting to Home Assistant"
   and hides it at the page's first paint, or 1.5 s after its load event if that was missed, with a
   300 ms fade. The 2.5 s clear that fallback with 0.7 s to spare.)
3. **Live updates.** It goes live: warm-up of 1 s, then one window of 100 display refreshes (1.67 s
   at 60 Hz) of frames while the updates run.
4. **Tap.** 10 taps on the toggle while the updates run.
5. **Scroll.** 6 swipes. Each swipe goes from 85% to 15% of the screen's height in 300 ms, down the
   page, then back up when it reaches the bottom. The fling is included.

## What it measures

### The graded tasks

| Task | How | Where it comes from |
|---|---|---|
| Tap | INP: the worst of 10 taps on the toggle, taken while the live updates run. Event Timing gives the delay, the handler time and the time to the next paint. Taps under 16 ms aren't reported by Event Timing; they count as 16 ms | Each page |
| Scroll | Real swipes (`input swipe`). A swipe's window runs from its first scroll event until 400 ms with no scroll event, the fling included, capped at 100 refreshes. All of a run's swipes are pooled: rAF over every swipe, SurfaceFlinger over the swipes whose history it kept (decided swipe by swipe). The frames are the worse of the two, each run gets a band, and the grade takes the median band. A run in which SurfaceFlinger kept no swipe rests on rAF only, which can't see frames the compositor dropped: the report says so and the grade is provisional. `scrolled_px` proves the page moved: a page that didn't move is "not measured" | Each page; SurfaceFlinger |
| Live updates | **The frames that had a change to draw.** Each change is logged with the time it was due, the time it ran and how long its transition lasts (180 ms for a tile, 0 for text). A rAF interval counts when it overlaps a change's span, from due to ran plus the transition: the frame that should draw it, and each frame of its fade. Idle frames, with nothing due or moving, don't count; on a still screen there is nothing to be late for. A change that ran late (a busy main thread) makes the frame it was due in long, and that counts. Graded on rAF only, **a project choice**: between changes the screen is still, so SurfaceFlinger has nothing steady to count, and its change-to-present time includes the engine's one to three frames of buffering on every frame. That time is reported (`change_to_present`), not graded. Each run gets a band; the grade takes the median band. A **live check** at the window's start logs whether the toggle and everything that changes are on screen, and the change log proves the updates ran. If something was off screen, there were no targets, or two or more of the window's state updates are missing, the updates aren't graded (and, if off screen, the taps neither) | Each page |
| Open the view | From the page's navigation start to **settled**: the first frame followed by 750 ms with no new frame, where that quiet reaches past the page's load event. The search starts at **the frame that shows the first paint**: the first frame at or after it, less 50 ms for the clock mapping. A pause before that frame is mid-load, like a pause before the load event. Settled is never earlier than the load event or the first paint. The page's times come from its own clock, tied to logcat's by its log line (navigation start mapped 11 to 23 ms before PageStart on the S1). Without a load event, a frame after the first paint, or a quiet gap, it is "not measured", never 0. The time from the first paint to the frame that shows it is reported (`paint_to_screen_ms`) | Each page; SurfaceFlinger |
| Restart | From the app's process start (`Start proc` in logcat) to the engine being ready: GeckoView's own first page, `about:blank`. Once per run, for the whole frame. See [Why restart ends at engine ready](#why-restart-ends-at-engine-ready) | logcat |

Each graded run is one restart, a warm-up page, then the three reference dashboards in the run's
order. The first page opens once the kiosk's own page has stopped for 2 s with no new load
(`OWN_PAGE_QUIET_S`), so they never compete with it. A second
load of the kiosk's own page (a reload, a sign-in redirect) restarts that wait. If the kiosk's page
keeps painting after it finished (a clock card), that is normal and changes nothing. If it hasn't
finished 30 s after engine ready (Home Assistant down or hung), the pages open anyway, and the grade is marked
**provisional**: the kiosk may still have been retrying its page. The letter itself is kept, because
dropping a measurement would let Home Assistant move it. While the pages are measured, and for 1.5 s
after the last one, any other page the kiosk loads makes the run an error, and that run isn't graded.

**SurfaceFlinger's data.** It keeps the last 127 frames and is read just after each window. A window
is at most 100 refreshes, so the other 27 slots cover that read, and the page's own logged window
length marks the window's end. The data is complete when the history reaches back before the
window. Then every gap inside the window counts, and so do gaps at its edges longer than 1.5
refreshes. If the history doesn't reach back, that swipe counts with rAF only; the others in the
run keep SurfaceFlinger's frames. `sf_valid` lists, per run, whether every swipe had it.

**Not available:** checkerboarding (blank areas while scrolling). GeckoView doesn't expose it to a
page, and the runner can't read it without changing the app. The time from touch to the first
scroll event is reported per page (`first_scroll_ms`), not graded: it is measured on the main thread
and lags the compositor's first scrolled frame.

### Reported, not graded

| What | How |
|---|---|
| Animations it can handle | The animation test page's ladder: five families of animated elements, each doubling from 1 to 1024 (CSS transform, CSS opacity, SVG, canvas 2D, HA-like tiles whose numbers change every frame). The report gives the largest count held at 60 fps and at 30 fps. The same page has a scripted scroll stage and a JS workload (an HA-like state update for 400 entities, checked by a checksum) |
| Android's TTID | The `Displayed …: +Nms` line of each restart |
| Your dashboard | A restart back to your own dashboard: process start to its `PageStart`, its load to settled, and the whole wait from the force-stop. A run that doesn't finish within 120 s is recorded as an error |
| Memory | PSS of every app process (the app, Gecko's GPU, content and crash helper processes), the main process's App Summary, each process's peak RSS (`VmHWM`), the radio's processes, and `MemTotal`/`MemAvailable`, at idle on your dashboard and at the run's peak |
| CPU at idle | Jiffies of every app and radio process over 15 s, and the whole device's busy share |
| Idle frames | Frames your dashboard presents while nobody touches it |
| Panes | What each pane adds; see [Memory and CPU per pane](#memory-and-cpu-per-pane) |
| Device | Model, Android version and API, ABI, SoC, cores, max clock, RAM, display, app version, APK size, GeckoView version (from the running engine's user agent), temperatures |

Your dashboard isn't swiped. A drag on it can move a slider or flip a switch; the reference
dashboards stand in for it.

## The bands

Each measurement gets a band, as in Core Web Vitals: **good**, **needs improvement** or **poor**.
The thresholds live in one file, `tools/perf/page/rubric.json`. `grade.py` grades from it, and the
animation test page reads it to stop its ladder. The sources were checked on 2026-11-12.

| Measurement | Good | Poor | Source |
|---|---|---|---|
| Frame time, p95 | ≤ 17.17 ms (one frame at 60 Hz, 16.67 ms, plus 0.5 ms slack) | > 33.83 ms (two frames plus slack) | [RAIL](https://web.dev/articles/rail): "the maximum budget for each frame is 16 ms (1000 ms / 60 frames per second ≈ 16 ms)". [Android vitals](https://developer.android.com/topic/performance/vitals/render): slow frames take longer than 16 ms |
| Jank rate (frames over 16.7 ms) | ≤ 5% | > 50% | Poor: [Play Console](https://support.google.com/googleplay/android-developer/answer/9844486) "excessive slow frames", more than 50% of frames over 16 ms. Good: the same as a p95 within budget |
| Frozen frames (over 700 ms) | 0% (**a project choice**) | > 0.1% | [Android vitals](https://developer.android.com/topic/performance/vitals/render): frozen frames take longer than 700 ms; Play Console "excessive frozen frames" is more than 0.1% |
| INP: worst tap to next paint | ≤ 200 ms | > 500 ms | [Core Web Vitals INP](https://web.dev/articles/inp): good at or below 200 ms, poor above 500 ms |
| Open the view, navigation start to settled | ≤ 2.5 s | > 4 s | [Core Web Vitals LCP](https://web.dev/articles/lcp): "Good LCP values are 2.5 seconds or less, poor values are greater than 4.0 seconds". Settled is at or after the largest paint, so this is stricter than LCP |
| Restart, process start to engine ready | ≤ 2.5 s (**a project choice**: LCP's bound) | ≥ 5 s | [Android vitals](https://developer.android.com/topic/performance/vitals/launch-time) counts a cold start that "takes 5 seconds or longer" as excessive (vitals apply it to TTID) |

A frames band is the worst of its three measurements. A window holds about 100 frames, so a single
frozen frame in it is 1%, which is poor. Frame times are snapped to whole display refreshes before
the percentile is taken, so timer jitter doesn't count a frame that made its refresh as late. The
0.5 ms slack covers displays that run slightly under 60 Hz. The budget stays 16.7 ms on a faster
display.

## The grade

**A page passes at a level when every task does:**

| Level | Tap | Scroll and live updates | Open the view |
|---|---|---|---|
| **Smooth** | INP good (≤ 200 ms) | 60 fps: frames band good | good, or needs improvement (up to 4 s) |
| **Fluid** | INP good (≤ 200 ms) | 30 fps: frames band good or needs improvement | good, or needs improvement (up to 4 s) |

**The letter is the first one whose pages pass:**

| Grade | Needs |
|---|---|
| A | Heavy smooth |
| B | Typical smooth and Heavy fluid |
| C | Typical fluid |
| D | Light fluid |
| E | None of the above |

**A restart of 5 s or more caps the grade at C.** A panel whose engine takes that long to come back
after a restart (an update, a crash, a power cut) is not an A or B panel. A cap never raises a letter.

A measurement that is missing fails whatever needs it, and the grade is marked **provisional**, with
what is missing listed. Each band counts over the runs **attempted** (`plan.json`, written by
`run.sh`), not only those that finished: a run that failed or left no file, a task measured in fewer
runs than attempted, or a scroll that rests on rAF only also make it provisional.

The headline names the tasks that keep the frame from the next letter up, with their numbers, and
says if the restart capped it; an A says every task holds 60 fps on large dashboards. (On smoke5 the
old headline read "E, … Large dashboards scroll at about 53 fps", which read against the letter.)
The report's table gives every task's value and band per page, and the task that holds each page
back. Another table gives each page's results at each place in the run order.

Both tables (the levels and the letters) and the restart cap live in `tools/perf/page/rubric.json`
(`grade`), next to the thresholds. `tools/perf/grade.py` and `tools/perf/grade.ts` both read them,
and neither holds a rule of its own. `tools/perf/test/grade-cases.json` lists each task's pass rule
and each letter's boundary with its answer: a frame that just meets a level or a letter gets it, and
moving any one requirement past its edge drops it. `test_grade.py` checks Python against it and
`grade.test.mjs` checks TypeScript, so a rule changed in one language only fails.

### Not tuned to any frame

The bands come from the cited guidelines. The pages, the tasks, the levels and the letter table are
project choices. They changed twice, both times for product reasons, and each time before any frame
had results under the new definition:
1. The first letter measured how much **animation** a frame runs (light, typical and busy animated
   pages). The S1 ran under it in smoke runs and graded D.
2. The maintainer then put **scrolling a big dashboard** ahead of animation.
3. Then came the final definition: **every wall-panel task** (tap, scroll, live updates, opening a
   view, restart) on dashboards of growing size. No frame had scroll or wall-panel results when it
   was set. The S1's D under the animation pages was not the reason for either change.

The S1 is just the first frame graded under it. Its result goes in this document whatever letter it
gets. The thresholds, the pages and the tables must not be moved to change a particular frame's
letter. Change them only for a reason that holds for every frame, in their own PR, with the docs and
tests.

The smoke5 fixes (warm-up, turning order, the shared first screen, the change frames, the first-paint
settle rule) are measurement fixes: each made a page's numbers depend less on where it ran or on a
clock artefact. They became protocol 1. None moved a threshold or a letter rule. Rubric version 4
only moved the existing letter rules into `rubric.json`, unchanged, so the site can read them.

### Why restart ends at engine ready

Android vitals time a cold start to the launched activity's first frame (TTID). On the S1 that is
MainActivity's first window: the kiosk's own native loading screen ("Starting up", "Connecting to
Home Assistant…"), not a splash activity (the `:boot` direct-boot home activity ships disabled). In
the S1 smoke run (2026-11-12) Android reported a TTID of 111 ms. The timeline after the force-stop:

| Event | After the force-stop |
|---|---|
| App process starts | 0.22 s |
| `Displayed` (MainActivity: the loading screen) | 0.40 s |
| First GeckoView frame | 3.2 s |
| Your dashboard's `PageStart` | 4.5 s |
| Your dashboard settled | 11.5 s |

Any kiosk can draw a loading screen within 100 ms, so TTID doesn't separate frames. Android's answer
is TTFD (`reportFullyDrawn`), which the kiosk doesn't call, and this test changes no app code.
Timing your own dashboard would make the grade depend on your Home Assistant. Opening a reference
page during the restart doesn't work either: until GeckoView's session is up, the kiosk ignores
`open` (#103's pins; seen on the S1 too), and once it is up, the request races the kiosk's own
dashboard load. So restart ends at **engine ready**, the engine's own first page (`about:blank`; the
report records it, and a restart whose first page is anything else isn't graded). That comes before
the kiosk does anything for Home Assistant. Both ends are stamped on the device in logcat, so the
harness's adb round trips aren't in it. The first paint of a real page is graded under "open the
view".

**How the 5 s bound compares with Android vitals.** Engine ready comes after TTID, so passing 5 s
here is harder than passing it on TTID. It still ends before any page has painted, so it is more
lenient than a bound on the time to the first real content. Proposal, not adopted: also cap the
grade at C when engine ready plus the light page's first paint reaches 5 s.

### Zoom and viewport

The kiosk's zoom is a Home Assistant entity (0 to 3; 0 is "auto"). It sets Gecko's device pixel
ratio, which changes how much of a dashboard fits on a screen: at a large zoom, the same page holds
fewer cards per screen and needs more scrolling. So the grade compares frames only at their
**default** zoom. The report records each page's CSS viewport and device pixel ratio. The default
ratio is the display's density / 160 (1.5 on the S1: density 240). If a page ran at another ratio,
the grade is **refused** ("not graded"), with the reason. It isn't marked provisional, because a
letter would then look comparable when it isn't. If the density wasn't recorded, the check can't
run, and the grade is provisional.

## What the grade does and doesn't tell you

- It describes **this frame running this app version** on the reference dashboards, at its default
  zoom. It doesn't change with your Home Assistant, your dashboard or your network. It doesn't say
  how fast **your** dashboard is: that is in the report's real-use section, which isn't graded.
- **Heat matters.** A warm device throttles its CPU. The runner waits for the CPU to drop below
  50 °C and logs every thermal zone at each stage (`thermal.tsv`). Compare runs only at similar
  temperatures.
- **Background load matters.** The radio, voice wake word and Sendspin run during every part, as
  they do in daily use. That is the one way the panel's own setup can reach the grade: a pane that
  uses the CPU all the time (on the S1, see [#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105))
  slows the reference dashboards too. The device's idle busy share is in the report.
- **Screen brightness** doesn't change the work the app does. Over a long run it can add heat.
- The reference dashboards use HA's default card types. Custom cards, cameras and maps are heavier
  than the heavy page.
- The tap test taps one toggle. Real interactions (opening a more-info dialog) do more work.
- The device's busy share at idle includes the runner's own `dumpsys` calls. While a page goes
  live, is tapped or scrolls, the runner doesn't call `dumpsys`.
- The memory budget's `ok` is `false` if any check fails. It is `null` when none fails but a
  budgeted value wasn't measured, and `true` only when every check was measured and passed.

## Not in the grade

These have no published standard, or depend on your own Home Assistant:

| What | Band or budget | Why this choice |
|---|---|---|
| JS state update (median) | good ≤ 16.7 ms, poor > 50 ms | Good: the update fits in one frame. Poor: past RAIL's 50 ms input-processing budget ("process user input events within 50 ms"), which is also the [Long Tasks](https://www.w3.org/TR/longtasks-1/) cut-off (that draft is now discontinued). The 400-entity size stands for a larger home |
| Animations it can handle | reported only | A wall panel mostly shows still dashboards; animation capacity is useful to compare frames, not to judge a panel |
| App memory, peak PSS of all app processes | ≤ 50% of `MemTotal` | An always-on panel shares its RAM with the system, vendor daemons and the radio. Half for the app leaves room for them |
| Device memory, lowest `MemAvailable` during the run | ≥ 15% of `MemTotal` | Headroom for the system and the radio. A first choice, not a measured limit; change it in the same PR as the evidence |
| CPU at idle | reported only | No standard. Lower means less heat and power on a panel that never sleeps |
| Your dashboard's start and load | reported only | Your dashboard, your network and your Home Assistant, so not comparable between panels |
| Idle frames | reported only | A still dashboard should present almost nothing. A clock or an animated card adds frames |

The memory budgets are in `tools/perf/budgets.json`, with a default and a per-frame override. An
override names only the keys it changes; the rest come from the default.

### How Home Assistant animates (reference for the animation detail)

An earlier version graded animated reference pages. They were replaced before they ran on a panel,
but what they copied from HA's source (commit
[`77170b4`](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67)) still describes
how HA animates, for any future animation detail page:

| Element | How HA does it | Source |
|---|---|---|
| Pulsing state icon (alarm triggered or arming, lock jammed) | `@keyframes pulse` on the state icon: opacity 1 → 0 → 1, `1s infinite` | [`icon_color_css.ts` L8–L21](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/common/style/icon_color_css.ts#L8-L21) |
| Spinner (`ha-spinner`), the only spinning element on a dashboard | Web Awesome's spinner (HA's fork 3.14.0-ha.2): the SVG rotates with `transform` (3.5 s in HA) and its arc animates `stroke-dasharray`/`stroke-dashoffset` (1.5 s), repainted every frame | [`ha-spinner.ts` L45](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/ha-spinner.ts#L45), [spinner styles](https://cdn.jsdelivr.net/npm/@home-assistant/webawesome@3.14.0-ha.2/dist/components/spinner/spinner.styles.js) |
| Alert badge pulse | opacity 0 → 0.3 on a `::before`, 1 s, alternating | [`hui-alert-badge.ts` L179–L223](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/badges/hui-alert-badge.ts#L179-L223) |
| Charts | ECharts on a canvas, a 500 ms animation on each update | [`ha-chart-base.ts` L1224](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/chart/ha-chart-base.ts#L1224) |

HA's own cards don't spin state icons; custom cards such as Mushroom do, with a CSS `transform`.

## Memory and CPU per pane

Not graded: it depends on your panes and your Home Assistant. What each pane adds is measured by
turning that pane off through its Home Assistant entity and comparing with everything on. You need
an admin account in Home Assistant, and a panel you are allowed to disturb: while a pane is off, the
panel doesn't play music or hear its wake word. The runner doesn't talk to Home Assistant. You
toggle the entity, and take an idle sample after each change. Alternate on and off, three times
each. Use a separate output directory: the full run's `all-on` samples come 30 s after a restart,
and a panel that has been up for a while may use more memory, so mixing them could bias the
difference.

```sh
tools/perf/run.sh --frame aqara-s1-plus --out perf-s1-panes --idle-only all-on
# turn the pane off in Home Assistant, wait 10 s
tools/perf/run.sh --frame aqara-s1-plus --out perf-s1-panes --idle-only sendspin-off
# turn it back on, wait 10 s, and repeat both steps twice more
# finish with the pane back on, as you found it
```

For `assist`, "off" means the mute switch is **on**: `--idle-only assist-off` is taken with
`switch.<panel>_mute_microphone` on, and you finish by turning it off again.

Each `<pane>-off` label is compared with the `all-on` samples. If a difference is smaller than the
spread of the `all-on` samples, the report says "within noise". On the S1:

| Pane | Entity (turn off → on) | What turning it off stops |
|---|---|---|
| `sendspin` | `switch.<panel>_music_assistant_player_sendspin`: off, then on | The Sendspin player and its connection |
| `assist` | `switch.<panel>_mute_microphone`: **on**, then off | The microphone and wake word detection. The wake word model stays loaded, so this measures the running cost, not all of the pane's memory |
| `radio` | (not switched) | The radio's own processes (`otbr-agent`, `cpcd`, `zigbeed`) are measured where they run. Changing the radio mode would change the network |
| `wall-base` | none | Only if a base is fitted (`sensor.<panel>_base`) |

## The report (`glassworks.perf/3`)

`report.json` has one frame per file. Repeated measurements are given as
`{"median", "min", "max", "n"}`.

| Key | What |
|---|---|
| `schema` | `"glassworks.perf/3"`. A field that changes meaning gets a new version |
| `protocol_version` | The measurement protocol the pages ran (`null` if the runs disagree). See [Protocol and rubric versions](#protocol-and-rubric-versions) |
| `measured_on` | The run's date (UTC, from the computer that ran it) |
| `runs_attempted` | Graded runs attempted (`plan.json`), the count each band is over |
| `frame` | `id`, `name`, `device` (model, Android, API, ABI, SoC, CPU, RAM, display with density and refresh, temperatures), `app` (package, version, APK bytes on the device, GeckoView version) |
| `apk` | One entry per APK given with `--apk`: `abis`, `bytes`, `parts_bytes` (dex, native, assets, resources, other) |
| `grade` | `grade` (`null` when refused), `meaning`, `headline`, `levels` (per page: `smooth`, `fluid` or `null`), `bands` (per page: `tap`, `scroll`, `updates`, `load`), `start`, `capped_by_start`, `viewport` (`state`, `reason`), `refused`, `provisional`, `provisional_reasons`, `missing`, `rubric_version`. Computed from `graded` only. `provisional_reasons` are measurement facts (a run failed, a task was measured in fewer runs, scrolling rests on rAF only, the pages ran another protocol) |
| `graded` | `reference`: `start_ms`, `start_errors`, `engine_uris`, `android_ttid_ms` (not graded), `launch`, `own_page_states`, `orders` (each run's page order), `warmups`, `protocols`, `errors`, `runs`, `graded_runs`, `per_run` (each with its `order` and `between`, whether the screen was still before each page opened), and `tiers` with, per page: `inp_ms`, `load_ms`, `load_errors`, `paint_to_screen_ms`, `scroll` (p95, jank, frozen, fps, n), `scroll_bands`, `scroll_band`, `scroll_runs` (each run's frame stats), `scroll_sources`, `scroll_limited_by`, `scroll_raf_only` (per run), `sf_swipes` and `swipes_moved`, `scroll_errors`, `scrolled_px`, `first_scroll_ms`, `sf_valid` (per run), `updates` (the frames that had a change to draw), `updates_bands`, `updates_band`, `updates_runs`, `updates_all_p95_ms` (every frame of the window, detail), `change_to_present_p95_ms` (detail), `updates_errors`, `positions` and `by_position` (the page's results at each place in the order), `fcp_ms`, `nodes`, `cards`, `viewport` |
| `detail` | `synthetic`, the animation test page: `ladder` (per family: `held_60fps`, `held_30fps`, `…_at_top`), `steps_without_sf`, `scroll`, `scroll_moved`, `js_burst_ms`, `js_band`, `js_checksum_ok`, `page_lcp_ms`, `page_lcp_from`, `per_run` |
| `real_use` | Not graded. Your dashboard: `app_start_to_dashboard_ms`, `android_ttid_ms`, `launch`, `stop_to_dashboard_ms`, `dashboard_load_ms`, `dashboard_page_stop_ms`, `settle_how`, `errors`, `per_run`. Then `idle`, `peak`, `memory` (`idle`, `peak`, `budget`) and `panes` |

`own_page_states` says, per run, how the kiosk's own page ended before the reference dashboards
opened: `finished-still` (stopped, then quiet), `finished-moving` (stopped, still painting, such as
a clock card; normal) or `never-finished` (still loading 30 s after engine ready; the grade is provisional).

## The summary (`glassworks.perf-summary/1`)

`tools/perf/summary.py` writes a small, stable, frame-level summary of a finished report, for places
that show a frame's grade (glassworks.dev, later a frame's `design/frame.json`). It holds
**measurements only, never the letter or its meaning**: the letter is derived, so it stays derived
(see [Showing the grade](#showing-the-grade)). Only graded, deterministic measurements go in.
Nothing from real use, and nothing device-unique.

| Key | What |
|---|---|
| `schema` | `"glassworks.perf-summary/1"` |
| `protocol_version` | How it was measured. A site grades it only with a rubric for the same protocol |
| `report_schema` | The report it came from, `"glassworks.perf/3"` |
| `measured_on` | The run's date, `YYYY-MM-DD` (UTC) |
| `frame` | `id`, `name` |
| `app_version` | The kiosk's version name |
| `viewport` | `css` (CSS px), `dpr` (device pixel ratio), `default_dpr` (density / 160) |
| `runs` | `attempted`, `graded` |
| `pages` | Per page (`light`, `typical`, `heavy`): `inp_ms` (median), `open_ms` (median), `scroll` (`fps`, `p95_ms`, `scrolled_px`: medians; `sf_valid` per run; `runs`: each run's `p95_ms`, `jank_pct`, `frozen_pct`, `fps`, `n`), `updates` (`p95_ms` median; `runs` as for scroll), `runs` (how many runs measured `tap`, `scroll`, `updates`, `open`) |
| `restart_ms` | Process start to engine ready (median) |
| `gaps` | Measurement facts that leave it short: "1 of 3 graded runs failed or are missing", "heavy: scrolling rests on rAF only in 1 run(s)…". Empty when complete |
| `not_comparable` | Why the numbers can't be compared with other frames' (e.g. not at the default zoom), or `null` |
| `report` | A relative path or URL of the full report (`--report-url`) |
| `complete` | `false` when it was exported with `--allow-provisional` and has gaps, missing measurements or isn't comparable |

The frames bands need each run's stats (each run is banded, then the median band is taken), which is
why the summary carries `runs` and not only medians. Medians are kept for display. Fields are only
added in `/1`; a change of meaning is a new schema version.

### Protocol and rubric versions

Two numbers say whether a stored summary can still be graded:

| Version | Where | Bump it when | A stored summary |
|---|---|---|---|
| `protocol_version` | `tiers.js` `PROTOCOL`, and `rubric.json` `protocol_version` (the protocol its rules are for; `page.test.mjs` pins them equal) | **How** something is measured changes: the pages, the first screen, the swipes, the update rate, the windows, the warm-up and order, or how the runner and report read them | From another protocol: **re-measure**. Its numbers mean something else |
| `version` (rubric) | `rubric.json` | **How it is judged** changes: a threshold, a level, a letter rule, the restart cap | Graded again with the new rubric at render time. No re-measure |

So a change to the letter table changes every frame's letter on the site without a new run, while a
change to the pages makes the site ask for a new run. Protocol 1 is this test as of 2026-11-12
(warm-up, turning order, the shared first screen, change frames, the first-paint settle rule).

### Showing the grade

The site computes the letter **at render time** from the frame's summary and `rubric.json`, with
`tools/perf/grade.ts` (plain TypeScript; the shared frame package can import it later, #114):

```ts
import { gradeSummary } from "./grade.ts";
const g = gradeSummary(summary, rubric);   // rubric = tools/perf/page/rubric.json
// g.status: "graded" | "re-measure" | "not-comparable"
// g.grade, g.meaning, g.levels (per page), g.limits (what keeps it from the next letter), g.provisional
```

| `status` | Show |
|---|---|
| `graded` | The letter and its meaning. When `provisional` is set (something wasn't measured, or `gaps` isn't empty), mark it provisional |
| `re-measure` | "Re-measure", not a letter: the summary's protocol isn't the rubric's |
| `not-comparable` | No letter, and `why` (from `not_comparable`) |

`grade.py`'s `grade_summary` gives the same answer. `test_summary.py` builds fixture reports
(letters A, B, C by the restart cap, E, a refused zoom, rAF-only scrolling, an even run count, a
missing run), exports each summary, and checks that Python's grade from the summary equals the
report's grade, and that `grade.ts` (run under node) equals Python's.

### How #68 uses it

[#68](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/68) is the CI report of APK size
and RAM per frame and pane, with budgets. It can read these reports as they are:

- **Per frame:** one `report.json` per frame, keyed by `frame.id`. Its job compares
  `real_use.memory.peak` and `real_use.memory.idle.app_pss_kb` with the frame's RAM budget, and
  `apk[].bytes` with the frame's size budget, per ABI.
- **Per pane:** `real_use.panes[]` uses the pane ids from the code (`sendspin`, `assist`,
  `wall-base`, `radio`), so a pane's RAM budget is checked against `pss_kb`. #68 measures on an
  emulator, each pane on by itself against all off. It writes its results in the same shape, with
  its own `method` value.
- **Budgets** live in `tools/perf/budgets.json` until #68 moves them into each frame's Gradle config.
  Over budget fails the check unless the same PR raises the budget.
- `apk[].parts_bytes` is the start of #68's split; #68 adds each pane's dex, assets and native
  libraries.

#68 waits for the Gradle modules (#21) and the pane switches (#67). This test doesn't.

## Page log lines

The pages write one console line per event, `GWPERF {json}`. GeckoView passes page console output
to logcat under the `GeckoConsole` tag. Every line has `run`, `nonce` (the runner's id for that page
load) and `t`:

| `t` | Fields |
|---|---|
| `refdash` | Reference dashboard: `tier`, `protocol`, `nodes`, `cards`, `css` (viewport), `dpr`, `scroll_height`, `fcp_ms`, `lcp_ms`, `load_ms` (all after `performance.timeOrigin`), `origin_epoch_ms` and `epoch_ms`, which tie the page's clock to logcat's |
| `warmup` | `stage`, `n`. The page goes live; the runner wakes the screen and stops polling SurfaceFlinger |
| `measure` | `stage`, `n`. A window starts. For `updates`, the live check: `moving`, `offscreen`, `targets`, `update_every_ms` |
| `step` | `stage` (`updates`, `scroll`, or a ladder family), `n`, `window_ms` (the window's length on the page's clock), `raf` (frame stats). For `updates`: `t0` (the window's start on the page's clock), `raf_times` and `changes` (`[due, ran, transition ms, kind]`, kind 0 a state update and 1 the clock), all ms after `t0`. For `scroll`: `scrolled_px`, `first_scroll_ms`, `raf_intervals`. The runner reads SurfaceFlinger |
| `input-ready` | `x`, `y` (device pixels), `taps`. The runner taps |
| `swipe-ready` | `n`, `x`, `y1`, `y2` (device pixels), `ms`. The runner swipes once |
| `input` | `stage`, `method`, `taps`, `values`, `inp_ms` |
| `hello`, `js` | Animation test page: `ua`, `dpr`, `css`, `cores`, `load`; the JS workload's `runs`, `median_ms`, `p95_ms`, `checksum`, `expected` |
| `done`, `error` | `message` on error. At `done` the page has stopped its updates |

The warm-up page is the light page opened with `taps=0&swipes=0&warmup=1`; its lines are kept in
`refstart-<k>.json` under `warmup`, not graded.

Frame statistics are defined once, in `tools/perf/page/stats.js` and `tools/perf/perflib.py`.
`tools/perf/test/stats-cases.json` pins both to the same answers.

## Example: Aqara S1 Plus

Not graded yet under this definition. The first full run on the S1 goes here, whatever grade it gets.

What the earlier runs on the S1 (2026-11-12, kiosk 0.4.3, GeckoView 159, Android 10) established:

- `dumpsys SurfaceFlinger --latency 'SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0'`
  returns the 16.67 ms refresh period and the last 127 presented frames, about 2.1 s at 60 fps. That
  is why a window is at most 100 refreshes and the runner reads SurfaceFlinger just after it.
- The kiosk runs as six processes: the app, Gecko's GPU process, three content processes and a
  crash helper. The app process is under a third of the total PSS, so the report adds them all up.
- The kiosk is the S1's home app. After `am force-stop` the system starts it again within 0.2 s.
  GeckoView's `about:blank` follows about 3 s later, and the dashboard's `PageStart` about 1.5 s
  after that. An `open` sent at `Displayed` was dropped: the session wasn't up yet.
- The S1's CSS viewport is 960 × 480 at a device pixel ratio of 1.5.
- Gecko doesn't run CSS animations that are off screen. An animated page whose animated cards sat
  below the fold showed only its clock ticking, and read as frozen frames (p95 1017 ms). The live
  check exists because of that.
- At 128 spinning CSS boxes on the animation test page, rAF counted 118 frames in 2 s while
  SurfaceFlinger presented 64, which is why both views are kept. One quick ladder run can differ
  from a full run's at a band's edge (47 against 78 frames at 16 boxes), so read ladder counts from a
  full run's median.
- Its idle samples found the app's main process using about 40% of one core with nothing changing on
  screen ([#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105)).
- **smoke5** (80df5ab, one run, before protocol 1) showed the swipes work (`scrolled_px` 2646, 3248
  and 3324; SurfaceFlinger valid on every page; restart 2487 ms) and an order bias that decided the
  letter (E). Light, always first and right after the kiosk's dashboard, scrolled at 42.7 fps (poor)
  against heavy's 53.1 fps. Its updates read p95 50 ms against heavy's 16.7 ms, but light's first
  screen updated fading tiles and heavy's updated rows that don't fade; rAF wasn't idling (light's
  median interval was 16.64 ms). Light's first paint (907 ms on its own clock) reached the screen at
  1255 ms, 348 ms later (typical 146 ms, heavy 92 ms), so its load wasn't measured. Rebuilt with the
  first-paint settle rule, light opens in 1754 ms, and heavy in 2137 ms instead of 1357 ms: the old
  rule had let heavy settle at its load event, before its first paint was on screen. Protocol 1's
  warm-up, turning order and shared first screen answer the rest; they are not yet measured on the
  S1.

## Run the tests

```sh
python3 -m unittest discover -s tools/perf/test
node --test tools/perf/test/page.test.mjs tools/perf/test/grade.test.mjs
```

The node tests need node 24 or later, which runs `grade.ts` without a build step.

CI runs both in the `scripts` job.
