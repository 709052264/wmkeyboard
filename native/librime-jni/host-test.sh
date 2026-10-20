#!/usr/bin/env bash
# Builds the desktop side of the RIME bridge: rime_host_test, which drives
# rime_bridge.cc against real schema data, and libwmrime for the desktop JVM,
# which RimeNative can load (-Djava.library.path) to drive the Kotlin side.
# Neither ships; they are for checking a change before a phone sees it.
#
#   RIME_SRC=/path/to/librime BOOST_SRC=/path/to/boost-1.92.0 \
#   JNI_INCLUDE=/dir/with/jni.h native/librime-jni/host-test.sh [build-dir]
#
# RIME_SRC is a librime checkout at the tag build.sh pins, with its
# deps/leveldb, marisa-trie, opencc and yaml-cpp submodules; BOOST_SRC the
# unpacked Boost release build.sh fetches. JNI_INCLUDE is only needed when
# JAVA_HOME has no include/ (a JRE): the NDK's sysroot jni.h, copied alone
# into a directory, works.
#
# Then, with the packs from the data repository unpacked into shared/:
#
#   build/rime_host_test shared/ user/ luna_pinyin nihaoshijie 1 0
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-${TMPDIR:-/tmp}/wmrime-host}
: "${RIME_SRC:?set RIME_SRC to a librime checkout}"
: "${BOOST_SRC:?set BOOST_SRC to an unpacked Boost}"

cmake -S "$HERE" -B "$OUT" -G Ninja -DCMAKE_BUILD_TYPE=Release -DWM_HOST_TEST=ON \
    -DRIME_SRC="$RIME_SRC" -DBOOST_SRC="$BOOST_SRC" -DRIME_VERSION=host \
    -DJNI_INCLUDE="${JNI_INCLUDE:-}"
cmake --build "$OUT" --target rime_host_test wmrime_host
echo "$OUT/rime_host_test"
ls "$OUT"/libwmrime.*
