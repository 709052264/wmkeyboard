#!/usr/bin/env bash
# Builds the Finnish dictionary the keyboard downloads for Voikko: voikko-fi's
# standard morphology (mor-standard, VFST, dictionary format 5) from the
# signed voikko-fi release tarball, packed as a zip of
# 5/mor-standard/{index.txt,mor.vfst,autocorr.vfst}.
#
#   native/voikko-jni/build-dict.sh <out-dir>
#
# Needs foma, Python 3, GNU make, CMake, Ninja and a host C++ compiler;
# voikkovfstc (libvoikko's transducer compiler) is built by build.sh --host
# unless VOIKKOVFSTC names one. The zip is reproducible: fixed build date,
# C-locale sort, fixed zip timestamps and order. Publish it in the data
# repository beside the tarball it was built from (README.md, "Dictionary").
set -euo pipefail

VOIKKO_FI_VERSION=2.6
VOIKKO_FI_SHA256=5c147c1c92323941e5847b5c46d00b35ff6b62dc3cf348516bc0fbdc87340220
# The rel-voikko-fi-2.6 tag's commit date, so index.txt does not carry the
# day it was rebuilt.
BUILD_DATE="Fri, 20 Feb 2026 13:08:07 +0000"

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:?usage: build-dict.sh <out-dir>}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd -P)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

command -v foma >/dev/null || { echo "foma is not on PATH" >&2; exit 1; }
MAKE=make
command -v gmake >/dev/null && MAKE=gmake

tarball=voikko-fi-$VOIKKO_FI_VERSION.tar.gz
if [ -n "${VOIKKO_FI_TARBALL:-}" ]; then
    cp "$VOIKKO_FI_TARBALL" "$WORK/$tarball"
else
    curl -fsSL --retry 5 -o "$WORK/$tarball" "https://www.puimula.org/voikko-sources/voikko-fi/$tarball"
fi
got=$( (sha256sum "$WORK/$tarball" 2>/dev/null || shasum -a 256 "$WORK/$tarball") | cut -d' ' -f1)
[ "$got" = "$VOIKKO_FI_SHA256" ] || { echo "$tarball has SHA-256 $got, expected $VOIKKO_FI_SHA256" >&2; exit 1; }
tar -xzf "$WORK/$tarball" -C "$WORK"

VFSTC=${VOIKKOVFSTC:-}
if [ -z "$VFSTC" ]; then
    "$HERE/build.sh" --host "$WORK/host" > "$WORK/host.log" 2>&1 || { tail -30 "$WORK/host.log"; exit 1; }
    VFSTC=$WORK/host/voikkovfstc
fi
mkdir -p "$WORK/bin"
ln -s "$(cd "$(dirname "$VFSTC")" && pwd -P)/$(basename "$VFSTC")" "$WORK/bin/voikkovfstc"

(
    cd "$WORK/voikko-fi-$VOIKKO_FI_VERSION"
    export LC_ALL=C PATH="$WORK/bin:$PATH"
    "$MAKE" vvfst SM_BUILDDATE="$BUILD_DATE" > "$WORK/make.log" 2>&1 \
        || { tail -40 "$WORK/make.log"; exit 1; }
)

zip=$OUT/voikko-fi-$VOIKKO_FI_VERSION-mor-standard.zip
python3 -I - "$WORK/voikko-fi-$VOIKKO_FI_VERSION/vvfst" "$zip" <<'EOF'
import sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for name in ("index.txt", "mor.vfst", "autocorr.vfst"):
        info = zipfile.ZipInfo("5/mor-standard/" + name, date_time=(2026, 2, 20, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(f"{src}/{name}", "rb") as f:
            z.writestr(info, f.read(), compresslevel=9)
EOF
ls -l "$zip"
(sha256sum "$zip" 2>/dev/null || shasum -a 256 "$zip")
unzip -l "$zip"
