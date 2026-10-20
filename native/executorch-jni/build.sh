#!/usr/bin/env bash
# Builds libwmexecutorch.so for every ABI: first a selective, static
# ExecuTorch (only the operators in ops.txt, optimized kernels where they
# exist, the XNNPACK delegate, the Module extension), then executorch_jni.cpp
# over it; copies the results into
# core/voice/src/executorch/jniLibs/. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/executorch-jni/build.sh
#
# Needs CMake, Ninja and a Python 3 with PyYAML and typing_extensions on PATH,
# for the operator registration it generates. The generator is ExecuTorch's
# own, over PyTorch's torchgen, which is fetched from PyTorch's sources at the
# pinned commit rather than taken from an installed torch, so the generated
# code does not depend on what the machine has. Fetches ExecuTorch at the
# pinned commit unless EXECUTORCH_SRC points at a checkout of it (ExecuTorch
# insists the directory be named "executorch").
#
# Paths on the build machine are mapped out of the library, so two machines
# with the same NDK build the same bytes; F-Droid's reproducible build relies
# on that.
set -euo pipefail

ET_TAG=v1.5.1
ET_COMMIT=3b60683923245cf472b7323426920e15623ba361
# The PyTorch release ExecuTorch v1.5.1 pins (torch_pin.py), for torchgen.
TORCH_TAG=v2.14.0

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the libraries go: the jniLibs directory of the module that packages
# them for the non-Play builds. F-Droid's recipe scandeletes the same path.
OUT=${ET_OUT:-$REPO/core/voice/src/executorch/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=${ET_WORK:-$(mktemp -d)}
mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd -P)
# A scratch directory of our own is about 4.7 GB after three ABIs; remove it
# on exit like the other build scripts. An ET_WORK the caller named is kept,
# since reusing it is the point of naming one.
[ -n "${ET_WORK:-}" ] || trap 'rm -rf "$WORK"' EXIT
PY=$(command -v python3)

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

# PyTorch, from source at the pinned tag, for two things a selective build
# takes from an installed torch: torchgen with the two operator tables it
# reads (laid out as the wheel packages them, torchgen/packaged/ATen/native/),
# and the C++ headers the optimized kernels include (ATen's CPU vector
# classes and the c10 utilities under them). ExecuTorch finds the headers by
# asking Python where the torch package is, so they go in a stub package of
# that name. Nothing of torch is compiled; the headers it would have
# generated are written below with the values a CPU-only build has.
PT=$WORK/pytorch-src
if [ ! -f "$PT/.wm-ready" ]; then
    rm -rf "$PT"
    retry fresh_clone "$PT" --quiet --depth 1 --branch "$TORCH_TAG" --filter=blob:none --no-checkout \
        https://github.com/pytorch/pytorch
    git -C "$PT" sparse-checkout set --no-cone /torchgen/ /aten/src/ATen/ /c10/ /torch/headeronly/
    retry git -C "$PT" checkout --quiet
    mkdir -p "$PT/torchgen/packaged/ATen/native"
    cp "$PT/aten/src/ATen/native/native_functions.yaml" "$PT/aten/src/ATen/native/tags.yaml" \
        "$PT/torchgen/packaged/ATen/native/"
    touch "$PT/.wm-ready"
fi
STUB=$WORK/torch-stub
rm -rf "$STUB"
mkdir -p "$STUB/torch/include/torch"
: > "$STUB/torch/__init__.py"
(cd "$PT/aten/src" && find ATen -name '*.h' | LC_ALL=C sort | tar -cf - -T -) | tar -xf - -C "$STUB/torch/include"
(cd "$PT" && find c10 -name '*.h' | LC_ALL=C sort | tar -cf - -T -) | tar -xf - -C "$STUB/torch/include"
(cd "$PT/torch" && find headeronly -name '*.h' | LC_ALL=C sort | tar -cf - -T -) | tar -xf - -C "$STUB/torch/include/torch"
cat > "$STUB/torch/include/ATen/Config.h" <<'H'
#pragma once
#define AT_MKLDNN_ENABLED() 0
#define AT_MKLDNN_ACL_ENABLED() 0
#define AT_MKL_ENABLED() 0
#define AT_MKL_SEQUENTIAL() 0
#define AT_POCKETFFT_ENABLED() 0
#define AT_NNPACK_ENABLED() 0
#define CAFFE2_STATIC_LINK_CUDA() 0
#define AT_BUILD_WITH_BLAS() 0
#define AT_BUILD_WITH_LAPACK() 0
#define AT_PARALLEL_OPENMP 0
#define AT_PARALLEL_NATIVE 1
#define AT_BLAS_F2C() 0
#define AT_BLAS_USE_CBLAS_DOT() 0
#define AT_KLEIDIAI_ENABLED() 0
#define AT_USE_EIGEN_SPARSE() 0
H
printf '#ifndef C10_MACROS_CMAKE_MACROS_H_\n#define C10_MACROS_CMAKE_MACROS_H_\n#endif\n' \
    > "$STUB/torch/include/torch/headeronly/macros/cmake_macros.h"
