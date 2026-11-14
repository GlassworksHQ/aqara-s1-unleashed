# ONVIF and RTSP: what HA and UniFi Protect need from a panel's camera, and what to build it on

**Status (2026-11-14): ported, not yet run on a panel.** First desk research for the camera pane's
opt-in stream (#117). Then the maintainer decided that Glassworks follows their own implementation,
which they tested against Home Assistant's ONVIF integration and UniFi Protect (see [Result](#result)).
HA's needs below are read from its source; UniFi Protect's from Ubiquiti's help page and an MIT proxy
whose author reports that Protect adopts it. No panel has met a Protect console yet.

Device: none. Sources, read 2026-11-13 and pinned to the commits that were current then (the files
at these commits are byte-identical to the ones read):

| Source | Commit |
|---|---|
| `home-assistant/core`, `homeassistant/components/onvif/` | `5b84714499e4` (`dev`, 2026-11-13) |
| `openvideolibs/python-onvif-zeep-async`, `onvif/client.py` (HA pins 4.3.0) | `ab5664281877` (`async`, 2026-09-28) |
| `connorgallopo/rtsp-to-onvif` (MIT; a fork of `p10tyr/rtsp-to-onvif`; its README reports Protect 7.1.60 with Hikvision-family cameras) | `d07f08add7e6` (`release`, 2026-10-01) |

## Question

With the pane's "ONVIF stream" switch on, the panel should show up in HA's ONVIF integration,
Frigate, go2rtc and UniFi Protect as a camera. What is the smallest server that does that, and do we
write it or take a library?

## What we tried

1. **What HA's ONVIF integration calls** (2026-11-13, read: `onvif/device.py`, `camera.py`,
   `config_flow.py`, and `onvif/client.py` of `onvif-zeep-async`)

   | Step | Call | Needed? |
   |---|---|---|
   | Discovery | WS-Discovery Probe with scope `onvif://www.onvif.org/Profile/Streaming`; reads the `name` and `hardware` scopes (`config_flow.py:58–98`) | For the "discovered" card. Adding by IP works without |
   | Service addresses | `GetServices`, falling back to `GetCapabilities` (`client.py` `update_xaddrs`) | Yes |
   | Capabilities | `GetCapabilities(Category=All)` for the events flags | Yes |
   | Clock | `GetSystemDateAndTime` (`device.py:184`), for the WS-Security clock offset | Yes |
   | Identity | `GetDeviceInformation`, then `GetNetworkInterfaces` for the MAC as unique id (`device.py:335–360`). A "not implemented" fault falls back to the serial | Yes, MAC optional |
   | Media | `GetServiceCapabilities` (`SnapshotUri`), `GetProfiles`; only profiles whose `VideoEncoderConfiguration.Encoding` is `H264` count, with a resolution (`device.py:412–470`) | Yes |
   | Stream | `GetStreamUri` with `RTP-Unicast` over `RTSP` (`device.py:478–486`) | Yes |
   | Snapshot | `GetSnapshotUri`, then an HTTP GET with Digest or Basic; FFmpeg on the stream otherwise (`camera.py:83–110`) | Optional |
   | Imaging, PTZ | created if offered | No |
   | Events | a PullPoint subscription, tried even if not advertised; failure is fine (`device.py` `async_start_events`) | No |
   | Auth | WS-Security `UsernameToken` with password digest (`client.py:164`) | Yes |

