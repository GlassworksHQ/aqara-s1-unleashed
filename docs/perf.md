# Clarity: the performance test and grade

<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
</p>

**Clarity** is the Glassworks perf test. `tools/perf/run.sh` measures how good a wall panel a frame
(a device definition, such as the S1's `aqara-s1-plus`) makes, and gives it a Clarity grade from
**A+** to **E**, as in "Clarity A". The name lives in `rubric.json` (`name`), which the report, the
summary and `grade.ts` read; the files keep their `perf` paths. The letter says **the biggest
fixed dashboard on which every wall-panel task still feels good**:

| Grade | Meaning |
|---|---|
| A+ | Premium: an A where every task on large dashboards also holds 60 fps |
| A | A good wall panel even for large dashboards |
| B | A good wall panel for typical dashboards; large ones stay fluid |
| C | A good wall panel for typical dashboards |
| D | A good wall panel for light dashboards |
| E | Not a good wall panel, even for light dashboards |

The report opens with one line such as "Aqara S1 Plus: Clarity C, a good wall panel for typical dashboards.
Held back by large dashboards: scrolling averages 41.5 fps (p95 50 ms)." (an example of the
format, not a measured result). The second sentence names the tasks that keep the frame from the
next letter up. A table under it shows each page's tasks.

The letter is derived, so it isn't stored anywhere it is shown: a frame's
[summary](#the-summary-glassworksperf-summary2) holds the measurements, and the site computes the
letter at render time from the summary and `rubric.json` (see [Showing the grade](#showing-the-grade)).

The grade is measured on three fixed **reference dashboards** that this test ships: light, typical
and heavy. Since protocol 4 (2026-11-13) they are **Home Assistant's real frontend**, a pinned
release, showing fixed Lovelace dashboards. A small fake Home Assistant on your computer
(`tools/perf/fakeha.py`) serves that frontend and answers its websocket with fixed states and the
same stream of updates every time. So the grade doesn't depend on your Home Assistant, your
dashboard or your network. The one way your panel's own setup reaches it is background load: the
panes, the radio and the device's heat keep running while the pages are measured, as they do every
day (see [What the grade does and doesn't tell you](#what-the-grade-does-and-doesnt-tell-you)). The
bands underneath come from published guidelines (Core Web Vitals, RAIL, Android vitals), not from
any frame's own numbers.

The test also measures RAM on the reference dashboards (per process, at idle and at the peak, and
per pane), the panel as you run it (your own dashboard, memory, CPU) and animation capacity. Those
are reported separately and never graded. It needs no app changes: every page runs in the kiosk's
own GeckoView, loaded the way the kiosk loads your dashboard.

## Run it

You need adb, Python 3 and the kiosk installed on the device. Let the device idle for 10 minutes
first, with the screen on, at normal room temperature, and the kiosk's zoom at its default ("auto").
Note the dashboard the kiosk shows now (its URL setting, or `auto`): the graded part points the
kiosk at the fake Home Assistant and needs to know what to put back.

```sh
tools/perf/run.sh --frame aqara-s1-plus --name "Aqara S1 Plus" --runs 3 \
  --restore-dashboard http://homeassistant.local:8123/lovelace/0 --out perf-s1
```

| Option | Default | What |
|---|---|---|
| `--frame` | (required) | The frame id that goes in the report |
| `--name` | the frame id | The frame's name in the report's headline |
| `--serial` | `$ANDROID_SERIAL` | Which device |
| `--runs` | 3 | Repeats of each part. The report gives the median and range |
| `--restore-dashboard` | (required for the grade) | The kiosk's dashboard setting now: a URL, or `auto`. The graded part puts it back when it ends and on any exit. The runner can't read it (it never reads app data), so you give it. It can't be the fake Home Assistant's address |
| `--pane-ram` | | Also measure each switchable pane's RAM, with the panes' settings now, which it puts back: `assist=on,sendspin=on,mic=on` (`mic` is Assist's microphone: on unless muted). See [RAM](#ram) |
| `--taps` | 10 | Taps on each reference dashboard's toggle. 0 skips them: tap isn't measured, which fails every level, so the grade is E, provisional |
| `--skip-synthetic`, `--skip-reference`, `--skip-real-use` | | Skip the animation test page (detail), the reference dashboards (the grade), or the real-use part. Skipping the reference dashboards gives no grade (refused, not an E) |
| `--quick` | | Short ladder on the animation test page (1, 16 and 128 elements), to check the setup works |
| `--apk FILE` | | Add the APK's size per ABI to the report (repeat for each ABI) |
| `--idle-only LABEL` | | Take one idle sample with this label and nothing else (for [panes on your dashboard](#ram-of-each-pane-on-your-own-dashboard)) |
| `--max-start-temp` | 50 | Waits up to 10 minutes for the CPU to cool below this (°C) |

The first run fetches HA's frontend (117 MB, once) into `~/.cache/glassworks-perf` and checks its
SHA-256 (`tools/perf/hafrontend.py`). A full run with `--runs 3` should take about 40 minutes, and
`--pane-ram` adds about 10. Those are estimates from the step timings, not yet timed on a device
under protocol 4. A graded run is a restart back to the reference dashboard's start view, then the
three reference views, each opened in the loaded frontend as a view tab opens it, with 10 taps and
6 swipes each, then an idle sample on the typical view.

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

- **The dashboard setting, for the graded part only.** The kiosk is pointed at the start view of
  the fake Home Assistant's reference dashboard with its own `--es url` extra (the saved
  dashboard), so a restart comes back to a reference dashboard as it comes back to yours. When the
  graded part ends, and on any exit the shell can catch, it is pointed back at
  `--restore-dashboard` with `am start -W`, and the runner reads the answer: `am` exits 0 even when
  the kiosk didn't take the intent. If it didn't, the runner tries once more, then exits with
  status 3 and logs the command to send yourself. A run killed with `SIGKILL` can't put it back
  either; send `--es url <your dashboard>` yourself. The fake's sign-in is stored for its own
  address (`127.0.0.1:8089`) only, so your Home Assistant's sign-in is untouched.
- **The pages.** The frontend loads once per graded run, when the kiosk comes back after the
  restart. The three reference views then open **inside** it: the runner hands each one to the fake
  Home Assistant (`/clarity/open`), and the harness, already running in the frontend, picks it up
  (`/clarity/next`) and navigates there as HA's view tabs do. No page loads, and any page load while
  a view is measured fails the run. Only the animation test page and the idle sample's page open
  with the kiosk's one-off `--es open <url>` extra. Everything is served from your computer through
  `adb reverse` (`http://127.0.0.1:8089` on the device), so the device's network plays no part.
  HA's frontend files are cached for a year, as HA serves them (their names carry a hash); the app
  shell, the API and the harness are never cached. The harness's URL carries a hash of its content,
  so a panel that cached an older harness (8c0027b served it at a fixed URL for a year) never runs
  it again. HA's frontend registers its service worker only on https or `localhost`
  (`register-service-worker.ts`), so on `http://127.0.0.1` there is none, as on an http HA.
- **The panes, with `--pane-ram` only.** The kiosk's own extras turn the switchable panes off and
  on in turn, in two intents: first the settings it applies live (`--ez sendspin_enabled`,
  `--ez mic_muted`), then `--ez voice_enabled`, which restarts the app. The runner then reads the
  kiosk's log to check what it runs (see [RAM](#ram)). On exit they are put back to the settings you
  gave. While a pane is off, the panel doesn't play music or hear its wake word.
- **The screen stays on** because the runner sends the kiosk's own wake intent (`--es screen on`):
  just before each view opens, every 8 s while it opens and between views, and when it goes live.
  Not while a view is live (its taps and swipes are touches, which keep the screen on) and not
  during the restart. It changes no setting. If the screen goes off anyway during a graded run
  (Android's `PowerManagerService: Going to sleep` in logcat), that run is an error: with the
  screen off Gecko runs no animation frames and slows its timers, so nothing timed then is the
  panel's. In smoke10 (9d986c9, 22:06, the kiosk's 20 s night timeout) the screen went off three
  times: light's view switch waited 22 s for it to wake, typical's main thread looked busy for
  112 s, and heavy never ran.
- **Idle samples and the animation test page** keep the screen on the same way: an idle sample
  sends the wake intent every 8 s through its settle and its window, and the test page on every
  line it logs. If the screen goes off anyway, the sample (`screen_off`) or the run (a `screen_off`
  line in `synthetic-<n>.jsonl`) records it, and the report lists it and leaves it out. In s1v7
  (131f6da, 2026-11-13) the screen went off at the end of the third `ref-idle` window, 0.14 s before
  the runner's wake, after the graded run had ended: no graded window overlapped it, and the RAM
  was read after the wake. Before this, that sample would have counted.
- **Taps and swipes**, only on the reference pages and only when the page asks: each page logs
  where its "Living room" toggle is (`input tap`) and where to swipe (`input swipe`), tagged with
  that page load's nonce. A request with another page's nonce is ignored. Before anything reaches
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

Since protocol 4 they are **Home Assistant's real frontend**: the `home-assistant-frontend` release
**20261028.5** (Apache-2.0), the build Home Assistant core serves. `tools/perf/hafrontend.py` fetches
its wheel from PyPI on first use and checks it against a pinned SHA-256, like Magisk; it isn't in
the repo (117 MB). Its files are only served to the panel, never run on your computer. The licence
is in `THIRD-PARTY-NOTICES.md`.

**The fake Home Assistant** (`tools/perf/fakeha.py`, standard-library Python) serves the frontend over
HTTP and answers its websocket API: HA's sign-in flow (without the login page), the 27 commands this
release sends while it loads and runs a dashboard (recorded from it, `test/fakeha-traffic.json`),
`subscribe_entities` with fixed states, and the live updates. The dashboard, states, history and
pictures come from `tools/perf/dashboards.py`, seeded, from the counts in `tools/perf/page/tiers.json`
(pinned by `test/page.test.mjs` and `test/test_pages.py`). The counts are **project choices**, fixed
before any frame was graded under protocol 4.

**One dashboard, four views.** `clarity-ref` is one Lovelace dashboard, as a real panel's is. Its
views are the **start** view (`/clarity-ref/start`), where the kiosk comes back to after a restart,
and the three **reference views** (`/clarity-ref/light`, `typical`, `heavy`), which the grade is
measured on. A view opens inside the loaded frontend, as a view tab or a navigate action opens it
(see [Open the view](#open-the-view-a-navigation-in-the-loaded-frontend)).

| View | Cards | What |
|---|---|---|
| **Start** | 22 | The light view's cards, plus a history graph and a picture (`START` in `tiers.json`) |
| **Light** | 20 | 12 tiles, 4 entities cards of 5 rows, a glance, 2 sensor cards with a graph, a clock |
| **Typical** | 50 | 26 tiles, 11 entities cards of 6 rows, 2 glances, 6 sensor cards, 2 history graphs, 2 pictures, a clock |
| **Heavy** | 120 | 50 tiles, 22 entities cards of 8 rows, 4 long lists of 20 rows, 4 glances, 16 sensor cards, 8 history graphs, 15 pictures, a clock |

Every card is one of HA's own types (`tile`, `entities`, `glance`, `sensor` with its graph,
`history-graph`, `picture`, `clock`), in HA's masonry view, with HA's default theme. The pictures are
generated PNGs the fake serves, and the graphs' history and statistics follow the window each card
asks for, so they look the same on any date. In headless Chromium at 960 CSS px (2026-11-13) heavy
builds about 8000 elements, counted through every shadow root; protocol 3's imitation of it had 4463.

**Why the start view has a history graph and a picture.** HA loads the code of some card types only
when a card of that type first appears (`create-card-element.ts` at the pinned tag: `history-graph`
L86, `clock` L94, `picture` L100; tile, entities, glance and sensor are always loaded). On a panel,
that happens once, after the frontend loads. With the start view holding every type the reference
views use, the cold load pays for that code, inside the restart, and opening a view measures the
view, not the first download of a card's code. In smoke9 (8c0027b, 2026-11-13), each page loaded
the whole frontend, and typical, the first page in the order with a history graph and a picture,
opened in 8.2 s, against heavy's 5.0 s. Its first screen was drawn at 5.2 s (light 4.5 s, heavy
5.0 s), but its main thread stayed busy until 8.2 s.

**The first screen is the same on every view** (`HEAD` in `tiers.json`): the clock (with seconds),
the "Living room" toggle (a tile whose tap toggles its light), 4 sensor tiles, 3 on/off tiles (two
lights and a switch), and an "Energy" entities card of 5 sensor rows: the **head**, the first 10
cards of every view's config. The rest of the view comes after it, shuffled. Only the head changes
while the view is live, so every view gets the same update stream; the views differ in how much is
around and below it.

**Room to flick (protocol 2 and later).** Every page leaves at least 3 screens of scrolling below its
top, so no swipe reaches the end. On HA's frontend the harness does it with one style in
`hui-root`'s shadow root (`#view { min-height: … }`), after the page has loaded: light's 20 cards
would otherwise end within about one screen. Before protocol 2, light could scroll less than one
swipe travels (about 560 px on the S1), so every light swipe hit the end of the page and read worse
than heavy.

No header: the kiosk hides HA's header and sidebar, as it does on your dashboard. The page scrolls as
a document, so a touch scroll goes through GeckoView's compositor.

**Live updates, as Home Assistant sends them.** The fake sends each change the way HA core does: a
`subscribe_entities` event with the entity's new state, a new context id, and new `last_changed` and
`last_updated`. Read from HA's frontend at
[`77170b4`](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67):

| What HA does | Source | So |
|---|---|---|
| Every state change reaches the dashboard as it arrives: no throttling in the frontend | [`connection-mixin.ts` L269](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/state/connection-mixin.ts#L269) | One event per change |
| Only the card whose entity changed re-renders | [`has-changed.ts` L67–L86](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/common/has-changed.ts#L67-L86) | The harness watches only the first screen's cards |
| A tile's colour comes from whether its entity is active. A sensor with a value is always active, so a value change doesn't change its colour; an on/off change fades the icon over 180 ms | [`hui-tile-card.ts` L198–L233](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/hui-tile-card.ts#L198-L233), [`ha-tile-icon.ts` L125–L143](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/tile/ha-tile-icon.ts#L125-L143) | A sensor change has no transition; an on/off change has 180 ms (`tileTransitionMs`) |

The stream: **5 sensor value changes a second** over the first screen's 4 sensor tiles and 5 sensor
rows, and **one on/off change every 2 s** over its 3 on/off tiles. HA's clock card ticks by itself.
The rates are **project choices**. 5 a second is a busy home: dozens of power and energy sensors that
report every 5 to 10 s add up to a few changes a second on one view, and those are value changes.
Lights and switches change on someone's action or an automation, far less often. One every 2 s is
still more than most homes see; it is there so every window has an HA tile fade in it. The changes go
out at fixed times, so a late one doesn't push the next ones back, and every value step is non-zero
(a zero step is no change for HA, and nothing would be drawn). The view starts the stream when it
goes live and stops it when it is done (`/clarity/live`, `/clarity/still`), so the next view never
opens over one that is still updating. Each start is a new **generation** of the stream, and each
change's context id carries its generation and its number (`01JC1ARITY` `S`/`F`, 5 digits, 10
digits: 26 characters, as HA's ULIDs; the frontend treats it as opaque). So the harness knows when
the fake sent each change, and never mistakes a late one from the view before for this view's. When
a stream stops, and before the next view opens, the fake puts every entity the stream or a tap
changed back as it started (`Hub.reset`), told to the frontend as any change is. So every view starts
from the same states, and its stream sends the same values. The fake logs how late it sent each
change (`fakeha.jsonl`) and puts that in the change's context id too, so each change is timed from
when it actually went out, and a late send (smoke10: one, 92 ms, on the Mac that ran it) doesn't
count against the panel. The report is provisional only if a send was more than one update
interval (200 ms) late, when the stream's own rhythm wasn't the protocol's.

**The order.** Each graded run starts with a restart: the kiosk comes back to the start view, which
is the restart's measurement and also takes the cold caches, the first JIT and every card type's
code. So protocol 4 has no separate warm-up page (protocols 1 to 3 opened one; `--warmup-tier` can
still add a view, but not one that is also graded: hui-root keeps a view it has shown, so its graded
open would skip building it, and the runner refuses that). Then the three views open in an order that turns by one each run: run 1 light,
typical, heavy; run 2 typical, heavy, light; run 3 heavy, light, typical. So no view always comes
first, and the report gives each view's results at each place in the order. Each view opens from
the view before it (the start view for the first), as you would tap from one tab to the next. The
runner waits for the screen to be still for 2 s first, at most 10 s. On HA's dashboards that wait
never ends early: the head's clock card shows seconds, so a frame is drawn every second. So each view
opens 10 s after the view before it ended, and every run's `between` reads `moving` (s1v7, all
three runs). The 10 s stays, as s1v7 was measured with it: by then the view before has stopped its
updates and its last work.

Each view runs in phases, one log line per event:
1. **Open the view.** The harness navigates to it and reports when it was usable.
2. It stays still for 2.5 s after it was usable (`stillAfterUsableMs`), as you look before you touch.
3. **Live updates.** It goes live: a warm-up of 1 s, then a 4 s window in which each change is timed to
   the screen (about 22: 20 sensor values and 2 on/off changes). The first 100 display refreshes (1.67 s at 60 Hz) of it also give
   the frame pacing (detail).
4. **Tap.** 10 taps on the toggle while the updates run. The fake toggles the light and sends the
   change back, as HA does.
5. **Scroll.** 6 swipes while the updates still run. Before each, the view jumps back to its top and
   waits 0.5 s still; each swipe is then the same flick down the view, from 85% to 15% of the
   screen's height in 300 ms. The fling is included.

### How Clarity measures HA's frontend

The harness (`tools/perf/page/harness.js`) is the first script in HA's `index.html`: the fake puts it
right after `<head>`, with the frame stats (`stats.js`) and the config from `tiers.json`, at a URL
that carries a hash of all of it. It changes nothing HA does except the room to flick. It runs as
long as the frontend does: it reports when the view it landed on was usable (the restart's end, with
no nonce), then waits for the runner's commands and measures each view it is asked to open. It asks
for animation frames only while a view is measured, so between views and at idle it adds none.

#### Open the view: a navigation in the loaded frontend

On a wall panel HA's frontend loads once (after a restart), and opening a view is a navigation inside
it: a view tab, or a navigate action on a card. Both end in HA's `navigate()`
(`src/common/navigate.ts`, `performNavigation`, at the pinned tag): `history.pushState`, then a
`location-changed` event on the window. `hui-root` reads the route and swaps in the view
(`hui-root.ts` `_selectView` L1289–L1347: the old view's element comes out of `#view`, the new
view's `hui-view` goes in, cached if it was shown before in this load; its cards are built when it is
first shown). The harness calls that same pair, so the frontend can't tell it from a tab. Each view
is shown once per run, so none comes from the cache. Open the view runs from the navigation to
usable. The frontend's cold load is the restart's, and is graded there.

Protocol 4 as first written (8c0027b, smoke9) loaded the whole frontend for each reference
dashboard and called that "open the view". That counted the cold load twice (it is the restart too),
judged it against a page-load bar, and let first-use code downloads land on whichever page came
first (above).

#### Usable

Usable is when HA has drawn the view and would answer a touch at once. Two parts, both rules in
`stats.js` with a node test for each clause (`page.test.mjs`):
1. **Drawn** (`usableStep`): the first of two animation frames in a row in which HA's launch screen
   is gone, `hui-root` shows the asked view (`hui-view.index`), every head card (the view's first 10
   configured cards, from the layout's own list, so one HA hasn't put on the page yet counts as not
   drawn) and every card on the screen has its `ha-card` laid out (`offsetHeight > 0`), and the
   layout hasn't moved. The report also checks the first screen held at least the 10 head cards.
2. **Free** (`freeStep`): from then, the main thread runs every task posted back to back within
   50 ms (RAIL's input budget) for 500 ms (`freeWindowMs`, a project choice); usable is when that
   stretch began. A single quick task isn't enough: HA builds the cards below the first screen in
   many tasks, and one probe can land in a gap between two long ones. In smoke9 heavy's single probe
   passed 47 ms after its first screen was drawn while typical's main thread stayed busy for 3 s.
   500 ms is far shorter than Lighthouse's TTI window (5 s quiet): it asks that a touch now, and the
   next, would be answered, not that HA has finished every card below the fold.

On the screen, usable is the first frame SurfaceFlinger presented that can carry the drawn frame (one
refresh after it, less 3 ms for the clock mapping), and never before the main thread was free.

#### Change to screen

- **When a change was sent.** Each change's context id carries its slot in the stream and how late
  the fake sent it, and the fake sends change k at a fixed time after the stream started (plus that
  lateness, a few ms). One that never reached the page counts from its fixed time. The fake answers `/clarity/live` with that
  start on its own clock, and the harness ties its clock to the fake's while the view is still: 12
  round trips to `/clarity/time`, the shortest kept, uncertain by half its length (`clock_rtt_ms`;
  the report is provisional over 40 ms). So it knows when the fake sent each change, and change to
  screen runs from then. The moment the harness asks for the stream won't do: a busy main thread can
  hold the request back before it leaves (heavy, in headless Chromium, 2026-11-13: about 625 ms,
  which would have added that much to every change).
- **When it reached the page.** The harness wraps `WebSocket` so its own message listener is
  registered before HA's. On HA's frontend that listener runs on the main thread, so a busy main
  thread holds a change back before HA has seen it. That wait counts: the user waits for it too. The
  report gives how many changes reached the page in the window and how long they waited
  (`arrivals`). The frontend can hold two connections for a moment (a reconnect), so a change is
  counted once per entity and context id.
- **When HA drew it.** A `MutationObserver` on each watched card's shadow roots sees HA change the
  card. The first animation frame after that is the frame the change is drawn in (its tick). The
  report then takes the first SurfaceFlinger present at least one refresh later, as before.
- **One that never arrived** in the window (smoke9's heavy: frames stalled for up to 649 ms and the
  changes came in bursts) counts as not shown by the window's end, from when it was sent, so at least
  that long. Before this, such a window read "only 17 of 20 state updates ran in the window" and
  heavy's updates were "not measured", which hid the panel failing the task.

**What differs from your dashboard.** The kiosk loads the reference pages as it loads yours, in the
same GeckoView, with its loading cover, zoom and hidden header. The differences:

| | Your dashboard | The reference dashboards |
|---|---|---|
| Server | Your Home Assistant, over Wi-Fi | The fake, on your computer over `adb reverse` (USB) |
| Sign-in | HA's login page once, or the kiosk's phone sign-in (`external_auth=1`) | HA's own sign-in flow; the fake answers `/auth/authorize` at once with a code, and the frontend keeps its tokens as it does with HA |
| Scripts | HA's | HA's, after the harness (one script) and one `min-height` style |
| Translations, icons | HA sends them | None: words HA would translate show HA's fallback, and icons come from the frontend's own set |
| Registries, areas, services | Yours | Empty or minimal: enough for the cards on the page |
| History, pictures | Yours | Seeded history and statistics over the window each card asks for, and generated pictures |
| Opening a view | Your tap on a view tab or a navigate action | The same `navigate()`, called by the harness: no touch, so the tap's own cost isn't in it (tap is graded on its own) |

The fake answers every command the frontend sent while it was recorded. A command it doesn't know
gets an error, which the frontend handles as it would from HA, and the fake logs it in
`fakeha.jsonl` (`"unknown"`), which makes the grade provisional.

## What it measures

### The graded tasks

| Task | How | Where it comes from |
|---|---|---|
| Tap | INP: the worst of 10 taps on the toggle, taken while the live updates run. Event Timing gives the delay, the handler time and the time to the next paint. Taps under 16 ms aren't reported by Event Timing; they count as 16 ms | Each page |
| Scroll | Real swipes (`input swipe`), while the live updates run. A swipe's window runs from its first scroll event until 400 ms with no scroll event, the fling included, capped at 100 refreshes. A swipe that reached the end of the page anyway (`hit_edge`) is left out and counted: it stops early, so its window is mostly the flick's slow start. All of a run's other swipes are pooled: rAF over every swipe, SurfaceFlinger over the swipes whose history it kept (decided swipe by swipe). The frames are the worse of the two, judged on the run's scroll level (average fps over the swipes and p95; see [The grade](#the-grade)), and the page takes the median level. A run in which SurfaceFlinger kept no swipe rests on rAF only, which can't see frames the compositor dropped: the report says so and the grade is provisional. `scrolled_px` proves the page moved: a page that didn't move is "not measured". Each swipe logs how many state changes (sensor values and on/off changes, not the clock) ran during it (`changes_during`); a page where none did is provisional | Each page; SurfaceFlinger |
| Live updates | **Change to screen**: per change, from when the fake sent it (from the stream's schedule; see [Change to screen](#change-to-screen)) to the first frame on screen that can show it. The harness notes when each change reached the page (before HA's own listener), when HA changed the card, and the next animation frame (the frame the change is drawn in); that frame can reach the screen one refresh later at the earliest, so the first SurfaceFlinger present after that is taken (frames presented sooner were already on their way). A change with no frame by the window's end counts as the time from it to the window's end, which is conservative: it may show later still (one less than 250 ms from the end isn't counted). A change that never reached the page in the window counts the same way. One SurfaceFlinger's history doesn't reach isn't counted, and if fewer than half are counted the run isn't. The two clocks are tied by the page's log line to within a few ms, so a value can be a refresh off; the bars are 100 and 200 ms. Each run's level is from the p95 over the window's changes; the page takes the median level. **Frame pacing** over the frames that had a change to draw (a rAF interval counts when it overlaps a change, from when it was due to when it ran plus its transition) decides only the premium level (A+) and is reported. A **live check** at the window's start logs whether the toggle and everything that changes are on screen, and the change log proves the updates ran. If something was off screen or there were no targets, the updates aren't graded (and, if off screen, the taps neither). Since protocol 4 the change log is the fake's schedule, so updates that didn't arrive are never a reason to skip the window: they count | Each view; SurfaceFlinger |
| Open the view | From the navigation to the view (HA's `navigate()`, as a view tab calls it, in the frontend that is already loaded) to **usable**: the view's head and every card on its first screen drawn, and the main thread free (see [Open the view](#open-the-view-a-navigation-in-the-loaded-frontend) and [Usable](#usable)). The times come from the page's own clock, tied to logcat's by its log line. If the view never says it was usable, or no frame reached the screen after it, it is "not measured", never 0. (Protocols 1 to 3, and protocol 4 as first written, loaded the whole page for each dashboard) | Each view; SurfaceFlinger |
| Restart | From the app's process start (`Start proc` in logcat) to the reference dashboard's start view being **usable**, as for open the view: the kiosk comes back to it by itself, because the graded part points its dashboard setting there. This is HA's cold load: the app, the websocket, the registries, every card type's code and the first view. Once per run, for the whole frame. The engine being ready (GeckoView's own first page, `about:blank`) is reported, not graded (`engine_ready_ms`). See [Restart: back to a usable dashboard](#restart-back-to-a-usable-dashboard) | logcat; the restart page |

Each graded run is one restart back to the start view, then the three reference views in the run's
order. The first view opens once the restart page has said it was usable and the screen has been
still for 2 s, never on HA's load event, which comes seconds earlier. If its `done` line is lost, the runner goes on 10 s after its usable line. If the
kiosk doesn't come back to a reference dashboard (another page loads, or none says it was usable),
the restart is "not measured" ("the kiosk didn't come back to a reference dashboard") and that
run's views still open after the old wait for the kiosk's own page: up to 30 s after engine ready,
then provisional. While the views are measured, and for 1.5 s after the last one, any page the
kiosk loads makes the run an error, and that run isn't graded: a view switch loads no page. So does
a view that never says done by the run's time limit (smoke10's heavy), and the screen going off.

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
| RAM on the reference dashboards | PSS per process at idle on the typical reference dashboard and the peak during the graded tasks, and each switchable pane's share. See [RAM](#ram) |
| Memory on your dashboard | PSS of every app process (the app, Gecko's GPU, content and crash helper processes), the main process's App Summary, each process's peak RSS (`VmHWM`), the radio's processes, and `MemTotal`/`MemAvailable`, at idle on your dashboard and at the run's peak |
| CPU at idle | Jiffies of every app and radio process over 15 s, and the whole device's busy share |
| Idle frames | Frames your dashboard presents while nobody touches it |
| Panes on your dashboard | What turning each pane off saves; see [RAM of each pane on your own dashboard](#ram-of-each-pane-on-your-own-dashboard) |
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
| Open the view, navigation to usable | ≤ 2.5 s | > 4 s | [Core Web Vitals LCP](https://web.dev/articles/lcp): "Good LCP values are 2.5 seconds or less, poor values are greater than 4.0 seconds". Usable needs every first-screen card drawn and the main thread free, so it is at or after the largest paint. See the note below the table |
| Restart, process start to a usable dashboard | ≤ 2.5 s (**a project choice**: LCP's bound) | ≥ 5 s | [Android vitals](https://developer.android.com/topic/performance/vitals/launch-time) counts a cold start that "takes 5 seconds or longer" as excessive (vitals apply it to TTID) |

**LCP's bands are lenient for an in-app navigation.** They are for a page load, network included.
Opening a view loads no page: it is a response to a tap. [RAIL](https://web.dev/articles/rail) asks
to "complete a transition initiated by user input within 100 ms", says that from 100 to 1000 ms
"things feel part of a natural and continuous progression of tasks", and that "beyond 1000
milliseconds (1 second), users lose focus on the task they are performing". Its load goal is for
pages: "be interactive in 5 seconds or less" on a first load, "under 2 seconds" for later ones
(checked 2026-11-13). So 2.5 s is generous for a view switch. The bands are unchanged here (rubric v5): changing them
is a rubric decision for its own PR, not a measurement fix, and it must not be made to move a
letter. A proposal, not adopted: grade a view switch against RAIL's 1 s.

A frames band is the worst of its three measurements. A window holds about 100 frames, so a single
frozen frame in it is 1%, which is poor. Frame times are snapped to whole display refreshes before
the percentile is taken, so timer jitter doesn't count a frame that made its refresh as late. The
0.5 ms slack covers displays that run slightly under 60 Hz. The budget stays 16.7 ms on a faster
display.

## The grade

**What it grades: feel, not video.** A wall panel is good when it feels quick and steady: when a
light or a sensor changes, the new state shows up at once, and when you flick through the dashboard
it doesn't stutter. People notice how fast a change appears, not the frame rate of its repaint, and
a panel scrolling at 50 frames a second feels fine even though it isn't 60. So (rubric v5, a user
decision on 2026-11-12):
- **Live updates** are graded on how fast a change reaches the screen. Their frame pacing is
  reported, not graded.
- **Scroll** is graded on its average frame rate over the swipes, with a cap on hitches. The 60 fps
  rule is the premium level, which earns **A+**, not A.
- Tap and opening the view keep their bars.

**Each run gets a level per task**, then the page takes the median level of the runs (the worse of
the two middles for an even count):

| Task | Premium | Good | Fluid |
|---|---|---|---|
| Scroll | the 60 fps rule: frames band good (p95 ≤ 17.17 ms, ≤ 5% late, nothing frozen) | average ≥ 50 fps over the swipes, and p95 ≤ 50 ms | average ≥ 30 fps, and p95 ≤ 67 ms |
| Live updates | good, and the frames that had a change to draw hold the 60 fps bar | 95% of changes on screen within 100 ms | within 200 ms |
| Tap | INP good (≤ 200 ms) | INP good | INP good |
| Open the view | ≤ 2.5 s, or up to 4 s | the same | the same |

Below fluid, a measured scroll or live-update level is `slow`. The p95 caps are whole frames at
60 Hz plus the rubric's 0.5 ms slack: 50.5 ms is three frames, 67.17 ms four, as the user's 50 and
67 ms. Scroll is measured during the live updates (they keep running while the page scrolls), so
updates that make scrolling stutter cost the page its scroll level.

**A page is at a level when every task is**, and the letter is the first one whose pages are:

| Grade | Needs |
|---|---|
| A+ | Heavy premium: every task there holds 60 fps (the headline reads "A+, premium") |
| A | Heavy good |
| B | Typical good and Heavy fluid |
| C | Typical fluid |
| D | Light fluid |
| E | None of the above |

The scale is A+, A, B, C, D, E (a user decision on 2026-11-12; an earlier draft of v5 had premium as
a label on an A). A+ needs only heavy, like A; when light or typical weren't measured, the grade is
provisional and the headline doesn't claim that every task holds 60 fps.

**No reference dashboard measured** (`--skip-reference`, or no graded run left a file) gives no
letter: the grade is refused ("the reference dashboards weren't measured"), not an E, and the
summary says it isn't comparable.

**A restart of 5 s or more caps the grade at C.** A panel that takes that long to come back to a usable
dashboard after a restart (an update, a crash, a power cut) is not an A+, A or B panel. A cap never raises a
letter. Letters rank by their place in the table, not by spelling.

A measurement that is missing fails whatever needs it, and the grade is marked **provisional**, with
what is missing listed. Each band counts over the runs **attempted** (`plan.json`, written by
`run.sh`), not only those that finished: a run that failed or left no file, a task measured in fewer
runs than attempted, or a scroll that rests on rAF only also make it provisional.

The headline names the tasks that keep the frame from the next letter up, with their numbers, and
says if the restart capped it. An A says what keeps it from A+ ("Short of A+ on large dashboards:
…"); an A+ says every task holds 60 fps on large dashboards. (On smoke5 the old headline read "E, … Large dashboards scroll at about 53 fps", which read against the letter.)
The report's table gives every task's value and band per page, and the task that holds each page
back. Another table gives each page's results at each place in the run order.

The task levels (`scroll_levels`, `update_levels`), the page levels, the letters (A+ first) and the
restart cap live in `tools/perf/page/rubric.json` (`grade`), next to the thresholds.
`tools/perf/grade.py` and `tools/perf/grade.ts` both read them,
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

**Rubric v5 is different: it came after the S1 had results.** Under v4 the S1's protocol 1 runs
graded E, held back by scroll and live-update frame pacing. The user then decided, for a reason that
holds for every frame, that a wall panel is judged on feel, not video: how fast a change shows, and
whether scrolling stutters (see [The grade](#the-grade)). The new bars are project choices:
- **Live updates:** 100 ms is RAIL's bound for a response to feel immediate (["complete a transition
  initiated by user input within 100 ms"](https://web.dev/articles/rail)), applied to a state
  change; 200 ms is INP's "good" bound.
- **Scroll:** 50 and 30 fps, with their p95 caps, are the user's numbers.

They weren't picked to give the S1 a letter. Re-graded offline under v5, its s1v5 runs give C:
typical scrolls at 49.6 to 50.8 fps, just under the good bar in two of three runs. A letter that
lands where the user guessed it would isn't evidence either way. The record of what changed and when
is here so anyone can check.

**Protocol 4 (2026-11-13) changed what is measured, not how it is judged.** The S1's first full run
under protocol 3 graded A (see [the example](#example-aqara-s1-plus)). The maintainer questioned it:
the panel feels slower than that, and the reference pages were an imitation of HA's cards, lighter
than HA's frontend (heavy had 4463 elements against about 8000) and without its work (loading the
app, the websocket, Lit's rendering, HA's state handling). So the reference dashboards became HA's
real frontend, and restart now ends at a usable dashboard. No threshold, level or letter rule moved:
rubric v5 stands. The protocol-3 A is superseded. The S1's protocol 4 result is a D (s1v7,
2026-11-13; [Example](#example-aqara-s1-plus)).

### Restart: back to a usable dashboard

Android vitals time a cold start to the launched activity's first frame (TTID). On the S1 that is
MainActivity's first window: the kiosk's own native loading screen ("Starting up", "Connecting to
Home Assistant…"), not a splash activity (the `:boot` direct-boot home activity ships disabled). Any
kiosk can draw a loading screen within 100 ms, so TTID doesn't separate frames; it is reported, not
graded. The S1's timeline after a force-stop (smoke run, 2026-11-12, your own dashboard):

| Event | After the force-stop |
|---|---|
| App process starts | 0.22 s |
| `Displayed` (MainActivity: the loading screen) | 0.40 s |
| First GeckoView frame | 3.2 s |
| Your dashboard's `PageStart` | 4.5 s |
| Your dashboard settled | 11.5 s |

What you wait for after a restart is a usable dashboard. Timing your own would make the grade depend
on your Home Assistant, and opening a reference page during the restart doesn't work: until
GeckoView's session is up, the kiosk ignores `open` (#103's pins; seen on the S1 too), and once it is
up, the request races the kiosk's own dashboard load. So protocols 1 to 3 ended the restart at
**engine ready**, GeckoView's own first page (`about:blank`).

Protocol 4 changes the kiosk's dashboard setting instead, for the graded part only (a user decision,
2026-11-13: "I'm okay with it changing for the test and then it changing back"). The kiosk points at
the start view of the fake Home Assistant's reference dashboard, so after the force-stop it comes
back to it by itself, by the same path that brings it back to yours. The restart ends when that dashboard is usable, as
defined for open the view. Its start, the process start, is stamped on the device in logcat, and its
end is on the page's clock, tied to logcat's by the page's log line, so the harness's adb round trips
aren't in it. Engine ready is still reported (`engine_ready_ms`).

**How the 5 s bound compares with Android vitals.** Vitals apply 5 s to TTID, the first frame of any
kind. Here the same 5 s covers the whole way to a dashboard you can use, so it is much stricter than
vitals, and stricter than the engine-ready bound of protocols 1 to 3.

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
  uses the CPU all the time (on the S1, the wake word: [idle-cpu.md](research/idle-cpu.md), #105)
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
| App memory, peak PSS of all app processes (on the reference dashboards, and on yours) | ≤ 50% of `MemTotal` | An always-on panel shares its RAM with the system, vendor daemons and the radio. Half for the app leaves room for them |
| Device memory, lowest `MemAvailable` during the run | ≥ 15% of `MemTotal` | Headroom for the system and the radio. A first choice, not a measured limit; change it in the same PR as the evidence |
| A pane's RAM, its PSS on by itself against every pane off | the frame's `ram_pss_max_kb` for that pane (48 MB for `assist` and `sendspin` on the S1) | Set on 2026-11-13, before the first per-pane measurement; revisit with the first one. A pane that needs more should say why in its PR |
| CPU at idle | reported only | No standard. Lower means less heat and power on a panel that never sleeps |
| Your dashboard's start and load | reported only | Your dashboard, your network and your Home Assistant, so not comparable between panels |
| Idle frames | reported only | A still dashboard should present almost nothing. A clock or an animated card adds frames |

The budgets are the frame's own, in `frames/<id>/budgets.json` (#68): `ram` for the frame and
`panes.<id>` for each pane. `tools/perf/budgets.json` is the default for a frame without one; a
frame's `ram` names only the keys it changes, and its panes are its own. Clarity reports a budget
as `ok`, `over` or `n/a`; it doesn't fail the run, because the device run isn't CI.

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

## RAM

Not graded (#68). RAM is read with `dumpsys meminfo` (PSS per process) and `/proc/<pid>/status`
(`VmHWM`, the peak RSS since the process started), per process: `main` (the app), `boot` (`:boot`,
the direct-boot home activity, when it runs), `gpu` (Gecko's GPU process), `tab` (Gecko's content
processes, summed) and `crashhelper`. The radio's processes run outside the app and are reported on
their own.

**On the reference dashboards** (the report's `ram`), so the numbers don't depend on your Home
Assistant:

- **At idle**: after each graded run, the runner opens the typical reference dashboard (with no
  nonce, so nothing on it is timed and the live updates stay off), waits 30 s, samples CPU and
  frames for 15 s, then RAM (label `ref-idle`). The report gives the median of the runs per process.
- **Peak during the graded tasks**: after each graded page, while the screen is still and nothing is
  timed, the runner samples RAM. The largest of those samples is the peak. It is taken between tasks,
  not inside them (a `dumpsys` during a task would disturb what it measures), so a short peak inside
  a task can be missed; `VmHWM` catches it, and the report gives it beside the PSS peak.
- **Per pane**, with `--pane-ram assist=on,sendspin=on,mic=on` (the settings now, put back
  afterwards). The kiosk is pointed at the typical reference dashboard. In each run the runner turns every switchable
  pane off and takes an idle sample (`ref-panes-off`), then turns each pane on by itself and takes one
  (`ref-<pane>-on`). A pane's share is the median of its "on" samples less the median of the "off"
  samples. The **tolerance** is the larger spread (max − min) of the two sets; a share inside it is
  reported as "within tolerance", not as a number to act on. With `--runs 3`, each median is of
  three samples. The switches are the kiosk's own extras, named in the frame's `budgets.json`
  (`switch`): `voice_enabled` for `assist` and `sendspin_enabled` for `sendspin`. `mute` names
  Assist's microphone mute (`mic_muted`) and `state` what the kiosk's log shows while the pane runs
  (below). Turning Assist off
  this way stops the whole pane, unlike the mute switch (below), which leaves its model loaded. It
  also leaves out the timers pane, which only comes from the voice assistant, so `assist`'s share
  includes the timers'.
- **Each sample is checked** against the kiosk's log (`perflib.pane_state`): the ESPHome line the
  app logs at start (`voice=true|false`), then `Sendspin player starting`, and `Microphone open`,
  `Microphone muted (released)` or `Microphone unmuted`. The runner tries a state up to three times
  (`panes-<label>-<n>.json` has what it asked and what ran), and the idle sample records what ran
  (`panes_state`). The report counts only samples that ran what their label says, gives how many it
  left out (`excluded`), and has no number for a pane with none left (`error`). Samples from before
  this check say `verified: false`.
- **Assist off by its mute.** S1 Kiosk 0.4.3 and earlier save `voice_enabled` asynchronously and
  then exit at once to restart, so the write is lost and voice stays on. In s1v7 (131f6da,
  2026-11-13) every switch was lost: the "panes off" samples ran Assist and Sendspin, and Assist read
  −0.4% of a core against
  [#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105)'s 37%. So the runner also
  mutes the microphone (`mic_muted`) for "off" states, which the kiosk applies live, and stops after
  two tries at `voice_enabled`. When voice stayed on, Assist's share is its running cost only (the
  wake word, recorder and inference; [idle-cpu.md](research/idle-cpu.md) step 7), not the code and
  model it loads. The report says so (`off_by: "mute"`). s1v7's pane numbers are not valid; the S1's
  are s1p1 and s1p3 ([Example](#example-aqara-s1-plus)).
- **The tolerance is too wide for the pane budgets yet.** On the S1 (s1p3, 2026-11-13) it was
  ±31.6 MB, two thirds of the 48 MB budget: a pane near 0 and a pane well over budget would both
  read "within tolerance", and the budget check (`pane_budget`), which compares the share alone,
  says "ok" either way. Two things made it wide, neither of them a pane:
  1. **The first sample is low.** In both s1p1 and s1p3 the first sample (`ref-panes-off-1`) had
     the GPU process at 141 to 142 MB, against 155 to 157 MB in every later one, and the app 21 to
     33 MB under the other samples of its run. It sets the panes-off spread (564.7 to 596.4 MB).
  2. **`:boot` comes and goes.** The direct-boot home activity's process (29.2 MB) was in 2 of 12
     samples (s1p1's `ref-sendspin-on-1` and s1p3's `ref-sendspin-on-3`), not in the other two
     Sendspin samples. That is what s1p1's Sendspin +50 MB was. Why it runs then is untested; it is a
     HOME activity, so a restart may start it.

  Leaving out s1p3's first sample and counting the app without `:boot` gives a tolerance of about
  ±9 MB, from the same samples (computed, not a new run). So the fix, not made yet: an unrecorded
  warm-up cycle before the first run, and `:boot` reported apart from the pane's share (or a RAM
  read that waits for it to exit). More runs alone won't do it: the tolerance is the spread
  (max − min), which only grows with more samples. With more runs it would have to be another
  statistic, such as the median absolute deviation.
- **Waiting on #67**: `radio`, `wall-base`, `timers`, `time-zone` and `diagnostics` have no on/off switch until
  [#67](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/67), so they aren't measured on
  their own. The report lists them. The radio's processes are measured where they run.

**Why not an emulator in CI** (assessed 2026-11-13, from the build and the panes' needs; not tried).
#68 asked whether an x86_64 Android emulator in CI could measure RAM per pane. Not now:
1. The kiosk ships `armeabi-v7a` and `arm64-v8a` APKs only. An x86_64 run needs a third ABI build of
   the app and of GeckoView; an ARM APK on an x86_64 image runs through translation, whose memory
   isn't the panel's.
2. Gecko's GPU process holds a large part of the app's PSS (about 210 MB of 700 MB on the S1, s1v6),
   and that depends on the graphics driver: the emulator's is not the S1's PowerVR Rogue GE8300
   (read on the panel 2026-11-12, [boot-animation.md](research/boot-animation.md)).
3. The panes need what an emulator fakes or lacks: Assist a microphone and a voice pipeline,
   Sendspin a Music Assistant server and audio output, the radio its serial port and the root side.
   Their memory without those isn't their memory on a panel.

So **the device run is the source of RAM numbers**, and CI checks only the APK size, which needs no
device.

### RAM of each pane on your own dashboard

The older method, on your dashboard and through Home Assistant, still works and is reported under
`real_use.panes`: turn a pane off through its Home Assistant entity and compare with everything on.
You need an admin account in Home Assistant, and a panel you are allowed to disturb. The runner
doesn't talk to Home Assistant. You toggle the entity, and take an idle sample after each change.
Alternate on and off, three times each. Use a separate output directory: the full run's `all-on`
samples come 30 s after a restart, and a panel that has been up for a while may use more memory, so
mixing them could bias the difference.

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

## The report (`glassworks.perf/4`)

`report.json` has one frame per file. Repeated measurements are given as
`{"median", "min", "max", "n"}`.

| Key | What |
|---|---|
| `schema` | `"glassworks.perf/4"`. A field that changes meaning gets a new version (`/4`, 2026-11-12: `grade.bands` carries scroll and live-update levels, rubric v5) |
| `protocol_version` | The measurement protocol the pages ran (`null` if the runs disagree). See [Protocol and rubric versions](#protocol-and-rubric-versions) |
| `measured_on` | The run's date (UTC, from the computer that ran it) |
| `runs_attempted` | Graded runs attempted (`plan.json`), the count each band is over |
| `frame` | `id`, `name`, `device` (model, Android, API, ABI, SoC, CPU, RAM, display with density and refresh, temperatures), `app` (package, version, APK bytes on the device, GeckoView version) |
| `apk` | One entry per APK given with `--apk`: `abis`, `bytes`, `parts_bytes` (dex, native, assets, resources, other) |
| `fake_ha` | What the fake Home Assistant's log (`fakeha.jsonl`) says: `commands` (the frontend sent), `unknown` and `failed` (per command; any makes the grade provisional), `stream_sent`, `stream_late_max_ms` (over 200 ms, one update interval, is provisional). `null` without the log, which is provisional for protocol 4 |
| `ram` | Not graded. RAM on the reference dashboards (`null` if none was sampled): `reference_idle` (`samples`, `app_pss_kb`, `by_role_pss_kb`, `main_kb`, `device_used_pct`), `graded_peak` (`samples`, `app_pss_kb`, `by_role_pss_kb`, `app_rss_hwm_kb`, `mem_available_min_kb`), `panes` (each: `id`, `method` `on-alone`, `samples_on`, `samples_off`, `pss_kb`, `pss_tolerance_kb`, `pss_measurable`, `by_role_pss_kb`, `cpu_pct_of_core`, `cpu_tolerance_pct`, `cpu_measurable`, `verified`, `excluded`, and `off_by: "mute"` when Assist was off by its mute alone; or `id`, `method` and `error` when no sample ran as asked), `screen_off_samples` (idle samples the screen went off in, left out), `panes_waiting` (`id`, `waiting_on`, `note`), `budget` (the frame's `ram` checks) and `pane_budget` (per pane: `value_kb`, `budget_kb`, `ok`). See [RAM](#ram) |
| `grade` | `grade` (`null` when refused), `meaning`, `headline`, `levels` (per page: `premium`, `good`, `fluid` or `null`), `bands` (per page: `tap` and `load` bands, `scroll` and `updates` levels), `start`, `capped_by_start`, `viewport` (`state`, `reason`), `refused`, `provisional`, `provisional_reasons`, `missing`, `rubric_version`. Computed from `graded` only. `provisional_reasons` are measurement facts (a run failed, a task was measured in fewer runs, scrolling rests on rAF only, the pages ran another protocol) |
| `graded` | `reference`: `start_ms`, `restart_to` (`usable` since protocol 4, else `engine-ready`), `engine_ready_ms` (detail), `start_errors`, `engine_uris`, `android_ttid_ms` (not graded), `launch`, `own_page_states`, `orders` (each run's page order), `warmups`, `protocols`, `bad_page_lines` (page lines that didn't parse or never arrived whole), `lost_scroll_steps` (swipes answered with no scroll step back), `errors`, `runs`, `graded_runs`, `per_run` (each with its `order` and `between`, whether the screen was still before each page opened), and `tiers` with, per page: `inp_ms`, `load_ms`, `load_errors`, `paint_to_screen_ms`, `scroll` (p95, jank, frozen, fps, n), `scroll_levels` (per run) and `scroll_level` (graded), `scroll_bands` and `scroll_band` (the 60 fps rule, detail), `scroll_runs` (each run's frame stats), `scroll_sources`, `scroll_limited_by`, `scroll_raf_only` (per run), `sf_swipes`, `swipes_moved`, `swipes_at_edge` (left out), `swipes_during_updates` (per run), `scroll_errors`, `scrolled_px`, `first_scroll_ms`, `sf_valid` (per run), `latency_p95_ms` and `latency_median_ms` (change to screen), `updates_levels` (per run) and `updates_level` (graded), `updates_runs` (each run's latency and change frames), `updates` and `updates_bands` (pacing of the frames that had a change to draw, detail), `updates_all_p95_ms` (every frame of the pacing stretch, detail), `updates_errors`, `state_ticks`, `positions` and `by_position` (the page's results at each place in the order), `fcp_ms`, `nodes`, `cards`, `viewport` |
| `detail` | `synthetic`, the animation test page: `ladder` (per family: `held_60fps`, `held_30fps`, `…_at_top`), `steps_without_sf`, `screen_off_runs` (left out of the rest), `scroll`, `scroll_moved`, `js_burst_ms`, `js_band`, `js_checksum_ok`, `page_lcp_ms`, `page_lcp_from`, `per_run` |
| `real_use` | Not graded. Your dashboard: `app_start_to_dashboard_ms`, `android_ttid_ms`, `launch`, `stop_to_dashboard_ms`, `dashboard_load_ms`, `dashboard_page_stop_ms`, `settle_how`, `errors`, `screen_off_samples`, `per_run`. Then `idle`, `peak`, `memory` (`idle`, `peak`, `budget`) and `panes` |

`own_page_states` says, per run, how the kiosk's own page ended before the reference dashboards
opened: `reference` (protocol 4: it came back to the reference dashboard and said it was usable),
`finished-still` (stopped, then quiet), `finished-moving` (stopped, still painting, such as
a clock card; normal) or `never-finished` (still loading 30 s after engine ready; the grade is provisional).

## The summary (`glassworks.perf-summary/2`)

`tools/perf/summary.py` writes a small, stable, frame-level summary of a finished report, for places
that show a frame's grade (glassworks.dev, later a frame's `design/frame.json`). It holds
**measurements only, never the letter or its meaning**: the letter is derived, so it stays derived
(see [Showing the grade](#showing-the-grade)). Only measurements from the reference dashboards and
the APKs go in: the graded ones, and two optional parts that aren't graded (`ram`, `apk`). Nothing
from real use, and nothing device-unique.

| Key | What |
|---|---|
| `schema` | `"glassworks.perf-summary/2"` |
| `test` | The test's name, `"Clarity"` (`rubric.json` `name`), so a summary says what measured it |
| `protocol_version` | How it was measured. A site grades it only with a rubric for the same protocol |
| `report_schema` | The report it came from, `"glassworks.perf/4"` |
| `measured_on` | The run's date, `YYYY-MM-DD` (UTC) |
| `frame` | `id`, `name` |
| `app_version` | The kiosk's version name |
| `viewport` | `css` (CSS px), `dpr` (device pixel ratio), `default_dpr` (density / 160) |
| `runs` | `attempted`, `graded` |
| `pages` | Per page (`light`, `typical`, `heavy`): `inp_ms` (median), `open_ms` (median), `scroll` (`fps`, `p95_ms`, `scrolled_px`: medians; `sf_valid` per run; `runs`: each run's `p95_ms`, `jank_pct`, `frozen_pct`, `fps`, `n`), `updates` (`latency_p95_ms`, `latency_median_ms`: medians of change to screen; `pacing_p95_ms`, detail; `runs`: each run's `latency_n`, `latency_median_ms`, `latency_p95_ms` and `frames`, the stats of the frames that had a change to draw), `scroll.during_updates` (per run, how many swipes ran during the updates), `runs` (how many runs measured `tap`, `scroll`, `updates`, `open`) |
| `restart_ms` | Process start to a usable reference dashboard (median; to engine ready before protocol 4) |
| `gaps` | Measurement facts that leave it short: "1 of 3 graded runs failed or are missing", "heavy: scrolling rests on rAF only in 1 run(s)…". Empty when complete |
| `not_comparable` | Why the numbers can't be compared with other frames' (e.g. not at the default zoom), or `null` |
| `report` | A relative path or URL of the full report (`--report-url`) |
| `complete` | `false` when it was exported with `--allow-provisional` and has gaps, missing measurements or isn't comparable |
| `ram` | Not graded; `null` if not measured. In kB: `idle_app_pss_kb` and `idle_by_process_pss_kb` (medians at idle on the typical reference dashboard), `peak_app_pss_kb` and `peak_by_process_pss_kb` (the peak during the graded tasks), `panes` (each: `id`, `pss_kb`, `tolerance_kb`, `measurable`, `runs`, `budget_kb` and `budget_ok` (the frame's pane budget and whether the share is within it; `null` without a budget; read it with `measurable`, since a share inside the tolerance can't show that the pane fits), and `off_by` when the report has it; a pane with no number isn't listed), `panes_waiting` (pane ids), `budget_ok` (`true`, `false` or `null`) |
| `apk` | Not graded; `null` if no APK was given. Per APK: `file`, `abis`, `bytes` |

The levels need each run's measurements (each run gets a level, then the median level is taken), which
is why the summary carries `runs` and not only medians. Medians are kept for display. Fields are only
added within a version; a change of meaning is a new one (`ram` and `apk` were added within `/2`,
2026-11-13, and `grade.ts` ignores them). `/2` (2026-11-12, rubric v5): `updates`
carries change to screen and its runs are latency records, where `/1` had frame pacing.

### Protocol and rubric versions

Two numbers say whether a stored summary can still be graded. A change that doesn't change the work
the device does, such as a colour value, doesn't bump either (2026-11-12: the pages moved to the
Glassworks palette, below, within protocol 3):

| Version | Where | Bump it when | A stored summary |
|---|---|---|---|
| `protocol_version` | `tiers.json` `PROTOCOL`, and `rubric.json` `protocol_version` (the protocol its rules are for; `page.test.mjs` pins them equal) | **How** something is measured changes: the pages, the first screen, the swipes, the update rate, the windows, the warm-up and order, or how the runner and report read them | From another protocol: **re-measure**. Its numbers mean something else |
| `version` (rubric) | `rubric.json` | **How it is judged** changes: a threshold, a level, a letter rule, the restart cap | Graded again with the new rubric at render time. No re-measure |

So a change to the letter table changes every frame's letter on the site without a new run, while a
change to the pages makes the site ask for a new run. Protocol 1 (2026-11-12) added the warm-up, the
turning order, the shared first screen, change frames and the first-paint settle rule. Protocol 2
(2026-11-12) added room to flick on every page, a flick from the top for every swipe with edge swipes
left out, the HA-faithful update stream, change-to-screen timing over a 4 s window, and the changes
counted during each swipe. Protocol 3 (2026-11-12) fades only what HA fades on a tile (the icon's
shape and colour, not a background behind it) and counts only state changes, not the clock, during a
swipe. Protocol 4 (2026-11-13) runs HA's real frontend (release 20261028.5) behind a fake Home
Assistant, times the restart to a usable dashboard, points the kiosk's dashboard setting at the
reference dashboard for the restart, and drops the warm-up page. Within protocol 4, before any
frame had a protocol 4 result (2026-11-13, after smoke9): the reference dashboards became views of
one dashboard, opened by navigation in the loaded frontend; usable waits for the head and a 500 ms
free stretch; change to screen runs from when the fake sent a change; and the fake resets the home
between views. smoke9's numbers came before that and aren't protocol 4's. Every summary from
protocol 3, the S1's A included, is now "re-measure".

### Showing the grade

The site computes the letter **at render time** from the frame's summary and `rubric.json`, with
`tools/perf/grade.ts` (plain TypeScript; the shared frame package can import it later, #114):

```ts
import { gradeSummary } from "./grade.ts";
const g = gradeSummary(summary, rubric);   // rubric = tools/perf/page/rubric.json
// g.status: "graded" | "re-measure" | "not-comparable"
// g.grade (A+ to E), g.meaning, g.levels (per page), g.limits (what keeps it from the next
// letter), g.provisional
```

| `status` | Show |
|---|---|
| `graded` | The letter (A+ to E) and its meaning. When `provisional` is set (something wasn't measured, or `gaps` isn't empty), mark it provisional |
| `not-comparable` | No letter, and `why` (from `not_comparable`): not at the default zoom, or nothing measured. Checked first |
| `re-measure` | "Re-measure", not a letter: the summary's protocol isn't the rubric's (a missing protocol reads "unknown") |

`report.md` still gives a letter for pages from another protocol, for the record, and its headline
ends "Re-measure to publish: the site shows no letter for these pages."

`grade.py`'s `grade_summary` gives the same answer. `test_summary.py` builds fixture reports
(letters A, B, C by the restart cap, E, a refused zoom, rAF-only scrolling, an even run count, a
missing run), exports each summary, and checks that Python's grade from the summary equals the
report's grade, and that `grade.ts` (run under node) equals Python's.

### How #68 uses it

[#68](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/68) is the report of APK size and RAM
per frame and pane, with budgets. Its two halves come from different places:

- **RAM comes from this test, on the device** (see [RAM](#ram) for why not an emulator). The
  report's `ram` holds the reference dashboard's numbers per process and per pane, and checks them
  against the frame's budgets in `frames/<id>/budgets.json`. The summary carries them (`ram`),
  with each pane's budget and whether it is within it (`budget_kb`, `budget_ok`).
- **APK size comes from CI** on every pull request, which needs no device: the size per ABI split
  into the engine, the core, each pane, assets and native libraries, the change from the base branch,
  and the frame's size budget in the same `frames/<id>/budgets.json`. See [APK size](size.md).
  `--apk` still adds sizes to a device report.
- **Panes** use the ids from the code (`assist`, `sendspin`, `radio`, `wall-base`, `timers`,
  `time-zone`, `diagnostics`). Only `assist` and `sendspin` can be switched today; the others wait on
  [#67](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/67).

## Page log lines

The pages write one console line per event, `GWPERF {json}`. It reaches logcat from the content
process (tag `Isolated Web Content` on the S1), and **only its first 1023 bytes arrive** (read from
the S1's logcat, 2026-11-12: smoke7's four 4 s updates steps, about 1.5 KB each, were cut there and
didn't parse; logcat itself allows 4068 bytes). So a page line longer than 900 bytes of UTF-8 is split, never inside a character, into
numbered parts, `GWPERF+ <nonce>:<n> <k>/<parts> <chunk>` (`stats.js`, `consoleLines`), and the
runner joins them (`perflib.PageLines`). A line that still doesn't parse is counted
(`bad_page_lines`) and makes the grade provisional, and so is a line whose parts haven't all arrived
when its page says done. A swipe the runner answered with no scroll step back is counted too
(`lost_scroll_steps`), and makes the grade provisional. A page that says done without its updates step
doesn't fail the run: its other tasks stand, and its live updates read "not measured". Every line has `run`, `nonce` (the runner's id for that page
load) and `t`:

| `t` | Fields |
|---|---|
| `refdash` | A reference view, usable: `tier` (the view: `start` for the restart page, or `light`, `typical`, `heavy`), `protocol`, `frontend` (HA's frontend release), `nodes`, `cards` (the view's cards), `cards_rendered` (how many HA had built when it was usable), `css` (viewport), `dpr`, `scroll_height`, `fcp_ms`, `lcp_ms`, `load_ms` (the frontend's own load: the restart page only, else `null`), `nav_ms` (the navigation to the view; 0 for the frontend's own load), `usable` (`rendered_tick_ms`, the frame in which it was drawn; `first_screen_cards`; `head_cards`, the 10 head cards it waited for; `interactive_ms`, when the main thread's free stretch began) (all after `performance.timeOrigin`), `origin_epoch_ms` and `epoch_ms`, which tie the page's clock to logcat's |
| `warmup` | `stage`, `n`. The page goes live; the runner wakes the screen and stops polling SurfaceFlinger |
| `measure` | `stage`, `n`. A window starts. For `updates`, the live check: `moving`, `offscreen`, `targets`, `update_every_ms` |
| `step` | `stage` (`updates`, `scroll`, or a ladder family), `n`, `window_ms` (the window's length on the page's clock), `raf` (frame stats). For `updates`: `t0` (the window's start on the page's clock), `pacing_ms` (the stretch `raf_times` covers), `from` (`sent`: the changes start when the fake sent them), `stream_at_ms` (when the harness asked for the stream), `raf_times` and `changes` (`[sent, arrived, transition ms, kind, tick]`: every change the fake sent up to the window's end, from its schedule; `arrived` and `tick` are `null` for one that hadn't reached the page when the window was logged; kind 0 a sensor value, 2 an on/off change (1, the clock, was protocol 3's); tick, the animation frame it was drawn in), all ms after `t0`. For `scroll`: `scrolled_px`, `hit_edge`, `changes_during`, `first_scroll_ms`, `raf_intervals`. The runner reads SurfaceFlinger |
| `input-ready` | `x`, `y` (device pixels), `taps`. The runner taps |
| `swipe-ready` | `n`, `x`, `y1`, `y2` (device pixels), `ms`. The runner swipes once |
| `input` | `stage`, `method`, `taps`, `values`, `inp_ms` |
| `hello`, `js` | Animation test page: `ua`, `dpr`, `css`, `cores`, `load`; the JS workload's `runs`, `median_ms`, `p95_ms`, `checksum`, `expected` |
| `done`, `error` | `message` on error. At `done` the page has stopped its updates |

The restart page logs with an empty nonce (its parts are numbered `restart:<n>`): only `refdash` and
`done`, kept in `refstart-<k>.json` under `restart_page`. A warm-up view (`--warmup-tier`, none by
default since protocol 4) is a view opened with `taps=0&swipes=0&warmup=1`; its lines are kept
under `warmup`, not graded.

The runner and the harness talk through the fake Home Assistant, never through adb:

| Request | From | What |
|---|---|---|
| `/clarity/open?view=&nonce=&run=&vsync=&taps=[&swipes=&warmup=]` | the runner | Open this view next. Checked: a known view, a hex nonce, numbers only; else 400 |
| `/clarity/next?after=<nonce>` | the harness | Waits up to 20 s for a command other than `after` (the last one this page ran); 200 with it, or 204. The command stays until its view's stream stops, so a long poll left behind by a page that died (a force-stopped frontend's) can't use it up |
| `/clarity/live`, `/clarity/still` | the harness | Start the stream (200 `{"gen": n}`, its generation), or stop it, end the view's command and reset the home |
| `/clarity/cancel` | the runner | Before each restart: no command, no stream and no changed state is left from a view that was cut short |

Frame statistics are defined once, in `tools/perf/page/stats.js` and `tools/perf/perflib.py`.
`tools/perf/test/stats-cases.json` pins both to the same answers.

## Example: Aqara S1 Plus

**Protocol 4: D** (s1v7, 131f6da, 3 of 3 runs, rubric v5, S1 Kiosk 0.4.3, the radio in
`thread_router`, 2026-11-13; not provisional). "A good wall panel for light dashboards. Held back by
typical dashboards: scrolling averages 35.1 fps (p95 83 ms); the view takes 4.8 s to open." The
verifier recomputed the D by hand, with `grade.py` and with `grade.ts`.

| View | Level | Tap | Scroll | Change to screen (p95) | Open the view |
|---|---|---|---|---|---|
| light (20 cards) | fluid | 96 ms | 40.9 fps, p95 66.7 ms | 84 ms | 1.45 s |
| typical (50 cards) | neither | 96 ms | 35.1 fps, p95 83.3 ms | 96 ms | 4.82 s |
| heavy (120 cards) | neither | 88 ms | 23.3 fps, p95 133 ms | 156 ms | 14.5 s |

Restart, process start to the start view usable: **11.7 s** (11.6 to 13.0 s), poor; engine ready
2.9 s (detail). Medians of 3 runs. Scrolling holds back every view and open the view holds back
typical and heavy. Taps and the live changes are good everywhere except heavy's changes (fluid).
The panel ran with the wake word using about 37% of a core
([#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105)); the move to microWakeWord
(#181) needs a re-measure. s1v7's pane numbers are not valid (see [RAM](#ram)); s1p1 and s1p3 below
are.

**RAM per pane** (s1p1 and s1p3, 248ce09, 2026-11-13, S1 Kiosk 0.4.3, the radio in
`thread_router`; verified: every sample's kiosk log showed what its label says, none left out, and
the panel was put back each time). In both runs the kiosk kept `voice_enabled` (the 0.4.3 bug
above), so Assist is off by its microphone mute: its running cost, not the model it loads.

| | Assist CPU | Assist PSS | Sendspin CPU | Sendspin PSS |
|---|---|---|---|---|
| s1p3, 3 runs (9 samples) | **+36.3% of a core** (±1.2) | +5.2 MB, within ±31.6 | +1.9%, within ±2.9 | +0.3 MB, within ±31.6 |
| s1p1, 1 run (smoke) | +36.5% | +21.3 MB | +3.5% | +50.1 MB (`:boot`, see [RAM](#ram)) |

Assist's CPU matches [#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105)'s 37%
(the wake word's model). Neither pane's PSS is measurable at this tolerance, so these runs can't say
whether either fits its 48 MB budget ([why, and the fix](#ram)). The app at idle on the typical
reference dashboard, all panes off: 565 to 596 MB.

**smoke9** (8c0027b, one run, 2026-11-13, protocol 4 as first written: each reference dashboard a
full page load) read **E, provisional**: open the view 4.9, 8.2 and 5.0 s (light, typical, heavy);
restart 10.4 s; scroll 42.9, 36.9 and 19.1 fps (heavy's p95 167 ms); change to screen 112 and 115 ms
on light and typical, heavy not measured ("only 17 of 20 state updates ran in the window"); taps 168,
88 and 232 ms. Its RAM part ran (Assist +2.8 MB, Sendspin +15.2 MB, n=1) and the restore worked. It
is not a protocol 4 result: open the view then counted HA's cold load each time, heavy's updates
were dropped instead of counted (its changes reached the page in bursts, with frames stalled for up
to 649 ms), and its fake HA log was never written (`run.sh` didn't pass `--log`), so its "no
unknown commands" couldn't have seen one. All three are fixed above.

**Protocol 3, superseded: A** (s1v6, 3 of 3 runs, rubric v5, S1 Kiosk 0.4.3, measured 2026-11-13
UTC, the page order turning each run, nothing missing, no gaps). Protocol 3's pages were an imitation
of HA's cards and its restart ended at engine ready, so this letter doesn't carry over (see
[Not tuned to any frame](#not-tuned-to-any-frame)):

| Page | Scroll, median of runs (each run) | Change to screen, p95 | Tap (INP) | Opens in |
|---|---|---|---|---|
| light | 56.0 fps (54.2 to 56.3), good in all 3 | 76.5 ms (67.7 to 77.6), good | 88 ms | 1181 ms |
| typical | 53.8 fps (53.1 to 53.8), good in all 3 | 64.0 ms (61.6 to 68.8), good | 64 ms | 1382 ms |
| heavy | 54.4 fps (54.3 to 54.7), good in all 3 | 70.4 ms (69.2 to 87.9), good | 120 ms | 1985 ms |

Restart (process start to engine ready) took 2929 ms: needs improvement, under the 5 s cap. Every
swipe moved freely (none reached an edge) and all 18 ran while the live updates did. Android's
framework didn't crash during the run: its dropbox count was the same before and after (checked by
the root on the panel). The run's `summary.json` (`summary.py`) grades A in both `grade.py` and
`grade.ts`. It is an A, not
an A+: on heavy, scrolling averages 54 fps with p95 33 ms, under the 60 fps rule, and the frames of the
live changes don't hold 60 fps either. s1v6 ran with the pages' old colours; the palette change after
it changes no work (see [Protocol and rubric versions](#protocol-and-rubric-versions)).

The earlier runs, under earlier definitions, are below.

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
  warm-up, turning order and shared first screen answer the rest.
- **s1v5** (d00fb81, protocol 1, 3 runs, S1 Kiosk 0.4.3, orders rotated). Under rubric v4 it graded
  E. Re-graded offline under v5 (the raw data has what change to screen needs: when each change
  applied, the rAF frames and SurfaceFlinger's presents), it gives **C, provisional** (protocol 1
  pages):

  | Page | Scroll (per run) | Live updates, change to screen p95 (per run) | Tap | Opens in |
  |---|---|---|---|---|
  | light | 42.6 fps, fluid, in all three runs | 72 to 88 ms, good | 96 ms | 1182 ms |
  | typical | 49.6, 50.0 and 50.7 fps: fluid, fluid, good, so fluid | 66 to 87 ms, good | 144 ms | 1377 ms |
  | heavy | 47.7 fps, fluid, in all three runs | 66 to 78 ms, good | 120 ms | 2004 ms |

  Typical misses the good scroll bar by under 0.5 fps in two of three runs, and that decides the C.
  Light's scroll is the protocol 1 artefact above: every light swipe hit the end of the page, at
  41 to 46 fps in every position. These were 9 changes per run, so the p95 is the slowest of them.
- **smoke7** (d60ec95, protocol 2, one run, 2026-11-12) failed with "did not finish within 600 s"
  although all three pages finished. Every updates step was cut at 1023 bytes in logcat and didn't
  parse, and the runner then counted the run as failed. Fixed: long lines are split, and a lost step
  no longer fails a run. Its other measurements, re-read: light's page is 1936 px tall; every swipe
  moved 559 to 563 px without reaching an edge, with 8 or 9 live changes during each. Scroll is good
  on every page (light 56.0 fps, typical 53.8, heavy 53.5, p95 33 ms), so protocol 2 removed light's
  scroll artefact. Live updates weren't measured in that run.

## Run the tests

```sh
python3 -m unittest discover -s tools/perf/test
node --test tools/perf/test/page.test.mjs tools/perf/test/grade.test.mjs
```

`test_fakeha.py` checks the fake Home Assistant against the traffic HA's frontend sent it
(`test/fakeha-traffic.json`), on a stub frontend, so the tests never download the real one. After a
frontend upgrade, record the traffic again and add any new command to the fake.

The node tests need node 24 or later, which runs `grade.ts` without a build step.

CI runs both in the `scripts` job.
