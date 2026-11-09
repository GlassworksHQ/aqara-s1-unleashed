# ESPHome device (native API, voice assistant, panel controls)

The kiosk app makes the panel look like **one ESPHome device** to Home Assistant. HA's built-in
**ESPHome** integration connects to it over the ESPHome native API (TCP port **6053**) and gets:

- an **Assist satellite** with an on-device wake word (openWakeWord on TFLite), like Home Assistant
  Voice PE: wake word select, pipeline select, VAD sensitivity, announcements, timers;
- a **media player** (`media_player.s1_panel_speaker`) for TTS, announcements and audio;
- the **screen** as a light, the presence and light sensors, and the kiosk/screen settings.

One integration, discovered automatically, no Wyoming or MQTT. It replaces the earlier Wyoming
satellite (remove that Wyoming entry from HA when you switch).

Code: `app/kiosk/src/main/java/io/github/aguilaair/s1kiosk/esphome/` (protocol core, pure JVM) and
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
   TFLite files, and pushes them to `/sdcard/Android/data/io.github.aguilaair.s1kiosk/files/models/`
   (no root). Without models the API still works; the engine retries loading every 30 s.
2. **Discovery**: the panel advertises `s1-panel._esphomelib._tcp` over mDNS. If mDNS reaches HA,
   **Settings → Devices & services** shows a discovered **ESPHome** device "S1 Panel". Click
   **Add**.
3. **Manual**: **Settings → Devices & services → Add integration → ESPHome**, host = the panel's
   IP, port `6053`.
4. On the device page pick the **Assist pipeline** and the **Wake word** (one select per pipeline;
   the options are the models found on the panel). Say the wake word.

### Encryption (optional)

Noise encryption (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`, the same as ESPHome) is **off by
default**. A random 32-byte key is generated on first start and logged once
(`adb logcat -s S1ESPHome | grep "encryption key"`).

```sh
A="adb shell am start -n io.github.aguilaair.s1kiosk/.MainActivity"
$A --es api_key $(openssl rand -base64 32)    # or keep the generated one
$A --ez api_encryption true                     # restarts the API server; mDNS then advertises api_encryption
```

HA then asks for the key (or shows a re-auth flow for an existing entry). The device does not
offer HA's automatic key provisioning (`api_encryption_supported` is false).

## Device identity

| Field | Value |
|---|---|
| name / friendly name | `s1-panel` / `S1 Panel` |
| MAC (unique id in HA) | the Wi-Fi MAC of `wlan0` if readable, else a random locally administered MAC; persisted in prefs (`device_mac`), so it never changes |
| manufacturer / model | `Aqara` / `Panel Hub S1 Plus (S1 Kiosk)` |
| esphome_version / compilation_time | `2026.10.2` / `S1 Kiosk <app version>` (HA shows "2026.10.2 (S1 Kiosk 0.1.0)") |
| API version | 1.20 (as ESPHome 2026.10/11); `DeviceCapabilitiesRequest` is answered |
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
| text `Dashboard URL` | `dashboard_url` | sets + loads the dashboard |
| number `Zoom` (0 = auto) | `zoom` | CSS px ratio; restarts the app |
| button `Reload dashboard` | `reload_dashboard` | |
| button `Restart app` | `restart_app` | restarts the kiosk process |
| switch `Hide HA header` | `hide_ha_header` | header + sidebar, applied live |
| media_player `Speaker` | `speaker` | play/pause/stop, volume, mute, play media, announce; formats: mp3 (default + announcement) |
| switch `Mute microphone` | `mute_microphone` | releases the microphone entirely |
| number `Mic gain` | `mic_gain` | 0.5–4× |
| number `Wake word threshold` | `wake_word_threshold` | 0.1–0.95 (default 0.5) |
| select `Radio mode` | `radio_mode` | `Aqara (stock)`, `Off`, `Zigbee router`, `Zigbee coordinator (TCP)`, `Thread (future)`; stored only (`RadioController` is a stub) |
| text_sensor `IP address` | `ip_address` | diagnostic |
| text_sensor `App version` | `app_version` | diagnostic |

Plus the entities HA adds itself for a voice device: `assist_satellite.s1_panel_assist_satellite`,
*Assist pipeline* and *Wake word* selects (two of each), *Finished speaking detection*.

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
| HA → panel | `VoiceAssistantTimerEventResponse` | Tracked and logged; a finished timer plays a short chime. |

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
  client against the plaintext server. Skipped without `ESPHOME_PYTHON`. Also passes with
  aioesphomeapi 46.28.0.
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
- Timers only chime; there is no on-screen timer UI.
- No acoustic echo cancellation / barge-in: detections are ignored while listening or speaking.
- Shared microphone with GeckoView: if the dashboard's in-browser Assist uses the mic at the same
  time, one of the two captures may get silence. *Mute microphone* releases it.
- Music Assistant: the speaker is a normal HA media player, so MA can use it through its
  *Home Assistant* player provider (enable `S1 Panel Speaker` there). That's MA → HA → panel, so
  no MA-synced multi-room. A native MA protocol (e.g. a Snapcast/Squeezelite client in the app)
  would be needed for that.
- Chromecast: not possible. A Cast receiver must be a Google-certified device.
- `RadioController` only logs; the radio mode is stored but nothing is switched yet.
- No dynamic key provisioning from HA (`NoiseEncryptionSetKeyRequest`).
