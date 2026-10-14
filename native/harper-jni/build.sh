#!/usr/bin/env bash
# Builds libharper_jni.so for every ABI and copies them into
# core/intelligence/src/main/jniLibs/. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/harper-jni/build.sh
#
# Needs rustup (the toolchain and Android targets below are installed if
# missing) and cargo-ndk at the pinned version. Paths on the build machine are
# remapped out of the library, so two machines with the same toolchain build
# the same bytes; F-Droid's reproducible build relies on that.
set -euo pipefail

RUST_VERSION=1.98.1
CARGO_NDK_VERSION=4.1.2

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the libraries go: the jniLibs directory of the module that packages
# them for the non-Play builds. F-Droid's recipe scandeletes the same path.
OUT=${HARPER_OUT:-$REPO/core/intelligence/src/main/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)

TARGETS=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android)
rustup toolchain install "$RUST_VERSION" --profile minimal --no-self-update \
    $(printf -- '--target %s ' "${TARGETS[@]}") >/dev/null
export RUSTUP_TOOLCHAIN=$RUST_VERSION
if [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$CARGO_NDK_VERSION" ]; then
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
fi

CARGO_HOME=${CARGO_HOME:-$HOME/.cargo}
SYSROOT=$(rustc --print sysroot)
# Panic locations carry source paths: the crates.io registry, the standard
# library (rust-src, when installed) and this crate's vendored sources.
export RUSTFLAGS="--remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard"
export CARGO_TARGET_DIR=${HARPER_TARGET_DIR:-$HERE/target}
# burn-ndarray, under harper-pos-utils, seeds its RNG with const_random!(u64),
# a fresh random number on every compile, which lands in the machine code.
# const-random takes a fixed seed from the environment instead.
export CONST_RANDOM_SEED=wmkeyboard-harper

cd "$HERE"
ANDROID_NDK_HOME=$NDK cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 24 \
    -o "$OUT" build --release --locked
for abi in arm64-v8a armeabi-v7a x86_64; do
    f=$OUT/$abi/libharper_jni.so
    echo "$abi libharper_jni.so: $(wc -c < "$f") bytes"
done
