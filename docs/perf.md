# Performance test and grade

`tools/perf/run.sh` measures how a frame (a device definition, such as the S1's `aqara-s1-plus`)
runs the kiosk, and gives it a grade from **A** to **E**. The letter says **what kind of dashboard
the frame handles well**:

| Grade | Meaning |
|---|---|
| A | Handles busy, animated dashboards smoothly |
| B | Good for typical dashboards; busy ones stay fluid |
| C | Good for typical dashboards |
| D | Fine for light dashboards; animation stutters |
| E | Slow even on light dashboards |

The report opens with one line such as "Aqara S1 Plus: C, good for typical dashboards. Busy
animated dashboards average about 13 fps, with 90% of frames late." (an example of the format, not a
measured result).

The grade is measured on three fixed **reference dashboards** that this test ships: light, typical
and busy. They are seeded and load nothing from the network. So the grade doesn't change when your
Home Assistant, your dashboard or your network does. The bands underneath come from published
guidelines (Core Web Vitals, RAIL, Android vitals), not from any frame's own numbers.

The test also measures the panel as you run it (your own dashboard, memory, CPU and the panes) and
reports it separately. That part is never in the grade. It needs no app changes: every page runs in
the kiosk's own GeckoView.

## Run it

You need adb, Python 3 and the kiosk installed on the device. Let the device idle for 10 minutes
first, with the screen on, at normal room temperature.

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
| `--skip-synthetic`, `--skip-reference`, `--skip-real-use` | | Skip the test page (detail), the reference dashboards (the grade), or the real-use part. Skipping the reference dashboards makes the grade provisional |
| `--quick` | | Short ladder on the test page (1, 16 and 128 elements), to check the setup works |
| `--apk FILE` | | Add the APK's size per ABI to the report (repeat for each ABI) |
| `--idle-only LABEL` | | Take one idle sample with this label and nothing else (for [panes](#memory-and-cpu-per-pane)) |
| `--max-start-temp` | 50 | Waits up to 10 minutes for the CPU to cool below this (°C) |

A full run with `--runs 3` should take about 25 minutes (an estimate from the step timings, not yet
timed on a device). A graded run (a cold start, then the three reference dashboards with 10 taps
each) is about 1.5 minutes. A test page run is at most about 4.5 minutes, and a real-use run about
1.5 minutes.

The output directory holds the raw files, `report.json` and `report.md`. A full run needs a new or
empty directory; `--idle-only` adds samples to an existing one. `python3
tools/perf/report.py <dir>` rebuilds the report from the raw files at any time, without the device.

### What it changes on the device

- **The pages** open with the kiosk's one-off `--es open <url>` extra. The saved dashboard URL is
  **never changed**. They are served from your computer through `adb reverse`
  (`http://127.0.0.1:8089` on the device) with caching off, so every run loads them the same way.
  The device's network plays no part.
- **The screen stays on** because the runner sends the kiosk's own wake intent (`--es screen on`)
  at the start of each warm-up. No setting changes. The wake falls in the warm-up, never in a
  measured window.
- **Taps:** the runner taps each reference dashboard's "Living room" toggle (`input tap`). Nothing
  else is touched.
- **Cold starts** send the system `KEYCODE_WAKEUP` key (the kiosk's wake intent would start the
  app) and force-stop the app. A kiosk that is the device's home app, like the S1's, is started
  again by the system at once (0.2 s on the S1, 2026-11-12). Otherwise the runner starts it with
  `am start -W` after 3 s. If the run stops early, or the last cold start doesn't reach the
  dashboard, the exit trap restarts the app. While the app restarts, Home Assistant sees the panel
  go offline for a few seconds.
- Everything else is read-only: `getprop`, `/proc`, `dumpsys meminfo`, `dumpsys SurfaceFlinger
  --latency`, logcat.

## The reference dashboards

`tools/perf/page/dashboard.html?tier=…` renders a fixed Home Assistant-like dashboard. A **live
strip** of small cards sits at the top, under the tabs. It holds everything that moves or changes
once the page is live, so all of it is on screen on any frame. Below it are 44 static cards (a
"Living room" toggle, weather, 12 entities cards with 6 rows each, 24 tiles, 4 sensor cards with a
line chart, 2 gauges). Each card is a custom element with its own shadow root, and each row nests
icon, name and state the way HA's rows do. The page renders from a seeded 400-entity states payload
after it loads, as HA's frontend does. The icons are simple SVGs drawn for it, the font is the
system font, and nothing comes from the network. With the strip, the pages have 1185 (light), 1248
(typical) and 1491 (busy) elements, counted with the shadow roots (headless Chrome, 2026-11-12). The
three tiers fill the strip (`tools/perf/page/tiers.js`):

| Tier | What it adds | Stands for |
|---|---|---|
| **Light** | 5 sensor chips whose states change every 2 s. Nothing animates | Most dashboards: cards, icons and text. A real one on the S1 presented about 0.1 frames/s at idle |
| **Typical** | Light, plus 4 spinning fan icons, 2 pulsing icons, a progress ring and a clock that ticks every second | A dashboard with HA's default animated bits |
| **Busy** | Typical, plus 6 more spinners, a line chart redrawn every frame, and 24 chips of which a tenth update their numbers every frame | Many animations and frequent updates (the test page's old reference load) |

**Why the strip.** Gecko doesn't run CSS animations that are off screen. In the first version the
animated cards came after the static ones. On the S1's 960 × 480 CSS viewport that put them all
below the fold, so Typical showed a still page apart from its clock. SurfaceFlinger saw 3 frames in
1.67 s, and the report read that as frozen frames: p95 1017 ms, 2.4 fps (the S1 smoke run of 6f32a54,
2026-11-12). On the S1's viewport the strip ends at about 270 CSS px on Busy.

**The live check.** When the measured window starts, the page logs how many of its moving elements
and its toggle are off screen, and how many of its CSS animations are running. A tier's frames are
graded only when nothing is off screen and every expected animation runs (Typical 7, Busy 13).
Otherwise its frames are "not measured", and the grade is provisional. If the toggle is off screen,
its taps aren't graded either. So a frame with a small or narrow screen can't get a still page read
as a frozen one.

Each page runs the same phases:
1. It renders while still, so its load can end. It reports its load times.
2. It stays still for 2 s, then goes live: the tier's updates and animations start.
3. Warm-up of 1 s, the live check, then one measured window of 100 display refreshes (1.67 s at 60 Hz).
4. The taps on the toggle, while the tier stays live.

The tiers, the card mix and the DOM size are **project choices**. They were fixed before any frame
was graded, and a test pins them (`test/page.test.mjs`). A real dashboard with cameras, maps or many
custom cards is heavier than Busy. The tiers make frames comparable with each other, not with your
dashboard.

## What it measures

### Graded: the reference dashboards

| What | How |
|---|---|
| Frames | Two views of the measured window. SurfaceFlinger (`dumpsys SurfaceFlinger --latency`) gives every frame of the kiosk's GeckoView surface that reached the screen. `requestAnimationFrame` gives the page's main-thread frames. They disagree both ways. CSS animations run on the compositor, which can drop frames while the main thread keeps time: in the S1 smoke run (a 2 s window), rAF counted 118 frames at 128 spinning boxes while SurfaceFlinger presented 64. And a busy main thread can stall while the compositor still moves something. On Typical and Busy, the **worse** of the two counts, and `limited_by` says which: `compositor`, `main-thread`, or `equal`. On Light nothing is meant to move, and a still screen presents nothing, which isn't a stall. So Light's frames come from rAF only, and show whether the main thread keeps up while states change |
| SurfaceFlinger's data | It keeps the last 127 frames, read over adb just after the window. The window is 100 refreshes, so the other 27 slots (450 ms at 60 Hz) cover that read, and the page's own logged window length marks the window's end, not the read. The data is complete when the history reaches back before the window. Then every gap inside the window counts, and so do gaps at its edges if they are longer than 1.5 refreshes, so a stall anywhere shows. If the history doesn't reach back (`sf_valid: false`), that window uses rAF only |
| INP | 10 taps on the toggle while the tier is live. The tap updates the toggle and a card. Event Timing gives the delay, the handler time and the time to the next paint, which is what INP measures. Taps under 16 ms aren't reported by Event Timing; they count as 16 ms |
| Load | From the page's navigation start to **settled**: the first frame followed by 750 ms with no new frame, where that quiet reaches past the page's load event, and never earlier than the load event. The page's times (navigation start, first paint, load event) come from its own clock, tied to logcat's by its log line. Load is a number only when it was measured. With no load event, no frames on screen, no quiet before the page goes live, or a settle before its first paint, that run's load is "not measured", never 0 |
| Start | A cold start: from the app's process start (`Start proc` in logcat) to the engine being ready, which is GeckoView's own first page (`PageStart about:blank`). See [Why start is engine ready](#why-start-is-engine-ready) |

Each graded run is one cold start, then the three reference dashboards in turn. They are opened once
the kiosk's own first page has gone quiet (or after 30 s if it never loads, for example with Home
Assistant down), so they never compete with it. While they are measured, any other page the kiosk
loads, before a reference page starts or after, makes the run an error. That run isn't graded.

### Reported, not graded

| What | How |
|---|---|
| Animation ladder (test page) | Five families of animated elements, each doubling from 1 to 1024: CSS transform, CSS opacity, SVG, canvas 2D, and HA-like tiles whose numbers change every frame. Per step: 1 s warm-up, one window, 1.2 s still. A family stops at its first *poor* step, by the grade's rule (`tools/perf/page/rubric.json`). The report gives the largest count held at 60 fps and at 30 fps |
| Scroll (test page) | A list of 240 tiles in its own scroller, scrolled by script 6 px a frame through one window. `scrolled_px` shows that it moved; a scroll that moved nothing is flagged, not measured |
| JS speed (test page) | An HA-like state update for 400 entities: parse, format through a template, diff, sort, update the changed tiles, force a layout. 30 timed after 5 warm-ups. A checksum shows that the work ran |
| Test page load | Its Largest Contentful Paint (or FCP on an engine without LCP; `page_lcp_from` says which) |
| Android's TTID | The `Displayed …: +Nms` line of each cold start |
| Your dashboard | A cold start back to your own dashboard: process start to its `PageStart`, its load to settled, and the whole wait from the force-stop. A run that doesn't finish within 120 s is recorded as an error |
| Memory | PSS of every app process (the app, Gecko's GPU, content and crash helper processes), the main process's App Summary, each process's peak RSS (`VmHWM`), the radio's processes, and `MemTotal`/`MemAvailable`, at idle on your dashboard and at the run's peak |
| CPU at idle | Jiffies of every app and radio process over 15 s, and the whole device's busy share |
| Idle frames | Frames your dashboard presents while nobody touches it |
| Panes | What each pane adds; see [Memory and CPU per pane](#memory-and-cpu-per-pane) |
| Device | Model, Android version and API, ABI, SoC, cores, max clock, RAM, display, app version, APK size, GeckoView version (from the running engine's user agent), temperatures |

Your dashboard isn't scrolled. Scrolling it would need touch input, and a drag on a dashboard can
move a slider or flip a switch.

## The bands

Each measurement gets a band, as in Core Web Vitals: **good**, **needs improvement** or **poor**.
The thresholds live in one file, `tools/perf/page/rubric.json`. `grade.py` grades from it, and the
test page reads it to stop the ladder, so the two can't disagree. The sources were checked on
2026-11-12.

| Measurement | Good | Poor | Source |
|---|---|---|---|
| Frame time, p95 | ≤ 17.17 ms (one frame at 60 Hz, 16.67 ms, plus 0.5 ms slack) | > 33.83 ms (two frames plus slack) | [RAIL](https://web.dev/articles/rail): "the maximum budget for each frame is 16 ms (1000 ms / 60 frames per second ≈ 16 ms)". [Android vitals](https://developer.android.com/topic/performance/vitals/render): slow frames take longer than 16 ms |
| Jank rate (frames over 16.7 ms) | ≤ 5% | > 50% | Poor: [Play Console](https://support.google.com/googleplay/android-developer/answer/9844486) "excessive slow frames", more than 50% of frames over 16 ms. Good: the same as a p95 within budget |
| Frozen frames (over 700 ms) | 0% (**a project choice**) | > 0.1% | [Android vitals](https://developer.android.com/topic/performance/vitals/render): frozen frames take longer than 700 ms; Play Console "excessive frozen frames" is more than 0.1% |
| INP: worst tap to next paint | ≤ 200 ms | > 500 ms | [Core Web Vitals INP](https://web.dev/articles/inp): good at or below 200 ms, poor above 500 ms |
| Load, navigation start to settled | ≤ 2.5 s | > 4 s | [Core Web Vitals LCP](https://web.dev/articles/lcp): "Good LCP values are 2.5 seconds or less, poor values are greater than 4.0 seconds". Settled is at or after the largest paint, so this is stricter than LCP |
| Start, process start to engine ready | ≤ 2.5 s (**a project choice**: LCP's bound) | ≥ 5 s | [Android vitals](https://developer.android.com/topic/performance/vitals/launch-time) counts a cold start that "takes 5 seconds or longer" as excessive (vitals apply it to TTID) |

A frames band is the worst of its three measurements. **Smooth** (60 fps) means the frames band is
good. **Fluid** (30 fps) means it is good or needs improvement. A window holds about 100 frames, so
a single frozen frame is 1% of it, which is poor.

Frame times are snapped to whole display refreshes before the percentile is taken. On a 60 Hz
display, a 16.9 ms frame made its refresh, and a 33 ms frame missed one. Without snapping, timer
jitter would count frames that were on time as late. The 0.5 ms slack covers displays that run
slightly under 60 Hz. The budget stays 16.7 ms on a faster display: a 90 Hz panel's 22 ms frame
counts as over budget.

## The grade

The first letter whose every requirement holds is the grade:

| Grade | Busy | Typical | Light |
|---|---|---|---|
| A | smooth; INP good; load good | INP good; load good | INP good; load good |
| B | fluid; INP good; load good | smooth; INP good; load good | INP good; load good |
| C | (not needed) | fluid; INP good; load good or needs improvement | INP good; load good or needs improvement |
| D | (not needed) | (not needed) | fluid; INP not poor; load not poor |
| E | none of the above | | |

**A poor start caps the grade at C.** A panel whose engine takes 5 s or more to come up is
not an A or B panel, however well it renders afterwards. A cap never raises a letter.

A measurement that is missing fails whatever needs it, and the grade is marked **provisional**,
with what is missing listed. The headline adds why the letter isn't higher. For the busiest tier
that isn't smooth, it gives the average fps and the share of frames that were late. It names any
poor INP or load, and says if the start capped it.

`tools/perf/grade.py` holds this table (`LETTERS`). `tools/perf/test/test_grade.py` checks each
letter's boundary: a frame that just meets a letter gets it, and moving any one requirement past its
edge drops it.

### Not tuned to any frame

The tiers and the letter table were set before any frame had a grade. They come from the cited
bands (16.7 ms for smooth, 33.3 ms for fluid, INP 200 and 500 ms, LCP 2.5 and 4 s) and the page
definitions above. The S1 is just the first frame graded. Its result goes in this document
whatever letter it gets. The thresholds, the tiers and the table must not be moved to change a
particular frame's letter. Change them only for a reason that holds for every frame, in their own
PR, with the docs and tests.

### Why start is engine ready

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
Timing your own dashboard would make the grade depend on your Home Assistant.

An earlier version opened a reference page straight after `Displayed` and timed its first paint.
On the S1 that `open` was dropped: until GeckoView's session is up, the kiosk ignores `open`
(#103's pins found the same). Sending it once the session is up races the kiosk's own dashboard
load, which already talks to Home Assistant. So start ends at **engine ready**, the engine's own
first page (`about:blank`). That comes before the kiosk does anything for Home Assistant. Both ends
are stamped on the device in logcat, so the harness's adb round trips aren't in it. The first paint
of a real page is graded as part of load, on the reference dashboards. On frames that aren't the
home app, the runner waits 3 s before it starts the app itself; that wait isn't in start either,
because start begins at the process start.

## What the grade does and doesn't tell you

- It describes **this frame running this app version** on the reference dashboards. It doesn't
  change with your Home Assistant, your dashboard or your network. It doesn't say how fast **your**
  dashboard is: that is in the report's real-use section, which isn't graded.
- **Heat matters.** A warm device throttles its CPU. The runner waits for the CPU to drop below
  50 °C and logs every thermal zone at each stage (`thermal.tsv`). Compare runs only at similar
  temperatures.
- **Background load matters.** The radio, voice wake word and Sendspin run during every part, as
  they do in daily use. That is the one way the panel's own setup can reach the grade: a pane that
  uses the CPU all the time (on the S1, see [#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105))
  slows the reference dashboards too. The device's idle busy share is in the report.
- **Screen brightness** doesn't change the work the app does. Over a long run it can add heat.
- The device's busy share at idle includes the runner's own `dumpsys` calls. During the reference
  dashboards' windows and taps, the runner doesn't call `dumpsys`.
- The ladder's counts are for **the test page's** elements. A Home Assistant card is heavier than a
  test tile, so treat the counts as a comparison between frames, not a card budget.
- The input test taps one toggle. Real interactions (opening a more-info dialog) do more work.
- `source: raf` on a window means the main thread was the worse view, or SurfaceFlinger's history
  didn't reach back to the window's start (`sf_valid: false`), or (on Light) nothing was meant to
  move. The report counts test page windows of the second kind (`steps_without_sf`).
- The memory budget's `ok` is `false` if any check fails. It is `null` when none fails but a
  budgeted value wasn't measured, and `true` only when every check was measured and passed.

## Not in the grade

These have no published standard, or depend on your own Home Assistant:

| What | Band or budget | Why this choice |
|---|---|---|
| JS state update (median) | good ≤ 16.7 ms, poor > 50 ms | Good: the update fits in one frame. Poor: past RAIL's 50 ms input-processing budget ("process user input events within 50 ms"), which is also the [Long Tasks](https://www.w3.org/TR/longtasks-1/) cut-off (that draft is now discontinued). The 400-entity size stands for a larger home |
| App memory, peak PSS of all app processes | ≤ 50% of `MemTotal` | An always-on panel shares its RAM with the system, vendor daemons and the radio. Half for the app leaves room for them |
| Device memory, lowest `MemAvailable` during the run | ≥ 15% of `MemTotal` | Headroom for the system and the radio. A first choice, not a measured limit; change it in the same PR as the evidence |
| CPU at idle | reported only | No standard. Lower means less heat and power on a panel that never sleeps |
| Your dashboard's start and load | reported only | Your dashboard, your network and your Home Assistant, so not comparable between panels |
| Idle frames | reported only | A still dashboard should present almost nothing. A clock or an animated card adds frames |

The memory budgets are in `tools/perf/budgets.json`, with a default and a per-frame override. An
override names only the keys it changes; the rest come from the default.

## Memory and CPU per pane

Not graded: it depends on your panes and your Home Assistant. What each pane adds is measured by
turning that pane off through its Home Assistant entity and comparing with everything on. You need
an admin account in Home Assistant, and a panel you are allowed to disturb: while a pane is off, the
panel doesn't play music or hear its wake word. The runner doesn't talk to Home Assistant. You
toggle the entity, and take an idle sample after each change. Alternate on and off, three times
each. Use a separate output directory: the full run's `all-on` samples come 30 s after a cold
start, and a panel that has been up for a while may use more memory, so mixing them could bias the
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
| `schema` | `"glassworks.perf/3"`. A field that changes meaning gets a new version (3: the three reference dashboards and their letter; 2 was one reference dashboard; 1 graded your own dashboard) |
| `frame` | `id`, `name`, `device` (model, Android, API, ABI, SoC, CPU, RAM, display, temperatures), `app` (package, version, APK bytes on the device, GeckoView version) |
| `apk` | One entry per APK given with `--apk`: `abis`, `bytes`, `parts_bytes` (dex, native, assets, resources, other) |
| `grade` | `grade`, `meaning`, `headline`, `bands` (per tier: `frames`, `inp`, `load`), `start` (value and band), `capped_by_start`, `provisional`, `missing`, `rubric_version`. Computed from `graded` only |
| `graded` | `reference`: `start_ms`, `android_ttid_ms` (not graded), `launch`, `own_page_quiet`, `errors`, `per_run`, and `tiers` with, per tier, `frames` (p95, jank, frozen, fps), `sources`, `limited_by`, `inp_ms`, `input_method`, `load_ms`, `load_errors`, `fcp_ms`, `nodes` |
| `detail` | `synthetic`, the test page: `ladder` (per family: `held_60fps`, `held_30fps`, `…_at_top`), `steps_without_sf`, `scroll`, `scroll_moved`, `js_burst_ms`, `js_band`, `js_checksum_ok`, `page_lcp_ms`, `page_lcp_from`, `per_run` |
| `real_use` | Not graded. Your dashboard: `app_start_to_dashboard_ms`, `android_ttid_ms`, `launch`, `stop_to_dashboard_ms`, `dashboard_load_ms`, `dashboard_page_stop_ms`, `settle_how`, `errors`, `per_run`. Then `idle`, `peak`, `memory` (`idle`, `peak`, `budget`) and `panes` |

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
| `hello` | Test page: `ua`, `dpr`, `css` (viewport), `cores`, `load` (`fetch_ms`, `dcl_ms`, `load_ms`, `fcp_ms`, `lcp_ms`, `lcp_from`) |
| `refdash` | Reference dashboard: `tier`, `nodes`, `cards`, `fcp_ms`, `lcp_ms`, `load_ms` (all after `performance.timeOrigin`), `origin_epoch_ms` and `epoch_ms`, which tie the page's clock to logcat's |
| `warmup` | `stage`, `n`. The runner wakes the screen (and, on a reference dashboard, stops polling SurfaceFlinger) |
| `measure` | `stage`, `n`. The measured window starts |
| `step` | `stage`, `n`, `window_ms` (the window's length on the page's clock), `raf` (frame stats). The runner reads SurfaceFlinger |
| `input-ready` | Reference dashboard: `x`, `y` (device pixels), `taps`. The runner taps |
| `input` | `stage`, `method`, `taps`, `values`, `inp_ms` |
| `js` | Test page: `runs`, `median_ms`, `p95_ms`, `checksum`, `expected` |
| `done`, `error` | `message` on error |

Frame statistics are defined once, in `tools/perf/page/stats.js` and `tools/perf/perflib.py`.
`tools/perf/test/stats-cases.json` pins both to the same answers.

## Example: Aqara S1 Plus

Not graded yet. The first full run on the S1 goes here, whatever grade it gets.

These read-only checks were done on the S1 on 2026-11-12 (kiosk 0.4.3, GeckoView 159, Android 10):

- `dumpsys SurfaceFlinger --latency 'SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0'`
  returns the 16.67 ms refresh period and the last 127 presented frames, about 2.1 s at 60 fps. That
  is why a measured window is 100 refreshes and the runner reads SurfaceFlinger just after it.
- The kiosk runs as six processes: the app, Gecko's GPU process, three content processes and a
  crash helper. The app process is under a third of the total PSS, so the report adds them all up.
- logcat has `ActivityTaskManager: Displayed sh.edm.s1kiosk/.MainActivity: +Nms` and GeckoView's
  `GeckoView:PageStart` and `PageStop` lines (debug level, present in the release build).
- The kiosk is the S1's home app. After `am force-stop` the system starts it again within 0.2 s.
  GeckoView's `about:blank` follows about 3 s later, and the dashboard's `PageStart` about 1.5 s
  after that. An `open` sent at `Displayed` was dropped (the full run of 12e7172, 09:29 panel time):
  the session wasn't up yet.

The smoke run of 6f32a54 (`--quick`, one run) found the off-screen animations described under
[The reference dashboards](#the-reference-dashboards). Its test page ladder also showed how much one
run varies at a band's edge. At 16 spinning boxes, SurfaceFlinger presented 47 frames in the window,
evenly spread, against 78 in each of the three runs of the earlier full run, at similar temperatures
and load. Quick mode measures the same way as a full run; the difference is the compositor's rate
from run to run. Read ladder counts from a full run's median, not from one quick run.

A `--quick` smoke run (one run, 2026-11-12, an earlier version with a 2 s window) showed the test
page working through GeckoView. Its lines reached logcat and the JS checksum matched. At 128
spinning CSS boxes, rAF counted 118 frames in 2 s while SurfaceFlinger presented 64, which is why
both views are kept. Its scroll stage moved nothing, which led to the scroller fix. Its idle samples
found the app's main process using about 40% of one core with nothing changing on screen
([#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105)).

## Run the tests

```sh
python3 -m unittest discover -s tools/perf/test
node --test tools/perf/test/page.test.mjs
```

CI runs both in the `scripts` job.
