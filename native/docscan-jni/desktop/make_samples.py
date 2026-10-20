"""Synthetic phone photos of a page, with the page's true corners.

Each sample is a printed-looking page (title, paragraphs, a table, a coloured
box) set in perspective on a background that defeats one edge map or another:
a grey desk, wood grain, a white desk, a coloured cloth with the page's own
brightness, a page that runs off the frame, a dim photo with a hard shadow
across it. Then lens blur, sensor noise and JPEG, as a phone would.

    python3 make_samples.py out_dir

Writes out_dir/<name>.jpg and out_dir/truth.json ({name: [[x, y] * 4]},
clockwise from top left, or null where no page should be found).
"""

import json
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1600, 2133  # 3:4, what CameraX hands over at the camera tool's default size
PAGE_W, PAGE_H = 1240, 1754  # A4 at 150 dpi


def font(size):
    for name in ("/System/Library/Fonts/Supplemental/Times New Roman.ttf",
                 "/System/Library/Fonts/Helvetica.ttc",
                 "/Library/Fonts/Arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def page(rng):
    img = Image.new("RGB", (PAGE_W, PAGE_H), (247, 245, 238))
    d = ImageDraw.Draw(img)
    d.text((110, 110), "Quarterly report", fill=(20, 20, 30), font=font(64))
    words = ("the keyboard scans a page flat and sends it as a picture into "
             "any chat or mail app while the text stays readable").split()
    y = 230
    body = font(30)
    for _ in range(14):
        line = " ".join(rng.choice(words) for _ in range(9))
        d.text((110, y), line, fill=(35, 35, 40), font=body)
        y += 46
    d.rectangle((110, y + 30, 1130, y + 330), outline=(30, 30, 30), width=3)
    for i in range(1, 5):
        d.line((110, y + 30 + i * 60, 1130, y + 30 + i * 60), fill=(60, 60, 60), width=2)
    d.line((520, y + 30, 520, y + 330), fill=(60, 60, 60), width=2)
    d.rectangle((110, y + 400, 600, y + 620), fill=(40, 90, 200))
    d.text((140, y + 480), "Signed", fill=(255, 255, 255), font=font(48))
    y += 700
    for _ in range(5):
        d.text((110, y), " ".join(rng.choice(words) for _ in range(9)), fill=(35, 35, 40), font=body)
        y += 46
    return img


def coefficients(src, dst):
    """PIL's PERSPECTIVE takes the map from output to input pixels."""
    a = []
    b = []
    for (x, y), (u, v) in zip(dst, src):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        b += [u, v]
    return np.linalg.solve(np.array(a, float), np.array(b, float)).tolist()


def background(kind, rng):
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    if kind == "desk":
        base = np.full((H, W, 3), (92, 96, 104), np.float32)
        base += rng.normal(0, 6, (H, W, 1))
    elif kind == "wood":
        grain = np.sin(xx / 9 + 3 * np.sin(yy / 140)) * 22 + np.sin(xx / 2.7) * 9
        base = np.stack([150 + grain, 100 + grain * 0.8, 60 + grain * 0.5], -1)
    elif kind == "white":
        base = np.full((H, W, 3), (226, 226, 222), np.float32)
        base += rng.normal(0, 3, (H, W, 1))
    elif kind == "cloth":
        # Saturated, but about as bright as the paper in grey.
        check = ((xx // 40 + yy // 40) % 2)[..., None]
        base = np.where(check > 0, (250, 230, 120), (240, 200, 210)).astype(np.float32)
    else:
        raise ValueError(kind)
    return np.clip(base, 0, 255)


def shoot(name, kind, corners, rng, words, light=1.0, shadow=False, out=None):
    bg = background(kind, rng)
    sheet = page(words)
    src = [(0, 0), (PAGE_W, 0), (PAGE_W, PAGE_H), (0, PAGE_H)]
    coeffs = coefficients(src, corners)
    warped = sheet.transform((W, H), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    mask = Image.new("L", (PAGE_W, PAGE_H), 255).transform((W, H), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    m = np.asarray(mask, np.float32)[..., None] / 255
    img = np.asarray(warped, np.float32) * m + bg * (1 - m)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    # Uneven light: brighter towards one corner, as under a desk lamp.
    img *= (0.8 + 0.25 * (1 - (xx / W + yy / H) / 2))[..., None] * light
    if shadow:
        img *= np.where(xx + 0.6 * yy < W * 0.75, 0.62, 1.0)[..., None]
    img = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    img = img.filter(ImageFilter.GaussianBlur(1.2))
    arr = np.asarray(img, np.float32) + rng.normal(0, 4, (H, W, 3))
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).save(out / f"{name}.jpg", quality=88)


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    samples = {
        "desk_straight": ("desk", [(260, 300), (1340, 300), (1340, 1830), (260, 1830)], {}),
        "desk_tilted": ("desk", [(330, 260), (1420, 420), (1250, 1950), (150, 1760)], {}),
        "wood_perspective": ("wood", [(420, 380), (1200, 360), (1450, 1900), (160, 1930)], {}),
        "white_desk_low_contrast": ("white", [(240, 280), (1330, 340), (1290, 1880), (200, 1820)], {}),
        "cloth_same_brightness": ("cloth", [(300, 330), (1300, 280), (1360, 1840), (250, 1880)], {}),
        "page_fills_frame": ("desk", [(40, 30), (1570, 60), (1580, 2100), (20, 2110)], {}),
        "dim_with_shadow": ("wood", [(280, 260), (1320, 330), (1380, 1850), (230, 1800)], {"light": 0.7, "shadow": True}),
    }
    truth = {}
    for i, (name, (kind, corners, extra)) in enumerate(samples.items()):
        shoot(name, kind, corners, np.random.default_rng(100 + i), random.Random(i), out=out, **extra)
        truth[name] = [list(c) for c in corners]
    # Nothing page-shaped: the detector should say so.
    rng = np.random.default_rng(3)
    Image.fromarray(background("wood", rng).astype(np.uint8)).save(out / "no_page_wood.jpg", quality=88)
    truth["no_page_wood"] = None
    (out / "truth.json").write_text(json.dumps(truth, indent=1))


if __name__ == "__main__":
    main()
