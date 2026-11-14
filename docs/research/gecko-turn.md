# Gecko and a TURN relay on loopback: why the S1 never connected to Clarity's WebRTC camera

**Status (2026-11-13): working in Firefox; the S1 run is pending.** Gecko (Firefox, GeckoView) won't use
a TURN server given as a loopback IP, drops a relayed candidate whose address is loopback, and drops
one whose allocation says the browser is on loopback (XOR-MAPPED-ADDRESS). Chrome does none of these.
With the server as `localhost`, the relay on the computer's own address, and that address as the
mapped one when the browser comes through adb reverse, Firefox connects and plays.

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

5. **The S1's second smoke** (2026-11-13, about 20:49 UTC, verified; the root, at a718f9c). The panel
   now reached the relay through adb reverse (28 `turn_connected`, client `127.0.0.1`) and allocated
   (18 `turn_allocated` on the Mac's address), but still sent no candidate: 0 `camera/webrtc/candidate`,
   and its 7 offers carried none (`a=candidate` lines: 0; the offers are in `fakeha.jsonl`).
   - What differed from the Mac's Firefox: through adb reverse the relay sees the browser as
     `127.0.0.1`, and its Allocate answer says so (XOR-MAPPED-ADDRESS). Firefox's own connection came
     from the Mac's address.
6. **The mapped address in Firefox** (2026-11-13, verified, the same probe):

   | Allocate answer | Relay candidate |
   |---|---|
   | mapped = the browser's real address (as on the Mac) | yes |
   | mapped = `127.0.0.1` (what adb reverse makes the relay see) | **no**: the S1's symptom |
   | relayed = `192.0.2.55`, an address the browser can't route to | yes |

   So the third rule. Also: Gecko doesn't drop a relayed candidate for being on another subnet. The
   fix: the relay reports the media address as the mapped one when it sees loopback. The Gecko test
   now reaches the relay through a stand-in for adb reverse (a TCP forwarder that connects from
   `127.0.0.1`); with the fix reverted it times out waiting for a relay candidate, as the S1 did.

## Result

| | Gecko | Chrome |
|---|---|---|
| TURN server given as `127.0.0.1` | not used (701) | used |
| Relayed candidate on `127.0.0.1` | dropped | used |
| Allocation's mapped address `127.0.0.1` | relay candidate dropped | used |
| `turn:localhost`, relay on the computer's address, mapped not loopback | works | works |

Clarity now uses `turn:localhost:<port>?transport=tcp`, and puts the relay's UDP side and the peer's
only candidate on one IPv4 address of the computer that isn't loopback or link-local (`media_address`
in `tools/perf/webrtc.py`; `GW_PERF_WEBRTC_ADDR` overrides it), and reports that address as the mapped one for a browser that
comes from loopback (`TurnConn.mapped`). The panel still only talks to the relay over the TCP
connection; the media between the relay and the peer stays inside the computer.

## How to reproduce

`tools/perf/test/webrtc.gecko.test.mjs` is the probe, kept: it starts the fake on free ports, drives
its websocket API as HA's frontend does, and runs Firefox headless on an empty profile. Host only;
touches no device.
```
GW_PERF_FAKE_PYTHON=$(python3 tools/perf/webrtcenv.py) node --test tools/perf/test/webrtc.gecko.test.mjs
```
To see the rules again (each checked 2026-11-13):
- `GW_PERF_WEBRTC_ADDR=127.0.0.1` (a loopback relayed address): the test times out waiting for a relay
  candidate.
- `mapped()` returning the address as seen: the same timeout (the S1's second smoke).
- The TURN URL back to `127.0.0.1` in `client_config`: the test fails at once on its check of the URL;
  with that check removed, it times out waiting for a relay candidate.

## Open questions

- That GeckoView on Android behaves as Firefox desktop is inferred (same nICEr code; both S1 symptoms
  reproduced in Firefox), and the next panel run checks it.
- `localhost` on the kiosk does reach adb reverse: the second smoke's 28 `turn_connected` (verified).
