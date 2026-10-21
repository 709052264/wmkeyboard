#!/usr/bin/env bash
# Builds libwmllama for this Mac (or Linux) from the same CMakeLists.txt the
# phone libraries come from, so the JNI bridge and LlamaNative's Kotlin can be
# checked on a desktop JVM without a phone. CPU backend only, like the phones'
# CPU builds, unless WM_GPU=metal asks for Apple's GPU, which is there to
# measure what a GPU backend changes; no phone runs it.
#
#   LLAMA_SRC=/path/to/llama.cpp [WM_GPU=metal] native/llamacpp-jni/host-test.sh [out-dir]
#
# Prints the library's path. LLAMA_SRC must be the pinned commit (build.sh
# says which); without it the script clones that commit into a temp dir.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$(mktemp -d)}
# shellcheck disable=SC1091
. "$HERE/pin.sh"

SRC=${LLAMA_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$OUT/llama.cpp
    [ -d "$SRC" ] || git clone --quiet --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$SRC"
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$LLAMA_COMMIT" ] || { echo "llama.cpp is at $got, expected $LLAMA_COMMIT" >&2; exit 1; }

# The NDK's jni.h, which needs no jni_md.h, copied alone into a directory:
# the JDK Android Studio bundles has no headers, and a path with a space in
# it would not survive CMAKE_CXX_FLAGS anyway.
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-$(ls -d ~/Library/Android/sdk/ndk/* 2>/dev/null | tail -1)}}
mkdir -p "$OUT/jni"
cp "$(ls "$NDK"/toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h | head -1)" "$OUT/jni/"
# On an ARM host, the instruction set of the phones' libwmllama_v82.so, so
# the speed measured here is that library's kernels, not the ARMv8.0 ones.
ARCH_FLAGS=()
case "$(uname -m)" in arm64|aarch64) ARCH_FLAGS=(-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16) ;; esac
cmake -S "$HERE" -B "$OUT/build" -G Ninja -DCMAKE_BUILD_TYPE=Release "${ARCH_FLAGS[@]}" \
    -DLLAMA_SRC="$SRC" -DWM_LLAMA_BUILD="$LLAMA_BUILD" -DWM_LLAMA_COMMIT="$LLAMA_SHORT" -DWM_GPU="${WM_GPU:-}" \
    -DCMAKE_CXX_FLAGS="-I$OUT/jni" \
    > "$OUT/configure.log" 2>&1 || { tail -30 "$OUT/configure.log"; exit 1; }
cmake --build "$OUT/build" --target wmllama > "$OUT/build.log" 2>&1 || { tail -40 "$OUT/build.log"; exit 1; }
ls "$OUT"/build/libwmllama.*
