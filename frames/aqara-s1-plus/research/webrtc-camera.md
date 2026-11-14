# WebRTC cameras on the S1: does GeckoView play them, and with which decoder?

**Status (2026-11-13): partly working; Clarity's WebRTC now connects on this panel (smoke 3, below).** A real camera plays live in the kiosk, and the frontend started
WebRTC. That WebRTC (not HLS, which the frontend shows while WebRTC starts) drew the frames on screen
is likely but not proven. The video is very likely decoded in software (Gecko's bundled FFmpeg), not by
the Allwinner hardware decoder that HLS gets. That is read from the logs, not proven. Frame rate and
dropped frames weren't measured.

Device: Aqara Panel Hub S1 Plus, S1 Kiosk test build 5427a67, GeckoView 159. Home Assistant 2026.11
with go2rtc loaded. Tools: adb (read-only), the kiosk's one-shot `--es open` extra.

## Question

Clarity, the perf test, graded cameras over HLS until protocol 5. Real setups mostly play over WebRTC,
because go2rtc has been built into HA since 2024.11 (#202). Before grading WebRTC, we wanted to know:
1. Does GeckoView on the S1 play a real camera over WebRTC?
2. Does that video use the hardware decoder (`OMX.allwinner.video.decoder.avc`), as HLS does?

## What we tried

1. **One real camera over WebRTC, read-only** (2026-11-13, about 17:40 UTC, verified; by the root
   thread on the shared dev panel)
   ```
   $ adb shell am start -n sh.edm.s1kiosk/.MainActivity \
       --es open 'http://<ha>/tablet-home?more-info-entity-id=camera.<x>'
   ```
   The camera is a high-resolution channel of a network video recorder, through HA's go2rtc. The
   kiosk's saved dashboard URL was unchanged; it went back to the dashboard afterwards.
   - **The frontend started WebRTC.** The page logged a WebRTC warning from the browser ("Using
     five or more STUN/TURN servers slows down discovery"). HLS logs nothing like it. This shows that
     WebRTC started, not that it drew the frames: with go2rtc, HA's frontend shows HLS until WebRTC has
     video, and keeps HLS if WebRTC never gets any (`ha-camera-stream.ts`).
   - **It played live.** Across 10 s, about 226,000 pixels changed by more than 3 levels (compression
     noise on a still room), so frames were updating. From which player is not recorded. Next time, settle
     it from the visible video element: a `MediaStream` in its `srcObject` is WebRTC; HLS plays from a
     `blob:` source. (No `/api/hls/` requests wouldn't settle it: HLS can play from its buffer for a while.)
   - The commands for the pixel count and the CPU numbers below weren't recorded with the results;
     only the results were reported. Redo them with the commands written down before relying on them.
2. **Which decoder** (same session, read-only, verified)
   - `libmozavcodec.so` (Gecko's bundled FFmpeg) was loaded in the tab process.
   - logcat had no `MediaCodec`, `CCodec` or `OMXNodeInstance` lines for the stream.
   - For comparison, HLS on the same panel logs an `OMX.allwinner.video.decoder.avc` instance per
     stream, and its flushes (s1cam3, [docs/perf.md](../../../docs/perf.md#live-camera-streams-174-protocol-5)).

   So the WebRTC video was very likely decoded in software. This is **read** from the logs, not
   proven: a loaded library isn't proof that it decoded this stream.
3. **CPU over 36 s**, with the camera's more-info dialog over the dashboard (verified):

   | Process | CPU (% of one core) |
   |---|---|
   | Tab process (`:tab`) | 93.7 (its main thread "Isolated Web Co" 72.7, TaskController 18.1) |
   | GPU process | 24.9 (Renderer 19.0) |
   | Main app | 15.4 |

   The S1 has 4 cores, so this one stream used about a third of the CPU. Software decoding of a high
   resolution stream fits that, but the dashboard behind the dialog also ran.

Not measured: frames per second and dropped frames. `getStats` isn't reachable over adb.

## Result

| | HLS (hls.js on MSE) | WebRTC |
|---|---|---|
| Plays in GeckoView on the S1 | yes (s1cam3) | started, and the camera played live; WebRTC as the player on screen is likely, not proven (this note) |
| Decoder | `OMX.allwinner.video.decoder.avc`, hardware (logcat, verified) | very likely Gecko's FFmpeg, software (logcat and the loaded library, read) |
| Who fetches the video | hls.js, on the page's main thread | the browser's media stack |

What it means for Clarity (protocol 6, #202):
- The graded WebRTC camera task may measure software decoding on 4 cores. Heavy's four streams could
  be CPU-bound rather than fetch-starved, as they were over HLS. That is still the honest number for
  a real setup on this panel.
- The harness records the decoder the browser reports for each WebRTC stream (`getStats`:
  `decoderImplementation`, `powerEfficientDecoder`; Gecko may leave them out), and the runner keeps
  reading the decoders from logcat. A WebRTC view with no `MediaCodec` lines is consistent with
  software decoding.

Clarity's first WebRTC smoke on this panel (2026-11-13, about 20:20 UTC) didn't connect: GeckoView
got no relay candidate from the test's TURN relay on loopback. Why, and the fix, are in
[docs/research/gecko-turn.md](../../../docs/research/gecko-turn.md).

## Clarity's WebRTC smokes on this panel (#202)

| Smoke (2026-11-13, the root) | At | WebRTC | Why |
|---|---|---|---|
| 1, about 20:20 UTC | d668e26 | never connected | Gecko won't use a TURN server at a loopback IP ([gecko-turn.md](../../../docs/research/gecko-turn.md)) |
| 2, about 20:49 UTC | a718f9c | allocated, no candidate | the allocation said the browser was on loopback (adb reverse) |
| 3, later that evening | 5ae5884 | **connected**: 22 relay candidates from the panel, ICE completed 11 of 11 | heavy's counts broke (below) |
| 4, later still | 3c1e1da | connected; counts sound | every heavy session lost ICE by aiortc's consent check, while the panel's answers were only late (below) |
| 5, later still | 88f7d8f | connected; peer as go2rtc | heavy still re-made all 4 sessions: the panel stopped getting video (below); typical 99.2% shown |

Smoke 3, typical (verified): first frame 6.3 s from the navigation, 99.6% shown (the receiver's own
counts), 367 frames decoded, no freeze.

**Heavy re-made its sessions, and the counts went negative** (verified from `fakeha.jsonl` and the
page's camera line):
1. All four cameras connected about 1.5 to 3 s after heavy opened (the Mac's clock, relative:
   163.5 to 164.7 s).
2. About 31 s later (194.9 and 195.6 s), aiortc closed ICE for two of them. The browser hadn't
   unsubscribed: the sessions were still open.
3. 26 to 33 s after that, those two cameras sent new offers (221.1 and 228.7 s), which connected.
   The other two lost ICE the same way later (241.5 and 246.0 s) and re-offered at 271.8 and 272.1 s.
4. The older subscriptions were closed only when the page's websocket closed (412.3 s), long after
   the view had gone (293.2 s).
5. The page diffed one getStats snapshot at the window's start against one at its end. The end one
   was a different receiver (Gecko can reuse the stats id with new counters), so camera 0 read -189
   frames decoded and -0.7% shown; camera 2 had no inbound video at the end and fell back to its
   frame callbacks (0.2%).

**Why the sessions were re-made** (read from the frontend at the pinned tag, `ha-web-rtc-player.ts`;
the order of events above fits it):
- On `iceConnectionState` "failed", the player calls `restartIce()`. That fires `negotiationneeded`,
  and `_startNegotiation` sends a new `camera/webrtc/offer` on the same RTCPeerConnection. It
  overwrites `_unsub` without unsubscribing the previous offer, so the old session stays open until
  the websocket closes (point 4).
- So it isn't a re-render on scroll or on state updates: the video element stayed the same (its
  frame callbacks went on), and HA's player renegotiated after ICE failed. Gecko reports "failed" some
  time after traffic stops, which fits the 26 to 33 s between aiortc's close and the new offers.
- The harness now records, per camera, each connection's ICE states and offers (`connections` in
  the WebRTC detail), so the next run shows this directly.

**Why ICE was lost** (the first cause is read from aioice 0.13.0; the second is inferred):
- aioice's consent check (RFC 7675) sends one Binding request every 4 to 6 s (`CONSENT_INTERVAL` 5,
  randomised), gives the answer 0.5 s (`RETRY_RTO`, no retransmission), and closes ICE after 6
  unanswered in a row (`CONSENT_FAILURES`): about 30 s. An answer later than 0.5 s counts as none.
  RFC 7675 lets consent last 30 s from the last answer, however late each is; browsers and pion (in
  go2rtc) are that lenient. So aiortc is stricter than a real setup's peer.
- The relay's counts for those two connections: the panel's datagrams to the Mac (`to_peer`) were 112
  and 125 over their lives, about 4 a second for 28 to 31 s, then none; typical's ran 70 s at the
  same rate. Either the panel's answers came later than 0.5 s (TCP through adb carries them behind
  the video, and heavy keeps the panel's CPU busy), or the panel stopped sending.
- The relay now times each of aiortc's checks through the panel (`checks` in `turn_closed`:
  answered, the median and longest round trip, how many over 500 ms, the late ones with when, and
  unanswered), and the fake marks an ICE loss `reason: consent` when aioice's consent expired. The
  next smoke tells the two apart. If the answers were only late, the drops are the test's (aiortc's
  strictness, the TCP transport), not the panel's, and that is a decision for the maintainer.

**Smoke 4: late, never missing** (verified; the root, 3c1e1da). The relay timed each of aiortc's
checks through the panel:

| Connections | Answered / unanswered | Median round trip | Longest | Over 500 ms |
|---|---|---|---|---|
| light and typical (3) | 3/0, 13/0, 12/0 | 16 to 22 ms | 460 ms | 0 |
| heavy, first sessions (4) | 14/0 each | 2.3 to 2.6 s | 8.2 to 9.6 s | 10 to 11 of 14 |
| heavy, second sessions (4) | 7/0 each | 1.4 to 2.0 s | 2.4 to 2.8 s | 6 of 7 |

aioice logged "Consent to send expired" 8 times, and every ICE loss was by consent. All 4 heavy
cameras needed another session (41.9 s without video in all). So the losses were the test's: a
real setup's peer, go2rtc (pion), counts any packet from the browser as alive and gives up only after
30 s of nothing. The decision (the root, 2026-11-13): the fake's peer now uses pion's rules, with the
numbers and sources in [docs/perf.md](../../../docs/perf.md). The round trips stay logged.

**Frames shown read low on heavy** (smoke 4: 1090 decoded, 690 dropped, shown 4.9 / 3.9 / 34.1 /
16.9%). In GeckoView's libwebrtc, `framesDropped` counts frames that never came out of the decoder,
so `framesDecoded − framesDropped` subtracted frames that weren't in `framesDecoded`. Frames shown over
WebRTC are now `framesDecoded`; the sources are in docs/perf.md. Typical changes little (356 decoded,
2 dropped).

**Smoke 5: the panel stopped getting video first** (verified, the root's run at 88f7d8f; read from
`fakeha.jsonl` and the page's camera line, times from heavy's navigation):

| | When | Evidence |
|---|---|---|
| Heavy usable | 41 s | the page's `refdash` |
| The live phase, then the taps | 45 to 104 s | taps from 52 s; the runner sends each as `adb shell input tap` |
| Gecko's ICE "disconnected", all 4 cameras | 64, 71, 71, 76 s | `webrtc.connections[].ice`: nothing received from the Mac for some seconds |
| Gecko's ICE "failed"; HA's player restarts ICE | 91 to 98 s | the same; new offers at about 99 to 101 s (the fake's clock) |
| The Mac's peer: "disconnected", then "failed" | 10 s and 36 s after the new offers | `webrtc_liveness`: these are the sessions Gecko had just left, not a second silence |

So the Mac→panel direction dried up first, for all four streams at once, while the fake kept sending
the live updates on time (`late_ms` about 10 ms each second) and the panel's few websocket commands
kept arriving. The streams' own counts fit late bursts: 0 packets lost and 0 NACKs (TCP), a jitter
buffer of 600 to 710 ms, and about half the frames received dropped before decoding. The check
round trips (median 2.2 to 4.7 s, up to 11 s, a few unanswered) measure the same queue: a check goes
down to the panel and back.

Where the video waited isn't known yet: on the Mac (adb not taking it), in adb, or on the panel
(Gecko not reading). Two candidates fit: the adb path (one USB link carrying four relayed streams,
about 3 Mbit/s, the websocket and HLS), and a panel so busy that adbd or Gecko's network thread falls
behind (heavy keeps the panel's CPU near full, and each `input tap` starts a Java process on it).
The tools for the next run:
- the relay logs, each second, each connection's bytes and datagrams both ways and how much it holds
  queued toward the panel (`seconds` in `turn_closed`); the fake logs the websocket's traffic each
  second and its longest blocked send (`ws_second`);
- `tools/perf/transportwatch.py` samples the panel read-only: each second the TCP sockets on the
  fake's ports with their send and receive queues (`/proc/net/tcp`: a large `rx_queue` on the kiosk's
  socket is data Gecko hasn't read), and every few seconds each thread's CPU in the kiosk and adbd;
- the runner logs when each tap and swipe went out and how long `input` took (`gestures_sent`).

**The LAN path, as a diagnostic.** The panel's routing table (`/proc/net/route`, read 2026-11-13) has
`wlan0` on 192.168.1.0/24, the Mac's subnet (192.168.1.102), so it can likely reach the Mac without
adb. That isn't tested yet: a connection test from the panel isn't a read-only step. run.sh's
`--webrtc-transport lan-turn` (the same relay over TCP to the Mac's address) and `lan-udp` (no
relay: UDP between panel and Mac, as a real setup) take adb out of the video's path, for a
comparison only; the graded path stays `adb`.

**The decision: option B** (the maintainer, 2026-11-13). Camera video goes over the LAN when the
panel can reach the computer, UDP first, and through adb otherwise; the run records which (protocol 7,
`tools/perf/lanprobe.py`, docs/perf.md). Ping from the Mac to the panel's wlan0: 3 of 3, about 7 ms
(the root, 2026-11-13). 

**Smoke 6: the video waits inside adb, not in Gecko** (verified; the root's run at f27b5db with
`transportwatch.py --logcat`, 2026-11-13; read from `fakeha.jsonl`, `transport.jsonl`,
`refstart-1.json` and `logcat.txt`):

| Where | Heavy, all 230 s | Read from |
|---|---|---|
| The relay on the Mac, queued toward the panel | 0 kB every second; each stream sent steadily at its own rate (45 to 60 kB/s, all of it) | relay `seconds` |
| Gecko's sockets on the panel, received but not read | at most 16 kB | `/proc/net/tcp` rx_queue, uid of the kiosk |
| Gecko's sockets, written but not taken by adbd | at most 120 B | the kiosk's tx_queue, adbd's rx_queue (at most 136 B) |
| adbd's sockets toward Gecko | at most 18 kB | adbd's tx_queue |
| The peer's checks, round trip | median 2.3 to 2.6 s, up to 12.8 s; 15 to 20 of about 60 unanswered | relay `checks` |

So Gecko read what reached it at once, and its answers left it at once. Yet the round trip took
seconds, and Gecko received only 305 to 548 of the 885 or so frames each stream was sent in the
window. Its own ICE said "disconnected" at 67 to 76 s and "failed" at 91 to 101 s. Every queue that
can be read stayed small at 1 s sampling. So the seconds pass between the relay's socket on the Mac
and adbd's sockets on the panel: in the adb server on the Mac, the USB link, or adbd's own buffers.
Not Gecko.

- adbd had 12 to 25% of a core during heavy. The panel's kiosk processes used about 3 of its 4 cores
  at times: main up to 111%, the tab 122%, the GPU process 70%. So adbd is busy but isn't starved to a
  stop.
- Gecko's ICE disconnects came within a second of the taps' starts (the first three taps at 66.5,
  71.2 and 75.4 s). Each `adb shell input tap` took 3.0 to 4.1 s on the panel and goes over the same
  adbd and USB link.
- Java GC pauses stayed under 0.3 ms; nothing else in logcat points at the panel.
- A confound: the watcher's own adb commands (one each second, and a thread sample of about 5 s each)
  share the link. Smoke 5, run without the watcher, failed the same way.

This is the test's link, not the panel: a real setup's video doesn't go through adb. It supports
option B. Over the LAN (UDP), adb carries only the page's commands and the runner's taps.

**Smoke 7: B works; heavy's run hung, and the decoders kept up** (verified; the root's run at e7231bb,
2026-11-13; `refstart-1.json`, `fakeha.jsonl`). The probe chose `lan-udp` and the relay wasn't used.
On heavy, all four WebRTC sessions stayed connected for the whole view, with one session each and no
ICE loss. The receivers decoded 6998 to 7058 of the 7067 frames each received, with 0 freezes. So the
video arrived at full rate and was decoded: **the hardware decoders didn't run out in a way that
stopped WebRTC.** What went wrong:

| | Seconds after heavy's navigation | Evidence |
|---|---|---|
| Usable | 51 | `refdash` (smoke 6 over adb: 53) |
| The updates window: rAF at 3.0 fps, p95 833 ms; one live change 1.7 s late | 54 to 64 | updates step; `fakeha.jsonl` |
| The 10 taps go out (each `input tap` 3.0 to 4.6 s over adb) | 65 to 109 | `gestures_sent` |
| The page logs its taps (INP 584 ms) | **525** | the `input` line: the page hardly ran for 7 minutes |
| The screen goes off (the kiosk's idle timeout, about 2 minutes after the last tap) | 228, 615 | logcat |
| The run ends: heavy never said done | 900 s | the runner's limit; the run was dropped |

The video element's frame callbacks show 455 s of "stalls" per camera. Those are the screen being
off (nothing is drawn), not the video.

**Real, or the test's?** Three parts:
1. **The test's: HA's HLS players took the hardware decoders first.** With go2rtc, HA's frontend
   starts an HLS and a WebRTC player for each camera and keeps WebRTC once it has video. The fake
   answered "preload stream" on and served 4 s of HLS segments at once. Heavy's four `camera/stream`
   requests came 0.6 s before its WebRTC offers, and logcat shows 4 `OMX.allwinner.video.decoder.avc`
   (the S1's 4 hardware instances), then 6 `c2.android.avc.decoder` (software). Typical, one camera,
   got 2 OMX instances, and its WebRTC played 100% with HLS never showing a frame. So GeckoView's
   WebRTC does use MediaCodec when an instance is free. That corrects step 2's guess, which was read
   from a real camera's logs and not proven. Which player got which decoder isn't in the log; the
   order points to WebRTC on software for heavy. HA 2026.11.0 defaults to no preload
   (`camera/prefs.py`), and its master playlist waits for two segments (`stream/hls.py`), so in a
   real setup HLS starts seconds after go2rtc's WebRTC. The fake now does the same.
2. **The panel's: heavy is slow.** Heavy was usable after 51 s and drew 3 fps with the cameras
   playing. Over HLS (s1cam3) it was usable after 36 s at 5.7 fps. Decoding four full-rate streams,
   some in software, on a panel already busy with heavy costs CPU, and that is what a real home with
   four cameras on the view would see. Smoke 8 shows how much of it was the software decoders.
3. **The test's: the hang.** The runner keeps the screen awake between views but not during a live
   view (wake intents could disturb what's measured), and it waited 900 s for heavy, then dropped
   the whole run. Now it wakes a live view nothing has touched for 30 s, gives each view 300 s, and
   records one that runs out as timed out: what it measured counts, the rest fails as not measured.

The stream sizes aren't the cause: Clarity's are lighter than a real grid's (docs/perf.md).

**Three graded runs at c5f41a0** (s1rtc3, the root, 2026-11-13; verified from its files). Smoke 8
was clean, so the root ran the graded set: D, provisional. Heavy timed out in all 3 runs: usable
after 45 to 53 s, live updates measured in 2 runs (916 ms to screen, p95), then no done within
300 s. The runner then ended each run's views, and the order turns each run, so runs 2 and 3 never
opened the views after heavy. Light was measured in 1 run of 3 and typical in 2, and the summary was
refused. That was the test's rule, not the panel: a view that can't finish now fails its own tasks
(measured, not missing, so not provisional), and the runner restarts the kiosk and opens the rest
(docs/perf.md, "A view the panel can't finish"). The next 3 runs are pending.

**The 65 processes after the run.** The root counted about 65 processes named `sh.edm.s1kiosk`, one
in D state, after smoke 7. A read-only `ps` an hour later showed the usual 7. What they were isn't
known: the runner restarted the app at the end, so leftovers of the hung session are likely.

**The counts now** (`stats.js` `webrtcWindow`): the page samples every connection that carries a
camera's video once a second. Each receiver (connection, stats id, SSRC) counts only its own
increments: one first seen after the window's start counts from 0, a counter that goes back counts
on from 0, and no count is negative. The time between samples in which no receiver decoded a frame
is `no_video_ms`; the graded stalls stay those of the video element's frame callbacks, which already
counted heavy's gaps (37 s of stalls on camera 0 in smoke 3).

**Decoders on heavy, smoke 3** (logcat): 4 `OMX.allwinner.video.decoder.avc`, then 6
`c2.android.avc.decoder` (software), then 5 OMX. HA's frontend starts an HLS player for each camera
before WebRTC has video, and the log doesn't say which player each decoder served, so this neither
confirms nor rules out step 2's software decoding for WebRTC.

## How to reproduce

Read-only, on a panel with S1 Kiosk and HA with go2rtc:
1. `adb shell am start -n sh.edm.s1kiosk/.MainActivity --es open 'http://<ha>/<dashboard>?more-info-entity-id=camera.<x>'`
   (a one-shot page; the saved dashboard doesn't change).
2. `adb logcat -d -v threadtime -s MediaCodec CCodec OMXNodeInstance GeckoMediaManager` for hardware
   decoders.
3. As root, one way to see the library (untested as written): `grep -c libmozavcodec /proc/<pid>/maps`
   for the kiosk's tab process (find it with `ps -A | grep s1kiosk`).
4. Go back with the kiosk's own dashboard: `--es open` again with its URL, or restart the app.

## Open questions

- **Can GeckoView use MediaCodec for WebRTC H.264 on Android?** Gecko has prefs for hardware WebRTC
  H.264 (such as `media.webrtc.hw.h264.enabled`; the exact name and its default in GeckoView 159 are
  untested). Next test, for the root and the panel only: set it in the kiosk's Gecko config, play the
  same camera, and look for an `OMX.allwinner` instance in logcat. Read-only apart from that pref, and
  put it back after.
- Frame rate and drops of a real WebRTC camera on the S1: Clarity's protocol 6 runs will give them
  for its test stream (`getStats` detail).
