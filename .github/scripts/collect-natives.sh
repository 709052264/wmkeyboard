#!/usr/bin/env bash
# Collects the project's own native libraries that an APK leaves out and pins
# instead (native delivery), named the way the data repository's `natives`
# release publishes them: `<sha256>-<name>`.
#
#   collect-natives.sh <apk> <out-dir>
#
# Reads the APK's assets/native/pins.tsv, finds every `data` library in the
# jniLibs the build packaged from, and copies each one whose bytes match its
# pin. A pinned library with no matching file fails the run: the release
# would otherwise point every phone at a file that does not exist.
set -euo pipefail

apk=$1
out=$2
mkdir -p "$out"

pins=$(unzip -p "$apk" assets/native/pins.tsv)
dirs=(core/voice/src/main/jniLibs core/voice/src/whistle/jniLibs core/voice/src/executorch/jniLibs core/voice/src/onnx/jniLibs core/intelligence/src/main/jniLibs core/intelligence/src/tesseract/jniLibs core/intelligence/src/bergamot/jniLibs core/intelligence/src/zinnia/jniLibs core/intelligence/src/ct2/jniLibs core/intelligence/src/llama/jniLibs core/intelligence/src/docscan/jniLibs core/prediction/src/main/jniLibs core/input/src/main/jniLibs core/input/src/mozc/jniLibs)

echo "$pins" | awk -F'\t' '!/^#/ && $6 == "data" {print $2 "\t" $3 "\t" $4}' | while IFS=$'\t' read -r abi name sha; do
    found=
    for dir in "${dirs[@]}"; do
        file=$dir/$abi/$name
        [ -f "$file" ] || continue
        if [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$sha" ]; then
            cp "$file" "$out/$sha-$name"
            found=1
            break
        fi
    done
    [ -n "$found" ] || { echo "::error::no $abi $name with SHA-256 $sha in the jniLibs"; exit 1; }
    echo "$abi $name $sha"
done
