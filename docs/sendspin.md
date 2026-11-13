# Music Assistant player (Sendspin)

The kiosk app is a **native Sendspin player**. Music Assistant (MA) sees the panel as one of its
own players, called **Glassworks Panel** (**S1 Panel** before #63), plays to it in sync with your other Sendspin speakers and can
group it with them. You no longer need MA's "Home Assistant MediaPlayers" bridge for the panel.
That bridge goes MA → HA → `media_player.s1_panel_speaker` and has no sync.

Code: `app/kiosk/src/main/java/sh/edm/s1kiosk/sendspin/`. The protocol core is pure
JVM (`SendspinSession`, `SsCrypto`, `TimeFilter`, `PlaybackScheduler`, `WsClient`, `Flac`,
`AudioPipeline`) and `SendspinPlayer` and `MediaCodecFlacDecoder` are the Android glue. It is owned by `PanelDevice` and runs in the foreground
`KioskService`. Logs: `adb logcat -s S1Sendspin`.

## Setup

Nothing to do. With the HA ESPHome integration already set up for the panel (with an encryption
key, which HA provisions automatically):

1. The panel finds MA's Sendspin server and connects to it (see [Discovery](#discovery-and-connection)).
2. Home Assistant reads the panel's Sendspin **pairing token** over the ESPHome API and gives it
   to MA. MA pairs with the panel on the open connection.
3. MA creates the player **Glassworks Panel**. Its HA entity is `media_player.glassworks_panel` on a
   new panel, `media_player.s1_panel` on one MA met before #63 (from the `music_assistant`
   integration; an entity id never changes once made). It is separate from the ESPHome `media_player.s1_panel_speaker`,
   which keeps working for TTS, announcements and HA media.

Verified on the panel on 2026-11-09 (MA 2.10.9, HA 2026.11.0):

```
S1Sendspin: Noise handshake with 3EXw96k5… done (sentinel PSK)
S1Sendspin: Activated: activities=[] roles=[] (sentinel PSK)          <- held: unpaired, not approved
S1ESPHome:  [1] Sendspin pairing token requested: status 1            <- HA fetches the token
S1Sendspin: Re-handshake done (pairing PSK)
S1Sendspin: Activated: activities=[pairing] roles=[] (pairing PSK)
S1Sendspin: Paired with 'Music Assistant (d5369777-music-assistant)' …
S1Sendspin: Re-handshake done (long_term PSK)
S1Sendspin: Activated: activities=[] roles=[player@v1, metadata@v1] (long_term PSK)
MA:         [music_assistant.sendspin] Paired cY2syv2p… with the token from Home Assistant
```

MA logged no "non-compliant client" warning for the panel. It does log such warnings for MA's own
web player and for the HA Voice PE.

### Settings

