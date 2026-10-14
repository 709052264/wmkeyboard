#!/usr/bin/env bash
# Builds libwmwhispercpp*.so (whispercpp_jni.cpp over whisper.cpp + ggml, CPU
# backend) for every ABI and copies them into core/voice/src/main/jniLibs/.
# Fetches whisper.cpp at the pinned commit unless WHISPER_SRC points at a
# checkout of it. Needs CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/whispercpp-jni/build.sh
set -euo pipefail

WHISPER_TAG=v1.9.5
WHISPER_COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the libraries go: the jniLibs directory of the module that packages
# them for the non-Play builds. F-Droid's recipe scandeletes the same path.
OUT=${WHISPER_OUT:-$REPO/core/voice/src/main/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

# Network fetches retry: F-Droid's builders share GitHub and GitLab with
# everyone, and a busy host refusing one clone should not fail the build.
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

SRC=${WHISPER_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/whisper.cpp
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$WHISPER_COMMIT" ] || { echo "whisper.cpp is at $got, expected $WHISPER_COMMIT" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# asserts, the NDK's libc++ headers), so two machines with the same NDK build
# the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=whisper.cpp -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

# abi  library-name  extra cmake flags
build() {
    local abi=$1 name=$2; shift 2
    local out=$WORK/build-$name-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP" -DCMAKE_ASM_FLAGS="$MAP" \
        -DWHISPER_SRC="$SRC" -DWM_LIB_NAME="$name" "$@" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmwhispercpp >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/lib$name.so" "$out/lib$name.so"
    echo "$abi lib$name.so: $(wc -c < "$dest/lib$name.so") bytes"
}

# arm64: a baseline ARMv8.0 build every 64-bit phone runs, and one for
# ARMv8.2 cores with dot-product and half-precision instructions (2018 on),
# which quantized matrix maths runs several times faster on. WhisperCpp picks
# between them from /proc/cpuinfo at load.
build arm64-v8a wmwhispercpp     -DGGML_CPU_ARM_ARCH=armv8-a
build arm64-v8a wmwhispercpp_v82 -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
build armeabi-v7a wmwhispercpp
# x86_64 (Chromebooks, emulators): SSE4.2 is what the Android ABI guarantees.
build x86_64 wmwhispercpp -DGGML_SSE42=ON -DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_FMA=OFF \
    -DGGML_F16C=OFF -DGGML_AVX512=OFF -DGGML_BMI2=OFF -DGGML_AVX_VNNI=OFF
