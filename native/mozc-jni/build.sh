#!/usr/bin/env bash
# Builds libwmmozc.so (mozc_jni.cc + mozc_bridge.cc over Mozc's converter)
# for every ABI and copies it into core/input/src/mozc/jniLibs/. Mozc builds
# with Bazel upstream, and so does this: the bridge is copied into a Mozc
# checkout at the pinned release as a package of its own (src/wmkeyboard/)
# and built there with Mozc's own Android configuration, the one its CI
# uses for libmozc.so. Fetches Mozc and Bazel (through bazelisk) at pinned
# versions, both checked, unless MOZC_SRC points at a checkout already.
#
#   ANDROID_NDK=/path/to/ndk-r29 native/mozc-jni/build.sh
#
# With --data DIR it also builds Mozc's system dictionary, mozc.data, into
# DIR (on the host, since its generators run there). That file is the
# Japanese pack the keyboard downloads (CjkDictCatalog, `mozc`), not part of
# the library; its SHA-256 is pinned in the catalog. See README.md.
set -euo pipefail

MOZC_TAG=3.34.6239
MOZC_COMMIT=76887c679e1e4f156102e4bc62ea9cf9174678a3
BAZELISK_VERSION=1.29.0
# Bazel itself, at the version Mozc's src/.bazeliskrc names.
BAZEL_VERSION=9.0.2

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# The directory :core:input packages off Play and :feature:mozc on Play.
OUT=${MOZC_OUT:-$REPO/core/input/src/mozc/jniLibs}
DATA_OUT=
if [ "${1:-}" = "--data" ]; then
    DATA_OUT=${2:?--data needs a directory}
    mkdir -p "$DATA_OUT"
    DATA_OUT=$(cd "$DATA_OUT" && pwd)
fi
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r29)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT

retry() {
    local n
    for n in 1 2 3 4 5; do
        "$@" && return 0
        echo "attempt $n failed, retrying in $((n * 30)) s: $*" >&2
        sleep $((n * 30))
    done
    return 1
}
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64)
        host=linux-amd64
        BAZELISK_SHA256=5a408715e932c0250d28bd84555f12edbf70117de42f9181691c736eacc4a992
        BAZEL_SHA256=422e7a1690b76d7e615c29091d3aca28d0bd3a93fe3c93cbefb8f72d774926d5 ;;
    Darwin-arm64)
        host=darwin-arm64
        BAZELISK_SHA256=cee851f726789227d5561004e9904a52be45c3efb56f8b38b6993d6adbaa0409
        BAZEL_SHA256=525cfcbf9790af7319ea78c9ff2053b8a5634013c41783440b5d82433f14d280 ;;
    *) echo "No pinned bazelisk for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
BAZELISK=$WORK/bazelisk
retry curl -fsSL -o "$BAZELISK" \
    "https://github.com/bazelbuild/bazelisk/releases/download/v$BAZELISK_VERSION/bazelisk-$host"
[ "$(sha256 "$BAZELISK")" = "$BAZELISK_SHA256" ] || { echo "bazelisk checksum mismatch" >&2; exit 1; }
chmod +x "$BAZELISK"
export USE_BAZEL_VERSION=$BAZEL_VERSION BAZELISK_VERIFY_SHA256=$BAZEL_SHA256
export BAZELISK_HOME=${BAZELISK_HOME:-$WORK/bazelisk-home}

SRC=${MOZC_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/mozc
    retry git clone --quiet --depth 1 --branch "$MOZC_TAG" https://github.com/google/mozc "$SRC"
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$MOZC_COMMIT" ] || { echo "mozc is at $got, expected $MOZC_COMMIT" >&2; exit 1; }

# The bridge as a package of the Mozc workspace. Mozc's MODULE.bazel looks
# for the NDK at third_party/ndk/android-ndk-r29 and leaves the API level at
# rules_android_ndk's default, 31; the keyboard runs from Android 7 (24).
rm -rf "$SRC/src/wmkeyboard"
mkdir -p "$SRC/src/wmkeyboard" "$SRC/src/third_party/ndk"
cp "$HERE/BUILD.bazel" "$HERE/mozc_bridge.h" "$HERE/mozc_bridge.cc" "$HERE/mozc_jni.cc" \
    "$HERE/host_test.cc" "$HERE/jni.map" "$SRC/src/wmkeyboard/"
ln -sfn "$NDK" "$SRC/src/third_party/ndk/android-ndk-r29"
module=$SRC/src/MODULE.bazel
if ! grep -q 'api_level = 24' "$module"; then
    python3 - "$module" <<'EOF'
import sys
path = sys.argv[1]
text = open(path).read()
anchor = 'path = "$WORKSPACE_ROOT/third_party/ndk/android-ndk-r29",'
if anchor not in text:
    sys.exit("MODULE.bazel no longer configures the NDK where build.sh expects")
open(path, "w").write(text.replace(anchor, anchor + "\n    api_level = 24,", 1))
EOF
fi

# Bazel runs every action with paths relative to its execution root, so
# nothing of this machine reaches the library; a fresh output root per run
# keeps two builds from sharing anything but the pinned inputs.
ROOT=${MOZC_BAZEL_ROOT:-$WORK/bazel-root}
bazel() { (cd "$SRC/src" && "$BAZELISK" --output_user_root="$ROOT" "$@"); }

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
targets=()
for abi in arm64-v8a armeabi-v7a x86_64; do
    if wanted "$abi"; then targets+=("//wmkeyboard:wmmozc.$abi"); fi
done
log=$WORK/bazel.log
bazel build --config oss_android --config release_build "${targets[@]}" > "$log" 2>&1 \
    || { tail -60 "$log"; exit 1; }
for abi in arm64-v8a armeabi-v7a x86_64; do
    wanted "$abi" || continue
    lib=$(bazel cquery --config oss_android --config release_build --output=files \
        "//wmkeyboard:wmmozc.$abi" 2>/dev/null | head -1)
    mkdir -p "$OUT/$abi"
    "$BIN/llvm-strip" --strip-unneeded -o "$OUT/$abi/libwmmozc.so" "$SRC/src/$lib"
    echo "$abi libwmmozc.so: $(wc -c < "$OUT/$abi/libwmmozc.so") bytes"
done

if [ -n "$DATA_OUT" ]; then
    # macOS: the generators need std::filesystem, newer than Bazel's
    # default minimum macOS.
    case "$(uname -s)" in
        Darwin) hostcfg=(--config oss_macos --macos_minimum_os=11.0 --host_macos_minimum_os=11.0) ;;
        *) hostcfg=(--config oss_linux) ;;
    esac
    bazel build "${hostcfg[@]}" --config release_build //data_manager/oss:mozc_dataset_for_oss \
        > "$log" 2>&1 || { tail -60 "$log"; exit 1; }
    cp "$SRC/src/bazel-bin/data_manager/oss/mozc.data" "$DATA_OUT/mozc.data"
    chmod 644 "$DATA_OUT/mozc.data"
    echo "mozc.data: $(wc -c < "$DATA_OUT/mozc.data") bytes, sha256 $(sha256 "$DATA_OUT/mozc.data")"
fi
