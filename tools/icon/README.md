# Launcher icon

The app mark is a keyboard body with a speech-bubble pin carrying the **WM**
monogram, drawn on a deep-indigo gradient. Everything is defined once, in
`gen_icon.py`, on the 108dp adaptive-icon canvas. The whole lockup is scaled to
stay inside the 66dp safe circle, so no launcher mask clips it (circle,
squircle, rounded square or teardrop).

## Regenerate

```bash
python3 tools/icon/gen_icon.py
```

Install `fontTools`, `Pillow`, `rsvg-convert` (librsvg) and `cwebp` (webp)
first. You also need the monogram font. The script looks for it at
`~/Downloads/amazobitaemostrov/AmazOOSTROVv.2.ttf`. If your copy lives
somewhere else, pass `--font PATH`. To look before you write anything, pass
`--preview-only --preview out.png`.

## What it writes

| Output | Purpose |
| --- | --- |
| `drawable/ic_launcher_background.xml` | adaptive background, a linear gradient vector |
| `drawable/ic_launcher_monochrome.xml` | Android 13+ themed icon, one even-odd path |
| `mipmap-*/ic_launcher_fg.webp` | adaptive foreground (raster: the drop shadow needs a blur) |
| `mipmap-*/ic_launcher{,_round}.webp` | legacy pre-O icons, centre-72dp crop |
| `app/src/main/ic_launcher-playstore.png` | 512px store icon, full bleed |

## Addon-repository variant

`gen_addon_icon.py` renders the same lockup with a puzzle piece in the pin
instead of the monogram. That is the mark for an addon repository. It imports
`gen_icon.py`, so palette, geometry and safe-zone scale stay in step.

```bash
python3 tools/icon/gen_addon_icon.py --out ../wmkeyboard-addon-repository/icon.png
```

`--size` defaults to 256px, which is what `repo.icon` in the repo format wants.
This script needs no font.

## Media kit logos

`gen_media_kit.py` renders the logo files offered at `/media-kit/` on the docs
site into `docs/public/media-kit/logo/`: the icon (square and rounded), the
mark alone for dark and light backgrounds, a one-colour mark, the wordmark,
and horizontal and stacked lockups. Each is an SVG plus a PNG render. It
imports `gen_icon.py` for the geometry and palette, sets the wordmark in Inter
and converts it to outlines.

```bash
python3 tools/icon/gen_media_kit.py
```

Needs `uharfbuzz` (for Inter's kerning) and Inter at
`~/Library/Fonts/Inter-VariableFont_opsz,wght.ttf`. Without the monogram font it
recovers the WM outline from `ic_launcher_monochrome.xml`. Re-run it after
changing the icon. The page's screenshots and banners need nothing: they are
read from `play/` at build time.

## Notes

- The monogram is **Amaz Obitaem Ostrov V.2** (straight), converted to outlines.
  The font file is never committed or shipped. Only the resulting path data is.
- Palette follows the app seed colour (`#4C8DF6` → `#8B5CF6`). The keyboard body
  uses a lighter tint of the same ramp so it stays readable on the dark ground.
- To restyle, edit the palette and geometry constants at the top of
  `gen_icon.py`, then re-run it. The raster, vector and themed layers all derive
  from them.
