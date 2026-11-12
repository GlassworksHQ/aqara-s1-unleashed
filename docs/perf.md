# Performance test and grade

`tools/perf/run.sh` measures how a frame (a device definition, such as the S1's `aqara-s1-plus`)
runs the kiosk. It tells you how many animations the frame can handle, how fast the dashboard loads,
how quickly a tap shows on screen, and how much memory and CPU the app and each pane use. It ends
with a grade from **A** to **E**.

The grade uses fixed thresholds taken from published guidelines (Core Web Vitals, RAIL, Android
vitals). It does not depend on the numbers of whichever device we tested first. A grade means the
same thing on every frame.

It needs no app changes. The test page runs inside the kiosk's own GeckoView.

## Run it

You need adb, Python 3 and the kiosk installed on the device. Let the device idle for 10 minutes
first, with the screen on, at normal room temperature.

```sh
tools/perf/run.sh --frame aqara-s1-plus --runs 3 --out perf-s1
```

| Option | Default | What |
|---|---|---|
| `--frame` | (required) | The frame id that goes in the report |
| `--serial` | `$ANDROID_SERIAL` | Which device |
| `--runs` | 3 | Repeats of each part. The report gives the median and range |
| `--taps` | 10 | Taps for the input test, passed to the page in its URL (0 skips the stage; the grade is then provisional) |
| `--skip-synthetic`, `--skip-real-use` | | Run only one part |
| `--quick` | | Short ladder (1, 16 and 128 elements), to check the setup works |
| `--apk FILE` | | Add the APK's size per ABI to the report (repeat for each ABI) |
| `--idle-only LABEL` | | Take one idle sample with this label and nothing else (for [panes](#memory-and-cpu-per-pane)) |
| `--max-start-temp` | 50 | Waits up to 10 minutes for the CPU to cool below this (°C) |

A full run with `--runs 3` should take under 20 minutes (an estimate from the step timings, not yet
timed on a device). A synthetic run is at most about 4.5 minutes, and less on a slow device,
because the ladder stops sooner. A real-use run is about 1.5 minutes.

The output directory holds the raw files, `report.json` and `report.md`. `python3
tools/perf/report.py <dir>` rebuilds the report from the raw files at any time, without the device.

### What it changes on the device

- **The test page** opens with the kiosk's one-off `--es open <url>` extra. The saved dashboard URL
  is **never changed**. The page is served from your computer through `adb reverse`
  (`http://127.0.0.1:8089` on the device). The device's network plays no part, and no firewall is
  in the way.
- **The screen stays on** because the runner sends the kiosk's own wake intent (`--es screen on`)
  at the start of each warm-up. No setting changes. The wake falls in the warm-up, never in a
  measured window. On the S1 the night idle timeout is 20 s, and no measured window is longer than
  15 s.
- **Taps:** for the input test, the runner taps the middle of the test page (`input tap`). Nothing
  else is touched.
- **Cold starts** send the system `KEYCODE_WAKEUP` key (the kiosk's wake intent would start the
  app) and force-stop the app. A kiosk that is the device's home app, like the S1's, is started
  again by the system at once (0.2 s on the S1, 2026-11-12); that relaunch is the cold start.
  Otherwise the runner starts it with `am start -W` after 3 s. Each restart comes back on the saved
  dashboard, so the last one also puts the dashboard back. If the run stops early, the exit trap
  restarts the app for the same reason. While the app restarts, Home Assistant sees the panel go
  offline for a few seconds.
- Everything else is read-only: `getprop`, `/proc`, `dumpsys meminfo`, `dumpsys SurfaceFlinger
  --latency`, logcat.

## What it measures

There are two parts. The **synthetic** part is a fixed test page, the same for every frame. The
**real-use** part is the panel as people run it: the real dashboard, with every pane on.

| What | How | Where it comes from |
|---|---|---|
| Animation ladder | Five families of animated elements, each doubling from 1 to 1024: CSS transform, CSS opacity, SVG, canvas 2D, and Home Assistant-like tiles whose numbers change every frame. Per step: 1 s warm-up, a measured window, 1.2 s still. The window is 2 s, or 120 refreshes on displays faster than 60 Hz (1.33 s at 90 Hz), so it fits in SurfaceFlinger's 127-frame history. A family stops at its first *poor* step, by the same rule the grade uses (`tools/perf/page/rubric.json`) | Test page |
| Frame times | Two views of each window. SurfaceFlinger (`dumpsys SurfaceFlinger --latency`) gives every frame of the kiosk's GeckoView surface that reached the screen. `requestAnimationFrame` gives the page's main-thread frames. They disagree both ways: CSS animations run on the compositor, which can drop frames while the main thread keeps time (on the S1, 64 of 118 frames at 128 spinning boxes); and a busy main thread can stall while the compositor still moves something. Each step is graded on the **worse** of the two, and the report says which one limited it (`limited_by`: `compositor` or `main-thread`). SurfaceFlinger counts when its frames span at least 80% of the window; a screen that didn't change has no frames, and then rAF alone is used | SurfaceFlinger; page |
| Reference load | One fixed mix that stands for a busy wall dashboard: 24 updating tiles, 6 spinning icons and a sparkline redrawn every frame | Test page |
| Scroll | A list of 240 tiles in its own scroller, scrolled by script 6 px a frame through one window. `scrolled_px` shows that it moved; a scroll that moved nothing is flagged, not measured. Reported, not graded | Test page |
| Input (tap to paint) | 10 taps on a toggle while the reference load runs. Event Timing gives the delay, the handler time and the time to the next paint, which is what INP measures. Taps under 16 ms aren't reported by Event Timing; they count as 16 ms | Test page |
| JS speed | A Home Assistant-like state update for 400 entities: parse the JSON, format every entity through a template, diff against the last update, sort the changes, update the changed tiles, then force a layout. 30 timed after 5 warm-ups. A checksum shows that the work ran | Test page |
| Test page load | Largest Contentful Paint of the page itself. An engine without LCP reports First Contentful Paint instead (an earlier paint), and the report says which (`page_lcp_from`) | Test page |
| Cold start | Time to initial display: Android's own `Displayed …: +Nms` line, after the force-stop. The report records how the app came back (`launch`: `relaunched-by-system` or `am-start`) and the whole restart, from the force-stop to a settled dashboard. If no dashboard finishes loading within 120 s, that run is recorded as an error, isn't graded, and the run goes on | logcat |
| Dashboard load | From GeckoView's `PageStart` for the dashboard to the last frame before the dashboard stops painting for 750 ms, after its load event (`PageStop`). If the dashboard never goes still, for example because something on it always animates, the `PageStop` time is used and the report says `no-quiet-gap` | logcat, SurfaceFlinger |
| Memory | PSS of every app process (the app, Gecko's GPU, content and crash helper processes), the main process's App Summary (Java heap, native heap, code, graphics…), each process's peak RSS (`VmHWM`), the radio's processes, and `MemTotal`/`MemAvailable`. At idle on the dashboard, and the highest sample of the run (peak) | `dumpsys meminfo`, `/proc` |
| CPU at idle | Jiffies of every app and radio process over 15 s, and the whole device's busy share | `/proc/stat`, `/proc/<pid>/stat` |
| Idle frames | Frames the dashboard presents while nobody touches it | SurfaceFlinger |
| Device | Model, Android version and API, ABI, SoC, cores, max clock, RAM, display size and refresh period, app version, APK size, GeckoView version (from the running engine's user agent), temperatures | `getprop`, `/proc`, `wm`, `dumpsys` |

The dashboard isn't scrolled in the real-use part. Scrolling it would need touch input, and a drag
on a dashboard can move a slider or flip a switch. The synthetic scroll stage stands in for it.

## The rubric

Each metric gets a band, as in Core Web Vitals: **good**, **needs improvement** or **poor**.

| Metric | Good | Poor | Source |
|---|---|---|---|
| Frame time, p95, at the reference load | ≤ 17.17 ms (one frame at 60 Hz, 16.67 ms, plus 0.5 ms slack) | > 33.83 ms (two frames plus slack) | [RAIL](https://web.dev/articles/rail): "the maximum budget for each frame is 16 ms (1000 ms / 60 frames per second ≈ 16 ms)". [Android vitals](https://developer.android.com/topic/performance/vitals/render): slow frames take longer than 16 ms |
| Jank rate at the reference load (frames over 16.7 ms) | ≤ 5% | > 50% | Poor: [Play Console](https://support.google.com/googleplay/android-developer/answer/9844486) "excessive slow frames", more than 50% of frames over 16 ms. Good: the same as a p95 within budget |
| Frozen frames at the reference load (over 700 ms) | 0% (**a project choice**) | > 0.1% | [Android vitals](https://developer.android.com/topic/performance/vitals/render): frozen frames take longer than 700 ms; Play Console "excessive frozen frames" is more than 0.1% |
| Load (the dashboard; the test page if there is no dashboard run) | ≤ 2.5 s | > 4 s | [Core Web Vitals LCP](https://web.dev/articles/lcp): "Good LCP values are 2.5 seconds or less, poor values are greater than 4.0 seconds" |
| Input: worst tap to next paint | ≤ 200 ms | > 500 ms | [Core Web Vitals INP](https://web.dev/articles/inp): good at or below 200 ms, poor above 500 ms |
| Cold start (time to initial display) | ≤ 2.5 s | ≥ 5 s | Poor: [Android vitals](https://developer.android.com/topic/performance/vitals/launch-time) counts a cold start that "takes 5 seconds or longer" as excessive, measured as TTID. Good: **a project choice**. No source gives a good bound, so we use LCP's 2.5 s, because a kiosk cold start ends in a page load |

Between good and poor is **needs improvement**. The sources were checked on 2026-11-12. The
thresholds live in one file, `tools/perf/page/rubric.json`. `grade.py` grades from it, and the test
page reads it to stop the ladder, so the two can't disagree.

A measured window holds about 120 frames, so a single frozen frame is 0.8% of it, which is poor.
One freeze of 700 ms or more in a busy dashboard is enough to grade the frames part poor.

Frame times are snapped to whole display refreshes before the percentile is taken. On a 60 Hz
display, a 16.9 ms frame made its refresh, and a 33 ms frame missed one. Without snapping, timer
jitter would count frames that were on time as late. The bounds have 0.5 ms of slack for displays
that run slightly under 60 Hz. The budget stays 16.7 ms on a faster display: a 90 Hz panel's
22 ms frame counts as over budget.

### The grade

The metrics form four parts. A part's band is the worst band among its metrics:

| Part | Metrics |
|---|---|
| Frames | p95, jank rate, frozen frames (at the reference load) |
| Load | dashboard load |
| Input | worst tap to paint |
| Start | cold start |

| Grade | When |
|---|---|
| A | All four parts good |
| B | No part poor, one needs improvement |
| C | No part poor, two or more need improvement |
| D | One part poor |
| E | Two or more parts poor |

The letters are a project choice. They follow the published bands and nothing else. If a part
wasn't measured (for example, `--taps 0`), the grade is marked **provisional** and the report lists
what is missing. `tools/perf/grade.py` holds the table, and `tools/perf/test/test_grade.py` tests
every threshold on both sides of its edge.

### How many animations it can handle

For each ladder family, the report gives the largest element count that a frame **holds at 60 fps**
(the step's frames band is good) and **at 30 fps** (good or needs improvement). That is the step
before the first one that missed. A `+` means the frame held at the ladder's last step (1024), so
it can do more. These counts describe the frame; they aren't in the letter. The reference load is
in the letter.

The reference load and the ladder's steps are project choices. They are fixed, seeded (the same
positions and colours on every run) and the same on every frame, so counts compare across frames.

## Not in the grade

These have no published standard. They get their own sections in the report, with bands we chose:

| What | Band or budget | Why this choice |
|---|---|---|
| JS state update (median) | good ≤ 16.7 ms, poor > 50 ms | Good: the update fits in one frame. Poor: past RAIL's 50 ms input-processing budget ("process user input events within 50 ms"), which is also the [Long Tasks](https://www.w3.org/TR/longtasks-1/) cut-off (that draft is now discontinued). The 400-entity size stands for a larger home |
| App memory, peak PSS of all app processes | ≤ 50% of `MemTotal` | An always-on panel shares its RAM with the system, vendor daemons and the radio. Half for the app leaves room for them |
| Device memory, lowest `MemAvailable` during the run | ≥ 15% of `MemTotal` | Headroom for the system and the radio. A first choice, not a measured limit; change it in the same PR as the evidence |
| CPU at idle | reported only | No standard. Lower means less heat and power on a panel that never sleeps |
| Idle frames | reported only | A still dashboard should present almost nothing. A clock or an animated card adds frames |

The memory budgets are in `tools/perf/budgets.json`, with a default and a per-frame override. An
override names only the keys it changes; the rest come from the default.

## What the grade does and doesn't tell you

- It describes **this frame running this app version**, on the reference load and the dashboard you
  ran. A heavier dashboard (cameras, maps, many custom cards) loads slower than the one tested.
- **Heat matters.** A warm device throttles its CPU. The runner waits for the CPU to drop below
  50 °C and logs every thermal zone at each stage (`thermal.tsv`). Compare runs only at similar
  temperatures.
- **Background load matters.** The radio, voice wake word and Sendspin all run during the real-use
  part, as they do in daily use. The device's idle busy share is in the report.
- **Screen brightness** doesn't change the work the app does. Over a long run it can add heat.
- The device's busy share at idle includes the runner's own `dumpsys` calls. The app's and the
  radio's CPU numbers are their own processes only.
- The ladder's counts are for **this page's** elements. A Home Assistant card is heavier than a
  test tile, so treat the counts as a comparison between frames, not a card budget.
- The input test taps one toggle. Real interactions (opening a more-info dialog) do more work.
- `source: raf` on a step means either the main thread was the worse of the two
  (`limited_by: main-thread`), or the screen didn't change during the window, so SurfaceFlinger had
  nothing to count (`sf_valid: false`). The report counts the second kind (`steps_without_sf`).

## Memory and CPU per pane

What each pane adds is measured by turning that pane off through its Home Assistant entity and
comparing with everything on. You need an admin account in Home Assistant, and a panel you are
allowed to disturb: while a pane is off, the panel doesn't play music or hear its wake word. The
runner doesn't talk to Home Assistant. You toggle the entity, and take an idle sample after each
change. Alternate on and off, three times each. Use a separate
output directory: the full run's `all-on` samples come 30 s after a cold start, and a panel that has
been up for a while may use more memory, so mixing them could bias the difference.

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

## The report (`glassworks.perf/1`)

`report.json` has one frame per file. Its top-level keys:

| Key | What |
|---|---|
| `schema` | `"glassworks.perf/1"`. A field that changes meaning gets a new version |
| `frame` | `id`, `device` (model, Android, API, ABI, SoC, CPU, RAM, display, temperatures), `app` (package, version, APK bytes on the device, GeckoView version) |
| `apk` | One entry per APK given with `--apk`: `abis`, `bytes`, `parts_bytes` (dex, native, assets, resources, other) |
| `grade` | `grade`, `provisional`, `missing`, `components` (each part's band), `metrics` (each value and band), `load_from`, `rubric_version` |
| `synthetic` | `ladder` (per family: `held_60fps`, `held_30fps`, `…_at_top`), `reference` (with `limited_by`), `reference_sources`, `reference_limited_by`, `steps_without_sf`, `scroll`, `scroll_moved`, `js_burst_ms`, `js_band`, `js_checksum_ok`, `inp_ms`, `input_method`, `page_lcp_ms`, `page_lcp_from`, and `per_run` with every step (`source`, `limited_by`, `sf_valid`, and both the rAF and SurfaceFlinger stats) |
| `real_use` | `cold_start_ttid_ms`, `launch`, `stop_to_dashboard_ms`, `dashboard_load_ms`, `dashboard_page_stop_ms`, `settle_how`, `idle` (memory by process role, main process breakdown, radio, device RAM in use, CPU, idle frames), `peak`, `errors`, `per_run` |
| `memory` | `idle`, `peak` and `budget` (each check's value, budget and `ok`; `ok` is `null` when a budgeted value wasn't measured) |
| `panes` | One entry per pane: `id`, `method` (`off-diff` or `process`), `pss_kb`, `cpu_pct_of_core`, the noise, and whether each is measurable |

Repeated measurements are given as `{"median", "min", "max", "n"}`.

### How #68 uses it

[#68](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/68) is the CI report of APK size
and RAM per frame and pane, with budgets. It can read these reports as they are:

- **Per frame:** one `report.json` per frame, keyed by `frame.id`. Its job compares `memory.peak` and
  `memory.idle.app_pss_kb` with the frame's RAM budget, and `apk[].bytes` with the frame's size
  budget, per ABI.
- **Per pane:** `panes[]` uses the pane ids from the code (`sendspin`, `assist`, `wall-base`,
  `radio`), so a pane's RAM budget is checked against `panes[id].pss_kb`. #68 measures on an
  emulator, each pane on by itself against all off. It writes its results in the same shape, with
  its own `method` value.
- **Budgets** live in `tools/perf/budgets.json` until #68 moves them into each frame's Gradle config.
  Over budget fails the check unless the same PR raises the budget.
- `apk[].parts_bytes` is the start of #68's split; #68 adds each pane's dex, assets and native
  libraries.

#68 waits for the Gradle modules (#21) and the pane switches (#67). This test doesn't.

## Page log lines

The page writes one console line per event, `GWPERF {json}`. GeckoView passes page console output
to logcat under the `GeckoConsole` tag. Every line has `run` and `nonce` (the runner's id for that
run) and `t`:

| `t` | Fields |
|---|---|
| `hello` | `ua`, `dpr`, `css` (viewport), `cores`, `load` (`fetch_ms`, `dcl_ms`, `load_ms`, `fcp_ms`, `lcp_ms`) |
| `warmup` | `stage`, `n`. The runner wakes the screen |
| `measure` | `stage`, `n`. The measured window starts |
| `step` | `stage`, `n`, `raf` (frame stats). The window ends; the runner reads SurfaceFlinger |
| `input-ready` | `x`, `y` (device pixels), `taps`. The runner taps |
| `input` | `method`, `taps`, `values`, `inp_ms` |
| `js` | `runs`, `median_ms`, `p95_ms`, `checksum`, `expected` |
| `done`, `error` | `message` on error |

Frame statistics are defined once, in `tools/perf/page/stats.js` and `tools/perf/perflib.py`.
`tools/perf/test/stats-cases.json` pins both to the same answers.

## Example: Aqara S1 Plus

Not run yet. The first full run on the S1 goes here, whatever grade it gets.

These read-only checks were done on the S1 on 2026-11-12 (kiosk 0.4.3, GeckoView 159, Android 10):

- `dumpsys SurfaceFlinger --latency 'SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0'`
  returns the 16.67 ms refresh period and the last 127 presented frames. That is about 2 s at 60 fps,
  which is why a measured window is 2 s long and the page holds still afterwards.
- The kiosk runs as six processes: the app, Gecko's GPU process, three content processes and a
  crash helper. The app process is under a third of the total PSS, so the report adds them all up.
- logcat has `ActivityTaskManager: Displayed sh.edm.s1kiosk/.MainActivity: +Nms` and GeckoView's
  `GeckoView:PageStart` and `PageStop` lines (debug level, present in the release build).
- The kiosk is the S1's home app. After `am force-stop` the system starts it again within 0.2 s,
  and the dashboard's `PageStart` follows about 4 s later. That is why a cold start follows logcat
  from before the force-stop.

A `--quick` smoke run (one run, 2026-11-12, an earlier version of the runner) showed the page
working through GeckoView. The page's lines reached logcat and the JS checksum matched. It also
showed why both frame views matter. At 128 spinning CSS boxes, rAF kept 60 fps (118 frames in 2 s)
while SurfaceFlinger presented 64. Its scroll stage moved nothing, which led to the scroller fix.
It isn't the example result: it ran the short ladder once, without the real-use part.

## Run the tests

```sh
python3 -m unittest discover -s tools/perf/test
node --test tools/perf/test/page.test.mjs
```

CI runs both in the `scripts` job.
