# WebRTC cameras on the S1: does GeckoView play them, and with which decoder?

**Status (2026-11-13): partly working.** A real camera plays live over WebRTC in the kiosk. The video
is very likely decoded in software (Gecko's bundled FFmpeg), not by the Allwinner hardware decoder that
HLS gets. That is read from the logs, not proven. Frame rate and dropped frames weren't measured.

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
   - **The frontend took the WebRTC path.** The page logged a WebRTC warning from the browser ("Using
     five or more STUN/TURN servers slows down discovery"). HLS logs nothing like it.
   - **It played live.** Across 10 s, about 226,000 pixels changed by more than 3 levels (compression
     noise on a still room), so frames were updating.
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
| Plays in GeckoView on the S1 | yes (s1cam3) | yes (this note) |
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
