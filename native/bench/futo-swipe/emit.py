"""FUTO Swipe emissions for swipe.futo.org strokes, as the app computes them.

usage: emit.py <jsonl> <qwerty.json> <models dir> <out.bin> [max]
Each input line is a dataset record, or {"word","canvas_width","canvas_height","data":[{t,x,y}]}
with x, y in the unit canvas. Output, little endian: b"FSW1", u32 count, then per record
u16 word bytes, word utf-8, u32 session index, f32 W, f32 H, u32 n, n * (f32 x px, f32 y px, i32 t ms),
u16 steps, steps*27 f32 log-probs.
"""
import json, struct, sys, re
import numpy as np
import torch
from executorch.runtime import Runtime

src, layout, models, out = sys.argv[1:5]
limit = int(sys.argv[5]) if len(sys.argv) > 5 else 10**9
KEY_X = [0.10,0.60,0.40,0.30,0.25,0.40,0.50,0.60,0.75,0.70,0.80,0.90,0.80,0.70,0.85,0.95,0.05,0.35,0.20,0.45,0.65,0.50,0.15,0.30,0.55,0.20]
KEY_Y = [0.500,0.833,0.833,0.500,0.167,0.500,0.500,0.500,0.167,0.500,0.500,0.500,0.833,0.833,0.167,0.167,0.167,0.167,0.500,0.167,0.167,0.833,0.167,0.833,0.167,0.833]
lay = {k["letter"]: k for k in json.load(open(layout))["keys"]}
rt = Runtime.get()
enc = rt.load_program(f"{models}/honorable_sturgeon_model_fp32.pte").load_method("forward")
dec = rt.load_program(f"{models}/magic_macaw_model_fp32.pte").load_method("forward")
keys = torch.zeros(1, 64, 2)
for i in range(26):
    keys[0, i, 0] = KEY_X[i]; keys[0, i, 1] = KEY_Y[i]
mask = torch.zeros(1, 64, dtype=torch.bool); mask[0, :26] = True

def line(fr, to):
    fr, to = np.array(fr, np.float64), np.array(to, np.float64)
    mf, mt = fr.mean(), to.mean()
    a = ((fr - mf) * (to - mt)).sum() / ((fr - mf) ** 2).sum()
    return a, mt - a * mf

def features(xs, ys, ts):
    n = len(xs)
    dur = float(ts[-1] - ts[0])
    if dur > 0:
        count = max(2, int(round(dur / (1000 / 60))) + 1)
        step = dur / (count - 1)
        cx, cy = np.zeros(count), np.zeros(count)
        hi = 0
        for i in range(count):
            target = min(i * step, dur)
            while hi < n and ts[hi] - ts[0] < target: hi += 1
            if hi == 0: cx[i], cy[i] = xs[0], ys[0]
            elif hi >= n: cx[i], cy[i] = xs[-1], ys[-1]
            else:
                ta = ts[hi - 1] - ts[0]; span = ts[hi] - ts[0] - ta
                f = (target - ta) / span if span > 0 else 0
                cx[i] = xs[hi - 1] + f * (xs[hi] - xs[hi - 1]); cy[i] = ys[hi - 1] + f * (ys[hi] - ys[hi - 1])
    else:
        cx, cy = np.array(xs), np.array(ys)
    last = len(cx) - 1
    o = np.zeros(128, np.float32)
    for i in range(64):
        pos = i * last / 63; a = min(int(pos), last); b = min(a + 1, last); f = pos - a
        o[i] = min(max(cx[a] + f * (cx[b] - cx[a]), 0), 1)
        o[64 + i] = min(max(cy[a] + f * (cy[b] - cy[a]), 0), 1)
    return o

sessions = {}
recs = 0
with open(src) as fin, open(out, "wb") as fo:
    fo.write(b"FSW1"); fo.write(struct.pack("<I", 0))
    for ln in fin:
        if recs >= limit: break
        r = json.loads(ln)
        word = r["word"]
        if not re.fullmatch(r"[A-Za-z]{2,}", word): continue
        W, H = float(r["canvas_width"]), float(r["canvas_height"])
        pts = r["data"]
        if len(pts) < 3 or W <= 0 or H <= 0: continue
        kw = W * 0.1
        lx = [lay[c]["cx"] * W / kw for c in "abcdefghijklmnopqrstuvwxyz"]
        ly = [lay[c]["cy"] * H / kw for c in "abcdefghijklmnopqrstuvwxyz"]
        ax, bx = line(lx, KEY_X); ay, by = line(ly, KEY_Y)
        t0 = pts[0]["t"]
        px = [p["x"] * W for p in pts]; py = [p["y"] * H for p in pts]; ts = [p["t"] - t0 for p in pts]
        xs = [ax * (x / kw) + bx for x in px]; ys = [ay * (y / kw) + by for y in py]
        feat = torch.from_numpy(features(xs, ys, ts)).reshape(1, 2, 64)
        lg, co, gate = enc.execute([feat, keys, mask])
        steps = lg.shape[1]
        inp = torch.cat([lg[0, :, :26], lg[0, :, -1:], co[0], gate[0]], dim=1).unsqueeze(0).contiguous()
        lp = dec.execute([inp])[0][0].numpy().astype("<f4")
        sid = sessions.setdefault(r.get("session", ""), len(sessions))
        wb = word.encode()
        fo.write(struct.pack("<H", len(wb))); fo.write(wb)
        fo.write(struct.pack("<Iff", sid, W, H)); fo.write(struct.pack("<I", len(pts)))
        for x, y, t in zip(px, py, ts): fo.write(struct.pack("<ffi", x, y, int(t)))
        fo.write(struct.pack("<H", steps)); fo.write(lp.tobytes())
        recs += 1
        if recs % 2000 == 0: print(recs, flush=True)
    fo.seek(4); fo.write(struct.pack("<I", recs))
print("records", recs, "sessions", len(sessions))
