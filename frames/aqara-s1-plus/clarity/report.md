# Clarity report: Aqara S1 Plus

Allwinner QUAD-CORE R818 EVB2, Android 10 (API 29), armeabi-v7a, 4 cores, 1976 MB RAM, app pre-v1.

**Aqara S1 Plus: Clarity D, a good wall panel for light dashboards. Held back by typical dashboards: the view takes 4.9 s to open.**

Light dashboards feel good: taps, live updates and opening a view are quick, and scrolling is fluid. Bigger dashboards slow down: typical with a camera scrolls at 28 fps, and heavy takes 360 ms to answer a tap and takes 0.8 s to show a change. The likely limit is the browser's main thread: it stays busy (81% of a core on typical with a camera playing), and the page draws too few frames. Memory isn't the limit (925 of 988 MB).

The grade comes only from the reference dashboards this test ships (light, typical without and with a camera, heavy), so it doesn't depend on your Home Assistant. Background load (panes, the radio, heat) still reaches it.

Each page's tasks (median of runs; scroll and live-update levels are the median of each run's level). A page is good when scrolling averages at least 50 fps (p95 at most 50 ms), 95% of live changes show within 100 ms, taps within 200 ms and the view opens within 4 s; fluid at 30 fps (scroll p95 at most 67 ms) and changes within 200 ms; premium when scrolling and the live changes also hold 60 fps (A+ on heavy). Live-update pacing is detail, not graded.

| Page | Level | Tap (INP) | Scroll | Live updates | Open the view | Cameras | Holds it back |
|---|---|---|---|---|---|---|---|
| light (20 cards) | fluid | 56 ms (56 to 104, n=3), good | 47.0 fps (44.2 to 48.0, n=3), p95 50 ms (50 to 50, n=3), fluid | 97 ms (95 to 132, n=3) to show (p95), good; pacing p95 50.0 ms (50.0 to 66.7, n=3) | 1796 ms (1733 to 2659, n=3), good | none | scroll |
| typical-nocam (50 cards) | neither | 112 ms (88 to 120, n=3), good | 37.0 fps (36.4 to 39.7, n=3), p95 66.7 ms (66.7 to 83.3, n=3), fluid | 102 ms (97 to 114, n=3) to show (p95), fluid; pacing p95 66.7 ms (50.0 to 66.7, n=3) | 4852 ms (4301 to 5395, n=3), poor | none | load |
| typical (50 cards) | neither | 168 ms (120 to 176, n=3), good | 27.7 fps (26.0 to 28.7, n=3), p95 100 ms (100 to 100, n=3), slow | 131 ms (113 to 136, n=3) to show (p95), fluid; pacing p95 100 ms (100 to 100, n=3) | 6567 ms (5426 to 7804, n=3), poor | first frame 4856 ms (4378 to 6008, n=3), 100 % (100 to 100, n=3) shown, stalls 0 ms (0 to 0, n=3), slow | scroll, load, camera |
| heavy (120 cards) | neither | 360 ms (344 to 440, n=3), needs-improvement | 4.2 fps (3.2 to 5.0, n=3), p95 367 ms (317 to 467, n=3), slow | 820 ms (805 to 1498, n=3) to show (p95), slow; pacing p95 317 ms (250 to 350, n=3) | 37577 ms (32490 to 38901, n=3), poor | first frame 5805 ms (5466 to 7083, n=3), 99.7 % (99.2 to 99.9, n=3) shown, stalls 0 ms (0 to 0, n=3), slow | tap, scroll, updates, load, camera |

