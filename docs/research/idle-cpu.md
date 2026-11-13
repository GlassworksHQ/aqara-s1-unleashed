# Idle CPU: why the kiosk uses ~40% of a core with nothing on screen

**Status (2026-11-12): working (the microphone path confirmed on the panel), fix not chosen.** Muting
the microphone takes the main process from 39.1% to 2.2% of a core. The `voice-mic` thread runs
openWakeWord's embedding model on every 80 ms of audio, and that model is nearly all of the
thread's work (inferred from its cost; see Result). That costs 30 to
36 ms of CPU per frame on the S1 (37 to 45% of a core, by CPU clock), with the screen on or off.
The model needs that work; no bug wastes it. The options are below,
and the choice is the maintainer's ([#105](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/105)).

Device: Aqara Panel Hub S1 Plus (A133, 4× Cortex-A53, 32-bit userspace), S1 Kiosk 0.4.3, all panes
on, radio `thread_router`, wake word `hey_jarvis_v0.1`. Tools: adb (read-only, uid `shell`),
`tools/perf/threadcpu.py`, TensorFlow Lite 2.16.1 in the app, `ai-edge-litert` on a Mac for the
host timings.

## Question

The perf test from #97 found the app's main process at about 40% of one core while nothing changed
on screen. Which thread is it, is that work needed, and what would make it smaller?

## What we tried

1. **Per-thread CPU over 60 s** (2026-11-12, verified). `threadcpu.py` reads
   `/proc/<pid>/task/*/stat` for every kiosk process twice and divides the jiffies by the window.
   It writes nothing on the panel.
   ```
   $ tools/perf/threadcpu.py -s $SERIAL --seconds 60 --extra otbr-agent
   window 60.3 s, 212 threads, % of one core

     48.7  sh.edm.s1kiosk
     44.7    voice-mic (6769)
      1.2    AudioTrack (21099)
      0.8    AudioIPC Server (6758)
      0.3    process reaper (6520)
      0.2    HeapTaskDaemon (6456)

      7.8  sh.edm.s1kiosk:sandboxed_tab_disable_art_image_19
      4.8    Isolated Web Co (6730)
      1.1    GraphRunner (21096)
      ...
      0.1  sh.edm.s1kiosk:gpu_disable_art_image_
      0.0  otbr-agent
   ```
   Five windows. Another session restarted the app at 22:06 (same 0.4.3), so windows 4 and 5 are a
   new process; a window that spanned the restart is left out (the script now warns when that
   happens). The CPU clock is `scaling_cur_freq`, read before and after each window.

   | Window | Screen | Clock | `voice-mic` | Main process | Rest of the main process |
   |---|---|---|---|---|---|
   | 1 | on | 1.2 GHz | 44.7% | 48.7% | 4.0% |
   | 2 | on | 1.2 GHz | 43.6% | 49.0% | 5.4% |
   | 3 | on, then off part-way | 1.2 GHz | 42.6% | 47.2% | 4.6% |
   | 4 | off | 1.512 GHz | 37.2% | 38.9% | 1.7% |
   | 5 | off | 1.512 GHz | 37.3% | 39.3% | 2.0% |

   `voice-mic` is 37 to 45% of a core. The spread follows the clock: 43.6% of 80 ms at 1.2 GHz and
   37.2% at 1.512 GHz are both about 42 to 45 million cycles per frame, so the work is a fixed
   number of cycles and the governor decides the percentage. With the screen off (windows 4, 5) the
   load is the same; capture starts and stops only on the mute preference (`AssistPane.kt:134`,
   `:154`), never on the screen. Nothing else in the app is above 1.3%: not the ESPHome connection
   (`esphome-conn-*`), not Sendspin (`ss-timer`), not the UI thread, not the radio's `otbr-agent`.
   `top -H` and `dumpsys cpuinfo` agree (`voice-mic` 41.1%, process 42%, during window 3). The extra
   2 to 3 points in windows 1 to 3 are an audio stream the page had open (`AudioTrack`,
   `AudioIPC Server`, and `GraphRunner` in the tab; an OpenSL player in `dumpsys audio` from 21:57
   to 22:06); it was gone after the restart.

