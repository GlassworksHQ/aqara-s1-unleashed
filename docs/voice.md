# Voice satellite (Wyoming + on-device wake word)

The kiosk app can act as a Home Assistant **Assist satellite** over the
[Wyoming protocol](https://github.com/rhasspy/wyoming). The wake word is detected on the panel
with [openWakeWord](https://github.com/dscripka/openWakeWord) (TFLite). After the wake word,
the panel streams your speech to Home Assistant, and HA runs its Assist pipeline (STT, then the
conversation agent, then TTS). The panel then plays the spoken answer.

Code: `app/kiosk/src/main/java/io/github/aguilaair/s1kiosk/voice/`. The foreground
`KioskService` owns a `VoiceSatellite`.

## How it works

```
 mic (AudioRecord 16 kHz mono s16, 80 ms frames)
   │
   ├─► openWakeWord: melspectrogram.tflite → embedding_model.tflite → hey_jarvis_v0.1.tflite
   │        score ≥ threshold ──► detection + run-pipeline(asr→tts) + audio-start ─┐
   │                                                                               ▼
   └─► (while streaming) audio-chunk ───────────────────────────────► Home Assistant (Wyoming
                                                                          integration, TCP 10700)
 speaker (AudioTrack) ◄── audio-start / audio-chunk / audio-stop (TTS) ◄──┘   ──► played
```

- **Wyoming server**: TCP on port 10700. It accepts several connections at once, because HA opens
  short-lived ones just to `describe`. The connection that sends `run-satellite` becomes the active
  server. Framing follows `wyoming/event.py`: a JSON header line with `type`, `version`,
  `data_length` and `payload_length`, then the data JSON block, then the payload. On read, the
  older inline `data` in the header is also accepted and merged.
- **Discovery**: `_wyoming._tcp` is registered with Android `NsdManager`. The service name is
  `s1kiosk-<ANDROID_ID prefix>`. HA's Wyoming integration listens for `_wyoming._tcp.local.`.
- **Wake word**: runs on every frame, all the time. The model state never goes stale, so a
  wake word spoken during a conversation can't fire again afterwards. Detections only count while
  idle and after the refractory period.
- **Power**: while running, the satellite holds a partial wake lock and a Wi-Fi lock. The mic and
  the socket keep working with the screen off (the night DND rule turns the screen off; see
  [screen-policy.md](screen-policy.md)).

### Event flow

Checked against the real sources, not guessed. Sources: `rhasspy/wyoming` master
(`event.py`, `info.py`, `satellite.py`, `ping.py`, `wake.py`, `pipeline.py`, `audio.py`, `asr.py`,
`vad.py`, `tts.py`, `snd.py`, `error.py`); the reference satellite
`rhasspy/wyoming-satellite` (`satellite.py`, `WakeStreamingSatellite`); and HA's client
`homeassistant/components/wyoming/assist_satellite.py` and `data.py` (dev branch).

| Direction | Event | What we do |
|---|---|---|
| HA → sat | `describe` | Reply `info`: `satellite` (installed, name, area, `has_vad:false`, `active_wake_words`, `max_active_wake_words:1`, `supports_trigger:false`), plus `wake` (openWakeWord program with the model and its phrase, so HA can resolve a `detection` name to a phrase), `mic`, `snd`. |
| HA → sat | `run-satellite` | This connection becomes the active server; unpause; back to idle. |
| HA → sat | `pause-satellite` | Pause (HA mute or teardown); stop streaming. |
| HA → sat | `ping` | Reply `pong` with the same `text` right away. HA pings about every 2 s and reconnects if nothing arrives within 5 s, so TTS playback runs on its own thread. |
| sat → HA | `detection` `{name}` | On a local wake word. |
| sat → HA | `run-pipeline` `{start_stage:"asr", end_stage:"tts", restart_on_end:false, wake_word_name}` | Right after `detection`. |
| sat → HA | `audio-start` / `audio-chunk` (16000 Hz, width 2, 1 ch, 2560 B = 80 ms) | Mic audio while streaming. |
| HA → sat | `transcribe`, `voice-started` | Informational. |
| HA → sat | `voice-stopped`, `transcript` | Stop streaming, send `audio-stop`, go to *Thinking*. |
| HA → sat | `synthesize` | Informational (response text is logged). |
| HA → sat | `audio-start` / `audio-chunk` / `audio-stop` | Play with AudioTrack using the given rate, width and channels. Once it has drained, send `played`. Announcements use the same path. |
| HA → sat | `error` | Stop streaming, emit `VoiceEvent.Error(text)`, go idle. |
| HA → sat | `detect`, `detection`, `timer-*` | Ignored. |

HA never sends an explicit "pipeline finished" event. The satellite goes back to idle after
`played`, on `error`, `pause-satellite` or `run-satellite`, on disconnect, or through watchdogs:
15 s maximum stream, 60 s maximum in *Thinking*, and a dropped connection after 30 s without any
event (HA's pings normally arrive every 2 to 3 s).

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

## Setup

1. **Build and install** the app (see [app/README.md](../app/README.md)). The app needs
   `RECORD_AUDIO` (`adb install -g` grants it).
2. **Fetch the models on the host and push them.** The panel has no internet:

   ```sh
   export ANDROID_SERIAL=<panel serial>
   tools/voice/fetch-models.sh            # or --no-push to only download + verify
   ```

   The script downloads `melspectrogram.tflite`, `embedding_model.tflite`, `hey_jarvis_v0.1`,
   `alexa_v0.1` and `hey_mycroft_v0.1` from the openWakeWord **v0.5.1** release. It checks that
   each file is a real TFLite FlatBuffer (`TFL3` identifier), not an HTML error page, then pushes
   them to `/sdcard/Android/data/io.github.aguilaair.s1kiosk/files/models/`. No root is needed.
   If the models are missing, the satellite reports `VoiceEvent.Error` and keeps the Wyoming
   server up. It retries loading every 30 s, so pushing the models is enough and no restart is
   needed.
3. **Add it to Home Assistant.**
   - *Discovery*: go to **Settings → Devices & services**. A discovered **Wyoming Protocol** card
     named "S1 Panel" should appear. Click **Add**.
   - *Manual*: go to **Add integration → Wyoming Protocol**. Use the panel's IP as the host and
     `10700` as the port.
4. **Choose the pipeline.** On the new device's page, set **Assist pipeline** to the pipeline you
   want, for example one with Cloud STT, ElevenLabs TTS and the LLM agent. The pipeline doesn't
   need a wake word engine, because wake word detection happens on the panel. HA asks the TTS for
   22.05 kHz 16-bit mono WAV and streams it as PCM. The device also gets *Mute*, *VAD
   sensitivity* and *Assist in progress* entities. Mute sends `pause-satellite`.
5. Say **"Hey Jarvis"**. To use another model, set `VoiceConfig.wakeModel` to
   `alexa_v0.1.tflite` or `hey_mycroft_v0.1.tflite`. The wake word phrase comes from the file
   name.

`VoiceConfig` defaults: port `10700`, name `"S1 Panel"`, wake model `hey_jarvis_v0.1.tflite`,
threshold `0.5`, refractory `3000` ms, `micGain 1.0`, mDNS on, max stream `15000` ms, and models
dir `getExternalFilesDir("models")`.

Logs: `adb logcat -s S1Voice`.

## Licensing

- The satellite code is MIT, like the rest of the app. The openWakeWord feature pipeline is
  re-implemented from the Apache-2.0 upstream.
- TensorFlow Lite (`org.tensorflow:tensorflow-lite:2.16.1`) is Apache-2.0.
- **The openWakeWord pre-trained models are CC BY-NC-SA 4.0** (non-commercial, share-alike).
  That's why they are **not bundled** in the APK or the repo. Each user downloads them with
  `fetch-models.sh`. Don't ship them in a commercial product. Custom-trained models carry
  whatever license you give them.

## Tests (host)

```sh
cd app
python3 -m venv /tmp/wyo-venv && /tmp/wyo-venv/bin/pip install wyoming numpy
MODELS_CACHE=/tmp/oww-models ../tools/voice/fetch-models.sh --no-push
WYOMING_PYTHON=/tmp/wyo-venv/bin/python OWW_MODELS_DIR=/tmp/oww-models ./gradlew :kiosk:testDebugUnitTest
```

- `WyomingCodecTest`: framing round-trip, inline `data` plus a data block merge, UTF-8 lengths.
- `SatelliteFlowTest`: a full session against the real `wyoming` Python package, which plays HA
  (`src/test/resources/ha_sim.py`). The session covers config-flow describe, `run-satellite`,
  ping/pong, wake → `detection`/`run-pipeline`/audio, STT events → `audio-stop`, TTS → `played`,
  and pause. Skipped without `WYOMING_PYTHON`.
- `OwwFeaturesTest`: compares the Kotlin feature buffering with openWakeWord's own
  `AudioFeatures` code, run with deterministic fake models (fixture made by
  `tools/voice/oww_reference.py`).
- `TfliteShapePatchTest`: patches the real `melspectrogram.tflite`. Skipped without
  `OWW_MODELS_DIR`.

## Limitations / known gaps

- **Not yet tested on the panel.** Unknowns: TFLite inference time on the A133 (it should be a
  few ms per 80 ms frame; a warning is logged if it takes more than 60 ms), real wake word
  accuracy with the panel's mic, and the right `micGain`.
- **Shared microphone with GeckoView**: on Android 10, two apps capturing at once is limited. If
  the dashboard's in-browser Assist uses the mic, one of the two captures may get silence.
- No local VAD: HA's VAD decides when you stopped speaking, and there's a 15 s cap.
- No acoustic echo cancellation and no barge-in: wake word detections are ignored while the
  panel is listening, thinking or speaking.
- One wake word at a time (`max_active_wake_words: 1`). HA can't change it remotely, and
  `supports_trigger` is false.
- Timer events (`timer-*`) are ignored, so the panel has no timer UI or sound yet.
- No wake or "done" chimes. The UI can react to `VoiceEvent`.
- The mDNS name changes if `ANDROID_ID` changes (for example after a factory reset). HA then sees
  a new device.