2. **What UniFi Protect needs** (2026-11-13, read: [Third-Party Cameras in UniFi Protect](https://help.ui.com/hc/en-us/articles/26301104828439-Third-Party-Cameras-in-UniFi-Protect),
   [rtsp-to-onvif](https://github.com/connorgallopo/rtsp-to-onvif))
   - Protect 5.0 or newer. Turn on *Discover Third-Party Cameras*; the camera must be on the console's
     subnet, or be adopted by IP (*Advanced Adoption*). It asks for the ONVIF username and password.
   - Ubiquiti names no ONVIF profile. Its notes: time sync, Digest and WS-UsernameToken auth, and
     "if streams change after adoption, remove and re-add the camera". Motion has to come from the
     camera's own events.
   - rtsp-to-onvif is a fork of [p10tyr/rtsp-to-onvif](https://github.com/p10tyr/rtsp-to-onvif), which
     built on Daniela Hase's virtual ONVIF server. Its README says Protect 7.1.60 adopts it, tested by
     its author with Hikvision-family cameras only ("Not tested: … Protect versions other than 7.1.60,
     and other brands"). That is the author's test, not ours. Its ONVIF server answers only `GetCapabilities`,
     `GetDeviceInformation`, `GetNetworkInterfaces`, `GetProfiles`, `GetServices`, `GetSnapshotUri`,
     `GetStreamUri`, `GetSystemDateAndTime`, `GetVideoEncoderConfigurationOptions` and
     `GetVideoSources`, plus a WS-Discovery `ProbeMatch` for `NetworkVideoTransmitter`
     (`src/onvif-server.js`). Events are optional there too.
   - Protect knows a camera by its MAC and ONVIF UUID ("changing it makes Protect see a new camera").
     The panel must keep both stable, and must not put a MAC in logs or the repo (AGENTS.md rule 2).
   - It labels every stream H.264, because ONVIF Media 1 has no H.265.

3. **Libraries** (2026-11-13, read: GitHub's licence field and last push, from the API)
   ```
   $ for r in pedroSG94/RootEncoder pedroSG94/RTSP-Server fyhertz/libstreaming AlexxIT/go2rtc bluenviron/mediamtx rgaufman/live555; do
       gh api repos/$r -q '"\(.full_name) \(.license.spdx_id) \(.pushed_at[0:10])"'; done
   pedroSG94/RootEncoder Apache-2.0 2026-11-13
   pedroSG94/RTSP-Server Apache-2.0 2026-09-04
   fyhertz/libstreaming Apache-2.0 2023-01-26
   AlexxIT/go2rtc MIT 2026-11-05
   bluenviron/mediamtx MIT 2026-11-13
   rgaufman/live555 LGPL-3.0 2026-07-29
   $ gh api repos/AlexxIT/go2rtc/releases/latest -q '.tag_name, (.assets[] | select(.name=="go2rtc_linux_arm") | .size)'
   v1.9.15
   5092480
   ```


   | Project | Licence | Language | Last push | What it gives us |
   |---|---|---|---|---|
   | [pedroSG94/RootEncoder](https://github.com/pedroSG94/RootEncoder) | Apache-2.0 | Kotlin | 2026-11-13 | Camera2 → `MediaCodec` H.264, RTP packetising. A client: it pushes to a server |
   | [pedroSG94/RTSP-Server](https://github.com/pedroSG94/RTSP-Server) | Apache-2.0 | Kotlin | 2026-09-04 | "Plugin of RootEncoder to stream directly to RTSP player": the server side. Only on JitPack |
   | [fyhertz/libstreaming](https://github.com/fyhertz/libstreaming) | Apache-2.0 | Java | 2023-01-26 | An RTSP server for Android, unmaintained |
   | [AlexxIT/go2rtc](https://github.com/AlexxIT/go2rtc) | MIT | Go | 2026-11-05 | RTSP and an ONVIF server, tested with HA's ONVIF integration. A second process; `go2rtc_linux_arm` 1.9.15 is 5.1 MB |
   | [bluenviron/mediamtx](https://github.com/bluenviron/mediamtx) | MIT | Go | 2026-11-13 | RTSP server, no ONVIF |
   | live555, GStreamer `rtsp-server` | LGPL | C/C++ | | Copyleft native code we'd ship. Avoid |
   | gSOAP-based ONVIF servers | GPL or commercial | C | | Ruled out |

## Result

**Superseded on 2026-11-14 by the maintainer's decision: Glassworks follows the maintainer's own,
tested implementation** in [aguilaair/android-ip-camera](https://github.com/aguilaair/android-ip-camera)
(branch `onvif-rtsp-motion`, commit `7b5e6f28`, MIT, a fork of DigitallyRefined/android-ip-camera).
It is ported, not rewritten, in `app/kiosk/src/main/java/dev/glassworks/camera/onvif/`. The library
comparison above stays as the record of what was ruled out; RootEncoder and RTSP-Server are no longer
the plan.

| Part | In the port | What it does |
|---|---|---|
| RTSP | `RtspServer`, `RtspProtocol`, `H264Bitstream` | RTSP 1.0 on port 8554, H.264 as RTP (RFC 6184) over TCP-interleaved or UDP, Digest (MD5, qop=auth) and Basic auth, RTCP sender reports |
| ONVIF | `OnvifServer`, `OnvifSoap` | Profile S device, media (ver10) and media2 (ver20) services on port 8000, WS-Security UsernameToken and HTTP Digest/Basic, a snapshot URI (`/onvif/snapshot.jpg`), pull-point motion events on `tns1:RuleEngine/CellMotionDetector/Motion` and `tns1:VideoSource/MotionAlarm` |
| Discovery | `OnvifDiscovery` | WS-Discovery on UDP 3702 (2005/04, and 2009/01 probes answered in kind), Hello and Bye, scopes including `onvif://www.onvif.org/Profile/Streaming` |
| Motion | `MotionDetectorCore` (in `dev.glassworks.camera`) | The panel's presence and the ONVIF events both come from it: see [camera-presence.md](camera-presence.md) |

**What the maintainer tested** (the maintainer, 2026-11-13; not re-run for this note): Home
Assistant's ONVIF integration and UniFi Protect's third-party adoption. The fork's README records the
setup and two Protect notes (read 2026-11-14):

1. Home Assistant: add the camera with the ONVIF integration (host = the device's address, port
   8000), or adopt it in Protect and use the Protect integration. Both give the camera and a motion
   binary sensor.
2. Protect: on the console's network the camera shows up under third-party cameras; otherwise use
   *Try advanced adoption* with `<address>:8000` and the username and password.
3. Protect: turn **off audio recording** for the camera. The stream has no audio track, and Protect
   won't record without one while audio recording is on.
4. Protect: it needs a **motion zone** for a third-party camera before it creates motion events. If
   there is none, add a full-frame zone through the Protect API; the settings UI offers no zones for
   third-party cameras.

**What Glassworks changes** (each ported file lists its own changes in its header):

- With no sign-in set, both servers refuse every request that needs a user. Upstream let everyone
  in. The approved design has no default stream password; the sign-in comes from the Manage page.
  A username with a blank password counts as no sign-in.
- XML with a DTD is refused before it is parsed, in SOAP and in WS-Discovery: parsing runs before
  any sign-in, and Android's parser ignores upstream's `disallow-doctype-decl` flag, so an entity
  bomb or an external entity would otherwise reach it. An entity resolver refuses the rest.
- The RTSP server binds its port before it counts as running, so a port clash throws and a later
  start can still bind.
- Nothing listens until the pane starts the servers, and the pane starts them only while
  `onvif_stream` is on, the camera is in Presence + snapshots, and privacy is off
  (`CameraPolicy.streamMayRun`).

**Limits we accept, and what bounds them** (2026-11-14):

| Limit | Why it stays | What bounds it |
|---|---|---|
| Plain HTTP and RTSP, no TLS. Basic auth and WS-Security `PasswordText` send the password in a form anyone on the LAN can read | HA's ONVIF integration and UniFi Protect speak ONVIF over HTTP; they choose Digest or `PasswordDigest` when offered | The stream is off by default and needs its own sign-in, not the panel's or HA's. Use a password for nothing else |
| HTTP Digest (ONVIF snapshot, RTSP): a nonce stays valid until it is evicted (ONVIF) or for 12 h (RTSP), and `nc` isn't tracked, so a captured `Authorization` header can be replayed | ffmpeg and other RTSP clients reuse the first nonce for a whole session; refusing a reused `nc` breaks them | Replaying needs a captured header from the LAN; it gives what that client already saw. A future round can bind nonces to a connection |
| WS-Security `UsernameToken`: `Created` isn't range-checked and nonces aren't remembered, so a captured token can be replayed | Panel and client clocks differ, and HA corrects for that skew itself | As above: a LAN capture, for the operations that client already made |
| RTSP over UDP: a session ends when its control connection closes (upstream's choice), although RTSP 1.0 lets it outlive the connection | HA and Protect stream over TCP; ending with the connection stops video to an address whose client is gone | Clients that keep their control connection open, as ffmpeg does, are unaffected |

Bounds added in the camera pane PR: at most 16 RTSP connections, a 30 s read timeout on an RTSP
connection until a session plays (none after, since a playing client may stay silent), and at most 64
headers and 32 KiB in an ONVIF request head. The ONVIF server already had a 30 s read timeout and at
most 32 connection threads.

**Ports.** 8554 (RTSP) and 8000 (ONVIF), as upstream. Neither clashes with the panel's own listeners,
read from the code on 2026-11-14: 6053 (ESPHome API), 8099 (Manage page and setup sign-in), 6638 and
6639 (Zigbee over TCP), 8081 (the Thread border router's REST API, Thread mode only). Sendspin only
connects out. WS-Discovery takes UDP 3702, and RTP over UDP an even port pair between 50000 and 60000.

## How to reproduce

Read the sources at the pinned commits (read-only):

```
curl -sfL https://raw.githubusercontent.com/home-assistant/core/5b84714499e4c50e57c39f336e2790e22d3bc2fa/homeassistant/components/onvif/device.py | grep -n "await .*Get"
curl -sfL https://raw.githubusercontent.com/home-assistant/core/5b84714499e4c50e57c39f336e2790e22d3bc2fa/homeassistant/components/onvif/config_flow.py | sed -n 58,98p
curl -sfL https://raw.githubusercontent.com/openvideolibs/python-onvif-zeep-async/ab5664281877870ce2bbe1aa3dd190411b19c1ac/onvif/client.py | grep -n "update_xaddrs\|GetServices\|UsernameToken"
curl -sfL https://raw.githubusercontent.com/connorgallopo/rtsp-to-onvif/d07f08add7e61d8435b8d09dc1dc5b3dfdf0a8ed/src/onvif-server.js | grep -o "Get[A-Za-z]*" | sort -u
```

The library table's commands are in step 3.

## Open questions

- Adoption by a real Protect console (an acceptance criterion of #117). Needs a console and a frame
  with a camera.
- CPU of `MediaCodec` H.264 at 720p next to the presence model on an A53-class SoC. Hardware encoders
  differ by SoC; measure on the target device.
- RootEncoder + RTSP-Server's APK cost, and whether we accept a JitPack dependency.
- Which ports. ONVIF has no fixed port; RTSP's is 554, which an app can't bind, so 8554.
- Events: whether to publish presence as an ONVIF motion event, so Protect and Frigate get motion
  from the panel's own detector. Optional for both clients.
