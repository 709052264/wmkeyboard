#!/usr/bin/env python3
"""Joins hard-wrapped markdown lines back into whole paragraphs.

GitHub release bodies keep a single newline as a line break, so prose wrapped
at 80 columns in the source renders as a narrow ragged column instead of
filling the page. This rejoins every paragraph and list item onto one line and
leaves everything else (headings, tables, HTML, fenced code, blank lines)
exactly as it was.

Reads stdin, writes stdout.
"""
import re
import sys

FENCE = re.compile(r"^\s*(```|~~~)")
LIST_ITEM = re.compile(r"^\s*([-*+]|\d+[.)])\s")
# Lines that start a block of their own and so never continue the one above.
BLOCK_START = re.compile(r"^\s*(#|\||<|>|```|~~~|[-*_]{3,}\s*$)")


def unwrap(lines):
    out = []
    in_fence = False
    joinable = False  # the last line written is prose that may take a continuation
    for raw in lines:
        line = raw.rstrip("\n")
        if FENCE.match(line):
            in_fence = not in_fence
            out.append(line)
            joinable = False
            continue
        if in_fence or not line.strip():
            out.append(line)
            joinable = False
            continue
        if BLOCK_START.match(line):
            out.append(line)
            joinable = False
            continue
        if LIST_ITEM.match(line):
            out.append(line)
            joinable = True
            continue
        prev = out[-1] if out else ""
        hard_break = prev.endswith("  ") or prev.endswith("\\") or prev.rstrip().endswith("<br>")
        if joinable and not hard_break:
            out[-1] = prev.rstrip() + " " + line.strip()
        else:
            out.append(line)
            joinable = True
    return out


def main():
    text = sys.stdin.read()
    trailing = text.endswith("\n")
    result = "\n".join(unwrap(text.splitlines()))
    sys.stdout.write(result + ("\n" if trailing else ""))


if __name__ == "__main__":
    main()
