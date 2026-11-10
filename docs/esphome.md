# ESPHome device (native API, voice assistant, panel controls)

The kiosk app makes the panel look like **one ESPHome device** to Home Assistant. HA's built-in
**ESPHome** integration connects to it over the ESPHome native API (TCP port **6053**) and gets:

- an **Assist satellite** with an on-device wake word (openWakeWord on TFLite), like Home Assistant
  Voice PE: wake word select, pipeline select, VAD sensitivity, announcements, timers;
- a **media player** (`media_player.s1_panel_speaker`) for TTS, announcements and audio;
- the **screen** as a light, the presence and light sensors, and the kiosk/screen settings;
- the switch for the panel's native **Music Assistant player** (Sendspin, [sendspin.md](sendspin.md)),
  and the Sendspin pairing-token hand-off that lets Music Assistant pair with it automatically.

One integration, discovered automatically, no Wyoming or MQTT. It replaces the earlier Wyoming
satellite (remove that Wyoming entry from HA when you switch).

Code: `app/kiosk/src/main/java/sh/edm/s1kiosk/esphome/` (protocol core, pure JVM) and
`PanelDevice` (Android glue), owned by the foreground `KioskService`, so it keeps working while the
screen is off. Logs: `adb logcat -s S1ESPHome S1Voice`.

## Add it to Home Assistant

1. Install the app (see [app/README.md](../app/README.md)) and push the wake word models:

   ```sh
   export ANDROID_SERIAL=<panel serial>
   tools/voice/fetch-models.sh            # or --no-push to only download + verify
   ```

   The script downloads `melspectrogram.tflite`, `embedding_model.tflite`, `hey_jarvis_v0.1`,
   `alexa_v0.1` and `hey_mycroft_v0.1` from the openWakeWord **v0.5.1** release, checks they are
   TFLite files, and pushes them to `/sdcard/Android/data/sh.edm.s1kiosk/files/models/`
   (no root). Without models the API still works; the engine retries loading every 30 s.
2. **Discovery**: the panel advertises `s1-panel._esphomelib._tcp` over mDNS. If mDNS reaches HA,
   **Settings → Devices & services** shows a discovered **ESPHome** device "S1 Panel". Click
   **Add**.
3. **Manual**: **Settings → Devices & services → Add integration → ESPHome**, host = the panel's
   IP, port `6053`.
4. On the device page pick the **Assist pipeline** and the **Wake word** (one select per pipeline;
   the options are the models found on the panel). Say the wake word.

### Encryption (automatic)

