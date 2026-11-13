"""Runs one microWakeWord .tflite for MicroWakeWordClipTest, over stdin/stdout.

Prints one JSON line with the tensors' shape and quantization, then for every stride*40 int8 bytes
read from stdin, invokes the model (its streaming state carries over) and writes the raw uint8
output byte. Needs ai-edge-litert (or tensorflow) in the interpreter MWW_PYTHON names.
"""
import json, sys

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    import tensorflow as tf
    Interpreter = tf.lite.Interpreter
import numpy as np

it = Interpreter(model_path=sys.argv[1])
it.allocate_tensors()
inp, out = it.get_input_details()[0], it.get_output_details()[0]
stride = int(inp["shape"][1])
print(json.dumps({
    "stride": stride,
    "in_scale": float(inp["quantization"][0]), "in_zero": int(inp["quantization"][1]),
    "out_scale": float(out["quantization"][0]), "out_zero": int(out["quantization"][1]),
}), flush=True)
n = stride * 40
src, dst = sys.stdin.buffer, sys.stdout.buffer
while True:
    b = src.read(n)
    if len(b) < n:
        break
    it.set_tensor(inp["index"], np.frombuffer(b, dtype=np.int8).reshape(inp["shape"]))
    it.invoke()
    dst.write(bytes([int(it.get_tensor(out["index"]).ravel()[0])]))
    dst.flush()
