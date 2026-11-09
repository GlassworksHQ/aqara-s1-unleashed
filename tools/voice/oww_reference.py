"""Regenerate app/kiosk/src/test/resources/oww_reference.json.

Runs openWakeWord's real streaming feature code (`AudioFeatures` from
https://raw.githubusercontent.com/dscripka/openWakeWord/main/openwakeword/utils.py) with
deterministic fake melspectrogram/embedding/wake models, so OwwFeaturesTest can check that the
Kotlin port buffers, windows and caps exactly like upstream. Only numpy is needed:

    curl -fsSLo /tmp/oww_utils.py https://raw.githubusercontent.com/dscripka/openWakeWord/main/openwakeword/utils.py
    python3 -I tools/voice/oww_reference.py /tmp/oww_utils.py app/kiosk/src/test/resources/oww_reference.json
"""
import sys, types, json, math
import numpy as np

utils_path, out_path = sys.argv[1], sys.argv[2]
for name in ("tqdm", "openwakeword", "requests"):
    m = types.ModuleType(name); m.tqdm = lambda *a, **k: None; sys.modules[name] = m
src = open(utils_path).read()
mod = types.ModuleType("oww_utils"); mod.__file__ = utils_path; exec(compile(src, utils_path, "exec"), mod.__dict__)
AudioFeatures = mod.AudioFeatures

def lcg(n, seed, span):
    out = np.empty(n, dtype=np.int16); x = seed
    for i in range(n):
        x = (x * 1103515245 + 12345) % 2147483648
        out[i] = (x >> 8) % (2 * span) - span
    return out

def fake_mel(x):            # x: (1, n) float32 -> (1, 1, F, 32) like the tflite model
    n = x.shape[1]; F = math.ceil(n / 160) - 3
    out = np.zeros((1, 1, F, 32), dtype=np.float32)
    for j in range(F):
        s = float(np.sum(x[0, j*160 : j*160+400 : 40], dtype=np.float64))
        for b in range(32):
            out[0, 0, j, b] = s / (1000.0 * (b + 1)) + b * 0.01
    return out

def fake_emb(x):            # x: (B, 76, 32, 1) -> squeeze((B, 1, 1, 96))
    x = x[..., 0].astype(np.float64)
    B = x.shape[0]; out = np.zeros((B, 1, 1, 96), dtype=np.float32)
    w = (np.arange(76) + 1) / 76.0
    for bi in range(B):
        for d in range(96):
            out[bi, 0, 0, d] = float(np.sum(x[bi, :, d % 32] * w)) + d * 0.001
    return out.squeeze()

def fake_wake(f):           # f: (16, 96)
    return float(np.sum(f.astype(np.float64) * (np.arange(16)[:, None] + 1))) / 1000.0

af = object.__new__(AudioFeatures)
af.melspec_model_predict = fake_mel
af.embedding_model_predict = fake_emb
af.raw_data_buffer = mod.deque(maxlen=16000*10)
af.melspectrogram_buffer = np.ones((76, 32))
af.melspectrogram_max_len = 10*97
af.accumulated_samples = 0
af.raw_data_remainder = np.empty(0)
af.feature_buffer = af._get_embeddings(lcg(16000*4, 7, 1000))
af.feature_buffer_max_len = 120
init_rows = af.feature_buffer.shape[0]

audio = lcg(1280 * 120, 12345, 10000)
scores = []
for i in range(120):
    af(audio[i*1280:(i+1)*1280])
    scores.append(fake_wake(af.get_features(16)[0]))

json.dump({
    "init_feature_rows": int(init_rows),
    "mel_rows": int(af.melspectrogram_buffer.shape[0]),
    "feature_rows": int(af.feature_buffer.shape[0]),
    "scores": scores,
    "last_features": af.get_features(16)[0].astype(float).round(6).flatten().tolist(),
}, open(out_path, "w"))
print("init rows", init_rows, "mel", af.melspectrogram_buffer.shape, "feat", af.feature_buffer.shape, "score[0..3]", scores[:3], "score[-1]", scores[-1])
