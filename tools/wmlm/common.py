"""What the model and the engine agree on: how text becomes tokens.

The Rust reader (`wm-engine/src/lm.rs`) applies the same rules to the words
before the caret, so a token here is a token there. Keep the two in step.

A word is lower-cased, stripped of leading and trailing punctuation, and
kept whole with its inner apostrophes and hyphens. A word with a digit in
it is the one `<num>` token. Everything the vocabulary does not hold is
`<unk>`. A sentence is `<s> words... </s>` after its conditioning prefix:
one register token and one field token (see `SPECIALS`).
"""
import re
import unicodedata

PAD, BOS, EOS, UNK, NUM = "<pad>", "<s>", "</s>", "<unk>", "<num>"
REGISTERS = ["<reg:neutral>", "<reg:casual>", "<reg:formal>"]
FIELDS = ["<field:other>", "<field:chat>", "<field:email>", "<field:search>"]
# Up to four languages in one model; the header names which is which.
LANGS = ["<lang:0>", "<lang:1>", "<lang:2>", "<lang:3>"]
SPECIALS = [PAD, BOS, EOS, UNK, NUM] + REGISTERS + FIELDS + LANGS
assert len(SPECIALS) == 16

# Characters trimmed off either end of a word: punctuation and symbols, not
# letters, digits, apostrophes or hyphens.
_EDGE = re.compile(r"^[^\w'’\-]+|[^\w'’\-]+$", re.UNICODE)
_DIGIT = re.compile(r"\d")
_INNER_JUNK = re.compile(r"[^\w'’\-]", re.UNICODE)


def word_key(token: str) -> str | None:
    """One whitespace-separated token as the engine files it, or None."""
    t = unicodedata.normalize("NFC", token).lower().replace("’", "'")
    t = _EDGE.sub("", t)
    if not t or _INNER_JUNK.search(t):
        return None
    if _DIGIT.search(t):
        return NUM
    if t.strip("'-") == "":
        return None
    return t


def tokenize(sentence: str) -> list[str]:
    out = []
    for raw in sentence.split():
        w = word_key(raw)
        if w is not None:
            out.append(w)
    return out
