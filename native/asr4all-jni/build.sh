#!/usr/bin/env bash
# Builds libwmasr4all.so for every ABI: first a selective, static ExecuTorch
# (only the operators in ops.txt, the XNNPACK delegate, the Module extension),
# then asr4all_jni.cpp over it; copies the results into
# core/voice/src/full/jniLibs/. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/asr4all-jni/build.sh
#
# Needs CMake, Ninja and a Python with ExecuTorch's codegen requirements
# (pip install executorch torch pyyaml) on PATH, for the operator registration
# it generates. Fetches ExecuTorch at the pinned commit unless EXECUTORCH_SRC
# points at a checkout of it (ExecuTorch insists the directory be named
# "executorch").
set -euo pipefail

ET_TAG=v1.5.1
ET_COMMIT=3b60683923245cf472b7323426920e15623ba361

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=${ASR4ALL_WORK:-$(mktemp -d)}
mkdir -p "$WORK"
PY=$(command -v python3)

SRC=${EXECUTORCH_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/executorch
    git clone --quiet --depth 1 --branch "$ET_TAG" https://github.com/pytorch/executorch "$SRC"
    git -C "$SRC" submodule update --init --depth 1 --recursive --quiet \
        backends/xnnpack/third-party third-party/flatbuffers third-party/flatcc \
        third-party/json third-party/gflags third-party/pocketfft third-party/ao
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$ET_COMMIT" ] || { echo "ExecuTorch is at $got, expected $ET_COMMIT" >&2; exit 1; }
OPS=$(grep -v '^\s*$' "$HERE/ops.txt" | paste -sd, -)

for abi in arm64-v8a armeabi-v7a x86_64; do
    et=$WORK/et-$abi
    prefix=$WORK/et-install-$abi
    common=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
        -DCMAKE_BUILD_TYPE=Release
    )
    cmake -S "$SRC" -B "$et" -G Ninja "${common[@]}" -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DPYTHON_EXECUTABLE="$PY" -DBUILD_TESTING=OFF \
        -DEXECUTORCH_BUILD_XNNPACK=ON \
        -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON \
        -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON \
        -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON \
        -DEXECUTORCH_BUILD_KERNELS_OPTIMIZED=OFF -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=OFF \
        -DEXECUTORCH_BUILD_DEVTOOLS=OFF -DEXECUTORCH_BUILD_ANDROID_JNI=OFF \
        -DEXECUTORCH_ENABLE_LOGGING=OFF \
        -DEXECUTORCH_SELECT_OPS_LIST="$OPS" > "$et.log" 2>&1 || { tail -30 "$et.log"; exit 1; }
    cmake --build "$et" --target install >> "$et.log" 2>&1 || { tail -40 "$et.log"; exit 1; }

    jni=$WORK/jni-$abi
    cmake -S "$HERE" -B "$jni" -G Ninja "${common[@]}" -DET_PREFIX="$prefix" \
        -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$SRC=executorch -ffile-prefix-map=$HERE=." > "$jni.log" 2>&1 \
        || { tail -30 "$jni.log"; exit 1; }
    cmake --build "$jni" >> "$jni.log" 2>&1 || { tail -40 "$jni.log"; exit 1; }
    dest=$REPO/core/voice/src/full/jniLibs/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmasr4all.so" "$jni/libwmasr4all.so"
    echo "$abi libwmasr4all.so: $(wc -c < "$dest/libwmasr4all.so") bytes"
done
