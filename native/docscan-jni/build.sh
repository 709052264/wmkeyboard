#!/usr/bin/env bash
# Builds libwmdocscan.so (wm_docscan_jni.cpp and docscan.cpp over a static
# OpenCV, core and imgproc only) for every ABI and copies it into
# core/intelligence/src/docscan/jniLibs/. Fetches OpenCV at the pinned commit
# unless OPENCV_SRC points at a checkout of it. Needs CMake and Ninja on PATH.
# See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/docscan-jni/build.sh
set -euo pipefail

OPENCV_TAG=4.14.0
OPENCV_COMMIT=0654a42e19215ef25b1d367d822f3c630447e7c7

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: a directory of its own, so the on-demand
# :feature:docscan split carries it alone on Play. F-Droid's recipe
# scandeletes the same path.
OUT=${DOCSCAN_OUT:-$REPO/core/intelligence/src/docscan/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

# Network fetches retry: F-Droid's builders share GitHub with everyone, and
# a busy host refusing one clone should not fail the build.
retry() {
    local n
    for n in 1 2 3 4 5; do
        "$@" && return 0
        echo "attempt $n failed, retrying in $((n * 30)) s: $*" >&2
        sleep $((n * 30))
    done
    return 1
}
fresh_clone() {  # dir, then git clone arguments
    local dir=$1; shift
    rm -rf "$dir" && git clone "$@" "$dir"
}

SRC=${OPENCV_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/opencv
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$OPENCV_TAG" https://github.com/opencv/opencv
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$OPENCV_COMMIT" ] || { echo "OpenCV is at $got, expected $OPENCV_COMMIT" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# CV_Error and asserts, the NDK's libc++ headers), so two machines with the
# same NDK build the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=opencv -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
SECTIONS="-ffunction-sections -fdata-sections"

build() {
    local abi=$1
    local ocv=$WORK/opencv-$abi out=$WORK/build-$abi
    local android=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    )
    cmake -C "$HERE/opencv.cmake" -S "$SRC" -B "$ocv" -G Ninja "${android[@]}" \
        -DCMAKE_INSTALL_PREFIX="$ocv/install" \
        -DCMAKE_C_FLAGS="$MAP $SECTIONS" -DCMAKE_CXX_FLAGS="$MAP $SECTIONS" > "$ocv.log" 2>&1 \
        || { tail -40 "$ocv.log"; exit 1; }
    # OpenCV writes its configuration, this machine's paths and tools
    # included, into the library as cv::getBuildInformation(), which its
    # startup keeps alive (OPENCV_DUMP_CONFIG prints it). A fixed line
    # instead, or no two machines would build the same bytes.
    printf '"OpenCV %s, core and imgproc, built by native/docscan-jni\\n"\n' "$OPENCV_TAG" \
        > "$ocv/modules/core/version_string.inc"
    cmake --build "$ocv" --target install >> "$ocv.log" 2>&1 || { tail -40 "$ocv.log"; exit 1; }
    cmake -S "$HERE" -B "$out" -G Ninja "${android[@]}" \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_CXX_FLAGS="$MAP" \
        -DOpenCV_DIR="$ocv/install/sdk/native/jni" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmdocscan >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmdocscan.so" "$out/libwmdocscan.so"
    echo "$abi libwmdocscan.so: $(wc -c < "$dest/libwmdocscan.so") bytes"
    # The build tree of one ABI is a few hundred MB; gone before the next.
    rm -rf "$ocv" "$out"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64}; do
    case $abi in
        arm64-v8a|armeabi-v7a|x86_64) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
