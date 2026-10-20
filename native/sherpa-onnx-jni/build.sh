#!/usr/bin/env bash
# Builds libwmsherpa.so (sherpa_jni.cpp over a static sherpa-onnx) for every
# ABI and copies it into core/voice/src/onnx/jniLibs/. Fetches sherpa-onnx and
# ONNX Runtime's headers at their pinned commits unless SHERPA_SRC / ORT_SRC
# point at checkouts of them. Needs CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/sherpa-onnx-jni/build.sh
set -euo pipefail

SHERPA_TAG=v1.13.8
SHERPA_COMMIT=11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
# Headers only: the runtime itself is Microsoft's Maven Central build of the
# same version (com.microsoft.onnxruntime:onnxruntime-android), which the
# phone loads before this library. Keep in step with `onnxruntime` in
# gradle/libs.versions.toml.
ORT_TAG=v1.30.0
ORT_COMMIT=f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: its own jniLibs directory under :core:voice, apart
# from src/main/jniLibs, because it rides the `onnx` delivery gate (Play's
# :feature:onnx) and not the speech one. F-Droid's recipe scandeletes it.
OUT=${SHERPA_OUT:-$REPO/core/voice/src/onnx/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

# Network fetches retry: F-Droid's builders share GitHub with everyone, and a
# busy host refusing one clone should not fail the build.
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

SRC=${SHERPA_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/sherpa-onnx
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$SHERPA_TAG" https://github.com/k2-fsa/sherpa-onnx
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$SHERPA_COMMIT" ] || { echo "sherpa-onnx is at $got, expected $SHERPA_COMMIT" >&2; exit 1; }

ORT=${ORT_SRC:-}
if [ -z "$ORT" ]; then
    ORT=$WORK/onnxruntime
    retry fresh_clone "$ORT" --quiet --depth 1 --branch "$ORT_TAG" --filter=blob:none --sparse \
        https://github.com/microsoft/onnxruntime
    git -C "$ORT" sparse-checkout set --no-cone /include/onnxruntime/core/session \
        /include/onnxruntime/core/providers/cpu /include/onnxruntime/core/providers/nnapi
fi
got=$(git -C "$ORT" rev-parse HEAD)
[ "$got" = "$ORT_COMMIT" ] || { echo "onnxruntime is at $got, expected $ORT_COMMIT" >&2; exit 1; }
# The flat header directory the AAR's headers/ folder has.
HEADERS=$WORK/ort-headers
mkdir -p "$HEADERS"
cp "$ORT"/include/onnxruntime/core/session/*.h "$ORT"/include/onnxruntime/core/session/*.inc "$HEADERS"/ 2>/dev/null || true
cp "$ORT"/include/onnxruntime/core/providers/cpu/cpu_provider_factory.h \
   "$ORT"/include/onnxruntime/core/providers/nnapi/nnapi_provider_factory.h "$HEADERS"/
[ -f "$HEADERS/onnxruntime_cxx_api.h" ] || { echo "no ONNX Runtime headers in $ORT" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# asserts, the NDK's libc++ headers), so two machines with the same NDK build
# the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=sherpa-onnx -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

# clang target triple per ABI, for the link stub.
triple() {
    case $1 in
        arm64-v8a) echo aarch64-linux-android24 ;;
        armeabi-v7a) echo armv7a-linux-androideabi24 ;;
        x86_64) echo x86_64-linux-android24 ;;
    esac
}

build() {
    local abi=$1
    local out=$WORK/build-$abi
    local stub=$WORK/stub-$abi
    mkdir -p "$stub"
    "$BIN/$(triple "$abi")-clang" -shared -fPIC -O2 -Wl,-soname,libonnxruntime.so \
        -o "$stub/libonnxruntime.so" "$HERE/ort_stub.c"
    # sherpa-onnx's own dependencies (kaldi-native-fbank, kaldi-decoder,
    # simple-sentencepiece, OpenFst, ...) come from its CMake at the
    # versions this tag pins.
    SHERPA_ONNXRUNTIME_INCLUDE_DIR=$HEADERS SHERPA_ONNXRUNTIME_LIB_DIR=$stub \
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP" \
        -DSHERPA_SRC="$SRC" > "$out.log" 2>&1 \
        || { tail -40 "$out.log"; exit 1; }
    cmake --build "$out" --target wmsherpa >> "$out.log" 2>&1 || { tail -60 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmsherpa.so" "$out/libwmsherpa.so"
    echo "$abi libwmsherpa.so: $(wc -c < "$dest/libwmsherpa.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a x86_64; do
    if wanted "$abi"; then build "$abi"; fi
done