2. **What the thread does per frame** (2026-11-12, verified).
   ```
   $ adb shell 'P=$(pidof sh.edm.s1kiosk); T=<voice-mic tid>; cat /proc/$P/task/$T/schedstat; sleep 10; cat /proc/$P/task/$T/schedstat'
   351264021991 3039967668 58023
   355748620002 3060881130 58596
   $ adb shell 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq'
   1200000 (all four cores)
   ```
   4.48 s on CPU in 10 s, in 573 slices. 10 s of audio is 125 frames of 80 ms, so the thread spends
   about **36 ms of CPU per frame** at 1.2 GHz. The 4.6 slices per frame are `AudioRecord.read`
   returning in parts plus preemption; there is no spinning.

3. **Is the runtime set up right?** (2026-11-12, verified from logcat)
   ```
   I/tflite: Created TensorFlow Lite XNNPACK delegate for CPU.
   I/tflite: Replacing 17 out of 26 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 14 partitions for the whole graph.   ← melspectrogram
   I/tflite: Replacing 64 out of 64 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 1 partitions for the whole graph.    ← embedding
   W/S1Voice: Wake word inference slow: 61 ms per 80 ms frame    ← once, at start-up
   ```
   XNNPACK is on, and the embedding model runs fully inside it. The mel model is only re-allocated
   while its input length settles (1760 → 64 000 → 1280 → 1760 samples in the first frames), then
   never again. There is no second recorder and no second engine (one `voice-mic` thread).

4. **simpleperf** (2026-11-12, verified: not usable read-only).
   ```
   $ adb shell cat /proc/sys/kernel/perf_event_paranoid; adb shell getprop security.perf_harden
   3
   1
   $ adb shell simpleperf stat -t 1 --duration 1
   simpleperf E event_selection_set.cpp:546] failed to open perf event file ... Permission denied
   ```
   It is on the panel, but profiling needs `setprop security.perf_harden 0` (a state change) and
   `record` writes a file. So the split between the three models comes from the host (step 5).

5. **Where the 36 ms goes** (2026-11-12, verified on the host; the ratio, not the absolute time).
   The same three models from the openWakeWord v0.5.1 release (SHA-256 matching
   `ModelFetcher.FILES`), timed with LiteRT, one thread, XNNPACK, on an Apple-silicon Mac, five
   runs (`tools/voice/model_cost.py`):

   | Model | Input → output | Work per 80 ms frame | Host time per call (elapsed) | Share |
   |---|---|---|---|---|
   | `melspectrogram` | 1760 samples → 8 × 32 | 2.2 MMAC | 0.20 to 0.29 ms | 12 to 16% |
   | `embedding_model` | 76 × 32 mel → 96 floats, 20 conv layers | **42.0 MMAC** | 1.31 to 1.85 ms | **83 to 87%** |
   | `hey_jarvis_v0.1` | 16 × 96 → score | 0.2 MMAC | 0.015 to 0.049 ms | 1 to 3% |

   MACs are counted from the conv, fully-connected and batch-matmul tensor shapes. At 12.5 frames/s
   the embedding alone is about 1.05 GFLOP/s. One A53 at 1.2 GHz does at most about 4.8 fp32
   GFLOP/s with 32-bit NEON (2 multiply-adds per cycle). The panel's 36 ms per frame for about
   88 MFLOP is about 2.4 GFLOP/s, half of that peak, which is normal for XNNPACK on this core.
   **The limit is fp32 arithmetic on one A53 core, and the work is what openWakeWord's design asks
   for.**

6. **A lighter engine for comparison** (2026-11-12, verified on the host, untested on the panel).
   ESPHome's own wake word engine, microWakeWord, runs streaming int8 models (v2, from
   `esphome/micro-wake-word-models`, Apache-2.0) on an ESP32-S3. Counted and timed the same way:

   | Model | Input | Work per call | Calls per s | Work per s | Host elapsed time per s of audio |
   |---|---|---|---|---|---|
   | openWakeWord (all three models) | 80 ms frame | 44.3 MMAC | 12.5 | 554 MMAC | about 20 ms |
   | microWakeWord `hey_jarvis` | 3 × 40 int8 features (30 ms) | 0.02 MMAC | 33 | 0.7 MMAC | 0.1 to 0.8 ms |
   | microWakeWord `okay_nabu` | same | 0.02 MMAC | 33 | 0.7 MMAC | 0.15 to 1.7 ms |

   The model does about 800 times less arithmetic, and int8 instead of fp32. Its host time is only
   microseconds per call, so call overhead dominates and the time ratio varies between runs (12 to
   150 times less). This leaves out microWakeWord's feature front end (a 512-point FFT, filterbank,
   noise reduction and log every 10 ms), which also runs on the ESP32 in real time. **Guess, not
   measured:** on the S1 the whole thing would be a few percent of a core.