Noise encryption (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`, the same as ESPHome) works like an
ESPHome device built with `api: encryption:` and no key in YAML:

- **No key yet** (fresh panel): plaintext and zero-PSK Noise clients are accepted. DeviceInfo
  reports `api_encryption_supported` and `api_encryption_provisionable`. mDNS advertises
  `api_encryption_supported` + `api_provisioning=zero-psk`. When you add the panel in HA (2026.11+),
  HA generates a key and sends it over a zero-PSK Noise session (`NoiseEncryptionSetKeyRequest`).
  The panel stores it, activates it 100 ms later and drops every older session. HA reconnects with
  the key. You never see the key.
- **With a key**: only Noise with that key (as before). Logs show only a 4-hex fingerprint.
- Removing the device in HA sends an empty key, which puts the panel back to "no key".
- Manual key, as before: `$A --es api_key $(openssl rand -base64 32) --ez api_encryption true`
  (applied live; all sessions are dropped). Then enter the same key in HA.

Details and sources: [onboarding.md](onboarding.md#2-adoption--encryption-esphome-dynamic-keys-).

## Device identity

| Field | Value |
|---|---|
| name / friendly name | `s1-panel` / `S1 Panel` |
| MAC (unique id in HA) | the Wi-Fi MAC of `wlan0` if readable, else a random locally administered MAC; persisted in prefs (`device_mac`), so it never changes |
| manufacturer / model | `Aqara` / `Panel Hub S1 Plus (S1 Kiosk)` |
| esphome_version / compilation_time | `2026.10.2` / `S1 Kiosk <app version>` (HA shows "2026.10.2 (S1 Kiosk 0.1.0)") |
| API version | 1.20 (as ESPHome 2026.10/11); `DeviceCapabilitiesRequest` is answered |
| Sendspin | `DeviceCapabilitiesResponse.sendspin` = `{feature_flags: PAIRING_TOKEN, client_id}`; `SendspinPairingTokenRequest` (159) → `SendspinPairingTokenResponse` (160), token only over Noise with a key ([sendspin.md](sendspin.md#esphome-hand-off-how-ha-gives-ma-the-token)) |
| voice_assistant_feature_flags | 61 = VOICE_ASSISTANT 1 + API_AUDIO 4 + TIMERS 8 + ANNOUNCE 16 + START_CONVERSATION 32 (0 when `voice_enabled` is false) |

## Entities

HA names entities `<friendly name> <entity name>`, so with "S1 Panel" they become
`light.s1_panel_screen` and so on. The `object_id` is the ESPHome-style snake_case name, the key
is FNV-1a of the `object_id` (stable).

| Entity | object_id | What it does |
|---|---|---|
| light `Screen` | `screen` | on → wake the screen, off → screen off (`ScreenController.wake/sleep`); brightness sets a fixed brightness and turns *Auto brightness* off |
| switch `Auto brightness` | `auto_brightness` | light-sensor brightness curve |
| binary_sensor `Presence` (occupancy) | `presence` | VI5300 ToF "near" |
| sensor `Illuminance` (lx) | `illuminance` | MK6212 light sensor |
| select `Screen mode` | `screen_mode` | `presence` / `always` |
| number `Idle timeout` (s) | `idle_timeout` | screen off after idle (0 = never) |
| number `Night idle timeout` (s) | `night_idle_timeout` | same, inside the night window |
| text `Night window` | `night_window` | `HH:MM-HH:MM`, empty = off |
| number `Presence range` (m, 0–2.5, step 0.1) | `presence_range` | VI5300 threshold (needs `WRITE_SECURE_SETTINGS`); 0 = firmware default |
| text `Dashboard URL` | `dashboard_url` | sets + loads the dashboard; `auto` (default) = HA's root on the discovered instance |
| select `Dashboard` | `dashboard` | `Default` + every Lovelace dashboard (by title) + `Custom` only while the saved URL matches none; choosing one saves the URL and reloads ([onboarding.md](onboarding.md#5-which-dashboard-)). The list comes from the HA frontend on the panel; when the options change the entity list is rebuilt (debounced 10 s, at most once a minute) and HA reconnects |
| number `Zoom` (0 = auto) | `zoom` | CSS px ratio; restarts the app |
| button `Reload dashboard` | `reload_dashboard` | |
| button `Restart app` | `restart_app` | restarts the kiosk process |
| switch `Hide HA header` | `hide_ha_header` | header + sidebar, applied live |
| media_player `Speaker` | `speaker` | play/pause/stop, volume, mute, play media, announce; formats: mp3 (default + announcement) |
| switch `Music Assistant player (Sendspin)` | `sendspin` | the native Sendspin player ([sendspin.md](sendspin.md)), default on; off sends `client/goodbye` `user_request` |
| text_sensor `Music Assistant player status` | `sendspin_status` | diagnostic; e.g. `ready, paired with Music Assistant (…)`, `playing from …: Artist – Title`, `waiting for pairing or approval by …`, `off` |
| switch `Mute microphone` | `mute_microphone` | releases the microphone entirely |
| switch `Wake sound` | `wake_sound` | short two-tone chime when the wake word is detected (default on) |
| switch `Voice overlay` | `voice_overlay` | "Listening… / Speaking" pill at the bottom of the screen during voice runs (default on) |
| number `Mic gain` | `mic_gain` | 0.5–4× |
| number `Wake word threshold` | `wake_word_threshold` | 0.1–0.95 (default 0.5) |
| select `Radio mode` | `radio_mode` | `Off` (default), `Zigbee coordinator (TCP)`, `Zigbee router`, `Thread border router`, `Thread router` ([radio.md](radio.md)); without the root helper only `Off` applies, other choices are refused and snap back |
| text_sensor `Radio status` | `radio_status` | diagnostic; e.g. `Off: Aqara's radio services stopped, radio idle. …`, or the root helper's `ok zigbee_tcp: EZSP on tcp:6638` |
| text_sensor `IP address` | `ip_address` | diagnostic |
| text_sensor `App version` | `app_version` | diagnostic |
| text_sensor `Home Assistant URL` | `home_assistant_url` | diagnostic; the HA the panel uses: the origin of a configured dashboard URL, else the discovered one ([onboarding.md](onboarding.md)) |
| text_sensor `Signed in` | `signed_in` | diagnostic; `yes` / `no`: whether a QR sign-in refresh token is stored (never the token) |
| button `Sign out` | `sign_out` | revokes the refresh token at HA (`/auth/revoke`), deletes it, shows the sign-in screen |

Plus the wall base entities below, and the entities HA adds itself for a voice device: `assist_satellite.s1_panel_assist_satellite`,
*Assist pipeline* and *Wake word* selects (two of each), *Finished speaking detection*.

### Wall base entities

Built from the detected base, through the app's own UART driver (default) or the stock LocalService
([bases.md](bases.md#implementation-s1-kiosk)); both backends produce the same entities.
Only *Base* is always present; the rest exist only while a matching base is detected.

| Entity | object_id | When | What it does |
|---|---|---|---|
| text_sensor `Base` (diagnostic) | `base` | always | `none`, `switch (2ch)`, `ethernet`, `unknown (0xNN)` (`0x7f` = UART: an unsupported model answered), or `unavailable` (port/LocalService not usable) |
| text_sensor `Base firmware` (diagnostic) | `base_firmware` | any base | `8.0.2022`, raw |
| switch `Base relay N` | `base_relay_1`..`3` | switch base, per channel | writes `4.N.85` = 0/1. UART: the ack (else a re-read) confirms it; LocalService: re-reads |
| sensor `Base power` (W, power, measurement) | `base_power` | switch base | `0.12.85` |
| sensor `Base energy` (total_increasing, no unit) | `base_energy` | switch base | `0.13.85`. UART: integrated in the app from power, Wh (as libha_bracket does); LocalService: ha_master's value |
| text_sensor `Base protection` | `base_protection` | switch base | `8.0.2014`: `normal`, `over_temperature`, `over_load`, `over_temperature_error`, `over_load_error`, `over_temperature_removed`, `over_load_removed` |
| event `Base button N` (button) | `base_button_1`..`3` | switch base, per channel | `13.N.85` clicks (LocalService only; the UART protocol has no click frames); event types `single`, `double`, `triple`, `long`, `release`, `unknown` (mapping unverified, raw value logged) |

With the friendly name "S1 Panel" the entity ids are `switch.s1_panel_base_relay_1`,
`event.s1_panel_base_button_1`, and so on. The names are fixed and don't follow the channel names
configured in the Aqara app: HA 2026.11 derives the ESPHome unique id from the entity *name*
(aioesphomeapi `build_unique_id` v3), so a rename on the Aqara side would orphan the HA entity.
Rename them in HA.

When the detected base changes (none ↔ switch, channel count) and stays changed for 5 s, the panel
rebuilds its entity list and sends `DisconnectRequest` to every API client. HA reconnects (after
its short "expected disconnect" cool-down), lists the entities again and adds the new ones. Removed
entities stay in HA's registry, shown as unavailable/removed. The last layout is persisted, so
restarting the app with the same base doesn't trigger this.

The **Dashboard** select uses the same mechanism: its options are only sent in the entity listing,
so a changed dashboard list triggers the same rebuild + `DisconnectRequest` (same object_id, so the
entity keeps its key and its id in HA). The list it was built from is persisted (`dashboards`).

Two hooks feed the setup screen ([onboarding.md](onboarding.md#setup-screen-)): a Hello from a
client whose `client_info` starts with "Home Assistant", and `SubscribeStatesRequest` from one
(`ApiServer.onStatesSubscribed`). HA only subscribes after it has added the panel, so that is
persisted as `ha_seen`.

Kiosk actions are applied in the running activity exactly like the `adb am start` extras
(`MainActivity.commandSink`). State changes made on the panel (settings dialog, `adb` extras,
sensors) are pushed to HA right away (pref listener + `ScreenController` listeners), with a 5 s
poll as a safety net.

## Voice assistant

Same model as an ESPHome `voice_assistant` with a media player (Voice PE): audio goes over the API
connection, TTS is played from a URL. Checked against esphome `voice_assistant.cpp`,
`api_connection.cpp`, aioesphomeapi `client.py` / `api.proto` (v46.25.0, the version HA
2026.11.0 pins) and HA `components/esphome/assist_satellite.py` (2026.11.0).

| Direction | Message | What we do |
|---|---|---|
| HA → panel | `SubscribeVoiceAssistantRequest` | This connection receives voice messages. |
| HA → panel | `VoiceAssistantConfigurationRequest` | Reply with every `*.tflite` wake model on the panel (not mel/embedding), e.g. `hey_jarvis_v0.1` → "Hey Jarvis", the active one, max 1. |
| HA → panel | `VoiceAssistantSetConfiguration` | Switch the wake model live, persist it (`wake_model`); empty list = no wake word. |
| panel → HA | `VoiceAssistantRequest{start, flags=USE_VAD, wake_word_phrase, conversation_id}` | On a local detection. `USE_WAKE_WORD` is not set, so HA starts at STT. |
| HA → panel | `VoiceAssistantResponse{port=0}` | Start streaming; audio captured since the wake word (≤ 2 s) is sent first. |
| panel → HA | `VoiceAssistantAudio{data}` | 16 kHz s16le mono, 2560-byte (80 ms) chunks. |
| HA → panel | `RUN_START{url}` | Remember the streaming TTS URL. |
| HA → panel | `STT_VAD_END` / `STT_END` | Stop streaming → *Thinking*. |
| HA → panel | `INTENT_PROGRESS{tts_start_streaming=1}` | Start playing the RUN_START URL early (streaming TTS). |
| HA → panel | `INTENT_END{conversation_id, continue_conversation}` | Keep the conversation id (5 min). |
| HA → panel | `TTS_END{url}` | Play the URL (unless already streaming). |
| HA → panel | `RUN_END` | Back to idle if nothing is playing. |
| HA → panel | `ERROR{code,message}` | Stop (`VoiceAssistantRequest{start=false}`) and go idle; wake-word codes are ignored like ESPHome. |
| panel → HA | `VoiceAssistantAnnounceFinished{success}` | After the TTS / announcement finished playing; HA marks the response finished. If `continue_conversation` was set, a new run starts without a wake word. |
| HA → panel | `VoiceAssistantAnnounceRequest{media_id, text, preannounce_media_id, start_conversation}` | Play the pre-announce chime then the media, reply `AnnounceFinished`, optionally start a conversation. |
| HA → panel | `VoiceAssistantTimerEventResponse` | Shown as a live countdown pill (top right). When a timer finishes the screen wakes, a chime plays 3× and a "Timer done" banner shows until tapped (max 60 s). |

Playback uses **Media3 ExoPlayer** (`ExoAudioPlayer`), which streams mp3/flac/aac/ogg/wav over HTTP
in-process, including endless streams. Android's `MediaPlayer` is deliberately avoided: on this
panel it is backed by Allwinner's CedarX player inside `mediaserver`, whose HTTP code aborts
(`FORTIFY: FD_SET: file descriptor 1024 >= FD_SETSIZE`, `libcdx_base CdxSockAsynConnect`) and kills
`mediaserver` on the first stream. Because the media
player entity declares mp3 as its announcement format, HA asks the TTS engine for mp3 and routes
other media through its ffmpeg proxy as mp3. URLs from HA are absolute (HA's internal URL);
relative ones are resolved against the dashboard URL. Music on the media player is paused while the
assistant listens or talks.

The microphone has one consumer, `VoiceEngine`: every 80 ms frame goes through the wake word model
(always, so its buffers stay fresh), and during a run the same frame is handed to the API stream.
Detections only count while idle and 3 s after the previous one. Safety caps: 15 s maximum stream,
10 s for HA to answer a start request, 60 s waiting for a response.

### openWakeWord parameters

The code is a port of `AudioFeatures` (`openwakeword/utils.py`) and `Model.predict`
(`openwakeword/model.py`), fed exact 1280-sample frames:

| Parameter | Value |
|---|---|
| Frame | 1280 samples (80 ms) of 16 kHz int16, passed to the mel model as float32 *without* normalisation |
| Mel input | last `1280 + 160*3 = 1760` samples (only 1280 on the very first frame, like upstream) → 8 frames × 32 bins (5 for 1280) |
| Mel transform | `x / 10 + 2` |
| Mel buffer | starts as `ones(76, 32)`, capped at `10*97 = 970` frames |
| Embedding | last 76 mel frames, `[1, 76, 32, 1]` → 96 floats, one per 80 ms frame |
| Feature buffer | starts with embeddings of 4 s of uniform random int16 noise in [-1000, 1000) (76-frame windows, step 8 → 41 rows), capped at 120 rows |
| Wake model input | last 16 rows → `[1, 16, 96]` (read from the model's input shape), output `[1, 1]` |
| Warm-up | the first 5 scores are forced to 0 (as in `Model.predict`) |
| Detection | score ≥ `threshold` (0.5), then `refractoryMs` (3 s) before the next one |

TFLite Java detail: the `Interpreter` constructor allocates tensors at the model's *stored* input
shape. `melspectrogram.tflite` stores `[1, 1]` (signature `[-1, -1]`), and at that size its
CONV_2D fails to prepare, so the constructor throws. Upstream Python avoids this by resizing
before it allocates. We patch the stored shape in the FlatBuffer to `[1, 1760]` in memory before
building the interpreter (`TfliteShapePatch`), then `resizeInput` as upstream does. We checked
with the Python TFLite runtime that the patched model gives bit-identical output at 1280, 1760 and
64000 samples.

## Wire protocol notes

- **Unprovisioned**: the first byte picks the transport: `0x00` plaintext, `0x01` Noise with the
  all-zeros PSK (esphome `api_frame_helper_plaintext.cpp` hand-off). `NoiseEncryptionSetKeyRequest`
  (124, base64 text of 32 bytes) → `NoiseEncryptionSetKeyResponse` (125).
- **Plaintext framing**: `0x00`, varint payload length, varint message type, protobuf payload. A
  Noise client gets `0x00 "Bad indicator byte"` back (aioesphomeapi reports "device is using
  plaintext").
- **Noise**: frames are `0x01` + 16-bit length. ClientHello (empty) → ServerHello
  `01 "s1-panel" 00 <mac hex> 00` → NNpsk0 handshake with prologue `"NoiseAPIInit" 00 00`
  (ESPHome appends the ClientHello's length and contents). A wrong key gets
  `01 "Handshake MAC failure"` (HA shows "invalid encryption key"); a plaintext client gets a Noise
  reject frame (HA shows "requires encryption"). X25519 and ChaCha20-Poly1305 come from BouncyCastle
  (`bcprov-jdk18on`, lightweight API).
- Protobuf is hand-written (`Proto.kt`, `Entities.kt`, `ApiServer.kt`), with field numbers from
  aioesphomeapi `api.proto`. Unknown messages are ignored, as ESPHome does.
- **Event entities**: `ListEntitiesEventResponse` (107: object_id 1, key 2, name 3, icon 5,
  disabled_by_default 6, entity_category 7, device_class 8, event_types 9) and `EventResponse`
  (108: key 1, event_type 2). Events have no state: nothing is sent on `SubscribeStates`, one
  `EventResponse` per occurrence (`ApiServer.sendEvent`).
- **Entity set changes**: `ApiServer.setEntities` swaps the list and asks every client to
  disconnect (`DisconnectRequest`, closed after the ack or 2 s).
- aioesphomeapi sends `SubscribeVoiceAssistantRequest.flags = 4` for API audio while `api.proto`
  defines `VOICE_ASSISTANT_SUBSCRIBE_API_AUDIO = 1`; like ESPHome, the panel ignores the flags and
  uses `VoiceAssistantResponse.port` (0 = API audio).

## Tests (host)

```sh
cd app
python3 -m venv /tmp/esphome-venv && /tmp/esphome-venv/bin/pip install aioesphomeapi==46.25.0
MODELS_CACHE=/tmp/oww-models ../tools/voice/fetch-models.sh --no-push
ESPHOME_PYTHON=/tmp/esphome-venv/bin/python OWW_MODELS_DIR=/tmp/oww-models ./gradlew :kiosk:testDebugUnitTest
```

- `EspHomeApiTest`: runs the API server (plaintext and Noise) on the JVM against the real
  aioesphomeapi client (`src/test/resources/esphome_client.py` plays HA): hello/API version,
  device info, device capabilities, list entities, subscribe states, a command for every entity
  type, voice assistant subscribe, wake word configuration get/set, a full pipeline run (wake →
  start → mic audio → events → TTS playback → `AnnounceFinished`), an announcement with
  pre-announce, a timer event, and Noise with the right key, a wrong key, no key, and a Noise
  client against the plaintext server, an event entity (list + fired event), and an entity set
  change (server-initiated disconnect, reconnect lists the new entity), and HA 2026.11's dynamic
  key provisioning (keyless connect, zero-PSK `noise_encryption_set_key`, the keyless session is
  dropped, plaintext and zero PSK are refused, reconnect with the key, bad/same/empty keys, key
  survives a restart), and the Sendspin capability (client id) and pairing token (`OK` over Noise,
  `ENCRYPTION_REQUIRED` over plaintext). Skipped without
  `ESPHOME_PYTHON`. Also passes with
  aioesphomeapi 46.28.0.
- `NoiseKeysTest`: the key state machine. `OAuthTest` / `SignInServerTest`: authorize URL, state
  store, token replies, access token cache, external-auth URL flag, and `/signin` → `/callback`
  over sockets against a fake HA token endpoint. `HaUrlsTest`: mDNS TXT parsing, manifest probe.
- `BaseProtocolTest` / `BaseTrackerTest`: wall base dock class decoding, mask/count to channels,
  LocalService reply/event parsing (all known shapes, foreign DIDs, garbage), DID redaction,
  debounce (no rebuild loop on flapping), click de-duplication, entity ids per layout.
- `BracketProtocolTest` / `BracketSessionTest`: the wall base UART protocol. CRC-16/MODBUS, frames
  byte-identical to Aqara's `bracket_cli` output, resource ids/types, a resyncing framer (noise,
  bad/swapped CRC), identity → dock class, protection mapping, termios verification; the handshake
  sequence, retries (200 ms, 3 sends, new seq), unsupported model, reports/energy, an unacked write
  → read, and silence → probe → removal. `UartBaseLinkEmulatorTest`: the real `UartBaseLink` over
  TCP against `tools/bracket/base_emulator.py` (needs only python3; uses `BRACKET_PYTHON` /
  `ESPHOME_PYTHON` / `python3`).
- `VoiceAssistantControllerTest`: pre-roll, "never mind" runs, pipeline errors, the max-stream
  cap, continue-conversation.
- `OwwFeaturesTest`: compares the Kotlin feature buffering with openWakeWord's own
  `AudioFeatures` code, run with deterministic fake models (fixture made by
  `tools/voice/oww_reference.py`).
- `TfliteShapePatchTest`: patches the real `melspectrogram.tflite`. Skipped without
  `OWW_MODELS_DIR`.

## Licensing

- The ESPHome API and voice code is MIT, like the rest of the app. The openWakeWord feature pipeline is
  re-implemented from the Apache-2.0 upstream.
- TensorFlow Lite (`org.tensorflow:tensorflow-lite:2.16.1`) is Apache-2.0.
- **The openWakeWord pre-trained models are CC BY-NC-SA 4.0** (non-commercial, share-alike).
  That's why they are **not bundled** in the APK or the repo. Each user downloads them with
  `fetch-models.sh`. Don't ship them in a commercial product. Custom-trained models carry
  whatever license you give them.
- BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) is MIT-style (Bouncy Castle licence).

## Limitations / known gaps

- **Verified on the panel (2026-11-09):** HA adds it (by IP; the panel sits on a separate VLAN so
  mDNS didn't reach HA), all entities report live values, `announce` with pre-announce chime plays,
  `media_player.play_media` plays and reports playing → idle. **Not yet verified:** real-speech
  wake word accuracy / mic gain, Music Assistant playback, mDNS discovery on a flat network.
- The mDNS SRV target is the Android hostname, not `s1-panel.local`; HA uses the IP it discovered and
  replaces the name with `DeviceInfoResponse.name` once connected.
- No `SPEAKER` flag: TTS is fetched by URL, not streamed as PCM over the API.
- One wake word at a time (`max_active_wake_words = 1`); HA's custom (microWakeWord) wake words
  are ignored.
- No acoustic echo cancellation / barge-in: detections are ignored while listening or speaking.
- Shared microphone with GeckoView: if the dashboard's in-browser Assist uses the mic at the same
  time, one of the two captures may get silence. *Mute microphone* releases it.
- Music Assistant: the panel is a native, synced Sendspin player (`media_player.s1_panel`, see
  [sendspin.md](sendspin.md)), so MA's *Home Assistant* player provider is no longer needed for it.
  `media_player.s1_panel_speaker` stays for TTS, announcements and HA media; while it announces,
  the Sendspin output is muted locally.
- Chromecast: not possible. A Cast receiver must be a Google-certified device.
- Radio modes other than `Off` need the root helper from the boot-image patch ([radio.md](radio.md)).

### Aqara cloud

- switch `Block Aqara cloud` (`block_aqara_cloud`, default on): stops Aqara's cloud daemon `ha_master`.
  With the UART wall-base backend (default) it is always stopped; with the LocalService backend it is
  kept while a base is attached. See docs/debloat.md.
- text_sensor `Aqara cloud daemon` (`aqara_cloud`): `blocked`, `running`,
  `stopped (UART wall base backend owns /dev/ttyS3)`, `running (needed by the wall base, LocalService
  backend)`, or `error (…)`.