v=${TORCH_TAG#v}
printf '#pragma once\n#define TORCH_VERSION_MAJOR %s\n#define TORCH_VERSION_MINOR %s\n#define TORCH_VERSION_PATCH %s\n#define TORCH_VERSION_ABI_TAG 0\n#define TORCH_VERSION "%s"\n' \
    "${v%%.*}" "$(echo "$v" | cut -d. -f2)" "${v##*.}" "$v" > "$STUB/torch/include/torch/headeronly/version.h"
export PYTHONPATH=$PT:$STUB${PYTHONPATH:+:$PYTHONPATH}
"$PY" -c 'import yaml, typing_extensions, torchgen' || {
    echo "python3 needs PyYAML and typing_extensions" >&2; exit 1; }

SRC=${EXECUTORCH_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/executorch
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$ET_TAG" https://github.com/pytorch/executorch
    retry git -C "$SRC" submodule update --init --depth 1 --recursive --quiet \
        backends/xnnpack/third-party third-party/flatbuffers third-party/flatcc \
        third-party/json third-party/gflags third-party/pocketfft third-party/ao
    # Eigen lives on gitlab.com alone, which refused clones under load for
    # hours on the day 0.6.0 shipped. Git fetches the submodule by its pinned
    # commit hash, so a mirror cannot hand over different code: try
    # upstream, then two GitHub mirrors of it.
    eigen=kernels/optimized/third-party/eigen
    eigen_commit=$(git -C "$SRC" ls-tree HEAD "$eigen" | awk '{print $3}')
    fetch_eigen() {  # url
        rm -rf "${SRC:?}/$eigen" && git init --quiet "$SRC/$eigen" &&
            git -C "$SRC/$eigen" fetch --quiet --depth 1 "$1" "$eigen_commit" &&
            git -C "$SRC/$eigen" checkout --quiet FETCH_HEAD
    }
    for url in https://gitlab.com/libeigen/eigen.git https://github.com/eigen-mirror/eigen.git \
               https://github.com/live-clones/eigen.git; do
        retry fetch_eigen "$url" && break
    done
    [ "$(git -C "$SRC/$eigen" rev-parse HEAD 2>/dev/null)" = "$eigen_commit" ] || {
        echo "could not fetch Eigen $eigen_commit from any source" >&2; exit 1; }
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$ET_COMMIT" ] || { echo "ExecuTorch is at $got, expected $ET_COMMIT" >&2; exit 1; }
SRC=$(cd "$SRC" && pwd -P)
OPS=$(grep -v '^\s*$' "$HERE/ops.txt" | paste -sd, -)
MAP="-ffile-prefix-map=$SRC=executorch -ffile-prefix-map=$STUB/torch/include=torch -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

for abi in ${ET_ABIS:-${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64}}; do
    et=$WORK/et-$abi
    prefix=$WORK/et-install-$abi
    common=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP" -DCMAKE_ASM_FLAGS="$MAP"
    )
    cmake -S "$SRC" -B "$et" -G Ninja "${common[@]}" -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DPYTHON_EXECUTABLE="$PY" -DBUILD_TESTING=OFF \
        -DEXECUTORCH_BUILD_XNNPACK=ON \
        -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON \
        -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON \
        -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON \
        -DEXECUTORCH_BUILD_KERNELS_OPTIMIZED=ON -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=OFF \
        -DEXECUTORCH_BUILD_DEVTOOLS=OFF -DEXECUTORCH_BUILD_ANDROID_JNI=OFF \
        -DEXECUTORCH_ENABLE_LOGGING=OFF \
        -DEXECUTORCH_SELECT_OPS_LIST="$OPS" > "$et.log" 2>&1 || { tail -30 "$et.log"; exit 1; }
    cmake --build "$et" --target install >> "$et.log" 2>&1 || { tail -40 "$et.log"; exit 1; }

    jni=$WORK/jni-$abi
    cmake -S "$HERE" -B "$jni" -G Ninja "${common[@]}" -DET_PREFIX="$prefix" > "$jni.log" 2>&1 \
        || { tail -30 "$jni.log"; exit 1; }
    cmake --build "$jni" >> "$jni.log" 2>&1 || { tail -40 "$jni.log"; exit 1; }
    dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmexecutorch.so" "$jni/libwmexecutorch.so"
    echo "$abi libwmexecutorch.so: $(wc -c < "$dest/libwmexecutorch.so") bytes"
done