7. **Mute the microphone, then unmute it** (2026-11-12 22:41 to 22:46, verified; run by the panel's
   owner from the plan in reproduce step 5). S1 Kiosk 0.4.3, radio `thread_router`,
   `threadcpu.py` from 51e8304, 60 s windows.

   | State | Main process | `voice-mic` | Gecko tab | `otbr-agent` |
   |---|---|---|---|---|
   | Unmuted | 39.1% | 37.2% | 3.8% | 0.0% |
   | Muted (`switch.<panel>_mute_microphone` on; logcat `S1Voice: Microphone muted (released)` at 22:43:09), 30 s later | **2.2%** | no thread | 5.7% | |
   | Unmuted again, 15 s later | 38.5% | 36.7% | | |

   Muting removes the `voice-mic` thread and 36.9 of the main process's 39.1 points. Unmuting brings
   them back. This is the causal check for the whole microphone path (capture and inference stop
   together): **it is about 36 to 37 points of the process's 39**, and the rest of the app is about
   2%. The tab rose from 3.8 to 5.7% while muted,
   which is the page, not the mic. On unmute, the `Microphone open` log line was not in the last 400
   lines of logcat, but the thread and its load were back. The switch was turned back off, and
   nothing else changed.

   The perf test's own `smoke10` run reports the assist pane at +29.5% CPU in its pane diff (not
   rerun here). That is consistent: its window is device-wide, and it runs at a different clock.

## Result

| Thread | % of one core | What it is |
|---|---|---|
| `voice-mic` | **37 to 45** (median 42.6 over 5 × 60 s) | `Microphone.readLoop` → `VoiceEngine.onMicFrame` → `TfliteWakeWord.process`: melspectrogram, **embedding (85%)**, wake model, every 80 ms |
| everything else in the app | 2 to 5 | audio output of the open page, binder, GC |
| Gecko content and GPU processes | 0.1 to 8 | the page; the 8% was a dashboard with an audio stream open |
| `otbr-agent` (radio) | 0.0 | |

**Confirmed (step 7): the idle load is the microphone path.** Muting the microphone takes the main
process from 39.1% to 2.2% of a core, and unmuting brings it back to 38.5%. The load is the same
with the screen on and off. The mute switch stops the recorder and inference together
(`setMuted(true)`, `VoiceEngine.kt:73`), so the check alone can't split them.

**Inferred, not measured on the panel: inference is nearly all of it.** Capture wasn't measured on its
own (see Open questions). The evidence for inference:

- The thread blocks in `AudioRecord.read` and wakes 4.6 times per frame (step 2), with no spinning.
- Inference is 44.3 MMAC per frame (step 5), and the thread uses 42 to 45 M cycles per frame. That
  is about one multiply-add per cycle, half the A53's fp32 NEON peak, which XNNPACK normally reaches.
  If capture took a sizeable share, the models would have to run faster than that.
- Reading 16 kHz mono and applying the gain is about 1280 samples per frame of simple work.

So the load is openWakeWord's compute on this CPU, not a busy loop or a poll.

One side effect worth fixing whatever the choice: inference runs on the capture thread at
`THREAD_PRIORITY_URGENT_AUDIO` (`Microphone.kt:79`). That is 36 ms of model work every 80 ms at
audio priority, ahead of Gecko and the UI thread. **Read, not measured:** it may cost the dashboard
frames. Capture at audio priority and inference at normal priority on a second thread would keep
the recorder safe without that.

### Options

