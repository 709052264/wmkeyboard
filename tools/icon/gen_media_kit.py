#!/usr/bin/env python3
"""Logo files for the press kit at /media-kit/ on the docs site.

Builds on gen_icon.py, so the geometry and palette are the launcher icon's:
nothing here is drawn twice. Writes SVG masters plus PNG renders into
docs/public/media-kit/logo/:

  icon.svg / icon-{512,1024}.png             full-bleed square app icon
  icon-rounded.svg / icon-rounded-*.png      the same, corners rounded
  mark-on-dark.svg / .png                    keyboard + pin, transparent, white pin
  mark-on-light.svg / .png                   keyboard + pin, transparent, navy pin
  mark-mono-{white,black}.svg / .png         one-colour silhouette
  wordmark-{white,navy}.svg / .png           "WM Keyboard" in Inter, outlined
  lockup-{dark,light}.svg / .png             icon + wordmark, side by side
  lockup-stacked-{dark,light}.svg / .png     icon over wordmark

The wordmark is converted to outlines (HarfBuzz shaping, so Inter's kerning
applies), so the SVGs render the same with or without Inter installed.

The WM monogram comes from the font when it is on disk. Without it, the path
is recovered from drawable/ic_launcher_monochrome.xml, where gen_icon.py
appended it last, so a fresh checkout can still run this script.

Requires: fontTools, uharfbuzz, rsvg-convert, Inter (~/Library/Fonts).

    python3 tools/icon/gen_media_kit.py
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile

import uharfbuzz as hb
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

sys.path.insert(0, os.path.dirname(__file__))
import gen_icon as gi  # noqa: E402

OUT = os.path.join(gi.REPO, "docs", "public", "media-kit", "logo")
INTER = os.path.expanduser("~/Library/Fonts/Inter-VariableFont_opsz,wght.ttf")
WORD = "WM Keyboard"
WORD_WGHT, WORD_OPSZ = 750, 32
WORD_TRACK = -0.012            # em, matches the banners' tight display setting

NAVY = "#191C42"
WHITE = "#FFFFFF"
ICON_RX = 0.2237               # squircle-ish corner, same 22% the docs CSS uses

# Mark bounding box on the 108 canvas, before the safe-zone scale.
MARK_X0, MARK_X1 = gi.KB_X, gi.KB_X + gi.KB_W
MARK_Y0, MARK_Y1 = gi.PIN_TOP, gi.KB_Y + gi.KB_H


# ---------------------------------------------------------------- sources
def monogram_path() -> str:
    if os.path.exists(gi.DEFAULT_FONT):
        return gi.monogram(gi.DEFAULT_FONT)[0]
    xml = os.path.join(gi.RES, "drawable", "ic_launcher_monochrome.xml")
    data = re.search(r'android:pathData="([^"]+)"', open(xml).read()).group(1)
    prefix = " ".join([gi.rrect(gi.KB_X, gi.KB_Y, gi.KB_W, gi.KB_H, gi.KB_R)] + gi.key_paths()
                      + [gi.pin_path(gi.PIN_HALO), gi.pin_path()]) + " "
    if not data.startswith(prefix):
        sys.exit("ic_launcher_monochrome.xml no longer matches gen_icon.py; pass the font")
    return data[len(prefix):]


def wordmark_path() -> tuple[str, float, float, float]:
    """`WM Keyboard` outlined at 1 unit = 1 font unit, baseline at y=0, y-down.
    Returns (path, advance width, cap height, upem)."""
    blob = hb.Blob.from_file_path(INTER)
    font = hb.Font(hb.Face(blob))
    font.set_variations({"wght": WORD_WGHT, "opsz": WORD_OPSZ})
    buf = hb.Buffer()
    buf.add_str(WORD)
    buf.guess_segment_properties()
    hb.shape(font, buf, {"kern": True, "liga": True})

    tt = TTFont(INTER)
    upem = tt["head"].unitsPerEm
    glyphs = tt.getGlyphSet(location={"wght": WORD_WGHT, "opsz": WORD_OPSZ})
    order = tt.getGlyphOrder()
    pen = SVGPathPen(glyphs, ntos=lambda v: f"{v:.1f}")
    x = 0.0
    track = WORD_TRACK * upem
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        glyphs[order[info.codepoint]].draw(
            TransformPen(pen, (1, 0, 0, -1, x + pos.x_offset, -pos.y_offset)))
        x += pos.x_advance + track
    cap = tt["OS/2"].sCapHeight
    return pen.getCommands(), x - track, cap, upem


# ---------------------------------------------------------------- pieces
def defs(*extra: str) -> str:
    return "".join(extra)


def mark_group(wm: str, pin: str, wm_a: str, wm_b: str, uid: str, shadow: bool) -> tuple[str, str]:
    """Keyboard + pin + monogram at 108-canvas coordinates. Returns (defs, body)."""
    keys = "".join(f'<path d="{d}"/>' for d in gi.key_paths())
    d = (f'<mask id="kb{uid}" maskUnits="userSpaceOnUse" x="0" y="0" width="108" height="108">'
         f'<rect width="108" height="108" fill="black"/>'
         f'<path d="{gi.rrect(gi.KB_X, gi.KB_Y, gi.KB_W, gi.KB_H, gi.KB_R)}" fill="white"/>'
         f'<g fill="black" fill-opacity="0.5">{keys}</g>'
         f'<path d="{gi.pin_path(gi.PIN_HALO)}" fill="black"/></mask>'
         + gi.gradient(f"kbg{uid}", gi.KB_TOP, gi.KB_BOTTOM)
         + gi.gradient(f"wmg{uid}", wm_a, wm_b))
    if shadow:
        d += gi.SHADOW_FILTER.replace('id="sh"', f'id="sh{uid}"')
    filt = f' filter="url(#sh{uid})"' if shadow else ""
    body = (f'<g{filt}><rect width="108" height="108" fill="url(#kbg{uid})" mask="url(#kb{uid})"/>'
            f'<path d="{gi.pin_path()}" fill="{pin}"/></g>'
            f'<path d="{wm}" fill="url(#wmg{uid})"/>')
    return d, body


def icon_group(wm: str, rounded: bool, uid: str = "i") -> tuple[str, str]:
    """The launcher icon on its 108 canvas, safe-zone scale applied."""
    s = gi.lockup_scale()
    md, mb = mark_group(wm, gi.PIN_FILL, gi.BRAND_A, gi.BRAND_B, uid, shadow=True)
    rx = 108 * ICON_RX if rounded else 0
    d = md + gi.gradient(f"bg{uid}", gi.BG_TOP, gi.BG_BOTTOM)
    if rounded:
        d += f'<clipPath id="clip{uid}"><rect width="108" height="108" rx="{rx:.2f}"/></clipPath>'
    clip = f' clip-path="url(#clip{uid})"' if rounded else ""
    body = (f'<g{clip}><rect width="108" height="108" fill="url(#bg{uid})"/>'
            f'<g transform="translate({gi.C},{gi.C}) scale({s:.4f}) translate({-gi.C},{-gi.C})">{mb}</g></g>')
    return d, body


def mono_body(wm: str, colour: str) -> tuple[str, str]:
    """One-colour mark. Not the themed icon's single even-odd path: there the
    halo ring outside the keyboard fills in and the pin reads as an outline."""
    keys = " ".join(gi.key_paths())
    d = (f'<mask id="halo" maskUnits="userSpaceOnUse" x="0" y="0" width="108" height="108">'
         f'<rect width="108" height="108" fill="white"/>'
         f'<path d="{gi.pin_path(gi.PIN_HALO)}" fill="black"/></mask>')
    body = (f'<g fill="{colour}" fill-rule="evenodd">'
            f'<path mask="url(#halo)" d="{gi.rrect(gi.KB_X, gi.KB_Y, gi.KB_W, gi.KB_H, gi.KB_R)} {keys}"/>'
            f'<path d="{gi.pin_path()} {wm}"/></g>')
    return d, body


def doc(w: float, h: float, d: str, body: str, title: str, vb: str | None = None) -> str:
    vb = vb or f"0 0 {gi.f(w)} {gi.f(h)}"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{gi.f(w)}" height="{gi.f(h)}" '
            f'viewBox="{vb}" role="img" aria-label="{title}"><title>{title}</title>'
            f'<defs>{d}</defs>{body}</svg>\n')


# ---------------------------------------------------------------- documents
def icon_svg(wm: str, rounded: bool) -> str:
    d, b = icon_group(wm, rounded)
    return doc(108, 108, d, b, "WM Keyboard app icon")


MARK_PAD = 1.5
MARK_VB = (MARK_X0 - MARK_PAD, MARK_Y0 - MARK_PAD,
           MARK_X1 - MARK_X0 + 2 * MARK_PAD, MARK_Y1 - MARK_Y0 + 2 * MARK_PAD)


def mark_svg(wm: str, on_dark: bool) -> str:
    if on_dark:
        d, b = mark_group(wm, gi.PIN_FILL, gi.BRAND_A, gi.BRAND_B, "m", shadow=False)
    else:
        d, b = mark_group(wm, NAVY, gi.KB_TOP, gi.KB_BOTTOM, "m", shadow=False)
    x, y, w, h = MARK_VB
    return doc(w * 4, h * 4, d, b, "WM Keyboard mark", f"{gi.f(x)} {gi.f(y)} {gi.f(w)} {gi.f(h)}")


def mono_svg(wm: str, colour: str) -> str:
    x, y, w, h = MARK_VB
    d, body = mono_body(wm, colour)
    return doc(w * 4, h * 4, d, body, "WM Keyboard mark", f"{gi.f(x)} {gi.f(y)} {gi.f(w)} {gi.f(h)}")


def wordmark_svg(word: tuple, colour: str) -> str:
    path, adv, cap, upem = word
    # Box from cap height to baseline, a little descender room for the y.
    asc, desc = cap * 1.0, upem * 0.24
    body = f'<path fill="{colour}" d="{path}"/>'
    return doc(adv / upem * 100, (asc + desc) / upem * 100, "", body, "WM Keyboard",
               f"0 {gi.f(-asc)} {gi.f(adv)} {gi.f(asc + desc)}")


def lockup_svg(wm: str, word: tuple, dark: bool, stacked: bool) -> str:
    path, adv, cap, upem = word
    colour = WHITE if dark else NAVY
    d, icon = icon_group(wm, rounded=True)
    S = 108.0                                   # icon edge, lockup units
    cap_h = S * 0.36  # cap height relative to the icon
    k = cap_h / cap                              # font units -> lockup units
    word_w = adv * k
    if not stacked:
        gap = S * 0.2
        W, H = S + gap + word_w, S
        tx, base = S + gap, S / 2 + cap_h / 2
        icon_at = (0, 0)
    else:
        gap = S * 0.16
        desc = upem * 0.22 * k
        W = max(S, word_w)
        H = S + gap + cap_h + desc
        tx, base = (W - word_w) / 2, S + gap + cap_h
        icon_at = ((W - S) / 2, 0)
    body = (f'<g transform="translate({gi.f(icon_at[0])},{gi.f(icon_at[1])})">{icon}</g>'
            f'<path fill="{colour}" transform="translate({tx:.2f},{base:.2f}) scale({k:.5f})" d="{path}"/>')
    return doc(W * 4, H * 4, d, body, "WM Keyboard", f"0 0 {gi.f(W)} {gi.f(H)}")


# ---------------------------------------------------------------- output
def write(name: str, svg: str, *png_widths: int) -> None:
    src = os.path.join(OUT, f"{name}.svg")
    with open(src, "w") as fh:
        fh.write(svg)
    for w in png_widths:
        suffix = "" if len(png_widths) == 1 else f"-{w}"
        dst = os.path.join(OUT, f"{name}{suffix}.png")
        subprocess.run(["rsvg-convert", "-w", str(w), src, "-o", dst], check=True)


def main() -> None:
    gi.need("rsvg-convert")
    if not os.path.exists(INTER):
        sys.exit(f"Inter not found: {INTER}")
    os.makedirs(OUT, exist_ok=True)
    wm = monogram_path()
    word = wordmark_path()

    write("icon", icon_svg(wm, rounded=False), 512, 1024)
    write("icon-rounded", icon_svg(wm, rounded=True), 512, 1024)
    write("mark-on-dark", mark_svg(wm, on_dark=True), 1024)
    write("mark-on-light", mark_svg(wm, on_dark=False), 1024)
    write("mark-mono-white", mono_svg(wm, WHITE), 1024)
    write("mark-mono-black", mono_svg(wm, "#000000"), 1024)
    write("wordmark-white", wordmark_svg(word, WHITE), 1600)
    write("wordmark-navy", wordmark_svg(word, NAVY), 1600)
    for dark in (True, False):
        tone = "dark" if dark else "light"
        write(f"lockup-{tone}", lockup_svg(wm, word, dark, stacked=False), 2000)
        write(f"lockup-stacked-{tone}", lockup_svg(wm, word, dark, stacked=True), 1200)
    print(f"media kit logos written to {os.path.relpath(OUT, gi.REPO)}")


if __name__ == "__main__":
    main()
