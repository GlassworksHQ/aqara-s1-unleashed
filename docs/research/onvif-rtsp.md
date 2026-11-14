# ONVIF and RTSP: what HA and UniFi Protect need from a panel's camera, and what to build it on

**Status (2026-11-13): untested.** Desk research for the camera pane's opt-in stream (#117). Nothing
is built yet. HA's needs are read from its source; UniFi Protect's from Ubiquiti's help page and an
MIT proxy whose author reports that Protect adopts it. No Protect console has met a panel, and we
haven't run the proxy.

Device: none. Sources: `home-assistant/core` `dev` branch, `homeassistant/components/onvif/`
(read 2026-11-13); `onvif-zeep-async` 4.3.0 (`openvideolibs/python-onvif-zeep-async`, branch `async`);
`connorgallopo/rtsp-to-onvif` (MIT; a fork of `p10tyr/rtsp-to-onvif`; its README reports Protect
7.1.60 with Hikvision-family cameras).

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

3. **Libraries** (2026-11-13, read: GitHub's licence field and last push)

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

The panel needs three small servers, all off until the switch is on:

| Part | Size of the job | Build on |
|---|---|---|
| WS-Discovery responder (UDP 3702, multicast `239.255.255.250`) | One fixed `ProbeMatch` template, the `name` and `hardware` scopes | Our own code. No library worth a dependency |
| ONVIF device and media services (SOAP over HTTP) | About 11 operations with fixed answers; WS-Security digest check | Our own code, shaped after go2rtc's and rtsp-to-onvif's (both MIT, as references, not copied) |
| RTSP server and H.264 | `OPTIONS`, `DESCRIBE`, `SETUP`, `PLAY`, `TEARDOWN`, RTP over TCP, Digest auth; `MediaCodec` from the camera | RootEncoder + RTSP-Server (Apache-2.0), or our own RTP (RFC 6184) if JitPack or the size is a problem |

Recommendation: write the ONVIF and WS-Discovery parts ourselves, in plain Kotlin a JVM test can
drive against `onvif-zeep-async` (the same client HA uses) and `WSDiscovery` 2.1.2. Prototype RTSP
on RootEncoder + RTSP-Server first, and replace it only if the APK cost or JitPack is a problem.
go2rtc would do it all, but it is a second process and a Go binary per ABI.

The snapshot can reuse the camera entity's still (#120): `GetSnapshotUri` points at a small HTTP
handler that serves the same JPEG behind the same credentials.

## How to reproduce

Read the sources named above at their 2026-11-13 heads:

```
curl -sfL https://raw.githubusercontent.com/home-assistant/core/dev/homeassistant/components/onvif/device.py | grep -n "await .*Get"
curl -sfL https://raw.githubusercontent.com/connorgallopo/rtsp-to-onvif/HEAD/src/onvif-server.js | grep -o "Get[A-Za-z]*" | sort -u
gh api repos/pedroSG94/RTSP-Server -q .license.spdx_id
```

## Open questions

- Adoption by a real Protect console (an acceptance criterion of #117). Needs a console and a frame
  with a camera.
- CPU of `MediaCodec` H.264 at 720p next to the presence model on an A53-class SoC. Hardware encoders
  differ by SoC; measure on the target device.
- RootEncoder + RTSP-Server's APK cost, and whether we accept a JitPack dependency.
- Which ports. ONVIF has no fixed port; RTSP's is 554, which an app can't bind, so 8554.
- Events: whether to publish presence as an ONVIF motion event, so Protect and Frigate get motion
  from the panel's own detector. Optional for both clients.
