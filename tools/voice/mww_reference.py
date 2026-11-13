"""Regenerate the microWakeWord test fixtures in app/kiosk/src/test/resources/.

Host only, touches no device. Writes two files:

- mww_clip.wav: 16 kHz mono speech synthesised with Piper's en_US-kristin-medium voice (trained from
  scratch on public-domain LibriVox recordings; the voice repository is MIT). It says each wake word
  and one sentence without a wake word, with low noise in between. No recording of a real person.
- mww_reference.json: the reference the Kotlin tests compare with.
  - "features": the TFLite Micro audio frontend's output (pymicro-features, the same C code ESPHome
    runs) for the clip and for a synthetic signal the test rebuilds itself, as the frontend's uint16
    values (pymicro-features returns them times 0.0390625, which is exact in float).
  - "segments": where each phrase starts and ends in the clip, in samples.
  - "max_probability": for each official model, the highest sliding-window mean pymicro-wakeword
    reaches in each segment (diagnostics; the tests check against each model's own cutoff).

Setup (a venv outside the repo; pass paths as arguments):

    uv venv /tmp/mww-venv && uv pip install -p /tmp/mww-venv/bin/python \\
        pymicro-features==2.0.2 pymicro-wakeword==2.5.0 piper-tts==1.8.0 scipy numpy
    # The voice, from https://huggingface.co/rhasspy/piper-voices (en/en_US/kristin/medium):
    #   en_US-kristin-medium.onnx (+ .onnx.json)
    # The models, as the panel fetches them (tools/voice/fetch-models.sh --no-push):
    #   ~/.cache/s1kiosk/microwakeword-<commit>/{okay_nabu,hey_jarvis,alexa,hey_mycroft}.{tflite,json}
    /tmp/mww-venv/bin/python -I tools/voice/mww_reference.py \\
        --voice /path/en_US-kristin-medium.onnx --models ~/.cache/s1kiosk/microwakeword-<commit> \\
        --out app/kiosk/src/test/resources
"""
import argparse, json, wave
from pathlib import Path

import numpy as np
from scipy.signal import resample_poly
from piper import PiperVoice, SynthesisConfig
from pymicro_features import MicroFrontend
import pymicro_wakeword
from pymicro_wakeword import MicroWakeWord

RATE = 16000
WORDS = ["okay_nabu", "hey_jarvis", "alexa", "hey_mycroft"]
PHRASES = [
    ("okay_nabu", "Okay, Nabu."),
    ("hey_jarvis", "Hey Jarvis."),
    ("alexa", "Alexa?"),
    ("hey_mycroft", "Hey Mycroft."),
    ("none", "Good morning, what is the weather?"),
]
LENGTH_SCALE = 1.3
LEVEL = 0.3
GAP = RATE * 6 // 10  # 0.6 s of low noise before the first phrase and after each one
FEATURE_SAMPLES = RATE * 5 // 2  # the frontend reference covers the first 2.5 s (the first phrase)
FLOAT32_SCALE = 0.0390625


def lcg(n, seed, span):
    """Same generator as the Kotlin tests (and tools/voice/oww_reference.py)."""
    out = np.empty(n, dtype=np.int16)
    x = seed
    for i in range(n):
        x = (x * 1103515245 + 12345) % 2147483648
        out[i] = (x >> 8) % (2 * span) - span
    return out


def synthetic():
    """Loud noise (saturates the window), medium noise under the noise estimate (the noise floor
    binds), quiet noise, digital silence, medium noise: every frontend path."""
    return np.concatenate([lcg(4000, 1, 32767), lcg(3200, 4, 2000), lcg(3200, 2, 40), np.zeros(2400, np.int16), lcg(4000, 3, 3000)])


def synth(voice, text):
    cfg = SynthesisConfig(noise_scale=0.0, noise_w_scale=0.0, length_scale=LENGTH_SCALE)
    pcm = b"".join(c.audio_int16_bytes for c in voice.synthesize(text, syn_config=cfg))
    sr = voice.config.sample_rate
    g = np.gcd(RATE, sr)
    y = resample_poly(np.frombuffer(pcm, np.int16).astype(np.float64), RATE // g, sr // g) * LEVEL
    return np.clip(np.round(y), -32768, 32767).astype(np.int16)


def frontend(samples):
    """uint16 frontend values, one 40-value row per 10 ms step, as MicroWakeWordFeatures feeds them."""
    fe = MicroFrontend()
    rows = []
    for i in range(0, len(samples) - 159, 160):
        out = fe.process_samples(samples[i:i + 160].tobytes())
        assert out.samples_read == 160
        if out.features:
            vals = [v / FLOAT32_SCALE for v in out.features]
            assert all(v == int(v) for v in vals)
            rows.append([int(v) for v in vals])
    return rows


def probabilities(model_dir, word, samples):
    """(sample index at the end of each inference, sliding-window mean) for one model over the clip."""
    lib = next((Path(pymicro_wakeword.__file__).parent / "lib").glob("*tensorflowlite_c*"))
    m = MicroWakeWord.from_config(Path(model_dir) / f"{word}.json", libtensorflowlite_c_path=lib)
    fe = MicroFrontend()
    out = []
    for i in range(0, len(samples) - 159, 160):
        r = fe.process_samples(samples[i:i + 160].tobytes())
        if not r.features:
            continue
        p = m.process_streaming_prob(np.array(r.features).reshape((1, 1, 40)))
        out.append((i + 160, p))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voice", required=True)
    ap.add_argument("--models", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    voice = PiperVoice.load(a.voice)
    rng = np.random.default_rng(0)
    parts, segments, pos = [], {}, 0
    for name, text in PHRASES:
        gap = np.round(rng.standard_normal(GAP) * 30).astype(np.int16)
        speech = synth(voice, text)
        parts += [gap, speech]
        segments[name] = [pos + GAP, pos + GAP + len(speech)]
        pos += GAP + len(speech)
    tail = np.round(rng.standard_normal(GAP) * 30).astype(np.int16)
    clip = np.concatenate(parts + [tail])

    out = Path(a.out)
    with wave.open(str(out / "mww_clip.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(clip.astype("<i2").tobytes())

    # A detection may land up to ~0.5 s after the phrase ends (the sliding window and model latency).
    late = RATE // 2
    max_prob = {}
    for word in WORDS:
        probs = probabilities(a.models, word, clip)
        max_prob[word] = {
            name: round(max((p for t, p in probs if s <= t < e + late), default=0.0), 4)
            for name, (s, e) in segments.items()
        }

    ref = {
        "generator": "tools/voice/mww_reference.py",
        "voice": Path(a.voice).name,
        "features": {"clip": frontend(clip[:FEATURE_SAMPLES]), "synthetic": frontend(synthetic())},
        "segments": segments,
        "max_probability": max_prob,
    }
    (out / "mww_reference.json").write_text(json.dumps(ref, separators=(",", ":")) + "\n")
    for word in WORDS:
        print(word, max_prob[word])


if __name__ == "__main__":
    main()
