#!/usr/bin/env python3
"""Refresh wm-engine's copy of google/myanmar-tools (Apache-2.0).

    git clone https://github.com/google/myanmar-tools.git /tmp/myanmar-tools
    git -C /tmp/myanmar-tools checkout v1.2.0+java
    python3 -I scripts/update-myanmar-tools.py /tmp/myanmar-tools

Copies the Zawgyi detector's Markov model and the upstream test corpora into
native/wm-engine/data/myanmar/, pulls the Zawgyi <-> Unicode test pairs out of
the Java client's TransliterateTest.java into TSV files beside them, and
regenerates native/wm-engine/src/zawgyi_rules.rs from the three generated
Java transliterators (TransliterateZ2U, TransliterateU2Z, TransliterateZNorm),
which upstream compiles from CLDR's my-t-my-s0-zawgyi rules.

Rule patterns are written out exactly as the Java client runs them, typos
included (phase 6 of Z2U has `\\u000136u` where CLDR's source has `\\1u36`),
because wm-engine is held to the Java client's output on the upstream corpus.
"""

import os
import re
import shutil
import sys

PINNED_TAG = "v1.2.0+java"
PINNED_COMMIT = "24c2063ed533fb13cd3b0f2d1166be885bc10c74"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "native", "wm-engine", "data", "myanmar")
RULES_RS = os.path.join(ROOT, "native", "wm-engine", "src", "zawgyi_rules.rs")

JAVA = "clients/java/src/main/java/com/google/myanmartools"
RES = "clients/java/src/main/resources/com/google/myanmartools"
TEST_RES = "clients/java/src/test/resources/com/google/myanmartools"
TEST_JAVA = "clients/java/src/test/java/com/google/myanmartools"


def unicode_escapes(source):
    """Java's first translation step: every backslash-u escape becomes its character."""
    def repl(m):
        # An escape is only one when its backslash is not itself escaped.
        if len(m.group(1)) % 2 == 0:
            return m.group(0)
        return m.group(1)[:-1] + chr(int(m.group(2), 16))
    return re.sub(r"(\\+)u+([0-9A-Fa-f]{4})", repl, source)


STRING = r'"(?:[^"\\]|\\.)*"'


def string_value(literal):
    body = literal[1:-1]
    out = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == "\\":
            n = body[i + 1]
            out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\"}[n])
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def rust_string(value):
    out = []
    for c in value:
        if c in '"\\':
            out.append("\\" + c)
        elif 0x20 < ord(c) < 0x7F:
            out.append(c)
        else:
            out.append("\\u{%X}" % ord(c))
    return '"' + "".join(out) + '"'


def parse_transliterator(path):
    source = unicode_escapes(open(path, encoding="utf-8").read())
    phases = {}
    rule = re.compile(
        r"phase(\d+)\.addRule\(new Rule\((" + STRING + r")\s*,\s*(" + STRING + r")\)"
        r"((?:\s*\.set\w+\([^)]*\))*)\);",
        re.S,
    )
    for m in rule.finditer(source):
        phase = int(m.group(1))
        pattern = string_value(m.group(2))
        substitution = string_value(m.group(3))
        flags = m.group(4)
        on_start = ".setMatchOnStart()" in flags
        revisit = re.search(r"\.setRevisitPosition\((\d+)\)", flags)
        if revisit and revisit.group(1) != "0":
            sys.exit("%s: revisit position %s is not 0" % (path, revisit.group(1)))
        for bad in ("\\", ".", "|", "?", "{"):
            if bad in pattern:
                sys.exit("%s: pattern uses %r, which the rule engine does not read: %r" % (path, bad, pattern))
        if re.search(r"\$(?![0-9])", substitution) or "\\" in substitution:
            sys.exit("%s: substitution %r has more than $n references" % (path, substitution))
        phases.setdefault(phase, []).append((pattern, substitution, on_start, bool(revisit)))
    count = sum(len(r) for r in phases.values())
    declared = len(re.findall(r"\.addRule\(", source))
    if count != declared:
        sys.exit("%s: parsed %d rules of %d" % (path, count, declared))
    return [phases[i] for i in sorted(phases)]


def string_cases(source, name):
    start = source.index("String[][] %s = {" % name)
    body = source[start:]
    end = body.index("};")
    body = body[body.index("{") + 1:end]
    # Cases upstream has commented out ("to be resolved") are not cases.
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    cases = []
    for case in re.finditer(r"\{((?:[^{}\"]|" + STRING + r")*)\}", body):
        text = re.sub(r"//[^\n]*", "", case.group(1))
        parts = re.split(r",(?=(?:[^\"]|" + STRING + r")*$)", text)
        values = []
        for part in parts:
            literals = re.findall(STRING, part)
            if literals:
                values.append("".join(string_value(s) for s in literals))
        if len(values) != 2:
            sys.exit("%s: a case with %d strings" % (name, len(values)))
        cases.append(values)
    return cases