| Option | CPU, from about 40% of a core | Detection | Work |
|---|---|---|---|
| **A. microWakeWord** (ESPHome's engine, int8 streaming) | a few % (guess from step 6) | ESPHome's tuned models for Okay Nabu, Hey Jarvis, Hey Mycroft, Alexa; same words as HA voice hardware | Port the micro front end to Kotlin with a reference test (like `OwwFeaturesTest`), a `WakeWord` interface, model fetch. Days. Models are Apache-2.0, which also drops the CC BY-NC-SA models |
| B. Energy gate | about 6% while the room is quiet (mel only; estimate from the host split), about 40% when there is sound | Same scores when loud, if missed embeddings are caught up from the mel buffer; quiet speech under the floor is missed | Small, but the catch-up is a 16-embedding burst (about 0.5 s on the S1) against a 640 ms record buffer, and the floor needs tuning per mic |
| C. Lower frame rate or duty cycle | about half at a 160 ms step | The wake models are trained on an 80 ms step; changes their input. Unknown loss | Small code, retrain or re-validate |
| D. Int8 embedding model | maybe 2 to 3× less (guess) | No official quantized embedding; we'd quantize and validate it against the reference | Medium; owning a derived model |
| E. Do nothing | about 40% | unchanged | Mute is the per-panel off switch |

**Recommendation: A, microWakeWord.** It is the engine ESPHome itself uses, so it fits "ESPHome is
the wire" in [principles](../principles.md). It cuts the load the most at every noise level, and
the wake words stay the same as HA's own satellites. Its models are Apache-2.0 instead of
non-commercial. B is the cheapest change, but it saves nothing in a room with a TV on, and it adds
a tuning knob. C and D change detection without a way to check it on real speech yet. Whatever is
chosen, move inference off the audio-priority thread.

## How to reproduce

Steps 1 to 4 are read-only on the panel. Step 5 flips one HA switch and puts it back. Nothing is
installed.

1. `tools/perf/threadcpu.py -s <serial> --seconds 60 --extra otbr-agent`, three times. Note the
   screen state (`adb shell dumpsys power | grep mWakefulness`).
2. `adb shell 'P=$(pidof sh.edm.s1kiosk); grep -l voice-mic /proc/$P/task/*/comm'` for the thread,
   then `cat /proc/$P/task/<tid>/schedstat` 10 s apart: (Δ field 1 in ns) / 10 s is the share of a core.
3. `adb shell logcat -d | grep -E 'tflite|S1Voice'` for XNNPACK and the model loads.
4. On a computer: download `melspectrogram.tflite`, `embedding_model.tflite` and
   `hey_jarvis_v0.1.tflite` from openWakeWord v0.5.1 (`tools/voice/fetch-models.sh --no-push`), and
   microWakeWord's v2 `hey_jarvis.tflite` and `okay_nabu.tflite` for the comparison. Then
   `uv run --with ai-edge-litert --with numpy python -I tools/voice/model_cost.py <files>`. Host
   only, nothing touches the panel.
5. The causal check needs a state change, which is for whoever owns the panel: turn
   `switch.<panel>_mute_microphone` on, check logcat for `S1Voice: Microphone muted (released)`,
   wait 30 s, and run step 1 again. `voice-mic` should be gone and the main process at about 2%.
   Turn the switch back off, and run step 1 once more to see the thread return.

## Open questions

- microWakeWord on the S1: the front end's cost in Kotlin, and whether TFLite Java 2.16 runs the
  stateful streaming models (`VAR_HANDLE`, `READ_VARIABLE`, `ASSIGN_VARIABLE`, `CALL_ONCE`). They
  run in LiteRT on the host.
- How much of the 37 points is capture alone? The app can already run capture without inference:
  an empty wake word list closes the model while the recorder keeps running (`VoiceEngine.kt:93`,
  `:122`). Whether HA's wake word select can send an empty list is untested. Otherwise it needs a
  test build. Either way, measure it with `threadcpu.py` as in step 7.
- Does inference at audio priority cost dashboard frames? Compare the perf test's main-thread fps
  with the mic muted and unmuted (step 6 of the panel plan; not run yet).
- The Mirror (Android 6, slower CPU) will be worse with openWakeWord. Not measured.
