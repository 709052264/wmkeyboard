#!/usr/bin/env bash
# Builds, from source, every native library the lite edition ships, into the
# src/main/jniLibs directories of :core:language, :core:intelligence and
# :core:voice:
#
#   Text engines        wm-engine-jni/build.sh    libwm_engine_jni.so
#   Harper grammar      harper-jni/build.sh       libharper_jni.so
#   Offline Whisper     whispercpp-jni/build.sh   libwmwhispercpp*.so
#   Asr4All, Parakeet,  executorch-jni/build.sh   libwmexecutorch.so
#   Moonshine
#
# F-Droid's recipe deletes the committed copies of these libraries before its
# source scan and runs this script in their place (fdroid/README.md), and the
# release workflow's `fdroid` job builds the reference APK the same way. Each
# script maps the build machine's paths out of its output, so the two builds
# come out byte for byte the same.
#
#   ANDROID_NDK=/path/to/ndk native/build-lite-libs.sh
#
# WMKB_ABIS="arm64-v8a" builds one ABI's libraries instead of all three
# (any of arm64-v8a, armeabi-v7a and x86_64, space-separated). F-Droid's
# recipe has one build entry per ABI and each passes its own, so ExecuTorch
# is compiled once per entry rather than three times. Every ABI is built on
# its own either way, so its libraries come out the same bytes.
#
# The text engines are the phonetic and fixed-layout engines every edition
# types Bengali, Hindi, Urdu and the Indic languages through (native/wm-engine).
#
# Needs git, curl, CMake, Ninja, a host C++ compiler and Python 3 with PyYAML
# and typing_extensions. Rust comes from rustup, which is installed at a
# pinned version when the machine has none.
set -euo pipefail

RUSTUP_VERSION=1.28.2
RUSTUP_SHA256=20a06e644b0d9bd2fbdbfd52d42540bdde820ea7df86e92e533c073da0cdd43c

HERE=$(cd "$(dirname "$0")" && pwd)

if ! command -v rustup >/dev/null 2>&1; then
    export PATH=${CARGO_HOME:-$HOME/.cargo}/bin:$PATH
fi
if ! command -v rustup >/dev/null 2>&1; then
    case "$(uname -s)-$(uname -m)" in
        Linux-x86_64) host=x86_64-unknown-linux-gnu ;;
        *) echo "Install rustup first: https://rustup.rs" >&2; exit 1 ;;
    esac
    init=$(mktemp -d)/rustup-init
    curl -fsSL --proto '=https' --tlsv1.2 -o "$init" \
        "https://static.rust-lang.org/rustup/archive/$RUSTUP_VERSION/$host/rustup-init"
    echo "$RUSTUP_SHA256  $init" | sha256sum -c -
    chmod +x "$init"
    "$init" -y --no-modify-path --profile minimal --default-toolchain none
    rm -f "$init"
fi

"$HERE/wm-engine-jni/build.sh"
"$HERE/harper-jni/build.sh"
"$HERE/whispercpp-jni/build.sh"
"$HERE/executorch-jni/build.sh"
