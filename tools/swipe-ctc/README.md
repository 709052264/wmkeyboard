# Geometry-conditioned CTC swipe encoder (prototype)

A glide encoder of our own whose input includes the keyboard: the key
centres go in as a tensor beside the stroke, keys are embedded from their
position alone, and every output head scores a key slot by its embedding and
its distance from the frame. Nothing in the network knows a slot's index or
letter, so one set of weights reads QWERTY, AZERTY, QWERTZ, Colemak, Dvorak or
a user's own layout, and the caller decides which letter each slot stands
for. Its emissions feed wm-engine's `glide::ctc` trie beam (`CtcGlideBeam`)
unchanged: given the 26 letters in a..z order, the classes come out a..z then
blank, as FUTO Swipe's do.

Written from scratch on the MIT swipe.futo.org dataset. Nothing here comes
from CleverKeys (GPL) or FUTO's model code; the idea of key centres as an
input is public and is all that is shared. Not wired into the app.

## Files

| | |
|---|---|
| `geo.py` | stroke units, time-uniform resample, the anchor fit and transplant |
| `layouts.py` | the named eval layouts and the random training layouts |
| `prep.py` | dataset jsonl to training arrays |
| `model.py` | `GeoCtc` |
| `train.py` | training with layout augmentation |
| `transplant.py` | test strokes redrawn on the named layouts (jsonl + layout json) |
| `emit.py` | model emissions as FSW1 records for `ctc_glide_eval` |
| `tapmodel.py` | `TapCtc`, the same encoder for taps: two output steps per tap |
| `tapsim.py` | the simulated finger the tap encoder trains on |
| `train_taps.py`, `taps_eval.py` | training, and the comparison against the engine's walk on `typing_eval`'s exported cases |

## Taps

No public corpus holds phone taps with their coordinates, so the tap
encoder learns from a simulated finger (`tapsim.py`): words from the
keyboard's list, Gaussian scatter from careful to sloppy, a dropped, stray
or swapped letter at literature rates, log-normal gaps. The Rust harness
draws its cases from the same kind of finger, so the two can be compared on
identical taps:

```bash
python train_taps.py ../../app/dictionaries-src/en.txt taps_run steps=12000
cd ../../native/wm-engine && PACK=pack CASES_OUT=tap_cases.tsv SAVINGS=0 MODES=base \
    ./target/release/examples/typing_eval ../../app/dictionaries-src/en.txt heldout.tsv
cd ../../tools/swipe-ctc && python taps_eval.py taps_run/best.pt tap_cases.tsv ../../app/dictionaries-src/en.txt
```

The result is an upper bound: the encoder was trained on the very noise
model it is tested on. It is not wired into the app.

## Transplanting a stroke

swipe.futo.org was drawn on QWERTY only. To train on and test other layouts
with real hands, each stroke is cut at the points nearest its letters' QWERTY
keys (a monotone fit over the squeezed word) and every point moves by a blend
of its span's two letters' displacements to the new layout, by arc length:
`p + (1 - s) d[lo] + s d[hi]`. The stroke keeps the writer's timing, wobble,
overshoot and how far each key was missed; only where the keys sit changes.
Training draws a random letter order on a random grid for most strokes
(`layouts.bank`) and never sees the named layouts, so the eval on AZERTY,
QWERTZ, Colemak, Dvorak and an alphabetical layout is on layouts the model has
not met. It is still semi-synthetic: a real writer on an unfamiliar layout
swipes slower and less surely than the QWERTY strokes moved onto it.

## Running it

Needs a Python with torch and numpy (MPS on Apple silicon; no executorch) and
the dataset files from `native/bench/futo-swipe/README.md` step 1, plus the
train split (`train.jsonl` from the same revision). From this folder:

```bash
python prep.py train.jsonl qwerty.json train.npz
python prep.py test.jsonl qwerty.json test.npz
python train.py train.npz test.npz qwerty.json run steps=24000
python transplant.py test.jsonl qwerty.json layouts
python emit.py run/last.pt test.jsonl qwerty.json qwerty.bin
python emit.py run/last.pt layouts/dvorak.jsonl layouts/dvorak.json dvorak.bin
```

Then from `native/wm-engine`:

```bash
cargo run --release --example ctc_glide_eval -- qwerty.bin ../../app/dictionaries-src/en.txt
cargo run --release --example glide_beam_eval -- layouts/dvorak.jsonl layouts/dvorak.json ../../app/dictionaries-src/en.txt < /dev/null
```

The scripts import each other, so run them from this folder; keep the
downloaded files in their own folder outside it.
