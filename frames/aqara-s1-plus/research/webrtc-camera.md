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