def write_tsv(path, cases, header):
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("# " + header + "\n")
        for case in cases:
            for value in case:
                if "\t" in value or "\n" in value or "\r" in value:
                    sys.exit("%s: a case with a tab or line break" % path)
            out.write("\t".join(case) + "\n")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    upstream = sys.argv[1]
    head = open(os.path.join(upstream, ".git", "HEAD")).read().strip()
    if head != PINNED_COMMIT:
        sys.exit("checkout is at %s, not %s (%s)" % (head, PINNED_TAG, PINNED_COMMIT))

    os.makedirs(os.path.join(DATA, "tests"), exist_ok=True)
    shutil.copyfile(os.path.join(upstream, RES, "zawgyiUnicodeModel.dat"), os.path.join(DATA, "zawgyiUnicodeModel.dat"))
    shutil.copyfile(os.path.join(upstream, "LICENSE"), os.path.join(DATA, "LICENSE"))
    for name in (
        "compatibility.tsv",
        "mmgov_zawgyi_src.txt",
        "mmgov_unicode_out.txt",
        "udhr_mya_unicode_src.txt",
        "udhr_mya_zawgyi_out.txt",
    ):
        shutil.copyfile(os.path.join(upstream, TEST_RES, name), os.path.join(DATA, "tests", name))

    test = unicode_escapes(open(os.path.join(upstream, TEST_JAVA, "TransliterateTest.java"), encoding="utf-8").read())
    write_tsv(
        os.path.join(DATA, "tests", "convert_pairs.tsv"),
        string_cases(test, "testCases"),
        "Zawgyi<TAB>Unicode, from TransliterateTest.testCases at " + PINNED_TAG,
    )
    write_tsv(
        os.path.join(DATA, "tests", "normalize_pairs.tsv"),
        string_cases(test, "normalizeTestCases"),
        "Zawgyi<TAB>normalised Zawgyi, from TransliterateTest.normalizeTestCases at " + PINNED_TAG,
    )

    detector = unicode_escapes(open(os.path.join(upstream, TEST_JAVA, "ZawgyiDetectorTest.java"), encoding="utf-8").read())
    named = [(m.group(1), string_value(m.group(2))) for m in re.finditer(r"String (\w+) = (" + STRING + r");", detector)]
    difficult_body = detector[detector.index("public void testDifficult()"):]
    difficult_body = difficult_body[:difficult_body.index("for (Object[] cas")]
    difficult = [
        (m.group(2), string_value(m.group(1)))
        for m in re.finditer(r"\{\s*(" + STRING + r")\s*,\s*([0-9.]+)\s*\}", difficult_body)
    ]
    if len(named) != 5 or len(difficult) != 8:
        sys.exit("ZawgyiDetectorTest: %d named strings, %d difficult cases" % (len(named), len(difficult)))
    write_tsv(
        os.path.join(DATA, "tests", "detector_named.tsv"),
        named,
        "name<TAB>text, the strings ZawgyiDetectorTest names, at " + PINNED_TAG,
    )
    write_tsv(
        os.path.join(DATA, "tests", "detector_difficult.tsv"),
        difficult,
        "score<TAB>text, ZawgyiDetectorTest.testDifficult (to 0.005), at " + PINNED_TAG,
    )

    tables = [
        ("Z2U", "TransliterateZ2U.java", "Zawgyi to Unicode"),
        ("U2Z", "TransliterateU2Z.java", "Unicode to Zawgyi"),
        ("ZNORM", "TransliterateZNorm.java", "Zawgyi normalised"),
    ]
    with open(RULES_RS, "w", encoding="utf-8", newline="\n") as out:
        out.write(
            "//! The Zawgyi converters' rules, phase by phase. GENERATED by\n"
            "//! `scripts/update-myanmar-tools.py` from google/myanmar-tools %s\n"
            "//! (Apache-2.0), whose Java client compiles them from CLDR's\n"
            "//! my-t-my-s0-zawgyi transform; do not edit by hand. Each rule is\n"
            "//! (pattern, substitution, match only at the start of the text, look\n"
            "//! at the substitution again), as `Rule` and `Phase` in that client run\n"
            "//! them.\n\n" % PINNED_TAG
        )
        out.write("pub type Rule = (&'static str, &'static str, bool, bool);\n")
        for name, java, label in tables:
            phases = parse_transliterator(os.path.join(upstream, JAVA, java))
            out.write("\n/// %s, from `%s`.\npub const %s: &[&[Rule]] = &[\n" % (label, java, name))
            for rules in phases:
                out.write("    &[\n")
                for pattern, substitution, on_start, revisit in rules:
                    out.write(
                        "        (%s, %s, %s, %s),\n"
                        % (rust_string(pattern), rust_string(substitution), str(on_start).lower(), str(revisit).lower())
                    )
                out.write("    ],\n")
            out.write("];\n")


if __name__ == "__main__":
    main()
