# microWakeWord on the S1: can it replace openWakeWord, and what does it cost?

**Status (2026-11-13): working; CPU and synthetic-voice detection measured on the panel, false
wakes pending.** S1 Kiosk runs
microWakeWord's four official models behind a Kotlin port of the TFLite Micro audio frontend. The
port matches the frontend's C code bit for bit, and all four models fire on their own word in a
synthesised clip and nowhere else. On the S1 the wake word's thread went from 37.9% to 3.7% of a
core, and macOS text-to-speech from about 1 m woke it 36 times in 40 tries. Real people's voices and
false wakes are not measured yet.

Device: Aqara S1 Plus (Allwinner A133, Android 10, armeabi-v7a), S1 Kiosk 0.4.3 before the change.
Tools: pymicro-features 2.0.2, pymicro-wakeword 2.5.0, ai-edge-litert 2.3.0, TensorFlow 2.16.1,
piper-tts 1.8.0, clang (Apple, arm64), JDK 21.

## Question

openWakeWord's always-on inference costs about 37% of one core on the S1, and most of it is the
shared embedding model ([idle-cpu.md](idle-cpu.md), #105: muting the microphone took the main
process from 39% to 2%). microWakeWord's int8 streaming models do
about 800 times less arithmetic. Can the panel run them with the same results ESPHome and
pymicro-wakeword get, and without native code of our own (#175)?

## What we tried

1. **The models and their ops** (2026-11-12, verified). The four v2 models from
   esphome/micro-wake-word-models at `40ff33f` (Apache-2.0), loaded in LiteRT:
   ```
   # LiteRT: Interpreter(path).get_input_details(), get_output_details(), _get_ops_details()
   okay_nabu.tflite [1 3 40] int8 (0.10196078568696976, -128) [1 1] uint8 (0.00390625, 0)
   ['ASSIGN_VARIABLE', 'CALL_ONCE', 'CONCATENATION', 'CONV_2D', 'DEPTHWISE_CONV_2D', 'FULLY_CONNECTED',
    'LOGISTIC', 'QUANTIZE', 'READ_VARIABLE', 'RESHAPE', 'SPLIT_V', 'STRIDED_SLICE', 'VAR_HANDLE']
   ```
   All four take three 10 ms feature rows per inference and keep their streaming state in resource
   variables. The other three lack `SPLIT_V`. The manifests give `probability_cutoff` 0.97 (Okay
   Nabu, Hey Jarvis), 0.95 (Hey Mycroft) and 0.9 (Alexa), and `sliding_window_size` 5.
   pymicro-wakeword 2.5.0 bundles a different Okay Nabu (cutoff 0.85, more languages); we use
   ESPHome's.

2. **TFLite 2.16.1 runs them** (2026-11-12, verified on the host). The app ships
   `org.tensorflow:tensorflow-lite:2.16.1`. TensorFlow 2.16.1's Python interpreter (the same C++ core)
   ran all four models behind the Kotlin pipeline in `MicroWakeWordClipTest`, with the same
   detections as LiteRT 2.3.0. Not yet run through the Java API on armeabi-v7a; the panel run checks
   that.

3. **The frontend: JNI or a port?** (2026-11-12, read). The app builds no native code of its own
   (no CMake or ndk-build in `app/kiosk/build.gradle.kts`). JNI would add an NDK toolchain to CI and
   a `.so` per ABI. The frontend is about 600 lines of integer C with one fixed configuration, so we
   ported it: `voice/MicroFrontend.kt`, including kissfft's 16-bit real FFT (512 points, which
   factors as 4·4·4·4, so only the radix-4 butterfly is needed).

4. **Port vs pymicro-features** (2026-11-12, verified). `MicroFrontendTest` feeds the first 2.5 s
   of the clip and a synthetic signal (full-scale noise, medium and quiet noise, silence) and
   compares every uint16 value:
   ```
   speech (248 rows x 40): 0 values differ
   synthetic (103 rows x 40): 1 value differs (row 18, channel 38: 461 in the reference, 468 here)
   ```
   We traced the one value with the frontend's C built on the host
   (`tools/voice/microfrontend_check.cc`):
   ```
   $ clang++ -O2 -ffp-contract=off ... && ./mf_off > syn_off.json   # contraction off
   $ clang++ -O2 -ffp-contract=on  ... && ./mf_on  > syn_on.json    # clang's default
   Kotlin vs C without FMA:          0 values differ
   C with FMA vs pymicro-features:   0 values differ
   ```
   clang on arm64 fuses `mel_low + mel_spacing * (i + 1)` in `filterbank_util.c` into one FMA. That
   moves filterbank weight 293 of 316 by 1/4096 (weight/unweight 1401/2695 instead of 1400/2696), which drifts
   channel 38's noise estimate until one output rounds differently. The port follows the C as
   written, which is also what a build without FMA (x86-64 without `-mfma`) gives. The test allows at
   most 1 value in 1000 to differ, by at most 8, on the synthetic signal, and none on speech.
   Tables use `StrictMath`, so the panel and the JVM compute the same weights.

