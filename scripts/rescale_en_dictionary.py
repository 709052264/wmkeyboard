#!/usr/bin/env python3
"""Put every word of app/dictionaries-src/en.txt on one frequency scale (#792).

The bundled English list was assembled from three bands that never shared a
scale:

  * the seed head (about 1,280 words): numbered 10000 down to 1 by position,
    the last few hundred of them in alphabetical order, so `zoo` outranked
    `wait` and `meet` (300) sat far below `met` (1757);
  * hand additions (`is`, `was`, `said` ...) slotted into that numbering;
  * the corpus expansion (about 15,900 words), OpenSubtitles 2018 counts
    mapped as f = 354.85 * ln(count / 1000) + 1, so count 1000 is 1 and
    `going` is 2600.

The corpus band's mapping is the only one with a source behind it, so this
keeps its shape and puts every word on it: f is proportional to
ln(count / FLOOR_COUNT), with the line stretched so the commonest word in the
file lands on 10000, the scale the engine is tuned on
(SuggestionEngine.REFERENCE_TOP_FREQUENCY, DictionaryLoader.MAX_LOCAL_FREQUENCY).
Stretching multiplies every corpus word by the same factor, so the ln-gap
between any two of them, which is all the ranking reads, is unchanged.

Contractions are the exception. The reference splits words at the
apostrophe (it counts `i'd` 155 times), so their counts are useless. They
keep their order and are carried across by where their old number sat among
the old seed head: each one takes the median new value of the NEIGHBOURS seed
words whose old values were nearest, held monotone so a contraction that
outranked another still does.

Reproducible from two pinned inputs, both checked by hash:

  * the list as it stood before this rescale, git blob INPUT_BLOB
    (`git show fcb1f9fd5:app/dictionaries-src/en.txt` style, read by blob);
  * the reference, `data/en/en_full.txt.gz` of wasi-master/wmkeyboard-data
    (hermitdave FrequencyWords, OpenSubtitles 2018, CC BY-SA 4.0),
    sha256 REFERENCE_SHA256.

    scripts/rescale_en_dictionary.py --reference PATH/en_full.txt.gz          # rewrite
    scripts/rescale_en_dictionary.py --reference PATH/en_full.txt.gz --check  # verify

Word order, comments, coverage and the file format are untouched; only the
numbers change.

Comparing the ranking evals before and after a change to this list (#837):
the frequency-sampled suites draw their cases from the dictionary, so pin the
case set to the old list with WM_EVAL_SAMPLE_DICT (see EvalSampling in the
app's eval tests) and run them with each list in place. Measured for this
rescale (13dae189e) in #837, on one fixed case set where noted:

  * DuetBeamEvalTest top1, cases from the new list, CLEAN/LIGHT/TYPICAL/
    SLOPPY/LOOPED: old .8367/.8333/.8067/.7667/.7600, new .8367/.8267/.8033/
    .7633/.7600. From the old list: CLEAN-TYPICAL identical, SLOPPY .7733 to
    .7667, LOOPED .7900 to .7867. Every lost case decodes a word en_full
    rates more common (tops to stop, nest to best).
  * Glide on the same cases: top1 within .0067 per level, one loss
    (patty to party).
  * RerankGainTest, same cases by construction: contextful cold .8167 to
    .8800 before, .8850 to .9067 after; warm .8233 to .9083 before, .8883
    to .9233 after.
  * GlideBeamTest 38/38 after.
"""

import argparse
import bisect
import gzip
import hashlib
import math
import statistics
import subprocess
import sys
from pathlib import Path

INPUT_BLOB = "fcb1f9fd5d23a5a43784c2da383a2ed445adb205"
REFERENCE_SHA256 = "fc299cb4c570881518616dd3f1256425e2836ab7471c4c6dc41efd0b2682dfec"
TARGET = Path(__file__).resolve().parent.parent / "app" / "dictionaries-src" / "en.txt"

TOP = 10000
FLOOR = 1
# The corpus band's own floor: a count of 1000 was written as 1.
FLOOR_COUNT = 1000

# How many old seed words a contraction's new value is the median of.
NEIGHBOURS = 51

CONTRACTIONS_MARKER = "# Contractions."


def read_reference(path: Path) -> dict:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != REFERENCE_SHA256:
        sys.exit(f"reference sha256 {digest} is not the pinned {REFERENCE_SHA256}")
    counts = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2:
                counts.setdefault(parts[0], int(parts[1]))
    return counts


