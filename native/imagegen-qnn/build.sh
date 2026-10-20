#!/usr/bin/env bash
# Builds libwmimagegen_qnn.so, the sticker generator's NPU runner, for
# arm64-v8a (the only ABI Qualcomm's QNN runtime ships) into
# core/intelligence/src/imagegennpu/jniLibs/. Fetches the QNN API headers
# from Qualcomm's own repository at a pinned commit unless QNN_INCLUDE points
# at a QAIRT SDK's include/QNN. The headers are compiled against, never
# committed: they carry Qualcomm's notice. Needs CMake and Ninja on PATH.
#
#   ANDROID_NDK=/path/to/ndk native/imagegen-qnn/build.sh
set -euo pipefail

# qualcomm/geniex-qairt-plugin, qnn-api/include: QNN_SDK_BUILD_ID v2.50.40,
# API 2.39, the SDK line Qualcomm AI Hub compiled the context binaries with.
HEADERS_REPO=https://github.com/qualcomm/geniex-qairt-plugin
HEADERS_COMMIT=2c3d4fcd2e3c8c917da6308c1459ccf83086b29f

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=${IMAGEGEN_QNN_OUT:-$REPO/core/intelligence/src/imagegennpu/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

INC=${QNN_INCLUDE:-}
if [ -z "$INC" ]; then
    git init -q "$WORK/headers"
    git -C "$WORK/headers" remote add origin "$HEADERS_REPO"
    git -C "$WORK/headers" sparse-checkout set qnn-api/include
    git -C "$WORK/headers" fetch -q --depth 1 --filter=blob:none origin "$HEADERS_COMMIT"
    git -C "$WORK/headers" checkout -q FETCH_HEAD
    INC=$WORK/headers/qnn-api/include
fi
INC=$(cd "$INC" && pwd -P)
[ -f "$INC/QnnInterface.h" ] && [ -f "$INC/HTP/QnnHtpDevice.h" ] || { echo "no QNN headers in $INC" >&2; exit 1; }

MAP="-ffile-prefix-map=$INC=qnn -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
abi=arm64-v8a
cmake -S "$HERE" -B "$WORK/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS="$MAP" -DQNN_INCLUDE="$INC" > "$WORK/cmake.log" 2>&1 || { tail -30 "$WORK/cmake.log"; exit 1; }
cmake --build "$WORK/build" --target wmimagegen_qnn > "$WORK/build.log" 2>&1 || { tail -60 "$WORK/build.log"; exit 1; }
mkdir -p "$OUT/$abi"
"$BIN/llvm-strip" --strip-unneeded -o "$OUT/$abi/libwmimagegen_qnn.so" "$WORK/build/libwmimagegen_qnn.so"
echo "$abi libwmimagegen_qnn.so: $(wc -c < "$OUT/$abi/libwmimagegen_qnn.so") bytes"
