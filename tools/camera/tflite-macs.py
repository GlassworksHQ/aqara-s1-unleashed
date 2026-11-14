#!/usr/bin/env python3
# Prints a .tflite model's inputs, outputs, op counts and multiply-accumulates (conv, depthwise and
# fully connected layers), to compare detectors for the camera pane (docs/research/camera-presence.md).
# Read-only: reads the model files given, writes nothing, touches no device.
# Needs: pip install tflite==2.18.0 numpy. Usage: tflite-macs.py model.tflite [more.tflite ...]
import sys, collections, numpy as np, tflite
from tflite.BuiltinOperator import BuiltinOperator as B
names = {v: k for k, v in B.__dict__.items() if not k.startswith('_')}
TT = {0:'f32',1:'f16',2:'i32',3:'u8',9:'i8'}
for path in sys.argv[1:]:
    m = tflite.Model.GetRootAsModel(open(path,'rb').read(), 0)
    g = m.Subgraphs(0)
    shape = lambda t: [int(x) for x in g.Tensors(t).ShapeAsNumpy()]
    def desc(t):
        tt = g.Tensors(t); q = tt.Quantization()
        qs = f" scale={q.ScaleAsNumpy()[0]:.6g} zp={q.ZeroPointAsNumpy()[0]}" if q and q.ScaleLength() else ""
        return f"{tt.Name().decode()} {shape(t)} {TT.get(tt.Type(), tt.Type())}{qs}"
    ops = collections.Counter(); macs = 0
    for i in range(g.OperatorsLength()):
        op = g.Operators(i); code = m.OperatorCodes(op.OpcodeIndex())
        b = max(code.BuiltinCode(), code.DeprecatedBuiltinCode()); n = names.get(b, b); ops[n] += 1
        out = shape(op.Outputs(0))
        if n in ('CONV_2D', 'DEPTHWISE_CONV_2D'):
            w = shape(op.Inputs(1))  # conv: [O,kh,kw,I]; depthwise: [1,kh,kw,O]
            macs += int(np.prod(out)) * w[1] * w[2] * (w[3] if n == 'CONV_2D' else 1)
        elif n == 'FULLY_CONNECTED':
            macs += int(np.prod(shape(op.Inputs(1))))
    print(path)
    print("  in: ", [desc(g.Inputs(i)) for i in range(g.InputsLength())])
    print("  out:", [desc(g.Outputs(i)) for i in range(g.OutputsLength())])
    print("  ops:", dict(ops))
    print(f"  MACs: {macs/1e6:.1f} M")