| Where | Setting | Default |
|---|---|---|
| HA switch `switch.s1_panel_music_assistant_player_sendspin` / `--ez sendspin_enabled` | Run the player | on |
| HA diagnostic `sensor.s1_panel_music_assistant_player_status` | e.g. `ready, paired with Music Assistant (…)`, `playing from … (FLAC): Artist – Title`, `waiting for pairing or approval by …`; `…; FLAC off, using PCM: <reason>` after a [FLAC fallback](#flac) | |
| `--es sendspin_server <host[:port][/path] or ws://…>` | Server address; `auto` = discovery | `auto` |
| `--ez sendspin_unpaired_access true` | Let a server play **without pairing** (Sendspin "unpaired access", guest mode). MA then approves it automatically | off |
| `--ez sendspin_flac false` | Offer only PCM (no FLAC). Switching it back on also retries FLAC after a fallback | on (FLAC first, PCM fallback) |

`--e…` extras go to `adb shell am start -n sh.edm.s1kiosk/.MainActivity`, as in
[app/README.md](../app/README.md). Changing the server address or unpaired access reconnects
(`client/goodbye` `restart`) so the new `client/hello` carries the new value (also for `sendspin_flac`). Volume, mute and
the output delay are the Sendspin player's own (stored in `sendspin.xml`). They don't affect the
voice/TTS volume of `media_player.s1_panel_speaker`, and vice versa. Android's media volume
(stream 3) still scales everything.

### If pairing needs you

Pairing needs no user action when HA's ESPHome integration is connected with an encryption key and
MA's Home Assistant provider is running (it is when MA runs as the HA app). If it can't pair,
the status sensor stays at `waiting for pairing or approval by …` and MA lists the panel as a
device that is neither paired nor approved. Then do one of these:

- In MA: **Settings → Players → Glassworks Panel**, and pair with the token. You can read the token in HA's
  developer tools with the WebSocket command
  `{"type": "sendspin/get_pairing_token", "client_id": "<panel client id>"}` (admin only). The
  token is a credential: anyone who has it can pair a server with the panel.
- Or turn on guest mode (`--ez sendspin_unpaired_access true`). MA then approves the panel by
  itself. Guest mode is unauthenticated: anyone on the LAN who can reach the panel's connection
  path can pose as the server (see "Security" below).

## Protocol (what is implemented, with sources)

MA 2.10.9 pins **`aiosendspin[server]==10.2.1`**. Sources:
`music_assistant/providers/sendspin/manifest.json` and `requirements_all.txt` at tag `2.10.9`.
aiosendspin 10.2.1 (2026-10-19) is a strict implementation of the
[Sendspin spec](https://github.com/Sendspin/spec) as of mid-October 2026. Run with
`allow_noncompliant_clients=False` it rejects every deviation it checks for
(`aiosendspin/server/compliance.py`, `connection.py`). The panel implements that wire protocol.
It also follows later spec changes when they don't break that server. Example: it holds its first
`client/state` until the clock filter has converged (spec #349).

Read and cross-checked: the spec's `README.md` (at `c281aeb`); in aiosendspin 10.2.1, `noise/`
(`driver.py`, `session.py`, `wire.py`, `keys.py`, `pairing.py`, `pairing_token.py`),
`client/connection.py`, `models/core.py`, `models/player.py` and `server/connection.py`; MA's
`providers/sendspin/{provider.py,hass_auto_pair.py,security.py,player.py}`; HA 2026.11.0
`components/esphome/{sendspin.py,websocket_api.py}`; aioesphomeapi 46.25.0 `api.proto` and
`model.py`; `sendspin-js` (time sync bursts); `sendspin-cpp` (default unpaired access off).

### Discovery and connection

- **Client-initiated** (spec "Client Initiated Connections"). The panel connects to the server; it
  does **not** advertise `_sendspin._tcp`. The spec allows only one of the two methods at a time,
  and the panel sits on a separate VLAN where the server could not dial it anyway.
- Server address: mDNS `_sendspin-server._tcp` (MA advertises it, port 8927, TXT `path=/sendspin`).
  This worked on the panel's VLAN. If there is no answer within 3 s, the panel uses
  `ws://<Home Assistant host>:8927/sendspin`, since MA runs as an HA app on the same host. You can
  also set `sendspin_server`.
- Plain `ws://` through a small RFC 6455 client (`WsClient`, no `Origin` header). Confidentiality
  and integrity come from Noise.
- Reconnect: 1 s after a connection that reached `server/activate`, otherwise exponential backoff
  1 → 60 s. Ping/pong is answered (aiosendspin's heartbeat drops a client after 15 s with no PONG).

### Encryption: Noise `KKpsk2_25519_ChaChaPoly_SHA256`

- `client/init` `{client_id, version: 1, suite}` → `server/init` → Noise message 1 → message 2.
  The **server is the initiator**. The prologue is the exact bytes of `client/init` followed by the
  exact bytes of `server/init`.
- `client_id` is the base64url (no padding, 43 characters) X25519 public key of a CSPRNG identity
  stored in the app's private prefs. It is not the MAC: the spec makes the static key the
  identity. The Wi-Fi MAC goes in `device_info.mac_address`, lowercase. MA uses that MAC to link
  protocol players.
- Message 1's payload names `psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK))` and
  `psk_category` (`lt` | `pr` | `sn`). The client picks its long-term record (checking the bound
  `server_id`), its pairing PSK, or the Sentinel PSK (`SHA-256("sendspin-sentinel-psk-v1")`). An
  unknown id on the first handshake falls back to the Sentinel ("credential mismatch"). Message 2's
  payload is the literal `{}`.
- Transport: each WebSocket binary message is one Noise transport message whose plaintext starts
  with a type byte: `0` JSON, `1` fragment (`[1][flags][orig_type]…`, reassembled per spec), `4`
  player audio. Other role IDs are discarded.
- Written from the Noise spec (rev. 34) on BouncyCastle X25519 and ChaCha20-Poly1305. A JVM test
  checks it byte for byte against a vector made with the Python `noiseprotocol` library that
  aiosendspin uses.
- **Re-handshake** in transport mode (`noise/handshake` as an encrypted JSON message). The prologue
  is the previous handshake hash. Message 2 goes out under the old keys and the keys are then
  swapped. The client sends nothing else until the next `server/activate`.

### Hello, activation, pairing

- `client/hello`: `name` "Glassworks Panel", `device_info` (Aqara / Panel Hub S1 Plus (S1 Kiosk) / app
  version / MAC), `supported_roles` `["player@v1", "metadata@v1"]`, and
  `player@v1_support {supported_formats: [{codec: flac, channels: 2, sample_rate: 48000,
  bit_depth: 16}, {codec: pcm, channels: 2, sample_rate: 48000, bit_depth: 16}],
  buffer_capacity: 1500000}`. FLAC is first (preferred) and PCM is the fallback; PCM only when
  `sendspin_flac` is off, the device has no FLAC decoder, or after a [FLAC fallback](#flac). It also sends
  `supported_pair_methods: {"pairing_psk": {}}` (an object, not a list) and
  `unpaired_access: {enabled: false}`. It sends none of the keys MA flagged on other clients:
  no `trust_level`, no hello-level `supported_commands`, no `static_delay_ms`.
- `server/activate` admission follows the spec table. A long-term PSK allows `[]` / `['playback']`.
  Pairing/Sentinel allow `['pairing']`, plus playback only with unpaired access. `active_roles` is
  sticky when omitted and is emptied when the connection stops being playback-capable. A rejected
  activation gets `client/goodbye` `pairing_required` or `unauthorized`. Roles the client didn't
  list are never used.
- **Pairing PSK flow** (the one method every client must implement). On `activities: ['pairing']`
  with `method: pairing_psk` on a pairing-PSK session, the client sends `client/pair-init
  {pairing_index}` and then, without waiting, `client/pair-finalize {long_term_psk}` (32 fresh
  CSPRNG bytes, never the Sentinel or the pairing PSK). It persists the record only when
  `server/pair-finalize` arrives. The server then re-handshakes to the long-term PSK. Any other
  method is answered with `pair/abort` `method_not_supported`. MA had warned the web player that
  its "Pairing PSK client/pair-finalize [was] sent without client/pair-init". The panel always
  sends pair-init first.
- **Pairing token** (version 0): `"SP:0" + base32(client_key || pairing_psk)`, `=` padding
  stripped, `2` → `9`. A JVM test checks it against the spec's reference vector. Up to 8 pairing
  records, least recently used evicted, never one in use. `server/unpair` on a paired session
  removes the record and closes with `client/goodbye` `unpaired`.

### ESPHome hand-off (how HA gives MA the token)

HA 2026.11 `components/esphome/sendspin.py` reads `DeviceCapabilitiesResponse.sendspin` (field 6:
`feature_flags` = `PAIRING_TOKEN` (1), `client_id`) on every connect and publishes the client ids
over `sendspin/subscribe_clients`. MA's `HassAutoPairer` subscribes to them. When a client is both
announced and connected unpaired, MA calls `sendspin/get_pairing_token`. HA forwards that as
`SendspinPairingTokenRequest` (159) to the panel, which answers `SendspinPairingTokenResponse`
(160) `{status, token}`. MA then pairs with the token. The panel follows ESPHome's
`sendspin_hub.cpp` rules:

| Status | When |
|---|---|
| `OK` (1) | encrypted API session with a provisioned key, player started since boot |
| `ENCRYPTION_REQUIRED` (2) | plaintext or zero-PSK session (no key): the token is never sent in clear |
| `NOT_READY` (0) / `DISABLED` (3) | player not started yet / switched off |

`client_id` is empty until the player has started since boot, as in ESPHome. HA keeps polling
until it appears.

### Clock synchronization

- Bursts of 8 `client/time`s, each sent after the previous reply. A 2 s timeout aborts a burst.
  Only the burst's lowest-`max_error` measurement is fed to the filter. Bursts start every 1 s for
  the first 10, then every 5 s (the spec allows at most 10 s between burst starts).
- T1 is taken immediately before encryption. T4 is when the last byte of the WebSocket message
  arrived, before decryption.
- `TimeFilter` is the spec's 2-D Kalman filter, step by step. It uses the recommended parameters
  (`process_std_dev 0`, `drift_process_std_dev 1e-11`, forget factor 2, cutoff 3, min samples
  100, drift significance 2, `max_error_scale 0.5`) and the spec's `compute_client_time` /
  `compute_server_time`, with rounding of halves away from zero.
- The first `client/state` (with the player object) goes out after the first burst has converged
  the filter. It is resent after every activation that keeps or adds the player role, and whenever
  volume, mute or the delay change.

### FLAC

Verified against aiosendspin 10.2.1 (`server/roles/player/v1.py`, `capabilities.py`,
`audio/codecs.py` `FlacEncoder`, `server/connection.py`), the spec `README.md` at `c281aeb`
("player@v1 support object", "Codec framing", "stream/start player object", "stream/clear player",
"Player Buffer Accounting"), MA 2.10.9 `providers/sendspin/player.py`, `sendspin-js`
(`src/audio/decoder.ts`, `src/core/codec-support.ts`) and `sendspin-cpp` (`src/protocol.cpp`,
`src/decoder.cpp`), and Android 10's decoder (`frameworks/av` `android10-release`:
`media/codec2/components/flac/C2SoftFlacDec.cpp`, `media/libstagefright/flac/dec/FLACDecoder.cpp`).

- **Negotiation.** The client lists formats in priority order; the server picks the first one it
  can encode (`filter_encodable_formats(...)[0]`; FLAC needs PyAV with FFmpeg's encoder, which MA
  has). A `client/state` `format` or an operator override in MA (per-player "preferred format",
  default *automatic*) would take precedence; the panel sends no `format`. In automatic mode MA
  only considers formats in the codec of the client's first entry and plays at their sample rate.
  The hello must list FLAC or PCM, or aiosendspin flags "listed neither flac nor pcm".
- **`stream/start`** `player: {codec: "flac", sample_rate: 48000, channels: 2, bit_depth: 16,
  codec_header}`. `codec_header` is standard Base64 (padded) of `fLaC` + `0x80` (last-metadata-block
  flag, type 0 = STREAMINFO) + 24-bit length `0x000022` + the 34-byte STREAMINFO: 42 bytes,
  56 characters. aiosendspin builds it from FFmpeg's extradata
  (`b"fLaC\x80" + len(header).to_bytes(3, "big") + header`); sendspin-cpp accepts exactly that.
  MA's STREAMINFO: block size 4608..4608, min frame size 0, max frame size 19032, 48000 Hz, 2 ch,
  16-bit, total samples 0, MD5 zero. The panel accepts `fLaC` + any blocks starting with
  STREAMINFO, or a bare STREAMINFO, and checks it against the `stream/start` fields.
- **Chunks.** Spec: "Each binary audio chunk carries whole codec units; a unit never spans
  chunks", FLAC = "one or more complete FLAC frames". aiosendspin sends exactly one frame per
  chunk: FFmpeg picks the block size (4608 samples = 96 ms at 48 kHz, under the 150 ms cap), and
  each `process()` output is one encoded frame. The chunk timestamp is its first sample:
  `stream_start + cumulative_samples * 1e6 // sample_rate`, so consecutive chunks are contiguous.
  FLAC has no codec delay (`lookahead_us` 0), so decoded samples map 1:1 onto that timeline.
- **Format changes.** A new `stream/start` mid-stream switches format for the chunks after it.
  The client must keep the chunks it already has and decode each in the format it came in, without
  a gap. aiosendspin skips `stream/start` when codec, rate, channels, depth and header are all
  unchanged.
- **`stream/clear`** keeps the format (no new `stream/start`, often the same running encoder) and
  frames stay independently decodable. **`stream/end`** ends the stream; the next one starts with
  a new `stream/start`.
- **Buffer accounting** counts encoded bytes, so with FLAC the 1.5 MB `buffer_capacity` covers
  about 13–15 s instead of 7.8 s (aiosendspin also caps it at 30 s). MA in fact keeps 1.5–4 s
  queued during the first half minute (below).

What the panel does (`Flac.kt`, `AudioPipeline.kt`, `MediaCodecFlacDecoder.kt`):

- **Decoder:** MediaCodec `audio/flac` (`c2.android.flac.decoder` on the panel, libFLAC in
  `media.swcodec`), synchronous mode, 16-bit output (`pcm-encoding` 2). `csd-0` = `fLaC` + the
  STREAMINFO block marked last, the same 42 bytes as the header. `C2SoftFlacDec` hands the
  first (`FLAG_CODEC_CONFIG`) buffer to `FLACDecoder::parseMetadata`, which runs libFLAC's
  `process_until_end_of_metadata` and needs the `fLaC` marker and a non-zero max block size.
- **One frame per input buffer.** `C2SoftFlacDec::process` calls `decodeOneFrame` once per input
  (`process_single`); extra frames in a buffer would stay queued and shift the output by a frame.
  So each chunk is split into frames first (`FlacFrames.split`: a boundary is where a valid frame
  header, sync + CRC-8, follows a matching CRC-16 footer). Each frame is checked against
  STREAMINFO (block size ≤ max, rate, channels, depth), which `decodeOneFrame` enforces by failing
  the component. A partial frame would also leave libFLAC at end-of-stream for good, so
  truncated/corrupt chunks are dropped instead of being queued.
- **Timestamps:** frame *i* of a chunk is scheduled at `chunk_ts + Σ previous block sizes / rate`.
  Each frame is queued with a serial number as `presentationTimeUs`; `C2SoftFlacDec` copies the
  input ordinal to its one output buffer, and the pipeline looks the frame up by it. Decoded PCM
  then enters the unchanged jitter buffer and `PlaybackScheduler` exactly like PCM chunks, so the
  sync maths doesn't know the codec.
- **Codec switches** (PCM ↔ FLAC): everything still in the decoder is drained first; the output
  stays 48 kHz/2 ch/16-bit PCM, so the AudioTrack keeps running and there is no gap.
- **`stream/clear`:** frames still in the decoder are forgotten by tag (with `from_timestamp`,
  only those at or after it). The codec is not flushed: one whole frame per input leaves the
  decoder with no partial state, and Android 10's C2 `flush()` resets libFLAC and drops the
  STREAMINFO (`FLACDecoder::flush` → `mStreamInfoValid = false`) without reliably resubmitting
  `csd-0`. **`stream/end`** releases the codec; a new or changed `codec_header` gets a new one.
- **Errors:** an exception, a frame inconsistent with STREAMINFO, a corrupt chunk or a stalled
  decoder (frames in, nothing out for 1.5 s) is logged and the codec is released; the next frame
  opens a fresh one. **3 errors within 60 s**, a missing/invalid `codec_header`, or a decoder that
  can't be opened disable FLAC for the rest of the app run. The player then reconnects
  (`client/goodbye` `restart`) with a PCM-only hello, and the status sensor says
  `…; FLAC off, using PCM: <reason>`. `--ez sendspin_flac true` (or an app restart) retries.
- The log line every 10 s while streaming: `decode: flac <kbit/s> on the wire | FLAC frames N,
  decoder latency avg/max, errors, dropped`.

### Audio and scheduling

- Formats: FLAC or PCM, both 48 kHz, stereo, 16-bit. These are formats every server must produce
  (spec: FLAC at 16/24-bit, 1–2 ch, 44.1/48 kHz; PCM at 16-bit, same rates). The panel's mixer runs
  at 48 kHz, so MA resamples and the panel never has to switch rates. No Opus. See [FLAC](#flac).
- Chunks (`[4][timestamp i64][send_ahead u32][audio]`; one shorter than 13 bytes is a protocol
  error) are decoded to 16-bit PCM on their own thread (`AudioPipeline`, `ss-decode`), in arrival
  order, and go into a jitter buffer ordered by server timestamp. A chunk that has already ended
  locally on arrival is dropped (counted as late).
- `PlaybackScheduler` feeds an `AudioTrack` (`USAGE_MEDIA`, 100 ms buffer, blocking writes, audio
  thread at `URGENT_AUDIO`) in 10 ms blocks. For each block it compares two times:
  - when the next buffered frame is due: `compute_client_time(ts) − output_delay_ms`;
  - when the next written frame will leave the output, from `AudioTrack.getTimestamp()` (a
    frame-position/`CLOCK_MONOTONIC` line, smoothed by EMA, re-anchored on jumps over 20 ms).

  Then it acts on the difference:
  - ≥ one block early → silence (stream start, gaps between chunks);
  - more than 3 ms off → one-shot snap (insert silence or drop audio);
  - otherwise the smoothed error drives soft correction. It duplicates or drops **one frame per
    10 ms block** at most (≤ 0.21 % speed, within the spec's ±0.5 %), with a ±150 µs dead band.
    This is the spec's "Suggested correction strategy", with N = 1 at 48 kHz.
- `stream/start` opens or continues the stream. `stream/clear` without `from_timestamp` drops the
  buffer and re-creates the AudioTrack, so nothing stale plays after a seek. With
  `from_timestamp` it drops only buffered chunks at or after that time. `stream/end` and losing
  `playback` or the player role stop output and clear buffers.
- Reported timing: `required_lead_time_ms` 300 (AudioTrack creation + its 100 ms buffer +
  network), `min_buffer_ms` 200, `output_delay_ms` 0–5000 (MA's per-player "static delay" sends
  `set_output_delay`; persisted). `supported_commands`: `volume`, `mute`, `set_output_delay`.
- Volume: `amplitude = (volume/100)^1.5` applied with `AudioTrack.setVolume` (the mixer ramps it).
  Mute sets the gain to 0. Both are persisted and reported back in `client/state`.
- **Voice coexistence**: on wake word / listening / speaking, and during announcements on
  `media_player.s1_panel_speaker`, the Sendspin output is muted locally while the stream keeps its
  timeline. Afterwards it comes back in sync. The ESPHome media player still pauses its own
  music as before (`mediaPausedForVoice`).

## Measured on the panel

A 20 s play of one library track to `media_player.s1_panel` only, panel media volume at 2/15. The
`sync:` lines are logged every 10 s while streaming:

```
Stream started: pcm 48000 Hz 2 ch 16-bit
AudioTrack open: 48000 Hz, 2 ch, buffer 4800 frames (min 2052), latency hint 100 ms
sync: offset 675603141.268 ms ±436 µs drift -19.23 ppm (unused) | err 133 µs (smoothed 121) buffer 1500 ms | per 10 s: +253/-0 frames, snaps 2/1, underrun blocks 0, late chunks 0, chunks 397
sync: offset 675603141.084 ms ±430 µs drift -19.05 ppm | err 139 µs (smoothed 126) buffer 2500 ms | per 10 s: +9/-0 frames, snaps 0/0, underrun blocks 0, late chunks 0, chunks 442
```

- Clock filter: offset standard deviation about **±0.43 ms** over Wi-Fi across the VLAN. Drift is
  −19 ppm (panel clock vs. MA host); the filter starts applying it after about 15 s.
- Scheduling error (next frame due vs. output position, by the panel's own reckoning):
  **~0.13 ms**, inside the soft band.
- Corrections in steady state: **9 frames per 10 s** (≈ 0.9 frame/s ≈ 19 ppm, exactly the clock
  drift). The start-up second had 3 snaps and 253 single-frame corrections, while the filter
  converged (≈ 5 ms in total, spread over the first seconds).
- Buffer depth 1.5–2.5 s (MA sends ahead to `buffer_capacity`). No underruns, no late chunks.
- A volume change from HA (`media_player.volume_set` 0.4) came back to HA as 0.4 through
  `client/state`. `media_stop` → `stream/end` → `activities: []`.

### FLAC vs PCM (2026-11-09)

Same library track (AJR – 100 Bad Days; MA delivers it at 48 kHz) to
`media_player.s1_panel` only, media volume 2/15, three plays of ~20–35 s. The first FLAC play
came after a minute of clock sync; the PCM play and the second FLAC play followed a reconnect
(`sendspin_flac false` / `true`), so their filters were still converging (drift unused).

```
Stream started: flac 48000 Hz 2 ch 16-bit (codec_header 42 bytes)
FLAC stream: block size 4608..4608, 48000 Hz 2 ch 16-bit
CCodec: Created component [c2.android.flac.decoder]
C2SoftFlacDec: process: decoder configuration : 48000 Hz, 2 channels, 0 samples, 4608 block size
decode: flac 810 kbit/s on the wire | FLAC frames 114, decoder latency avg 8740 µs max 20.2 ms, errors 0, dropped 0
sync: offset 689201933.446 ms ±380 µs drift -17.91 ppm | err 154 µs (smoothed 137) buffer 2340 ms | per 10 s: +9/-0 frames, snaps 0/0, underrun blocks 0, late chunks 0, chunks 114
decode: flac 924 kbit/s on the wire | FLAC frames 114, decoder latency avg 9734 µs max 49.1 ms, errors 0, dropped 0
sync: offset 689201933.239 ms ±357 µs drift -18.22 ppm | err 124 µs (smoothed 116) buffer 3234 ms | per 10 s: +11/-0 frames, snaps 0/0, underrun blocks 0, late chunks 0, chunks 114
```

| | PCM | FLAC |
|---|---|---|
| Audio on the wire (chunk bytes incl. headers, steady 10 s windows) | 1686–1702 kbit/s | 740–930 kbit/s (**−45–55 %**) |
| wlan0 receive, ~12–14 s window from 2 s after start (includes the start-up burst) | 1905 kbit/s | 1172 kbit/s |
| Sendspin CPU, % of one A53 core (`ss-conn` + `ss-decode` + `ss-audio` + `MediaCodec_loop` + `media.swcodec`) | ≈ 10 % (7.3 + 1.8 + 1.2) | ≈ 18 % (4.2 + 4.6 + ~1.2 + 3.4 + 4.5) |
| Scheduling error, steady state | 47–179 µs | 124–179 µs |
| Corrections per 10 s, converged filter | 9 (earlier PCM run, above) | 9–11 |
| Underruns / late chunks / decode errors | 0 / 0 / – | 0 / 0 / 0 |

- MA negotiated FLAC by itself (FLAC is the hello's first entry) and logged no non-compliance for
  the panel, neither for FLAC nor for the reconnects.
- FLAC halves the Wi-Fi traffic (less ChaCha20 work on `ss-conn`). It costs about 8 % of one core
  more for decoding (≈ 2 % of the quad-core), next to ~45–50 % of one core for wake-word
  inference (`voice-mic`), which runs either way.
- "Decoder latency" is from queueing a frame to getting its PCM back (C2 runs in `media.swcodec`),
  ~10 ms against 1.5–4 s of buffer. It doesn't affect timing, since each frame keeps its server
  timestamp.
- Sync is unchanged: the scheduler sees the same PCM it saw before. The 9–11 frames per 10 s are
  the clock drift (~18 ppm), as with PCM.
- Measured with `/proc/<pid>/task/*/stat` and `/proc/net/dev` deltas over adb.

These figures come from the panel's own clock model. Acoustic sync against another speaker was
not measured (the test played to the panel only). The remaining unknown is how accurate the
Allwinner HAL's `getTimestamp` is, that is, whether it really reports the DAC presentation time.
If the panel is consistently early or late against other speakers, set MA's **static delay** for
the player (it arrives as `set_output_delay`).

## Tests

```sh
cd app
python3 -m venv /tmp/sendspin-venv && /tmp/sendspin-venv/bin/pip install 'aiosendspin[server]==10.2.1'
SENDSPIN_PYTHON=/tmp/sendspin-venv/bin/python ./gradlew :kiosk:testDebugUnitTest
```

`aiosendspin[server]` brings PyAV with FFmpeg's FLAC encoder, which the FLAC stream test needs.

- `SsCryptoTest`: the Sentinel PSK and `psk_id` constants, the pairing-token reference vector,
  base32, peer-id parsing, the **KKpsk2 responder byte for byte against `noiseprotocol`** (message
  2, handshake hash, both transport directions), a wrong static key, and record eviction.
- `SsMessagesTest`: exact `client/init`, `client/hello` (FLAC then PCM, and PCM only) and
  `client/state` shapes (including the keys that must not be there), `codec_header` Base64, pairing messages, envelope parsing, the audio header (signed
  int64, saturated `send_ahead`), fragment reassembly and every malformed fragment sequence.
- `TimeFilterTest`: the measurement formulas and truncation, rounding, the first two updates, the
  conversions as inverses, tracking a 3.2 s offset with +40 ppm drift under 1.5–9.5 ms asymmetric
  delays (< 1 ms error), burst selection, stray replies, probe timeouts.
- `PlaybackSchedulerTest`: a simulated output (virtual clock, DAC rate error, latency,
  timestamp jitter, blocking 100 ms buffer). Each frame carries its index, so the test measures
  the real presentation error. Ideal: < 0.1 ms with no corrections. ±80–120 ppm DAC error:
  < 0.5 ms, with the correction count matching the drift. 400 µs timestamp jitter: < 1 ms max,
  < 0.3 ms RMS. Also output delay, late chunks, gaps, clear/realign, the jitter buffer and PCM
  decoding.
- `FlacTest`: the 42-byte header aiosendspin's `FlacEncoder` produces (fields, `csd-0` equal to
  it), headers with more metadata blocks, invalid headers, CRC-8/CRC-16 check values, every block
  size and sample rate code, and splitting a real FFmpeg FLAC file
  (`src/test/resources/tone48k.flac`, 3 frames: 4608/4608/2784) into its frames, alone or
  together; truncated, bit-flipped and non-FLAC chunks are rejected.
- `AudioPipelineTest` (a fake decoder fed those real frames): frames land on the chunk timeline
  despite decoder latency and multi-frame chunks, PCM ↔ FLAC switches keep order and the output
  format, `stream/clear` (with and without `from_timestamp`) forgets in-flight frames, a lost frame
  is skipped, one error recovers with a new decoder, three fall back exactly once (and can be
  re-armed), a bad `codec_header` falls back without a decoder, corrupt chunks are dropped, a
  stalled decoder is replaced, `stream/end` releases it.
- `SendspinServerTest` (needs `SENDSPIN_PYTHON`): the JVM client against a **real, strict
  aiosendspin 10.2.1 server** (`src/test/resources/sendspin_server.py`):
  - held while unpaired, then Pairing PSK flow with the client's token on the open connection,
    both re-handshakes, player role, `client/state` fields as the server parsed them, 1.5 s of
    PCM chunks, volume and mute commands, `stream/end`, and reconnecting on the long-term PSK;
  - guest access with operator approval, offering **FLAC then PCM**: the server picks FLAC and
    sends the 42-byte `fLaC` + STREAMINFO header; every chunk is whole frames consistent with it,
    on a contiguous timeline (each chunk starts where the previous one's samples end, ±1 µs);
    the received stream decodes with PyAV to the server's 440 Hz tone (sample count, peak,
    frequency). The pair test uses the PCM-only hello (the fallback);
  - fails if the server logs any non-compliance.
- `EspHomeApiTest` (needs `ESPHOME_PYTHON`): the real aioesphomeapi 46.25.0 client reads
  `DeviceCapabilities.sendspin` and gets the token over Noise (`OK`) but not over plaintext
  (`ENCRYPTION_REQUIRED`).

## Security

- Default: **paired only**. Unpaired access is off, as in ESPHome's and sendspin-cpp's defaults.
  The token leaves the panel only over the encrypted ESPHome API (HA's ESPHome key) and only to
  HA's admin-only `sendspin/get_pairing_token`. After pairing, every session uses the long-term
  PSK, so it is mutually authenticated.
- The identity private key, the pairing PSK and the pairing records are in the app's private
  `sendspin.xml` and are never logged.
- Guest mode, if you turn it on, is open to a man in the middle on the LAN (spec "Unpaired
  Access"). The panel has no `source` (microphone) role, so guest mode only exposes playback.

## Limitations

- FLAC and PCM at 48 kHz/16-bit stereo only; no Opus, no 24-bit (the output is 16-bit). FLAC
  decoding relies on the platform's `audio/flac` decoder behaving like Android 10's
  `C2SoftFlacDec` (one output per input frame, the input timestamp passed through); other
  devices fall back to PCM after 3 errors.
- With FLAC the 1.5 MB `buffer_capacity` allows ~13–15 s queued (decoded eagerly: up to ~3 MB of
  PCM in memory). MA kept 1.5–4 s queued in the tests.
- Sync is checked only by the panel's own clock model (see above). The panel was not grouped with
  another speaker during the test, by design.
- The panel doesn't offer `dynamic_pairing_code` or `static_pairing_code`. Pairing is by token
  only (automatic through HA, or pasted into MA).
- `metadata@v1` is used only for the status sensor and the log (now playing). There is no
  `controller@v1` (no on-screen transport controls) and no artwork.
- `available` is always `true`: voice and announcements mute the output locally rather than
  leaving the group.
- Spec additions after aiosendspin 10.2.1 that would break MA 2.10.9 are not enforced. For
  example, a `group_id` that is not 43 base64url characters is not treated as an error: 10.2.1
  sends a UUID.

## After a factory reset or reinstall: a new Music Assistant player

The Sendspin player's `client_id` is its X25519 public key. The key and its pairing secret are random
and stored only in the app's data. A factory reset or reinstall therefore makes a new identity (so does
switching a development build to a release without root, which resets the app's data), and
Music Assistant shows a new player, e.g. `media_player.s1_panel_2`, next to the old, unavailable one.
This is deliberate: deriving the key from a hardware ID would let anyone who knows that ID
impersonate the speaker. After a reset, remove the old player in Music Assistant (Settings → Players)
and, if wanted, rename the new entity back. The ESPHome device is unaffected: its identity comes from
the Wi-Fi MAC or the chip serial ([esphome.md](esphome.md#device-identity)), so Home Assistant
reconnects to the same device.
