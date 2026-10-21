"""swipe.futo.org strokes of words of 6+ letters cut at a share of their
path length: a finger lifted early. Keeps the original word; adds
"cut": the share kept.

usage: truncate.py <test.jsonl> <out.jsonl> <share> [max]
"""
import json, math, re, sys
src, out, share = sys.argv[1], sys.argv[2], float(sys.argv[3])
limit = int(sys.argv[4]) if len(sys.argv) > 4 else 10**9
n = 0
with open(src) as fin, open(out, "w") as fo:
    for ln in fin:
        if n >= limit: break
        r = json.loads(ln)
        w = r["word"].lower()
        if not re.fullmatch(r"[a-z]{6,}", w): continue
        W, H = float(r["canvas_width"]), float(r["canvas_height"])
        pts = r["data"]
        if len(pts) < 6: continue
        arc = [0.0]
        for a, b in zip(pts, pts[1:]):
            arc.append(arc[-1] + math.hypot((b["x"] - a["x"]) * W, (b["y"] - a["y"]) * H))
        stop = arc[-1] * share
        keep = [p for p, s in zip(pts, arc) if s <= stop]
        if len(keep) < 3: continue
        r["data"] = keep
        r["cut"] = share
        fo.write(json.dumps(r) + "\n")
        n += 1
print("wrote", n)
