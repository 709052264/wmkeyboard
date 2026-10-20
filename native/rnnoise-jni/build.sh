#!/usr/bin/env bash
# Builds libwmdenoise.so (denoise_jni.c over RNNoise) for every ABI and copies
# it into core/voice/src/main/jniLibs/. Fetches RNNoise at the pinned commit
# unless RNNOISE_SRC points at a checkout of it. Needs nothing but the NDK.
# See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/rnnoise-jni/build.sh
set -euo pipefail

RNNOISE_TAG=v0.1.1
RNNOISE_COMMIT=6cbfd53eb348a8d394e0757b4025c6ded34eb2b6

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Beside whisper.cpp's and ExecuTorch's libraries: it rides the speech gate
# (Play's :feature:speech). F-Droid's recipe scandeletes the same path.
OUT=${RNNOISE_OUT:-$REPO/core/voice/src/main/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

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

SRC=${RNNOISE_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/rnnoise
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$RNNOISE_TAG" https://github.com/xiph/rnnoise
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$RNNOISE_COMMIT" ] || { echo "rnnoise is at $got, expected $RNNOISE_COMMIT" >&2; exit 1; }

# Build paths mapped out, so two machines with the same NDK build the same bytes.
MAP="-ffile-prefix-map=$SRC=rnnoise -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

triple() {
    case $1 in
        arm64-v8a) echo aarch64-linux-android24 ;;
        armeabi-v7a) echo armv7a-linux-androideabi24 ;;
        x86_64) echo x86_64-linux-android24 ;;
    esac
}

build() {
    local abi=$1
    local dest=$OUT/$abi
    local tmp=$WORK/libwmdenoise-$abi.so
    mkdir -p "$dest"
    # The model is the one compiled into rnn_data.c; no file to load.
    "$BIN/$(triple "$abi")-clang" -shared -fPIC -O2 -std=c99 $MAP \
        -fvisibility=hidden -ffunction-sections -fdata-sections -Wno-null-dereference \
        -I"$SRC/include" -I"$SRC/src" \
        "$HERE/denoise_jni.c" \
        "$SRC/src/denoise.c" "$SRC/src/rnn.c" "$SRC/src/rnn_data.c" "$SRC/src/pitch.c" \
        "$SRC/src/kiss_fft.c" "$SRC/src/celt_lpc.c" \
        -lm -Wl,--no-undefined -Wl,--gc-sections -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 \
        -Wl,--version-script="$HERE/jni.map" -Wl,-soname,libwmdenoise.so \
        -o "$tmp"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmdenoise.so" "$tmp"
    echo "$abi libwmdenoise.so: $(wc -c < "$dest/libwmdenoise.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a x86_64; do
    if wanted "$abi"; then build "$abi"; fi
done