5. **Test speech** (2026-11-12, verified). Piper's LJSpeech voice (public domain data) barely
   triggers the models (best 0.76 for Okay Nabu, 0.0 for Hey Jarvis, at three speeds). LibriTTS-R,
   the voice microWakeWord trains with, is fine-tuned from the Lessac voice, whose data licence is
   non-commercial, so its output isn't committed. `en_US-kristin-medium` (trained from scratch on
   public-domain LibriVox recordings, MIT) fires all four. `tools/voice/mww_reference.py` makes the
   clip (length scale 1.3, noise 0, so it is deterministic: regenerating it gave the same bytes) and
   the reference. Best sliding-window mean per phrase, pymicro-wakeword:

   | Model (cutoff) | Okay Nabu | Hey Jarvis | Alexa | Hey Mycroft | Other sentence |
   |---|---|---|---|---|---|
   | okay_nabu (0.97) | **0.986** | 0.0 | 0.0 | 0.0 | 0.0 |
   | hey_jarvis (0.97) | 0.0 | **0.996** | 0.0 | 0.0 | 0.0 |
   | alexa (0.9) | 0.0 | 0.001 | **0.974** | 0.005 | 0.0 |
   | hey_mycroft (0.95) | 0.016 | 0.0 | 0.0 | **0.970** | 0.038 |

   Okay Nabu and Hey Mycroft clear their cutoffs by only 0.016 and 0.020. That is one synthetic
   voice, not a measure of accuracy on people.

6. **The whole pipeline** (2026-11-12, verified). `MicroWakeWordClipTest` runs the real models
   (LiteRT, over a pipe to Python) behind `MicroDetector` over the clip. Each fires in its own phrase
   only. The best scores match pymicro-wakeword's within 0.01. Okay Nabu and Hey Jarvis match
   exactly. Alexa reaches 0.9727 against 0.9742: LiteRT and the TFLite C library pymicro-wakeword
   bundles round a few int8 outputs differently. Running the same pymicro features through LiteRT
   also gives 0.9727.

7. **Quantization** (read). pymicro-wakeword: `round(value × 0.0390625 / scale + zero_point)` with
   numpy (half to even), no clamp. ESPHome: `(value × 256 + 333) / 666 − 128`, clamped. The scale
   is 26/255, so the two differ by under 0.3% of a step. We follow pymicro-wakeword and clamp like
   ESPHome.

   The cutoff differs too (read: ESPHome `micro_wake_word/__init__.py` and `streaming_model.cpp`,
   dev, 2026-11-13). ESPHome stores `int(cutoff × 255)` and fires when the sum of the last 5 raw
   uint8 outputs is above 5 times that. We follow pymicro-wakeword: outputs × 1/256, mean ≥ cutoff.
   For the same cutoff we need a sum of 1242 instead of 1236 at 0.97, 1216 instead of 1211 at 0.95,
   and 1152 instead of 1146 at 0.9: about 1.1 more per output, 0.5% stricter at 0.97.

8. **The noise floor can't be observed** (2026-11-12, verified). Removing the noise-reduction floor
   from the C changed no output on 8 loud-to-quiet signals. When the floor binds, the estimate is
   above the signal, and PCAN's gain is then so small that the output is 0 either way. The port
   keeps the floor, but no test can catch its removal.

9. **Host cost of the Kotlin frontend** (2026-11-12, measured on an M1 Pro JVM, not the panel):
   60 s of audio in 62 ms, 0.1% of a core, after warm-up. The A53 under ART is much slower; the
   panel run measures the whole engine.

## Result

