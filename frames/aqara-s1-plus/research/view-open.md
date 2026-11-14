# Opening a view: where typical's 4.7 s goes on the S1 (#244)

**Status (2026-11-14): answered. No app lever measured.** The open is one thread's work: HA's
frontend building the view's 50 cards on Gecko's content main thread, about two-thirds JavaScript.
On the panel every open ran at 1.512 GHz, with the tab's process at 106 to 118% of a core and a
third of the cores idle. The kiosk barely competes for the CPU. The one app lever that acts on that
thread, lower JIT thresholds, gave no gain on typical-nocam and cost about 15% on heavy (step 10).
What remains is upstream (HA's frontend's cost per card), the dashboard's weight, and the
GeckoView version as routine upkeep. An earlier 1.2 GHz reading under load is unexplained; no
traced run showed it.

Device: Aqara S1 Plus, Android 10 (`peony-perf1`), S1 Kiosk 0.4.3 builds of main (5d0a0d2),
GeckoView 159, the radio in `thread_router`. Tools: Clarity v1 (#205 at aedb6c7d), desktop Firefox
160 with the Gecko profiler, `tools/perf-lab/`.

## Question

Clarity's typical view without a camera (`typical-nocam`, 50 cards) opens in 4.7 s on the S1. The
bar for a C is 4 s, and this is the only thing between the S1 and a C. Where does the time go, and
what can the app change? Clarity measures "open the view" from HA's own navigation (`pushState`
and `location-changed`) to usable: the first screen drawn and stable for two frames, then the main
thread running every task within 50 ms for 500 ms ([docs/perf.md](../../../docs/perf.md#usable)).

## What we tried

1. **The runs Clarity already recorded** (2026-11-14, verified, read from `report.json` and
   `refstart-<n>.json`). Two graded runs each from s1rtc6 and s1rtc7 (s1rtc7's run 2 had the screen
   go off and isn't counted):

   | Run | Drawn | Usable | `busy_ms` (longest probe wait in each second after drawn) |
   |---|---|---|---|
   | s1rtc6 #1 | 974 ms | 4896 ms | 1089, 767, 359, 211, 40 |
   | s1rtc6 #3 | 741 ms | 4533 ms | 1192, 762, 332, 161, 23 |
   | s1rtc7 #1 | 960 ms | 4969 ms | 1368, 909, 367, 171, 19 |
   | s1rtc7 #3 | 738 ms | 4463 ms | 1139, 630, 331, 166, 19 |

   The first screen is drawn in under 1 s. The other 3.6 to 4.0 s is the main thread staying busy:
   a task of 1.1 to 1.4 s right after the first screen, then shorter ones of 160 to 900 ms. The
   median is 4.7 s and the spread is ±5% in these four. The clean v1 baseline s1rtc9 (#205 eb9c7af,
   3 runs) gave 4852 ms with 4301 to 5395 ms, about ±11%. At that spread, three runs a side show only
   a change of about 10% or more.

2. **What the main thread does: a desktop Gecko profile of the same open** (2026-11-14, verified
   on desktop, inferred for the S1). `tools/perf-lab/profile-view.sh` serves Clarity's fake HA and
   harness to desktop Firefox 160 at a 911 × 439 CSS viewport and 1.5 device pixels per px, opens the
   start view, then opens `typical-nocam` the way the runner does. `split.py` splits the content main
   thread's CPU from the navigation to usable. Two runs:

   | Category | Run 1 | Run 2 |
   |---|---|---|
   | JavaScript | 65.0% | 67.7% |
   | of which interpreters and JIT compiling | 30% | 25% |
   | DOM | 16.5% | 10.6% |
   | Layout (style, reflow, frame construction) | 7.6% | 7.2% |
   | Graphics (display lists) | 2.8% | 3.6% |
   | GC / CC | 1.0% | 5.4% |

   The main thread used 438 and 422 ms of CPU, and the parent process and the renderer about 50 ms
   each. Desktop drew the first screen at 0.17 to 0.22 s and was usable at 1.0 s. The S1 takes 0.86
   and 4.7 s, about the same 1 : 5.5 ratio, about 5× slower. So the open is HA's own JavaScript
   building cards (Lit templates, custom elements, shadow roots). Layout and painting together are
   about a tenth of it (10.4 and 10.8%); with GC they are 11.4 and 16.2%. Gecko prefs for layout,
   painting or GC can move at most that share.

3. **Lower JIT thresholds** (2026-11-14, verified on desktop). A quarter of the JavaScript time
   runs in the interpreters or in compiling, because this is the first time the view's cards are
   built. Two settings, alternated with the default, two runs each, main-thread CPU over the open:

   | Prefs | Run 1 | Run 2 |
   |---|---|---|
   | default (`baselinejit.threshold` 100, `ion.threshold` 1500) | 274 ms | 270 ms |
   | 30 and 500 | 269 ms | 281 ms |
   | 10 and 200, `blinterp.threshold` 2 | 292 ms | 232 ms |

   The interpreted share fell from 28% to 21 to 24% (these runs' own split), but the total didn't move outside the noise.
   This was the profiler at 1 ms, on a fast core. The panel's in-order Cortex-A53 cores may treat
   interpreted code worse; that is untested (test build `244-jit`).

4. **Who else runs on the four cores** (2026-11-14, verified, read from s1rtc6's idle samples, 15 s
   each):

   | Sample | Device busy (all cores) | Kiosk main process | Tab | GPU process |
   |---|---|---|---|---|
   | Your dashboard, idle, all panes on (3 samples) | 13.1 to 15.1% | 9.6 to 9.8% of a core | 6.0 to 6.4% | 1.5% |
   | Typical with its camera playing, idle (2 samples) | 39.0 to 42.9% | 18.3 to 19.8% | 21.3 to 21.9% | 32.0 to 34.1% |

   With nothing on screen changing, the kiosk itself uses about a tenth of one core (microWakeWord
   since #181; it was 37% with openWakeWord, [idle-cpu.md](idle-cpu.md)). The open runs on one main
   thread, so three cores are mostly free. No sample was taken during an open; the panel plan in
   #244 adds one (`tools/perf-lab/cpu-trace.sh`).

5. **Priorities** (2026-11-14, verified, read-only):
   ```
   $ adb shell 'for p in <kiosk pids>; do cat /proc/$p/oom_score_adj; cut -d" " -f19 /proc/$p/stat; done'
   sh.edm.s1kiosk                                 oom 0    nice -10
   sh.edm.s1kiosk:sandboxed_tab_..._3 (the page)  oom 0    nice -10
   sh.edm.s1kiosk:gpu_...                         oom 0    nice -10
   $ adb shell 'cat /proc/<main pid>/task/<voice-mic tid>/stat'   # field 19
   voice-mic ... -19
   ```
   The page's content process already runs at foreground priority, so raising it isn't a lever.
   The microphone thread runs at `THREAD_PRIORITY_URGENT_AUDIO` (nice -19,
   `Microphone.kt:79`), and the wake word model ran on it (`VoiceEngine.kt` `onMicFrame`), so it
   took a core from the page's main thread whenever they shared one. At about a tenth of a core,
   the most that can cost an open is about 0.4 s, if every frame collided. That is unlikely with three
   cores free. Test build `244-ww-thread` moves the model to its own normal-priority thread.

6. **Memory** (2026-11-14, verified, read from s1rtc6 and logcat). The lowest `MemAvailable` during
   the graded tasks was 671 MB, against a 296 MB budget. The app's only lowmemorykiller entries in
   that logcat were of other apps' cached processes. The kiosk's own ART GC runs every 30 s, frees
   about 4 MB, and takes 140 to 700 ms on a background thread with pauses under 4 ms. Memory isn't the
   limit.

7. **The CPU clock** (2026-11-14, verified, read-only). Sampled every 0.2 s for 6 s while the heavy
   view was loading, with 3 to 20 threads runnable:
   ```
   $ adb shell 'for i in $(seq 1 30); do cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq; sleep 0.2; done' | sort | uniq -c
        30 1200000
   $ adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies
   408000 600000 816000 1008000 1200000 1320000 1416000 1512000 1608000
   $ adb shell 'cat /sys/class/thermal/cooling_device0/cur_state /sys/class/thermal/thermal_zone0/temp /sys/class/thermal/thermal_zone0/trip_point_0_temp'
   0
   55369
   70000
   ```
   All four cores share one policy, and it sat at 1.2 GHz under full load. The thermal cooling device
   was off (55 °C, the first passive trip is 70 °C). [idle-cpu.md](idle-cpu.md) saw the same 1.2 GHz
   with the screen on and 1.512 GHz with it off (2026-11-12).

   **The same night, idle, the ceiling was 1.512 GHz** (2026-11-14 03:12, verified by the root with
   `tools/perf-lab/freq-around.sh`: screen on, the panel's own dashboard idle, CPU zone at 38.8 °C,
   9 windows of 6 s at 20 Hz, about 1000 samples). The most common sample in every window was
   1512000, with dips to 408000 to 1320000 as the load fell, and never 1608000. My own short traces
   at 40 to 41 °C at 03:20 read the same range. So 1.2 GHz is not a fixed cap. It showed under load
   during a Clarity run at about 55 °C, and with the screen on in idle-cpu.md (temperature not
   recorded). Temperature, the load, the screen and the HAL's scene changes on Clarity's app restarts
   are all still candidates.

   `getprop sys.cpu_temp` read 67.2 and 67.5 at 01:35 and 03:20, while the CPU zone read 55 and
   40 °C. So that property isn't the CPU zone's live value; what writes it, and whether anything
   acts on it, is unknown. `scaling_max_freq` and `scaling_governor` belong to
   `system` (mode 660) and aren't readable over adb without root.

   Who sets it, read from the strings of `/vendor/lib/hw/power.peony.so` (Allwinner's "AW Power HAL",
   service `vendor.power-hal-1-0`; not copied into the repo): it writes
   `/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq`, and has scenes named `NORMAL MODE`,
   `LAUNCH_MODE`, `HOME MODE`, `BENCHMARK MODE`, `BG_MUSIC MODE` and `BOOTCOMPLETE MODE`, the
   frequencies `1200000` and `1512000`, and `persist.vendor.power.enable` (`power_init disable scene
   control`). Its two numbers are the two ceilings seen, so the HAL very likely sets both, by scene
   (inferred from the strings; which scene sets which, and on what, is not yet read from the code).
   If an open runs at 1.2 GHz and scales with the clock, 1.512 GHz would take 4.7 s to about 3.7 s,
   and 1.608 GHz to about 3.5 s. If it already runs at 1.512 GHz, only about 6% is left. Which clock
   Clarity's opens ran at wasn't recorded; plan 1's trace records it. That is untested. An app
   can't change the scene: Android 10 gives apps no power hints, so this is a root-side question.

8. **Who uses the cores, and at what clock, during each open** (2026-11-14, verified by the root:
   plan 1 step 1. Build A, main 5415f64b; Clarity #205 eb9c7af, one graded-only run;
   `tools/perf-lab/cpu-trace.sh` without root, about 0.38 s per sample in this run; `cpu-align.py`):

   | View | Opens in | Device busy (4 cores) | Tab process | GPU process | Kiosk main process | `voice-mic` | Clock | CPU zone |
   |---|---|---|---|---|---|---|---|---|
   | light | 1413 ms | 75% | 100.9% of a core | 78.2% | 27.3% | 2.7% | 1512000 ×4 | 50.9 to 51.8 °C |
   | typical-nocam | 5207 ms | 62% | 106.0% | 34.4% | 14.1% | 2.6% | 1512000 ×15 | 43.5 to 53.4 °C |
   | typical (camera) | 5554 ms | 65% | 111.3% | 32.7% | 19.5% | 2.6% | 1512000 ×18 | 45.3 to 54.0 °C |
   | heavy | 41250 ms | 95% | 110.9% | 69.5% | 30.9% | 2.9% | 1512000 ×85 | 50.9 to 62.2 °C |

   Over the whole run, 1491 of 1600 samples read 1512000, 13 read 1608000, and the rest were dips to
   408000 to 1416000 between bursts (4 at 1200000). None of the 1608000 samples fell inside a view
   open: 6 came during the warm-up at 48 to 56 °C, and 7 during heavy's later steps, after it was
   usable, at 58 to 63 °C. The cpufreq
   cooling state was 0 in every sample, and the CPU zone stayed between 38.4 and 64.5 °C.
   `sys.cpu_temp` read 67.5 throughout, so it's a fixed value, not a sensor.

   What it shows:
   - **The open is bound by one thread in the page's process.** The tab process holds a little over
     one core; the device had a third of its cores free on typical-nocam. The trace reads the
     process total, not Gecko's main thread on its own (the process's pid thread is Android's main
     thread, idle: 0 jiffies over the run), so "the Gecko main thread is saturated" is inferred from
     that total and from the harness's `busy_ms` (a 1.1 s task right after the first screen), not
     measured per thread.
   - **The clock was 1.512 GHz, the HAL's higher ceiling, in every open.** The 1.2 GHz seen under
     load at about 55 °C (step 7) did not occur in this run, at the same or higher temperatures.
     It is unexplained. 1.608 GHz would add at most about 6%.
   - **The kiosk's own threads don't compete.** `voice-mic` (the wake word) used 2.6% of a core while
     a third of the cores sat idle, so moving it off the urgent-audio thread (`244-ww-thread`) can't
     gain anything measurable. The kiosk's main process (Gecko's parent and the app) used 14 to 31%.
   - typical-nocam opened in 5207 ms here, inside s1rtc9's 4301 to 5395 ms. Its first screen was
     drawn at 1417 ms.

9. **A touch boost** (2026-11-14 03:12, verified by the root: `tools/perf-lab/freq-around.sh`, 20 Hz,
   3 × nothing, a tap on a blank gap, the wake intent `--es screen on`). The clock mix was the same
   before and after each, so Clarity's intent-driven opens don't miss a boost a real tap would get.

10. **Lower JIT thresholds on the panel** (2026-11-14, verified by the root: plan 1 step 2. Build A,
    main 5415f64b; build J, the same plus `javascript.options.baselinejit.threshold` 30 and
    `ion.threshold` 500, which reached Gecko (the app's prefs log). Clarity #205 eb9c7af,
    graded-only, one run per install, alternating A, J, A, J, A, J, so every run used the same view
    order. All traced.) The decision rule, set before the runs: keep J only if it wins every pair
    on typical-nocam and its median gain beats A's own spread, with no regression elsewhere.

    | View | A (3 runs) | J (3 runs) | J against A | Tab process CPU per open, A → J (medians) |
    |---|---|---|---|---|
    | typical-nocam | 5110, 4977, 5271 ms | 5230, 5224, 5094 ms | J slower in pairs 1 and 2, faster in pair 3; median +2% | 5.25 → 5.63 s (+7%; J higher in every run) |
    | typical | 5433, 5066, 5451 | 5166, 5291, 5357 | median -3% | 5.69 → 5.62 s |
    | light | 2084, 1502, 1485 | 1383, 2066, 1941 | median +29%, a 1.4 to 2.1 s spread on both | 1.46 → 1.88 s |
    | heavy | 39.6, 39.7, 52.5 s | 45.5, 46.8, 44.7 s | J slower in pairs 1 and 2; median +15% | 44.0 → 50.9 s (+16%) |

    Every view open in all six runs ran at 1512000 (no other clock in any open), with the cpufreq
    cooling state 0 and the CPU zone between 41.9 and 65.5 °C, so the clock isn't a confound. RAM
    didn't differ: the app's PSS at idle was 917 to 968 MB on A and 939 to 991 MB on J, and at the
    peak 721 to 754 MB on A and 723 to 766 MB on J.

    **J fails the rule, so it is dropped.** It wins one pair of three on typical-nocam, and its median
    is 2% slower, inside A's own spread. The tab process's CPU time, the less noisy number, is
    higher on J in every run: lower thresholds make Gecko compile more code than it saves here. On
    heavy, which builds 120 cards, that costs about 15% in time and in CPU. This matches the desktop
    runs (step 3), where the same thresholds didn't change the main thread's CPU.

11. **Main since 5d0a0d2: does its newer background work slow the open?** (2026-11-14, read from
    the runs above.) Build A (main 5415f64b) opened typical-nocam in 4977 to 5271 ms over four runs
    (step 8 and step 10's A). The older build 5d0a0d2 opened it in 4301 to 5395 ms over seven
    (s1rtc6 and s1rtc7 on #205 aedb6c7d, s1rtc9 on eb9c7af), median 4852 ms. Every A run sits above
    that median. Over all seven a rank-sum gives a one-sided p of about 0.04; against s1rtc9 alone,
    p is about 0.31.

    **Not shown.** The two sets differ in more than the build: the harness head, the view order (A's
    runs always opened typical-nocam second; s1rtc6, 7 and 9 rotated it) and the trace's small load
    (only A was traced). Nothing in the page changed: between the builds the content script and
    GeckoView (159) are the same, and the engine gained only a name getter for the Manage page.
    The new background work (camera presence, the Manage page, the Bluetooth proxy) runs in the
    kiosk's main process, which used 14 to 17% of a core during A's opens while a third of the cores
    sat idle, so it has no free core to take from the tab's thread. If the 5% is real, those
    conditions are the likelier cause. An alternating 5d0a0d2 and A run with the same flags and
    trace would settle it (about 75 minutes of panel time).

## Result

| Part of the 4.7 s | Share | How sure |
|---|---|---|
| First screen drawn | 0.74 to 0.97 s | verified (4 runs) |
| Main thread busy after it | 3.6 to 4.0 s | verified (4 runs) |
| of which HA's JavaScript | about two-thirds | desktop profile, inferred for the S1 |
| of which DOM | 11 to 17% | desktop profile |
| of which layout and painting | about a tenth (10.4 to 10.8%; 11.4 to 16.2% with GC) | desktop profile |
| The page's process during the open | 106 to 118% of a core, device 60 to 64% busy | verified (steps 8 and 10, seven typical-nocam opens) |
| Background CPU from the kiosk | main process 14%, the wake word 2.6% of a core during the open | verified (step 8) |
| The clock | 1.512 GHz in every open of the traced run; an earlier 1.2 GHz under load is unexplained | verified (steps 7 and 8) |

| Lever | Ceiling | Evidence | Where |
|---|---|---|---|
| The CPU ceiling, 1.512 → 1.608 GHz | at most about 6% | verified clock (step 8) | Root side; not worth the heat. Plan C dropped |
| Wake word model off the urgent-audio thread | about 0: 2.6% of a core with a third of the cores idle | verified (step 8) | App, `244-ww-thread`; dropped |
| JIT tier-up thresholds | none on typical-nocam (+2%, inside the spread); about 15% slower on heavy; 7 to 16% more CPU | verified on the panel (step 10, 3 alternating pairs) and on desktop | App prefs; dropped |
| A newer GeckoView | small: 159 is one release behind 160.0 (2026-11-04), and the desktop lab already ran 160 | read (Mozilla's Maven) | App dependency, routine |
| HA's frontend's own work per card (Lit rendering, custom elements, shadow roots; the view builds all its cards before it is usable) | the bulk of the open | desktop profile | Upstream (HA's frontend), not the kiosk |
| The dashboard's cost: fewer or lighter cards per view | large: light's 20 cards open in 1.4 to 2.1 s, typical's 50 in 4.3 to 5.4 s | verified (Clarity) | The dashboard's owner; the frame's docs can say so |
| Layout, paint and GC prefs | layout and paint about a tenth; with GC 11 to 16% | desktop profile | ruled out as a main lever |
| Content process priority | none: already foreground | verified | ruled out |
| Memory pressure | none: 671 MB free at the peak | verified | ruled out |

**No app lever measured.** The open is HA's frontend's JavaScript on one Gecko thread, on a
1.5 GHz in-order core, and the kiosk barely competes for it. The app-side candidates either can't
reach that thread (the wake word, priorities, memory, layout and paint prefs) or made it slower
(JIT thresholds). What remains is outside the kiosk:

1. **What HA's frontend does per card.** The bulk of the open: Lit templates, custom elements and
   shadow roots for each card. The view builds all its cards before it is usable, below the first
   screen too (the harness's `cards_rendered` is 50 of 50 at usable on typical). This is upstream work in HA's frontend, outside the kiosk and the
   project's rules (nothing gets installed in HA).
2. **The dashboard's weight.** The same frontend opens light's 20 cards in 1.4 to 2.1 s and
   typical's 50 in 5.0 to 5.3 s on the S1. That is the dashboard owner's choice; the S1's docs can
   give these numbers so people can choose.
3. **The GeckoView version.** The app is on 159; 160.0 is out. Bumping is routine upkeep, not a
   speed plan: one release of SpiderMonkey and layout changes, and the lab's desktop profile already
   ran 160. The APK should stay about the same size: native code is 74 of its 98 MB (s1rtc6's
   `apk.parts_bytes`), most of it GeckoView's, and one release changes that little (not measured).
   A 64-bit GeckoView would have the better JIT backend, but the S1's userspace is 32-bit
   (`ro.product.cpu.abilist` is `armeabi-v7a,armeabi`), so it can't run one.
4. **The clock.** At most about 6% more, with heat. Not worth it unless the 1.2 GHz drop turns out
   to happen in real use.

## The CPU ceiling

**What is known, and how sure.**

| Claim | Grade |
|---|---|
| The four cores share one cpufreq policy and ran at 1.2 GHz under full load during a Clarity run, at about 55 °C | verified 2026-11-14 01:3x (30 samples while heavy loaded) |
| With the screen on and idle, at 39 to 41 °C, the ceiling was 1.512 GHz | verified 2026-11-14 03:12 to 03:20 (about 1000 samples by the root, plus short traces) |
| [idle-cpu.md](idle-cpu.md) saw 1.2 GHz with the screen on and 1.512 GHz with it off | verified 2026-11-12, temperatures not recorded |
| No reading has ever shown 1.608 GHz, the SoC's top step (`scaling_available_frequencies`, `cpuinfo_max_freq`) | verified (every reading above) |
| The kernel's thermal cooling didn't cause the 1.2 GHz: cooling state 0 at 55 °C, first passive trip 70 °C | verified (one reading). A softer step below the trip, in the HAL or a vendor service, isn't ruled out |
| What switches between 1.2 and 1.512 GHz: temperature, load, the screen, or the HAL's scene on app restarts | unknown; plan 1's trace logs the clock, the three thermal zones, the cooling state and (as root) the ceiling and governor across a whole Clarity run |
| Allwinner's power HAL (`power.peony.so`) writes `policy0/scaling_max_freq` by scene, and `1200000` and `1512000` are its two ceilings | read (strings only, not the code) |
| `persist.vendor.power.enable` turns the HAL's scene control off at its start | read (strings: `persist.vendor.power.enable`, `power_init disable scene control`); the value it wants is not known |
| An app launch lifts the ceiling for a moment | inferred from `LAUNCH_MODE`; untested |
| A touch lifts it (a touch boost) | no: verified 2026-11-14 03:12 by the root. Three taps on a blank gap, three wake intents (`--es screen on`) and three windows with nothing done had the same clock mix before and after |
| How much faster opens are at a higher ceiling | moot for now: every open in the traced run (step 8) was already at 1.512 GHz, leaving about 6%. Plan C is dropped |

An app can't lift it. Android 10 has no power hints for apps (`PerformanceHintManager` is API 31),
and the file belongs to `system`. It needs the root side.

**What a root-side fix would look like** (a separate issue, after the maintainer weighs in; not
built here). Step 8 found every open already at 1.512 GHz, so this is on hold. It stays here in case
the 1.2 GHz drop shows up in real use:

1. **Turn the HAL's scene control off at boot.** The root side sets `persist.vendor.power.enable`
   to the value that disables it, then restarts `vendor.power-hal-1-0` (or the next boot picks it
   up), and writes `scaling_max_freq` once, since the HAL no longer will. The kernel's own governor
   and thermal control then decide. This is the smallest change, but it also turns off the HAL's
   other scenes, such as the lower ceilings with the screen off or for background music, so idle
   power could rise. The value it needs, and whether the HAL also stops touching the governor,
   must be read from the code first.
2. **Keep the HAL and raise only the normal ceiling.** An init service writes `1608000` (or a
   middle step, `1416000`) to `scaling_max_freq` after each scene change. The HAL changes scene on
   every app launch and screen change, so this means a loop or a trigger on those, which is
   fragile and fights the vendor's code. Not recommended.
3. **A middle ceiling.** Either way, 1.512 GHz (the HAL's own other ceiling) instead of 1.608 GHz
   trades some speed for less heat. The thermal headroom decides which.

If plan 1 shows the clock drops to 1.2 GHz as the panel warms, the drop is a thermal policy, and
none of these is a fix: lifting it only trades the panel's heat for speed. The lever then is heat
(the app's own CPU use, the wall box), not the ceiling.

Either way it should be a setting, off by default until measured, with the radio bundle as the way
it reaches panels if its services can do it (no reflash), else the boot patch. Changes in
`tools/root` need a real panel before merge (hard rule 5).

**Risks.**
- **Heat.** The S1 idles at 55 to 67 °C, and its first passive trip is 70 °C. A higher ceiling runs
  closer to it, and once the kernel throttles, the speed drops at moments no one chose. The panel
  sits in a closed wall box with no fan.
- **Power.** 1.608 GHz most likely needs a higher operating voltage than 1.2 GHz (the kernel's
  operating points weren't read), and power then rises faster than the clock. The wall base's power budget, and the S1's own supply margin, aren't known. The panel
  never sleeps, so idle power matters as much as peak.
- **Why the HAL drops to 1.2 GHz, and never uses 1.608 GHz, is unknown.** It may be heat, a supply
  limit or a measured lifetime.
- **It hides the app's own cost.** A faster clock makes every CPU use cheaper, so app regressions
  show later. Clarity records the maximum clock, not the one each run ran at; that would need adding
  (a question for Clarity's owner).

**How to undo it.** Option 1: set `persist.vendor.power.enable` back to its old value (or delete
it) and restart the HAL or reboot; the HAL then writes its own ceilings again. Option 2: stop the
service and reboot (sysfs is in RAM). Either way, the installer's restore removes the whole root
side.

## What has no saved output

Three readings above have no output in the repo or with the Clarity runs. They are reported from
the session that made them:

- Step 3's desktop JIT runs: their per-run lines are in a local log on the computer that ran them;
  the profiles they came from were deleted after splitting (each was over 200 MB).
- Step 7's 30 clock samples at 1.2 GHz under heavy's load: printed to the terminal, not saved.
- Step 9's touch-boost run (03:12): its output is on the root's computer, not in the repo.

The Clarity runs, the traces and their `cpu-align.py` output for steps 1, 8, 10 and 11 are kept with
the Clarity runs (s1rtc6, s1rtc7, s1rtc9 and the #244 runs), outside the repo.

## How to reproduce

- The desktop profile (this computer only, touches no device): `tools/perf-lab/profile-view.sh
  /tmp/tn typical-nocam --perf <a Clarity checkout with typical-nocam>`. It needs desktop Firefox,
  and the fake HA's frontend cache (`~/.cache/glassworks-perf`, fetched by Clarity on its first run).
  With `--prefs FILE` it adds `user_pref` lines, as for the JIT runs.
- The clock and priorities: the read-only commands above.
- The cores, the clock and the temperatures during a Clarity run (read-only, adds a little load):
  `tools/perf-lab/cpu-trace.sh <serial> out.trace [--su 'su 0']` while Clarity runs (with `--su` it
  also reads the ceiling and governor), then `python3 -I tools/perf-lab/cpu-align.py out.trace
  <clarity out dir> --timeline`. It ends by itself after `--seconds` (default 7200).
- The clock around one action, at 20 Hz: `tools/perf-lab/freq-around.sh <serial> <label> '<action>'`.
  The sampling is read-only; the action (an `input tap`, an `am start`) is the operator's.

## Open questions

- Whether main's builds since 5d0a0d2 open typical-nocam about 5% slower, or only ran under
  different conditions (step 11).
- Which HAL scene sets which ceiling, and what `persist.vendor.power.enable` wants, from the code at
  `power_hint_platform`, `power_set_interactive_platform` and `power_init` (only needed if the
  1.2 GHz drop turns out to matter).
- What made the clock read 1.2 GHz under load at about 55 °C in step 7, when the traced run never did.
  The next trace that catches it, with root (`--su 'su 0'`), would show the ceiling and governor
  then.
- What writes `sys.cpu_temp` (a fixed 67.5).
- The S1's own split of the main thread's time. The desktop profile's split is inferred for the S1.
  `simpleperf` on the tab process (root) would measure it.
