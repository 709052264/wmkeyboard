"""Writes check inputs for imagegen_cli and compares its outputs with MNN's
Python bindings on the same bundle. usage: check.py prepare|compare <bundle> <work> [prefix]"""
import json, os, sys
import numpy as np

mode, bundle, work = sys.argv[1:4]
prefix = sys.argv[4] if len(sys.argv) > 4 else ""
meta = json.load(open(f"{bundle}/{prefix}wmimage.json"))
LAT = meta["size"] // 8
if mode == "prepare":
    rng = np.random.default_rng(7)
    ids = np.full((1, 77), meta["pad"], np.int32)
    ids[0, :8] = [meta["bos"], 320, 2242, 2368, 3309, 22385, 1229, meta["eos"]]  # "a cute cat wearing sunglasses"
    ids.tofile(f"{work}/ids.i32")
    rng.standard_normal((1, 4, LAT, LAT)).astype(np.float32).tofile(f"{work}/x.f32")
    np.array([float((meta["timesteps"] or [999])[0])], np.float32).tofile(f"{work}/t.f32")
    sys.exit(0)
import MNN.expr as F, MNN.nn as nn
rt = nn.create_runtime_manager(({"backend": "CPU", "precision": "normal", "numThread": 4},))
def py(f, names, arrs):
    m = nn.load_module_from_file(f"{bundle}/{prefix}{f}", names, [], runtime_manager=rt)
    vs = [F.const(a, list(a.shape), F.NCHW, F.int if a.dtype == np.int32 else F.float) for a in arrs]
    return F.convert(m.onForward(vs)[0], F.NCHW).read().copy()
ids = np.fromfile(f"{work}/ids.i32", np.int32).reshape(1, 77)
x = np.fromfile(f"{work}/x.f32", np.float32).reshape(1, 4, LAT, LAT)
t = np.fromfile(f"{work}/t.f32", np.float32)
h = py("text_encoder.mnn", ["ids"], [ids])
y = py("unet.mnn", ["x", "t", "e"], [x, t, h])
img = py("vae_decoder.mnn", ["z"], [y])
worst = 0.0
for name, ref in (("h", h), ("y", y), ("img", img)):
    got = np.fromfile(f"{work}/{name}.f32", np.float32)
    d = float(np.abs(got - ref.ravel()).max())
    worst = max(worst, d)
    print(f"{name}: {ref.shape} max abs diff {d:.2e}")
sys.exit(0 if worst < 1e-3 else 1)
