"""Profile banners for WM Keyboard's social accounts.

Run:  python3 gen_social_banners.py [OUTDIR]
Out:  ../social/*.png (+ the .svg each is rendered from)
Needs rsvg-convert and Inter installed (fontconfig resolves "Inter").
"""
import math, subprocess, sys, os
from PIL import ImageFont

INTER = os.path.expanduser("~/Library/Fonts/Inter-VariableFont_opsz,wght.ttf")
FONT = "Inter, sans-serif"

def text_w(s, size, weight, spacing=0.0):
    f = ImageFont.truetype(INTER, int(size))
    try:
        f.set_variation_by_axes([min(max(size, 14), 32), weight])
    except Exception:
        pass
    return f.getlength(s) + spacing * (len(s) - 1)

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
STAGGER = [0.0, 0.5, 1.5]
TRAIL = "ship"

def keyboard(x0, y0, k, gap, highlight, trail, cap_fill, cap_stroke, label, opacity=1.0, labels=True):
    """Return svg for keyboard whose top-left is x0,y0, plus key centres."""
    out, centres = [], {}
    pitch = k + gap
    r = k * 0.2
    for ri, row in enumerate(ROWS):
        for ci, ch in enumerate(row):
            x = x0 + (STAGGER[ri] + ci) * pitch
            y = y0 + ri * pitch
            centres[ch] = (x + k / 2, y + k / 2)
            hot = ch in highlight
            fill = "#2A2363" if hot else cap_fill
            stroke = "#7C6CFF" if hot else cap_stroke
            out.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{k:.1f}" height="{k:.1f}" rx="{r:.1f}" fill="{fill}" stroke="{stroke}" stroke-width="{max(1, k/40):.1f}"/>')
            if labels:
                col = "#FFFFFF" if hot else label
                out.append(f'<text x="{x+k/2:.1f}" y="{y+k*0.64:.1f}" text-anchor="middle" font-family="{FONT}" font-size="{k*0.4:.1f}" font-weight="600" fill="{col}">{ch.upper()}</text>')
    # space row
    y = y0 + 3 * pitch
    sx = x0 + 2.5 * pitch
    out.append(f'<rect x="{sx:.1f}" y="{y:.1f}" width="{5*pitch-gap:.1f}" height="{k:.1f}" rx="{r:.1f}" fill="{cap_fill}" stroke="{cap_stroke}" stroke-width="{max(1, k/40):.1f}"/>')
    trail_svg = ""
    if trail:
        pts = [centres[c] for c in trail]
        d = f"M{pts[0][0]:.1f},{pts[0][1]:.1f}"
        # Catmull-Rom -> cubic bezier for a smooth swipe
        P = [pts[0]] + pts + [pts[-1]]
        for i in range(1, len(P) - 2):
            p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
            c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
            d += f" C{c1[0]:.1f},{c1[1]:.1f} {c2[0]:.1f},{c2[1]:.1f} {p2[0]:.1f},{p2[1]:.1f}"
        w = k * 0.22
        trail_svg = (
            f'<path d="{d}" fill="none" stroke="url(#trail)" stroke-width="{w*2.6:.1f}" stroke-linecap="round" stroke-linejoin="round" opacity="0.25" filter="url(#blur)"/>'
            f'<path d="{d}" fill="none" stroke="url(#trail)" stroke-width="{w:.1f}" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<circle cx="{pts[-1][0]:.1f}" cy="{pts[-1][1]:.1f}" r="{w*1.1:.1f}" fill="#E0F2FE"/>'
        )
    width = 10 * pitch - gap
    height = 4 * pitch - gap
    g = f'<g opacity="{opacity}">' + "".join(out) + trail_svg + "</g>"
    return g, width, height

