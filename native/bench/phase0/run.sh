#!/usr/bin/env bash
# Phase 0 benchmark harness for native/RUST_NEON_PLAN.md. No Gradle: compiles
# Phase0Bench.kt with the Kotlin compiler from the Gradle cache against the
# release classes jars a previous build left in a checkout (plus Samplers.kt
# from source, which is newer than most checkouts' last build), then
#   host     runs it on this machine's JVM
#   dex      d8s it (plus the jars and kotlin-stdlib) into out/bench.dex
#   device   pushes the dex and runs it under ART with app_process
#
# Usage: native/bench/phase0/run.sh <host|dex|device> [only=vector,gif,ocr,image,glide,audio] [budget=1]
# Env:   CHECKOUT  checkout whose core/*/build jars to use (default: this one)
#        OUT       work dir (default: native/bench/phase0/out, gitignored)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
CHECKOUT="${CHECKOUT:-$REPO}"
OUT="${OUT:-$HERE/out}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
GC="$HOME/.gradle/caches/modules-2/files-2.1"
KV=2.4.21
mode="${1:-host}"; shift || true

cached() { find "$GC/$1" -name "$2" ! -name '*sources*' | sort | tail -1; }
STDLIB="$(cached org.jetbrains.kotlin/kotlin-stdlib/$KV "kotlin-stdlib-$KV.jar")"
KCP="$(cached org.jetbrains.kotlin/kotlin-compiler-embeddable/$KV '*.jar'):$STDLIB:$(cached org.jetbrains.kotlin/kotlin-script-runtime '*.jar'):$(cached org.jetbrains.kotlin/kotlin-reflect '*.jar'):$(cached org.jetbrains.kotlin/kotlin-daemon-embeddable/$KV '*.jar'):$(cached org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2 '*.jar'):$(cached org.jetbrains/annotations '*.jar')"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"

MODULES="common config content intelligence prediction voice language input emoji"
mkdir -p "$OUT/lib"
LIBS=""
for m in $MODULES; do
  src="$CHECKOUT/core/$m/build/intermediates/runtime_library_classes_jar/release/bundleLibRuntimeToJarRelease/classes.jar"
  [ -f "$src" ] || { echo "missing $src: build that checkout once first" >&2; exit 1; }
  cp "$src" "$OUT/lib/core-$m.jar"
  LIBS="$LIBS:$OUT/lib/core-$m.jar"
done
LIBS="${LIBS#:}"
FRIENDS="$(echo "$LIBS" | tr ':' ',')"

if [ ! -f "$OUT/classes/com/wasimaster/wmkeyboard/bench/Phase0Bench.class" ] || [ "$HERE/Phase0Bench.kt" -nt "$OUT/classes/com/wasimaster/wmkeyboard/bench/Phase0Bench.class" ]; then
  rm -rf "$OUT/classes"
  java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
    -Xfriend-paths="$FRIENDS" -cp "$STDLIB:$LIBS:$ANDROID_JAR" -d "$OUT/classes" "$HERE/Phase0Bench.kt" \
    "$REPO/core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/imagegen/Samplers.kt"
fi

case "$mode" in
  host)
    # android.jar last: its stubs only satisfy class loading; nothing here calls them.
    java -Xss8m -cp "$OUT/classes:$STDLIB:$LIBS:$ANDROID_JAR" com.wasimaster.wmkeyboard.bench.Phase0Bench "$REPO/app/dictionaries-src/en.txt" "$@"
    ;;
  dex|device)
    if [ ! -f "$OUT/bench.dex.jar" ] || [ "$OUT/classes" -nt "$OUT/bench.dex.jar" ]; then
      (cd "$OUT/classes" && jar cf "$OUT/bench-classes.jar" .)
      "$SDK/build-tools/36.0.0/d8" --release --min-api 31 --lib "$ANDROID_JAR" --output "$OUT/bench.dex.jar" \
        "$OUT/bench-classes.jar" "$STDLIB" $(echo "$LIBS" | tr ':' ' ')
    fi
    [ "$mode" = dex ] && exit 0
    adb push "$OUT/bench.dex.jar" /data/local/tmp/wmbench.jar >/dev/null
    adb push "$REPO/app/dictionaries-src/en.txt" /data/local/tmp/wmbench-en.txt >/dev/null
    adb shell "CLASSPATH=/data/local/tmp/wmbench.jar app_process /system/bin com.wasimaster.wmkeyboard.bench.Phase0Bench /data/local/tmp/wmbench-en.txt $*"
    ;;
esac