def read_input() -> list:
    root = TARGET.parent.parent.parent
    text = subprocess.run(
        ["git", "cat-file", "blob", INPUT_BLOB],
        cwd=root, check=True, capture_output=True, text=True,
    ).stdout
    return text.splitlines()


def parse(lines):
    """(index, word, freq, band) for every entry; band is seed/hand/corpus/contraction."""
    entries = []
    band = "seed"
    for i, line in enumerate(lines):
        if line.startswith("#"):
            if "Hand additions" in line:
                band = "hand"
            elif "Corpus expansion" in line:
                band = "corpus"
            elif line.startswith(CONTRACTIONS_MARKER):
                band = "contraction"
            continue
        if not line.strip():
            continue
        word, freq = line.rsplit(None, 1)
        entries.append((i, word, int(freq), band))
    return entries


def rescale(lines, counts):
    entries = parse(lines)
    scored = [e for e in entries if e[3] != "contraction"]
    missing = [e[1] for e in scored if e[1].lower() not in counts]
    if missing:
        sys.exit(f"not in the reference: {missing[:20]}")
    top_count = max(counts[e[1].lower()] for e in scored)
    slope = (TOP - FLOOR) / math.log(top_count / FLOOR_COUNT)

    def value(count):
        return max(FLOOR, min(TOP, round(slope * math.log(count / FLOOR_COUNT) + FLOOR)))

    new = {}
    for i, word, _, _ in scored:
        new[i] = value(counts[word.lower()])

    # Contractions: old seed value -> median new value of its nearest old
    # seed neighbours, then made monotone in the old value.
    seed = sorted((old, new[i]) for i, _, old, band in scored if band == "seed")
    olds = [old for old, _ in seed]

    def carried(old):
        k = bisect.bisect_left(olds, old)
        lo, hi = k, k
        while hi - lo < NEIGHBOURS and (lo > 0 or hi < len(seed)):
            if hi >= len(seed) or (lo > 0 and old - olds[lo - 1] <= olds[hi] - old):
                lo -= 1
            else:
                hi += 1
        return statistics.median(v for _, v in seed[lo:hi])

    contractions = sorted(
        ((old, i) for i, _, old, band in entries if band == "contraction"),
        key=lambda e: (-e[0], e[1]),
    )
    ceiling, previous = TOP + 1, None
    for old, i in contractions:
        # Strictly below the one before when it was strictly below it before.
        bound = ceiling - 1 if previous is not None and old < previous else ceiling
        ceiling, previous = min(bound, round(carried(old))), old
        new[i] = max(FLOOR, ceiling)

    out = list(lines)
    for i, word, _, _ in entries:
        out[i] = f"{word} {new[i]}"
    return out, slope, top_count


def annotate(lines, slope, top_count):
    """Rewrites the two header comments that describe the scale."""
    out = []
    for line in lines:
        if line.startswith("# Ranked subset of common English"):
            out.append(
                "# Ranked subset of common English; expand by appending lines. Every number is"
            )
            out.append(
                f"# {slope:.2f} * ln(count / {FLOOR_COUNT}) + 1 on OpenSubtitles 2018 counts, so the"
            )
            out.append(
                f"# commonest word ({top_count:,}) is {TOP}; see scripts/rescale_en_dictionary.py (#792)."
            )
            continue
        if line.startswith("# --- Hand additions"):
            out.append("# --- Hand additions: common words missing from the seed head, on the same scale. ---")
            continue
        if line.startswith("# --- Corpus expansion"):
            out.append("# --- Corpus expansion (OpenSubtitles 2018 frequency list), on the same scale. ---")
            continue
        out.append(line)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    counts = read_reference(args.reference)
    lines = read_input()
    rescaled, slope, top_count = rescale(lines, counts)
    result = "\n".join(annotate(rescaled, slope, top_count)) + "\n"
    if args.check:
        if TARGET.read_text(encoding="utf-8") != result:
            sys.exit(f"{TARGET} is not what the pinned inputs produce")
        print("en.txt matches the pinned inputs")
        return
    TARGET.write_text(result, encoding="utf-8")
    print(f"wrote {TARGET}: slope {slope:.2f}, top count {top_count:,}")


if __name__ == "__main__":
    main()