def make(name, W, H, safe, out_dir, align="split"):
    """safe = (x, y, w, h): region guaranteed visible on every device."""
    sx, sy, sw, sh = safe
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
             '<defs>',
             '<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1B1E3A"/><stop offset="1" stop-color="#12141F"/></linearGradient>',
             '<radialGradient id="glow" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#7C5CFF" stop-opacity="0.45"/><stop offset="1" stop-color="#7C5CFF" stop-opacity="0"/></radialGradient>',
             '<radialGradient id="glow2" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#38BDF8" stop-opacity="0.22"/><stop offset="1" stop-color="#38BDF8" stop-opacity="0"/></radialGradient>',
             '<linearGradient id="wm" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#A99BFF"/><stop offset="1" stop-color="#5CC8FA"/></linearGradient>',
             '<linearGradient id="trail" x1="0" y1="0" x2="1" y2="0" gradientUnits="objectBoundingBox"><stop offset="0" stop-color="#8B7CFF" stop-opacity="0.2"/><stop offset="1" stop-color="#7DD3FC"/></linearGradient>',
             '<linearGradient id="bar" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#6C5CE7"/><stop offset="1" stop-color="#0EA5E9"/></linearGradient>',
             '<filter id="blur" x="-20%" y="-50%" width="140%" height="200%"><feGaussianBlur stdDeviation="6"/></filter>',
             '</defs>',
             f'<rect width="{W}" height="{H}" fill="url(#bg)"/>']

    # Faint keycap field across the whole canvas (crop-safe decoration).
    k_bg = min(max(36, H / 7), 72)
    gap_bg = k_bg * 0.22
    pitch = k_bg + gap_bg
    cols = int(W / pitch) + 2
    rows = int(H / pitch) + 2
    field = []
    for r in range(rows):
        off = (r % 3) * pitch * 0.5
        for c in range(-1, cols):
            x = c * pitch + off - pitch * 0.3
            y = r * pitch - pitch * 0.4
            field.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{k_bg:.1f}" height="{k_bg:.1f}" rx="{k_bg*0.2:.1f}" fill="none" stroke="#FFFFFF" stroke-opacity="0.035" stroke-width="1.5"/>')
    parts.append("".join(field))

    # Content sizing from safe area.
    title = "WM Keyboard"
    tag = "Privacy-first Android keyboard"
    feats = ["Glide typing", "Hundreds of languages", "Offline AI", "Open source"]

    # Title size: fit height first, then width budget.
    text_budget_w = sw * (0.55 if align == "split" else 0.9)
    ts = sh * 0.30
    while text_w(title, ts, 800, -ts * 0.02) > text_budget_w and ts > 10:
        ts *= 0.96
    tag_s = ts * 0.34
    chip_s = ts * 0.21
    show_chips = sh / ts > 2.3

    # Keyboard cluster sizing.
    kb_w_budget = sw * 0.40
    kb_h_budget = sh * 0.80
    k = min(kb_w_budget / (10 + 9 * 0.18), kb_h_budget / (4 + 3 * 0.18))
    gap = k * 0.18
    kb_w = 10 * (k + gap) - gap
    kb_h = 4 * (k + gap) - gap

    block_h = ts * 0.78 + ts * 0.32 + tag_s + (ts * 0.42 + chip_s * 1.9 if show_chips else 0)
    title_w = text_w(title, ts, 800, -ts * 0.02)
    tag_w = text_w(tag, tag_s, 500)
    block_w = max(title_w, tag_w)

    if align == "split":
        between = sw * 0.06
        total = block_w + between + kb_w
        tx = sx + (sw - total) / 2
        kx = tx + block_w + between
    else:
        tx = sx + (sw - block_w) / 2
        kx = None
    ty = sy + (sh - block_h) / 2

    # Glows behind the content.
    gcx, gcy = tx + block_w * 0.3, sy + sh / 2
    gr = max(sh, 300) * 1.3
    parts.append(f'<ellipse cx="{gcx:.0f}" cy="{gcy:.0f}" rx="{gr*1.4:.0f}" ry="{gr:.0f}" fill="url(#glow)"/>')
    if kx is not None:
        parts.append(f'<ellipse cx="{kx+kb_w/2:.0f}" cy="{gcy:.0f}" rx="{kb_w*0.8:.0f}" ry="{kb_h*0.9:.0f}" fill="url(#glow2)"/>')

    # Title block.
    y = ty + ts * 0.78
    anchor = "start" if align == "split" else "middle"
    ax = tx if align == "split" else sx + sw / 2
    parts.append(f'<text x="{ax:.1f}" y="{y:.1f}" text-anchor="{anchor}" font-family="{FONT}" font-size="{ts:.1f}" font-weight="800" letter-spacing="{-ts*0.02:.2f}" fill="#FFFFFF">WM <tspan fill="url(#wm)">Keyboard</tspan></text>')
    y += ts * 0.32 + tag_s
    parts.append(f'<text x="{ax:.1f}" y="{y:.1f}" text-anchor="{anchor}" font-family="{FONT}" font-size="{tag_s:.1f}" font-weight="500" fill="#C3C8DA">{tag}</text>')
    if show_chips:
        y += ts * 0.42
        ch_h = chip_s * 1.9
        pad = chip_s * 0.9
        ws = [text_w(f, chip_s, 600) + pad * 2 for f in feats]
        cgap = chip_s * 0.6
        # drop chips that don't fit the text column
        limit = block_w if align == "split" else sw * 0.9
        while sum(ws) + cgap * (len(ws) - 1) > limit and len(ws) > 2:
            ws.pop(); feats = feats[:len(ws)]
        row_w = sum(ws) + cgap * (len(ws) - 1)
        cx = tx if align == "split" else sx + (sw - row_w) / 2
        for f, w in zip(feats, ws):
            parts.append(f'<rect x="{cx:.1f}" y="{y:.1f}" width="{w:.1f}" height="{ch_h:.1f}" rx="{ch_h/2:.1f}" fill="#FFFFFF" fill-opacity="0.06" stroke="#8B7CFF" stroke-opacity="0.45" stroke-width="1.2"/>')
            parts.append(f'<text x="{cx+w/2:.1f}" y="{y+ch_h*0.66:.1f}" text-anchor="middle" font-family="{FONT}" font-size="{chip_s:.1f}" font-weight="600" fill="#DCDDF5">{f}</text>')
            cx += w + cgap

    if kx is not None:
        ky = sy + (sh - kb_h) / 2
        g, _, _ = keyboard(kx, ky, k, gap, set(TRAIL), TRAIL, "#1C1F33", "#33384F", "#8D94AB")
        parts.append(g)

    # Brand bar along the bottom edge.
    parts.append(f'<rect x="0" y="{H - max(4, H*0.012):.1f}" width="{W}" height="{max(4, H*0.012):.1f}" fill="url(#bar)"/>')
    parts.append("</svg>")

    svg = os.path.join(out_dir, name + ".svg")
    png = os.path.join(out_dir, name + ".png")
    with open(svg, "w") as fh:
        fh.write("\n".join(parts))
    subprocess.run(["rsvg-convert", "-w", str(W), "-h", str(H), "-o", png, svg], check=True)
    print(png)

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "social")
    os.makedirs(out, exist_ok=True)
    # Reddit: 1920x384. Mobile crops the sides hard; community icon sits bottom-left.
    make("reddit-1920x384", 1920, 384, (300, 40, 1320, 304), out)
    # Reddit's newer banner editor crops to ~8.3:1 (measured from its crop box).
    # The community card on the sidebar/mobile shows only the centre ~3.6:1
    # (~840px of 1920), so everything sits in the middle 780px.
    make("reddit-wide-1920x232", 1920, 232, (570, 16, 780, 200), out)
    # Mastodon / Bluesky / X: 1500x500 (3:1). Avatar overlaps bottom-left.
    make("header-1500x500", 1500, 500, (330, 60, 1080, 330), out)
    # YouTube: 2560x1440, everything must sit in the 1546x423 centre safe area.
    make("youtube-2560x1440", 2560, 1440, (507, 509, 1546, 423), out)
    # Facebook cover: 1640x624 upload (820x312 desktop, crops sides on mobile).
    make("facebook-1640x624", 1640, 624, (260, 90, 1120, 400), out)
    # LinkedIn company cover: 1128x191. Logo overlaps bottom-left.
    make("linkedin-1128x191", 1128, 191, (250, 20, 820, 151), out)
    # Discord server banner: 960x540.
    make("discord-960x540", 960, 540, (60, 120, 840, 300), out)
