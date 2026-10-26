#!/usr/bin/env bash
# The bigger English context model, end to end, on a rented CUDA box:
# teacher soft targets, the long training run, the .wmlm export.
#
# Run from the unpacked bundle's `wmlm/` directory after setup.sh. The data
# lives beside it in `data/` (en2_lm/, and optionally en2_soft.npz from the
# small teacher, which a bigger teacher replaces here). Everything is tunable
# through the environment; the defaults are the run that was planned:
#
#   TEACHER   Qwen/Qwen2.5-1.5B   (3 GB fp16; Qwen2.5-3B needs a 16 GB card)
#   SENTENCES 150000              sentences the teacher reads
#   STEPS     30000               training steps
#   BATCH     512
#   D/LAYERS/FF/CTX 256/6/768/40  model size (10M parameters, ~10 MB int8)
#   ALPHA     0.5                 weight of the teacher's loss on its batches
#   OUT       out                 where checkpoints, the log and the .wmlm go
#
# Bring back OUT/en_context.wmlm (about 10 MB) and OUT/best.pt (41 MB).
set -euo pipefail
PY=${PY:-.venv/bin/python}
TEACHER=${TEACHER:-Qwen/Qwen2.5-1.5B}
SENTENCES=${SENTENCES:-150000}
STEPS=${STEPS:-30000}
BATCH=${BATCH:-512}
D=${D:-256}; LAYERS=${LAYERS:-6}; FF=${FF:-768}; CTX=${CTX:-40}
ALPHA=${ALPHA:-0.5}
OUT=${OUT:-out}
DATA=${DATA:-data/en2_lm}
mkdir -p "$OUT"

echo "== teacher $TEACHER over $SENTENCES sentences"
$PY distill.py "$DATA" "$OUT/soft.npz" teacher="$TEACHER" sentences="$SENTENCES" batch=64 topk=24 2>&1 | tee "$OUT/distill.log"

echo "== train d$D L$LAYERS ff$FF ctx$CTX, $STEPS steps, batch $BATCH"
$PY train.py "$DATA" "$OUT" steps="$STEPS" batch="$BATCH" d="$D" layers="$LAYERS" ff="$FF" ctx="$CTX" \
    eval_every=2000 distill="$OUT/soft.npz" distill_alpha="$ALPHA" 2>&1 | tee "$OUT/train.log"

echo "== export"
$PY export.py "$OUT/best.pt" "$DATA/vocab.txt" "$OUT/en_context.wmlm" --langs=en --name=English
ls -la "$OUT/en_context.wmlm" "$OUT/best.pt"
echo "done: copy $OUT/en_context.wmlm and $OUT/best.pt back"
