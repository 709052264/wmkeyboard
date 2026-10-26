#!/usr/bin/env bash
# One-time setup on a rented CUDA box (Ubuntu image with an NVIDIA driver).
# Run from the unpacked bundle's `wmlm/` directory.
set -euo pipefail
python3 -m venv .venv
.venv/bin/pip install --upgrade pip >/dev/null
.venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cu124
.venv/bin/pip install numpy transformers accelerate
.venv/bin/python - <<'EOF'
import torch
print("torch", torch.__version__, "cuda", torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else "")
EOF
