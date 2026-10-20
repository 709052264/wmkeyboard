#!/usr/bin/env bash
# Builds imagegen.cpp for this computer against the pinned MNN, runs a sticker
# model bundle's text encoder, UNet and decoder through it, and compares each
# output with MNN's own Python bindings on the same inputs. Checks the C++
# half of the app's pipeline without a phone.
#
#   native/imagegen-jni/desktop/check.sh <bundle dir> [file prefix]
#
# Needs CMake, Ninja, and a Python with numpy and MNN (PYTHON=..., default
# python3). MNN_SRC may point at a checkout of the pinned commit.
set -euo pipefail
BUNDLE=$(cd "${1:?usage: $0 <bundle dir> [prefix]}" && pwd)
PREFIX=${2:-}
PY=${PYTHON:-python3}
HERE=$(cd "$(dirname "$0")" && pwd)
MNN_COMMIT=$(sed -n 's/^MNN_COMMIT=//p' "$HERE/../build.sh")
MNN_TAG=$(sed -n 's/^MNN_TAG=//p' "$HERE/../build.sh")
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT
SRC=${MNN_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/mnn
    git clone --quiet --depth 1 --branch "$MNN_TAG" https://github.com/alibaba/MNN "$SRC"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$MNN_COMMIT" ] || { echo "MNN is not at $MNN_COMMIT" >&2; exit 1; }
cmake -S "$SRC" -B "$WORK/mnn" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_SEP_BUILD=OFF -DMNN_BUILD_TOOLS=OFF -DMNN_BUILD_CONVERTER=OFF \
    -DMNN_BUILD_PROTOBUFFER=OFF -DMNN_INTERNAL=OFF -DMNN_KLEIDIAI=OFF -DMNN_ARM82=OFF \
    -DMNN_SUPPORT_TRANSFORMER_FUSE=OFF -DMNN_LOW_MEMORY=ON -DMNN_METAL=OFF > "$WORK/mnn.log" 2>&1 \
    || { tail -30 "$WORK/mnn.log"; exit 1; }
cmake --build "$WORK/mnn" --target MNN >> "$WORK/mnn.log" 2>&1 || { tail -30 "$WORK/mnn.log"; exit 1; }
cmake -S "$HERE" -B "$WORK/cli" -G Ninja -DCMAKE_BUILD_TYPE=Release -DMNN_SRC="$SRC" -DMNN_BUILD="$WORK/mnn" > /dev/null
cmake --build "$WORK/cli" > "$WORK/cli.log" 2>&1 || { tail -30 "$WORK/cli.log"; exit 1; }
CLI=$WORK/cli/imagegen_cli
"$PY" "$HERE/check.py" prepare "$BUNDLE" "$WORK" "$PREFIX"
LAT=$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1]))['size']//8)" "$BUNDLE/${PREFIX}wmimage.json")
DIM=$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1]))['text_dim'])" "$BUNDLE/${PREFIX}wmimage.json")
"$CLI" describe "$BUNDLE/${PREFIX}unet.mnn"
"$CLI" run "$BUNDLE/${PREFIX}text_encoder.mnn" "$WORK/h.f32" ids:i32:1,77:"$WORK/ids.i32"
"$CLI" run "$BUNDLE/${PREFIX}unet.mnn" "$WORK/y.f32" x:f32:1,4,$LAT,$LAT:"$WORK/x.f32" t:f32:1:"$WORK/t.f32" e:f32:1,77,$DIM:"$WORK/h.f32"
"$CLI" run "$BUNDLE/${PREFIX}vae_decoder.mnn" "$WORK/img.f32" z:f32:1,4,$LAT,$LAT:"$WORK/y.f32"
"$PY" "$HERE/check.py" compare "$BUNDLE" "$WORK" "$PREFIX"
