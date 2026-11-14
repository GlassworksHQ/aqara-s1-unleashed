# Camera presence: which detector wakes the screen, at what cost?

**Status (2026-11-14): decided, not yet run on a device.** Desk research and model analysis for the
camera pane (#117), then the maintainer's decision: presence comes from **lighting-insensitive motion
detection**, the `MotionDetectorCore` of the maintainer's android-ip-camera, not from a person model.
The TFLite person model and its download are dropped. No frame with a camera has run it yet. The S1
has no camera.

Device: none. The CPU estimates are scaled from the S1's measured TFLite numbers (A133, 4× Cortex-A53).
Tools: `tflite` 2.18.0 (the flatbuffer schema, Python) to read the models; the script is in
[How to reproduce](#how-to-reproduce).

## Question

A panel with a camera and no presence sensor (a tablet, the Mirror) should wake its screen when
someone walks up. No frame may leave the panel for that. We need a detector that:

- runs on a 32-bit A53-class CPU next to Gecko, all day, at a low frame rate;
- has a licence we can ship under (no GPL, no terms that send data to a vendor);
- adds little to the APK.

## What we tried

1. **ML Kit face detection** (2026-11-13, read: [ML Kit terms](https://developers.google.com/ml-kit/terms),
   [face detection on Android](https://developers.google.com/ml-kit/vision/face-detection/android))
   - Closed source under the Google APIs Terms of Service. The terms forbid reverse engineering, models
     included.
   - "The ML Kit APIs also send metrics about the performance and utilization of the APIs in your app
     to Google", and the app must tell its users. A panel that promises "no cloud" can't say that.
   - The bundled model adds about 6.9 MB. The unbundled one (about 800 KB) needs Google Play services,
     which the S1 and the Mirror's community image don't have.
   - **Ruled out** by the principles (no cloud, no account), not by its quality.

2. **The TFLite Micro person model** (`person_detect.tflite`, 2026-11-13, read and parsed)
   - From [tensorflow/tflite-micro](https://github.com/tensorflow/tflite-micro) (Apache-2.0), at
     `tensorflow/lite/micro/models/person_detect.tflite`. 300 568 bytes, SHA-256 `808cfdfc…502e9d`.
   - A MobileNet v1 trained for "is there a person in the picture" (the Visual Wake Words task),
     with whole-body poses, not only faces.
   - Parsed with the script below:
     ```
     in:  input [1, 96, 96, 1] i8 scale=0.00784314 zp=-1
     out: MobilenetV1/Predictions/Reshape_1 [1, 2] i8 scale=0.00390625 zp=-128
     ops: DEPTHWISE_CONV_2D 14, CONV_2D 14, AVERAGE_POOL_2D 1, RESHAPE 1, SOFTMAX 1
     MACs: 7.2 M
     ```
   - The input is one grey channel: a pixel `p` (0–255) goes in as `p − 128`. The output's index 1 is
     "person" (`kPersonIndex = 1` in the example's `model_settings.h`), as `(q + 128) / 256`.
   - Only standard builtin ops, so the full TFLite runtime runs it too. The app already ships that
     runtime for microWakeWord (`org.tensorflow:tensorflow-lite:2.16.1`).

3. **MediaPipe BlazeFace short range** (`blaze_face_short_range.tflite`, 2026-11-13, read and parsed)
   - From MediaPipe's model storage; MediaPipe is Apache-2.0. 229 746 bytes, SHA-256 `b4578f35…40152f`.
     Check the model card's licence before shipping it.
   - Parsed with the same script:
     ```
     in:  input [1, 128, 128, 3] f32
     out: regressors [1, 896, 16] f32, classificators [1, 896, 1] f32
     ops: CONV_2D 21, DEPTHWISE_CONV_2D 16, DEQUANTIZE 74, ADD 16, …
     MACs: 30.8 M
     ```
   - Float16 weights, float maths, colour input, anchor decoding and non-max suppression after it.
     Faces only, for people within about 2 m who look at the panel.

4. **Detectors with no model** (2026-11-13, read, untested)
   - Camera2's own face detection (`STATISTICS_FACE_DETECT_MODE_SIMPLE`): no APK cost and no CPU in
     the app, but only where the camera HAL offers it. A frame can query
     `STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES` and use it when present.
   - Android's old `android.media.FaceDetector`: in the platform, frontal faces only, needs an
     `RGB_565` bitmap. Not measured.
   - Frame differencing (motion): cheap, but plain differencing lets a lamp, dusk or a TV wake the
     screen. The maintainer's detector (below) is differencing built to ignore those.

## Result

| | Person model (TFLM) | BlazeFace short range | ML Kit faces |
|---|---|---|---|
| Licence | Apache-2.0 | Apache-2.0 (confirm the card) | Proprietary, sends usage metrics to Google |
| Size | 0.30 MB model, runtime already in the APK | 0.23 MB model, runtime already in the APK | 6.9 MB bundled |
| Input | 96 × 96 grey, int8 | 128 × 128 colour, float | any |
| Work per frame | 7.2 M MACs | 30.8 M MACs + decoding | not known |
| Finds | a person, any pose | a face, within about 2 m | a face |
| CPU at 2 frames a second (estimate) | about 1% of one core or less | about 5% of one core | not known |

**The CPU estimates are inferred, not measured.** On the S1, float TFLite with XNNPACK runs at about
one multiply-add per cycle: openWakeWord's 44.3 M MACs take 36 ms a frame
([idle-cpu.md](../../frames/aqara-s1-plus/research/idle-cpu.md), steps 5 and 7). At that rate the
person model is about 6 ms a frame in float, and int8 should be faster; BlazeFace is about 25 ms. At
2 frames a second that is roughly 1% and 5% of one core. Not counted: the camera itself (the HAL, the
ISP and the YUV buffers), which may cost more than the model. Only a device with a camera can tell.

**Superseded on 2026-11-14.** The pick above was the person model. The maintainer decided instead
that presence works like their own tested implementation: `MotionDetectorCore` from
[aguilaair/android-ip-camera](https://github.com/aguilaair/android-ip-camera) (`onvif-rtsp-motion`,
`7b5e6f28`, MIT), ported unchanged to `app/kiosk/src/main/java/dev/glassworks/camera/`. The same
motion feeds the panel's presence and the ONVIF motion events
([onvif-rtsp.md](onvif-rtsp.md#result)). The table above stays as the record of what was weighed.

How it works (read from the code, 2026-11-14):

| Step | What it does |
|---|---|
| Grid | Each frame becomes 48 × 27 cells, each the mean of 16 luma samples (4 × 4) |
| Background | A slowly adapting copy, 5% toward each new frame, so dusk fades into it |
| Brightness shift | The frame-wide mean change is taken out first, so auto-exposure drift isn't a change |
| Changed cell | Over 34 − 2.6 × sensitivity luma levels after that (about 31 at 1, 8 at 10) |
| Changed frame | At least 4% ÷ sensitivity of the cells (4% at 1, 0.4% at 10) |
| Lighting change | Over 50% of cells changed, or the mean jumped over 35%: the background is re-seeded and detection pauses for 5 frames, instead of counting as motion |
| Start, end | Motion after 2 changed frames in a row; it ends 8 s after the last |

Glassworks runs it at 5 frames a second (its 200 ms sampling) with sensitivity 1 to 10 from the
`camera_sensitivity` number entity (default 5). `CameraPresenceTest` pins the lighting cases on whole
frames: a lamp switching on, the camera re-exposing after it, a five-minute dusk, an auto-exposure
step with a bright window, a TV cutting between scenes, and a person-sized change that is still seen.

**Cost (estimated, not measured):** 20 736 byte reads and about 4 000 arithmetic steps on 1 296 cells
per frame, so about 0.1 million operations a second at 5 frames a second. Negligible next to the
camera pipeline. Nothing is downloaded, and the APK grows by the ported classes only.

The engine (#117) in `app/kiosk/src/main/java/dev/glassworks/camera/`:

- `CameraPresence` is a `PresenceSource`. It takes frames from a `FrameSource` at about 5 a second and
  drops the rest, and gives them to `MotionDetectorCore`. Frames go nowhere else.
- The Camera2 `FrameSource` comes with the pane, once there is a device to run it on.

## How to reproduce

1. Download the models into a new empty directory (read-only, nothing on a device):
   ```
   curl -sfLO https://raw.githubusercontent.com/tensorflow/tflite-micro/main/tensorflow/lite/micro/models/person_detect.tflite
   curl -sfLO https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite
   shasum -a 256 *.tflite
   ```
2. Count the work with [`tools/camera/tflite-macs.py`](../../tools/camera/tflite-macs.py), in a
   venv with `pip install tflite==2.18.0 numpy`:
   ```
   python -I tools/camera/tflite-macs.py person_detect.tflite blaze_face_short_range.tflite
   ```
3. Measure the real cost on an A133 without a camera (RAM and `/data/local/tmp` only, no app change):
   push TensorFlow's prebuilt `android_arm_benchmark_model` and the model, then
   ```
   adb shell /data/local/tmp/benchmark_model --graph=/data/local/tmp/person_detect.tflite --num_threads=1 --use_xnnpack=true
   ```
   Undo: `adb shell rm /data/local/tmp/benchmark_model /data/local/tmp/person_detect.tflite`.

## Open questions

- The camera pipeline's CPU at 5 frames a second on a device with a camera.
- How the detector behaves in a dark room, where the picture is noisy and the panel's own screen may
  be the only light. Sensitivity 5 needs about 21 levels of change per cell.
- A pet or a curtain still counts as motion: the detector doesn't know what moved.
- Whether a frame should combine the camera with a better sensor (the issue's "frame setting"). The
  approved design uses the camera only when the frame has no other presence source.
