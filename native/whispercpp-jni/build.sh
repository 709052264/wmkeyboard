#!/usr/bin/env bash
# Builds libwmwhispercpp*.so (whispercpp_jni.cpp over whisper.cpp + ggml, CPU
# backend) for every ABI, and the arm64 OpenCL variant for Adreno GPUs, and
# copies them into core/voice/src/main/jniLibs/. Fetches whisper.cpp, and the
# OpenCL headers and loader, at the pinned commits unless WHISPER_SRC,
# OPENCL_HEADERS_SRC and OPENCL_LOADER_SRC point at checkouts of them. Needs
# CMake, Ninja and Python 3 on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/whispercpp-jni/build.sh
set -euo pipefail

WHISPER_TAG=v1.9.5
WHISPER_COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037
# What the OpenCL variant compiles and links against: Khronos's headers, and
# its ICD loader built only to have a libOpenCL.so to link to. Neither ships:
# on the phone the library finds the vendor's own libOpenCL.so by that name.
# The same pins as native/llamacpp-jni/pin.sh.
OPENCL_HEADERS_TAG=v2025.07.22
OPENCL_HEADERS_COMMIT=8a97ebc88daa3495d6f57ec10bb515224400186f
OPENCL_LOADER_TAG=v2025.07.22
OPENCL_LOADER_COMMIT=ad770a1b64c6b8d5f2ed4e153f22e4f45939f27f

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

# dir, env override, tag, commit, GitHub repository
khronos() {
    local dir=$1 src=$2 tag=$3 commit=$4 repo=$5
    if [ -z "$src" ]; then
        src=$WORK/$dir
        retry fresh_clone "$src" --quiet --depth 1 --branch "$tag" "https://github.com/KhronosGroup/$repo"
    fi
    local at
    at=$(git -C "$src" rev-parse HEAD)
    [ "$at" = "$commit" ] || { echo "$repo is at $at, expected $commit" >&2; exit 1; }
    echo "$src"
}

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

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# arm64: a baseline ARMv8.0 build every 64-bit phone runs, and one for
# ARMv8.2 cores with dot-product and half-precision instructions (2018 on),
# which quantized matrix maths runs several times faster on. WhisperCpp picks
# between them from /proc/cpuinfo at load.
if wanted arm64-v8a; then
    build arm64-v8a wmwhispercpp     -DGGML_CPU_ARM_ARCH=armv8-a
    build arm64-v8a wmwhispercpp_v82 -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
fi
# arm64 with OpenCL: the ARMv8.2 build plus Qualcomm's Adreno backend, bound
# to its own JNI class (WhisperCppGpu) so it loads beside a CPU build. It
# links to libOpenCL.so by name, which the loader built here provides at link
# time and the phone's vendor library at run time; a phone whose app
# namespace offers none fails the load and dictates on the CPU build.
if wanted arm64-v8a && [ "${WMKB_WHISPER_GPU:-1}" = 1 ]; then
    CL_HEADERS=$(khronos OpenCL-Headers "${OPENCL_HEADERS_SRC:-}" "$OPENCL_HEADERS_TAG" "$OPENCL_HEADERS_COMMIT" OpenCL-Headers)
    CL_LOADER=$(khronos OpenCL-ICD-Loader "${OPENCL_LOADER_SRC:-}" "$OPENCL_LOADER_TAG" "$OPENCL_LOADER_COMMIT" OpenCL-ICD-Loader)
    cmake -S "$CL_LOADER" -B "$WORK/cl-loader" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release \
        -DOPENCL_ICD_LOADER_HEADERS_DIR="$CL_HEADERS" -DOPENCL_ICD_LOADER_BUILD_SHARED_LIBS=ON \
        -DBUILD_TESTING=OFF -DENABLE_OPENCL_LAYERS=OFF > "$WORK/cl-loader.log" 2>&1 \
        && cmake --build "$WORK/cl-loader" --target OpenCL >> "$WORK/cl-loader.log" 2>&1 \
        || { tail -30 "$WORK/cl-loader.log"; exit 1; }
    build arm64-v8a wmwhispercpp_opencl -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DWM_GPU=opencl \
        -DOpenCL_INCLUDE_DIR="$CL_HEADERS" -DOpenCL_LIBRARY="$WORK/cl-loader/libOpenCL.so"
fi
if wanted armeabi-v7a; then
    build armeabi-v7a wmwhispercpp
fi
# x86_64 (Chromebooks, emulators): SSE4.2 is what the Android ABI guarantees.
if wanted x86_64; then
    build x86_64 wmwhispercpp -DGGML_SSE42=ON -DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_FMA=OFF \
        -DGGML_F16C=OFF -DGGML_AVX512=OFF -DGGML_BMI2=OFF -DGGML_AVX_VNNI=OFF
fi
