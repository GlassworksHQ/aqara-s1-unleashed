# Gecko and a TURN relay on loopback: why the S1 never connected to Clarity's WebRTC camera

**Status (2026-11-13): working.** Gecko (Firefox, GeckoView) won't use a TURN server given as a
loopback IP, and drops a relayed candidate whose address is loopback. Chrome does neither. With the
server as `localhost` and the relay on the computer's own address, Firefox connects and plays.

Browsers: Firefox 152.0.6 (macOS, headless), Chrome (macOS, headless), GeckoView 159 on the S1 (S1
Kiosk 0.4.3). Peer: aiortc 1.16.0 behind Clarity's TURN relay (`tools/perf/webrtc.py`).

## Question

Clarity's fake Home Assistant plays its cameras over WebRTC (#202). The panel reaches the computer
only through `adb reverse` (TCP), so the browser gets a TURN server over TCP, and the relay hands UDP
to the peer on the computer. The first version put all of it on `127.0.0.1`. Chrome played it; the S1
didn't. Why, and what works for Gecko?

## What we tried

1. **The S1's first WebRTC smoke** (2026-11-13, about 20:20 UTC, verified; by the root thread, `run.sh
   --quick --runs 1` at d668e26). The run exited 0, but no WebRTC stream played:
   - the fake's log: 14 `camera/webrtc/offer`, 14 answers, ICE `checking` then `closed` for each, no
     `completed`, and **no `camera/webrtc/candidate` at all**; the panel's offers carried no
     candidates either. With `iceTransportPolicy: relay`, that means GeckoView got no relay candidate.
   - The HLS fallback views played as before.
2. **The same config in Firefox desktop** (2026-11-13, verified). A page gathers ICE candidates with
   the fake's client config and reports `icecandidate` and `icecandidateerror` to a local server; the
   relay logs every TURN message and connection. Firefox launched through LaunchServices, because
   started from a sandboxed shell its content process never ran:
   ```
   open -n -a Firefox --args --headless --no-remote --profile <empty dir> http://127.0.0.1:<port>/
   ```
   | TURN server | Relayed address | TCP connection to the relay | Result |
   |---|---|---|---|
   | `turn:127.0.0.1:<p>?transport=tcp` | `127.0.0.1` | none | `icecandidateerror` 701, no candidate |
   | the same, with `media.peerconnection.ice.loopback` true | `127.0.0.1` | yes, allocates | relay candidates on `127.0.0.1` |
   | `turn:localhost:<p>?transport=tcp` | `127.0.0.1` | yes, 401 then authenticated Allocate, success | 701, no candidate |
   | `turn:localhost:<p>?transport=tcp` | the computer's own IPv4 address | yes | relay candidates on that address |
   | no `iceTransportPolicy` | (no TURN connection) | none | host and mDNS candidates only |

   So two rules: a TURN server at a loopback IP isn't used (the name `localhost` passes), and a relayed
   candidate on loopback is dropped. The `turn:localhost` row's Allocate succeeded on the wire, so the
   second drop is Gecko's, not the relay's.
3. **A whole session in Firefox** with `turn:localhost` and the relay and the peer on the computer's
   address (2026-11-13, verified): the offer, answer and candidates through the fake, ICE `connected`,
   and the video decoded at 15 fps (180 frames in 12 s). The relay's permission check first refused
   the peer: it allowed only `127.0.0.1` then. It now allows only the peer's address.
4. **Chrome with the same changes** (2026-11-13, verified, `tools/perf/test/webrtc.e2e.test.mjs`):
   HA's real frontend still plays (first frame 326 ms before the change; the test passes after it).

## Result

| | Gecko | Chrome |
|---|---|---|
| TURN server given as `127.0.0.1` | not used (701) | used |
| Relayed candidate on `127.0.0.1` | dropped | used |
| `turn:localhost`, relay on the computer's address | works | works |

Clarity now uses `turn:localhost:<port>?transport=tcp`, and puts the relay's UDP side and the peer's
only candidate on one IPv4 address of the computer that isn't loopback or link-local (`media_address`
in `tools/perf/webrtc.py`; `GW_PERF_WEBRTC_ADDR` overrides it). The panel still only talks to the
relay over the TCP connection; the media between the relay and the peer stays inside the computer.

## How to reproduce

`tools/perf/test/webrtc.gecko.test.mjs` is the probe, kept: it starts the fake on free ports, drives
its websocket API as HA's frontend does, and runs Firefox headless on an empty profile. Host only;
touches no device.
```
GW_PERF_FAKE_PYTHON=$(python3 tools/perf/webrtcenv.py) node --test tools/perf/test/webrtc.gecko.test.mjs
```
To see the first row again, set the TURN URL back to `127.0.0.1` in `client_config`, or run with
`GW_PERF_WEBRTC_ADDR=127.0.0.1` for a loopback relayed address: the test times out waiting for a relay
candidate (both checked 2026-11-13).

## Open questions

- That GeckoView on Android behaves as Firefox desktop is inferred (same nICEr code, same symptom on
  the S1), and the next panel run checks it.
- Whether the kiosk's `localhost` resolves to 127.0.0.1 only on Android 10 (adb reverse listens there).
  Untested; the next panel run's relay log (`turn_connected` in `fakeha.jsonl`) shows it.