Restart, app process to a usable reference dashboard: 13984 ms (13904 to 14390, n=3) (poor; relaunched-by-system; first page about:blank). Engine ready 4129 ms (3937 to 4207, n=3), not graded. Android's TTID 1359 ms (1356 to 1384, n=3): the first frame of the kiosk's loading screen; not graded.
Viewport (CSS px, device pixel ratio): ((960, 480), 1.5); zoom check: ok.
Drawn long before usable (detail): typical's first screen was drawn 1.2 s after the navigation, and its main thread was free (usable) after 6.6 s; heavy's first screen was drawn 1.3 s after the navigation, and its main thread was free (usable) after 37.6 s. Each run's `busy_ms` in report.json says how long the probes waited each second.
Camera video over the LAN (UDP), as in a real home: the panel reached this computer over UDP on the LAN, as a real setup's video goes.
typical cameras, run 1 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 2 (detail): decoders 2 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 3 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 1 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 891 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 2 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 1355 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 3 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 1251 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras over WebRTC, run 1 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 402 for 450 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 2 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 401 for 450 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 3 (detail): decoder not reported, 443 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 383 for 443 frames, stalls by them up to 0.0 s.
typical cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame 5788 ms, 100 % shown, stalls 0 ms, slow; run 2: first frame 5103 ms, 100 % shown, stalls 0 ms, slow; run 3: first frame 4301 ms, 100 % shown, stalls 0 ms, slow.
heavy cameras over WebRTC, run 1 (detail): decoder not reported, 1052 frames decoded, 1 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 295 for 1052 frames, stalls by them up to 18.0 s.
heavy cameras over WebRTC, run 2 (detail): decoder not reported, 675 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 170 for 675 frames, stalls by them up to 26.2 s.
heavy cameras over WebRTC, run 3 (detail): decoder not reported, 605 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 167 for 605 frames, stalls by them up to 23.5 s.
heavy cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame 5313 ms, 47 % shown, stalls 22262 ms, slow; run 2: first frame 5455 ms, 37 % shown, stalls 21658 ms, slow; run 3: first frame 5367 ms, 42 % shown, stalls 27051 ms, slow.

Order of the pages (it turns each run): light, typical-nocam, typical, heavy; typical-nocam, typical, heavy, light; typical, heavy, light, typical-nocam.

| Page | 1st | 2nd | 3rd | 4th |
|---|---|---|---|---|
| light | scroll 44 fps (fluid), updates p95 97 ms (good), opens in 1796 ms |  | scroll 47 fps (fluid), updates p95 132 ms (fluid), opens in 1733 ms | scroll 48 fps (fluid), updates p95 95 ms (good), opens in 2659 ms |
| typical-nocam | scroll 40 fps (fluid), updates p95 102 ms (fluid), opens in 5395 ms | scroll 37 fps (fluid), updates p95 114 ms (fluid), opens in 4852 ms |  | scroll 36 fps (slow), updates p95 97 ms (good), opens in 4301 ms |
| typical | scroll 26 fps (slow), updates p95 136 ms (fluid), opens in 6567 ms | scroll 28 fps (slow), updates p95 131 ms (fluid), opens in 7804 ms | scroll 29 fps (slow), updates p95 113 ms (fluid), opens in 5426 ms |  |
| heavy |  | scroll 3 fps (slow), updates p95 805 ms (slow), opens in 38901 ms | scroll 4 fps (slow), updates p95 1498 ms (slow), opens in 37577 ms | scroll 5 fps (slow), updates p95 820 ms (slow), opens in 32490 ms |

## RAM on the reference dashboards (not graded)

PSS per process, from `dumpsys meminfo`: at idle on the typical reference dashboard after each graded run, and between the graded pages (the largest of those is the peak during the graded tasks).

| Process | Idle (median of runs) | Peak during the graded tasks |
|---|---|---|
| crashhelper | 7.7 MB (7.6 to 8.6, n=3) | 9 MB |
| gpu | 235 MB (229 to 248, n=3) | 267 MB |
| main | 205 MB (203 to 207, n=3) | 201 MB |
| media | 25.2 MB (24.5 to 42.4, n=3) | 35 MB |
| tab | 430 MB (424 to 444, n=3) | 404 MB |
| utility | 15.8 MB (15.7 to 15.9, n=3) | 16 MB |
| app, all processes | 929 MB (910 to 949, n=3) | 925 MB |

Peak RSS of the app's processes since they started (VmHWM, summed): 1389 MB, from 10 sample(s) between pages.

Budgets (frames/<id>/budgets.json):
- app_pss_peak: 925 MB against 988 MB: ok
- device_available_min: 676 MB against 296 MB: ok

Not measured on their own: bluetooth-proxy (it scans only while Home Assistant listens, and Clarity's reference run has no Home Assistant listening, so on by itself it would measure an idle pane; measured by hand with HA listening (#121)); radio (its on/off is the radio mode, which Clarity leaves as it is so the panel's Zigbee or Thread network stays up); timers (it comes only with the voice assistant and has no switch of its own, so it is inside assist's share).
