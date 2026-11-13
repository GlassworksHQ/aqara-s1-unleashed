#!/usr/bin/env python3
"""Work and host time per wake word model (MIT). Host only: no adb, nothing touches a panel.

For docs/research/idle-cpu.md (#105). For each .tflite: its input, the multiply-accumulates of its
conv, fully-connected and batch-matmul ops, and the mean time of one call (LiteRT, one thread,
XNNPACK). The host time is only good for ratios between models; the panel is far slower.

  tools/voice/fetch-models.sh --no-push    # or download the files and check them against ModelFetcher.FILES
  uv run --with ai-edge-litert --with numpy python -I tools/voice/model_cost.py \\
      melspectrogram.tflite embedding_model.tflite hey_jarvis_v0.1.tflite okay_nabu.tflite

The openWakeWord mel model is resized to the app's 1760-sample input, as TfliteWakeWord does.
"""
import os
import sys
import time

import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType

MEL_INPUT = [1, 1280 + 160 * 3]


def interpreter(path, **kw):
    it = Interpreter(model_path=path, num_threads=1, **kw)
    if os.path.basename(path) == "melspectrogram.tflite":
        it.resize_tensor_input(0, MEL_INPUT, strict=True)
    it.allocate_tensors()
    return it


def macs(path):
    # Without the default delegate, so every op and tensor shape stays visible.
    it = interpreter(path, experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
    shape = {t["index"]: t["shape"] for t in it.get_tensor_details()}
    total = 0
    for op in it._get_ops_details():
        name, ins = op["op_name"], op["inputs"]
        if name not in ("CONV_2D", "DEPTHWISE_CONV_2D", "FULLY_CONNECTED", "BATCH_MATMUL"):
            continue
        out = int(np.prod(shape[op["outputs"][0]]))
        if name == "CONV_2D":
            total += out * int(np.prod(shape[ins[1]][1:]))
        elif name == "DEPTHWISE_CONV_2D":
            total += out * int(np.prod(shape[ins[1]][1:3]))
        elif name == "FULLY_CONNECTED":
            total += out * int(shape[ins[1]][-1])
        elif name == "BATCH_MATMUL":
            total += out * int(shape[ins[0]][-1])
    return total


def call_ms(path, calls=1000):
    it = interpreter(path)
    inp = it.get_input_details()[0]
    rng = np.random.default_rng(0)
    if np.issubdtype(inp["dtype"], np.integer):
        x = rng.integers(-128, 127, inp["shape"]).astype(inp["dtype"])
    else:
        x = rng.integers(-1000, 1000, inp["shape"]).astype(inp["dtype"])
    for _ in range(calls // 20):
        it.set_tensor(inp["index"], x)
        it.invoke()
    t = time.perf_counter()
    for _ in range(calls):
        it.set_tensor(inp["index"], x)
        it.invoke()
    return (time.perf_counter() - t) / calls * 1000, inp


def main(paths):
    if not paths:
        sys.exit(__doc__)
    print(f"{'model':28s} {'input':16s} {'MMAC':>7s} {'ms/call':>9s}")
    for p in paths:
        ms, inp = call_ms(p)
        dims = "x".join(str(d) for d in inp["shape"])
        print(f"{os.path.basename(p):28s} {dims + ' ' + inp['dtype'].__name__:16s} {macs(p) / 1e6:7.2f} {ms:9.4f}")


if __name__ == "__main__":
    main(sys.argv[1:])
