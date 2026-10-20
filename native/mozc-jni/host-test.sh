#!/usr/bin/env bash
# Builds the desktop side of the Mozc bridge in a Mozc checkout: mozc_host_test,
# which drives mozc_bridge.cc against a real mozc.data, libwmmozc_host.so,
# the JNI library for the desktop JVM (MozcNative loads it with
# -Djava.library.path, after a rename to the platform's name), and
# mozc.data itself. None of it ships; it is for checking a change before a
# phone sees it.
#
#   MOZC_SRC=/path/to/mozc native/mozc-jni/host-test.sh [out-dir]
#
# MOZC_SRC is a Mozc checkout at the tag build.sh pins; bazelisk must be on
# PATH (or BAZELISK pointing at it), and ANDROID_NDK set (for its jni.h). Then:
#
#   echo 'suggest きょうはいいてんき' | out/mozc_host_test out/mozc.data /tmp/mozc-user
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-${TMPDIR:-/tmp}/wmmozc-host}
: "${MOZC_SRC:?set MOZC_SRC to a Mozc checkout}"
BAZELISK=${BAZELISK:-bazelisk}
# On macOS the host tools need std::filesystem, which Bazel's default
# minimum macOS predates.
hostflags=()
case "$(uname -s)" in
    Darwin) cfg=oss_macos; ext=dylib; hostflags=(--macos_minimum_os=11.0 --host_macos_minimum_os=11.0) ;;
    *) cfg=oss_linux; ext=so ;;
esac

rm -rf "$MOZC_SRC/src/wmkeyboard"
mkdir -p "$MOZC_SRC/src/wmkeyboard" "$OUT"
cp "$HERE/BUILD.bazel" "$HERE/mozc_bridge.h" "$HERE/mozc_bridge.cc" "$HERE/mozc_jni.cc" \
    "$HERE/host_test.cc" "$HERE/jni.map" "$MOZC_SRC/src/wmkeyboard/"
# The NDK's jni.h stands alone; no JDK needs to be found.
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:?set ANDROID_NDK for its jni.h}}
mkdir -p "$MOZC_SRC/src/wmkeyboard/host_jni"
cp "$(ls "$NDK"/toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h | head -1)" "$MOZC_SRC/src/wmkeyboard/host_jni/"
cd "$MOZC_SRC/src"
"$BAZELISK" ${MOZC_BAZEL_ROOT:+--output_user_root="$MOZC_BAZEL_ROOT"} build --config "$cfg" --config release_build ${hostflags[@]+"${hostflags[@]}"} \
    //wmkeyboard:mozc_host_test //wmkeyboard:libwmmozc_host.so //data_manager/oss:mozc_dataset_for_oss
install -m 755 bazel-bin/wmkeyboard/mozc_host_test "$OUT/mozc_host_test"
install -m 644 bazel-bin/wmkeyboard/libwmmozc_host.so "$OUT/libwmmozc.$ext"
install -m 644 bazel-bin/data_manager/oss/mozc.data "$OUT/mozc.data"
ls -l "$OUT"
