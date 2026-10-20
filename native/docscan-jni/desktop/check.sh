#!/usr/bin/env bash
# Runs the scanner's detection, flattening and clean-up on a Mac (or Linux
# desktop) over a folder of photos, with the same docscan.cpp and the same
# OpenCV options the phone's library is built with. Writes, per photo, the
# page outlined, the flattened page and each clean-up mode as JPEGs, and
# prints the corners found. With a truth.json beside the photos (as
# make_samples.py writes), also the worst corner's distance from the truth.
#
#   OPENCV_SRC=/path/to/opencv native/docscan-jni/desktop/check.sh photos/ out/
#   python3 native/docscan-jni/desktop/make_samples.py photos/   # synthetic ones
#
# Needs CMake, Ninja, a host C++ compiler and Python 3 with Pillow.
# DOCSCAN_HOST_BUILD keeps the host OpenCV build there between runs.
set -euo pipefail

OPENCV_TAG=4.14.0
HERE=$(cd "$(dirname "$0")" && pwd)
PHOTOS=$(cd "$1" && pwd)
OUT=$2
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT
CACHE=${DOCSCAN_HOST_BUILD:-$WORK/host}

SRC=${OPENCV_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/opencv
    git clone --quiet --depth 1 --branch "$OPENCV_TAG" https://github.com/opencv/opencv "$SRC"
fi
if [ ! -f "$CACHE/install/lib/cmake/opencv4/OpenCVConfig.cmake" ] && [ ! -f "$CACHE/install/lib/libopencv_core.a" ]; then
    cmake -C "$HERE/../opencv.cmake" -S "$SRC" -B "$CACHE/build" -G Ninja \
        -DCMAKE_INSTALL_PREFIX="$CACHE/install" > "$WORK/opencv.log" 2>&1 || { tail -40 "$WORK/opencv.log"; exit 1; }
    cmake --build "$CACHE/build" --target install >> "$WORK/opencv.log" 2>&1 || { tail -40 "$WORK/opencv.log"; exit 1; }
    rm -rf "$CACHE/build"
fi
cfg=$(dirname "$(find "$CACHE/install" -name OpenCVConfig.cmake | head -1)")
cmake -S "$HERE" -B "$WORK/cli" -G Ninja -DCMAKE_BUILD_TYPE=Release -DOpenCV_DIR="$cfg" > "$WORK/cli.log" 2>&1 \
    || { tail -30 "$WORK/cli.log"; exit 1; }
cmake --build "$WORK/cli" >> "$WORK/cli.log" 2>&1 || { tail -30 "$WORK/cli.log"; exit 1; }

# Pillow turns each photo (EXIF rotation applied) into a PPM and the results back into JPEGs.
convert() {
    python3 -I -c '
import sys
from PIL import Image, ImageOps
Image.MAX_IMAGE_PIXELS = None
ImageOps.exif_transpose(Image.open(sys.argv[1])).convert("RGB").save(sys.argv[2], quality=90)
' "$1" "$2"
}

shopt -s nullglob nocaseglob
for photo in "$PHOTOS"/*.jpg "$PHOTOS"/*.jpeg "$PHOTOS"/*.png; do
    name=$(basename "${photo%.*}")
    convert "$photo" "$WORK/$name.ppm"
    line=$("$WORK/cli/docscan_cli" "$WORK/$name.ppm" "$WORK/$name")
    for ppm in "$WORK/$name"_*.ppm; do
        convert "$ppm" "$OUT/$(basename "${ppm%.ppm}").jpg"
        rm -f "$ppm"
    done
    rm -f "$WORK/$name.ppm"
    err=
    if [ -f "$PHOTOS/truth.json" ]; then
        err=$(python3 -I -c '
import json, math, sys
truth = json.load(open(sys.argv[1])).get(sys.argv[2], "missing")
found = sys.argv[3].split("\n")[0]
ok = "found=1" in found
pts = [tuple(map(float, p.split(","))) for p in found.split("corners ")[1].split()]
if truth == "missing":
    print("")
elif truth is None:
    print("expected none: " + ("WRONG, found one" if ok else "ok"))
elif not ok:
    print("MISSED")
else:
    print("worst corner off by %.0f px" % max(math.dist(a, b) for a, b in zip(pts, truth)))
' "$PHOTOS/truth.json" "$name" "$line")
    fi
    printf '%-26s %s %s\n' "$name" "$(echo "$line" | tr '\n' ' ')" "$err"
done