| | openWakeWord (0.4.3) | microWakeWord (#175) |
|---|---|---|
| Models | 3 per word (mel, embedding, wake), CC BY-NC-SA 4.0 | 1 per word, Apache-2.0 |
| Arithmetic | ~554 MMAC/s ([idle-cpu.md](idle-cpu.md)) | ~0.7 MMAC/s (same) |
| Features | float mel model | integer frontend in Kotlin, bit-exact to the C |
| Inference | every 80 ms | every 30 ms, int8 |
| Panel CPU, unmuted (main process / `voice-mic`) | 40.8% / 37.9% of a core | **8.1% and 6.5% / 3.7%** (target under 10%) |
| Detections, 10 synthetic voices at about 1 m | not measured | **36/40**: Okay Nabu 10, Alexa 9, Hey Mycroft 9, Hey Jarvis 8 |
| False wakes, 30 min (Hey Jarvis) | not measured | **pending** (run in progress) |

## How to reproduce

Host only; nothing touches a device.

1. A venv outside the repo: `uv venv /tmp/mww-venv && uv pip install -p /tmp/mww-venv/bin/python
   pymicro-features==2.0.2 pymicro-wakeword==2.5.0 piper-tts==1.8.0 ai-edge-litert scipy numpy`.
2. The models: `tools/voice/fetch-models.sh --no-push` (SHA-256 checked, into
   `~/.cache/s1kiosk/microwakeword-40ff33f…`). The JSON manifests are next to them in the models repo.
3. The fixtures: see the header of `tools/voice/mww_reference.py`.
4. The tests: `cd app && MWW_PYTHON=/tmp/mww-venv/bin/python ./gradlew :kiosk:testDebugUnitTest
   --tests '*Micro*'`. Without `MWW_PYTHON` the clip test is skipped; the frontend and detector tests
   always run.
5. The C cross-check: the header of `tools/voice/microfrontend_check.cc`.

## Panel run

**CPU and the update** (2026-11-13, 00:06 to 00:10, verified; run by the panel's owner). Aqara S1
Plus, S1 Kiosk 0.4.3, then test build `e88f1148` (6c1b5f8) installed over it with `adb install -r`.
`tools/perf/threadcpu.py`, 60 s windows, unmuted, CPU at 1.512 GHz:

| | Main process | `voice-mic` |
|---|---|---|
| Before (openWakeWord) | 40.8% | 37.9% |
| After (microWakeWord), two windows | 8.1%, 6.5% | 3.7% |

- The panel downloaded the four models and checked their SHA-256 on first start.
- The word saved by 0.4.3 moved over: `Wake word 'hey_jarvis' ('Hey Jarvis', microWakeWord) loaded;
  threshold 0.97`. TFLite 2.16.1's Java API on armeabi-v7a runs the resource-variable models.
- No FATAL in logcat.

**Detection with synthetic voices** (2026-11-13, verified; run by the panel's owner). Test build
5427a67, unmuted. macOS text-to-speech from a Mac's speaker about 1 m from the panel, 10 voices in
turn (Samantha, Alex, Daniel, Karen, Moira, Tessa, Fred, Victoria, Rishi, Fiona), one try per voice,
22 s apart because each detection starts an Assist run that listens for about 15 s.
`tools/voice/tts-trial.sh "<phrase>" 10` counted the `detected (score …)` lines in logcat:

| Word (cutoff) | Detected | Missed | Scores of the detections |
|---|---|---|---|
| Okay Nabu (0.97) | 10/10 | | 0.976 to 0.996 |
| Alexa (0.9) | 9/10 | Rishi | 0.906 to 0.995 |
| Hey Mycroft (0.95) | 9/10 | Victoria | 0.950 to 0.996 |
| Hey Jarvis (0.97) | 8/10 | Daniel, Rishi | 0.981 to 0.996 |

These are synthetic voices at about 1 m, not people at the 2 m #175 asks for. Several detections
sit just above the cutoff (Alexa 0.906, Mycroft 0.950), so a quieter or farther speaker will miss
more often.

**False wakes:** a 30-minute run on Hey Jarvis with `tools/voice/false-wake.sh` (macOS voices reading
sentences without a wake word, some with near-misses such as "Jarvis Street") is in progress. The
count goes here.

## Open questions

- Detection at 2 m with real people, and false wakes from room and TV sound. The cutoffs are ESPHome's,
  tuned for Voice PE's microphones and its XMOS front end, which the S1 doesn't have.
- Whether wake-word work at `THREAD_PRIORITY_URGENT_AUDIO` still costs dashboard fps (#105's open
  question): a Clarity run, muted vs unmuted.
