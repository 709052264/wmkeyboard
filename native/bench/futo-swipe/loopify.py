"""Real swipe.futo.org strokes of words with a doubled letter, redrawn with a
loop on the doubled key, the way SwipeCorpus draws one (a circle of 0.3 key
widths through the key, off to the side of the way on), at a steady 8 ms a
point. The rest of the stroke is the person's own.

usage: loopify.py <test.jsonl> <qwerty.json> <out.jsonl> [mode]
mode "double" (default): a loop on the doubled key of a word with one.
mode "stray": a loop on the first inner key of a word with no doubled letter,
  a curl that marks nothing.
"""
import json, math, re, sys

src, layout, out = sys.argv[1:4]
mode = sys.argv[4] if len(sys.argv) > 4 else "double"
lay = {k["letter"]: (k["cx"], k["cy"]) for k in json.load(open(layout))["keys"]}
R, N, DT = 0.3, 32, 8

def align(cent, pts):
    m, n = len(cent), len(pts)
    INF = float("inf")
    cost = [[(cent[i][0] - pts[j][0]) ** 2 + (cent[i][1] - pts[j][1]) ** 2 for j in range(n)] for i in range(m)]
    dp = [[INF] * n for _ in range(m)]
    back = [[0] * n for _ in range(m)]
    for j in range(n):
        dp[0][j] = cost[0][j] if j == 0 else INF
    for i in range(1, m):
        best, arg = INF, 0
        for j in range(n):
            if dp[i - 1][j] < best:
                best, arg = dp[i - 1][j], j
            dp[i][j] = best + cost[i][j]
            back[i][j] = arg
    j = n - 1
    at = [0] * m
    for i in range(m - 1, -1, -1):
        at[i] = j
        j = back[i][j]
    return at

wrote = 0
with open(src) as fin, open(out, "w") as fo:
    for ln in fin:
        r = json.loads(ln)
        w = r["word"].lower()
        if not re.fullmatch(r"[a-z]{2,}", w): continue
        W, H = float(r["canvas_width"]), float(r["canvas_height"])
        kw = W * 0.1
        squeezed = re.sub(r"(.)\1+", r"\1", w)
        if mode == "double":
            m = re.search(r"(.)\1", w)
            if not m: continue
            target = len(re.sub(r"(.)\1+", r"\1", w[: m.start() + 1])) - 1
        else:
            if re.search(r"(.)\1", w) or len(squeezed) < 3: continue
            target = 1
        pts = [(p["x"] * W, p["y"] * H, p["t"]) for p in r["data"]]
        if len(pts) < 3: continue
        cent = [(lay[c][0] * W, lay[c][1] * H) for c in squeezed]
        at = align(cent, pts)
        j = at[target]
        ax, ay, at_t = pts[j]
        k = j + 1
        while k < len(pts) and math.hypot(pts[k][0] - ax, pts[k][1] - ay) < 1e-3: k += 1
        if k < len(pts):
            dx, dy = pts[k][0] - ax, pts[k][1] - ay
        else:
            dx, dy = ax - pts[j - 1][0], ay - pts[j - 1][1]
        ln_ = math.hypot(dx, dy) or 1.0
        dx, dy = dx / ln_, dy / ln_
        rad = R * kw
        cx, cy = ax - dy * rad, ay + dx * rad
        start = math.atan2(ay - cy, ax - cx)
        loop = []
        for s in range(1, N + 1):
            a = start + 2 * math.pi * s / N
            loop.append((cx + rad * math.cos(a), cy + rad * math.sin(a), at_t + s * DT))
        shift = N * DT
        new = pts[: j + 1] + loop + [(x, y, t + shift) for x, y, t in pts[j + 1 :]]
        r["data"] = [{"t": t, "x": x / W, "y": y / H} for x, y, t in new]
        fo.write(json.dumps(r) + "\n")
        wrote += 1
print("wrote", wrote)
