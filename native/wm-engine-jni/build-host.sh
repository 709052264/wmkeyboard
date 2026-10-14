#!/usr/bin/env bash
# Builds libwm_engine_jni for this machine, for the JVM unit tests.
#
#   native/wm-engine-jni/build-host.sh
#
# The Kotlin facades in core/language load the engine through EngineNative.
# On a device that is the .so in the APK; in a JVM test it is the host build
# this script makes, found under native/wm-engine-jni/target/release by
# walking up from the test's working directory to settings.gradle.kts. The
# Gradle test tasks of the modules that touch the engine run this first.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
cargo build --release --locked
case "$(uname -s)" in
    Darwin) f=target/release/libwm_engine_jni.dylib ;;
    Linux) f=target/release/libwm_engine_jni.so ;;
    *) f=target/release/wm_engine_jni.dll ;;
esac
echo "$f: $(wc -c < "$f" | tr -d ' ') bytes"
