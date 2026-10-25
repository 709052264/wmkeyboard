"""Train WordLm on prep.py's arrays.

usage: train.py <data dir> <out dir> [steps=20000 batch=256 lr=1e-3 d=192 layers=4 heads=4 ff=512
                                     seed=0 eval_every=1000 drop_prefix=0.3]

Sentences are packed one per row, padded to the longest in the batch; the
loss ignores padding. `drop_prefix` is the share of rows whose register and
field tokens are replaced by the neutral ones, so the model also serves a
caller that does not know them. Saves out/last.pt and out/best.pt (lowest
held-out loss) with the config inside.
"""
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

from common import FIELDS, REGISTERS, SPECIALS
from model import WordLm

PAD = SPECIALS.index("<pad>")
NEUTRAL_REG = SPECIALS.index(REGISTERS[0])
OTHER_FIELD = SPECIALS.index(FIELDS[0])


def opts(argv):
    o = dict(steps=20000, batch=256, lr=1e-3, d=192, layers=4, heads=4, ff=512, seed=0, eval_every=1000, drop_prefix=0.3, ctx=32)
    for a in argv:
        k, v = a.split("=", 1)
        o[k] = type(o[k])(v)
    return o


class Data:
    def __init__(self, path, dev):
        z = np.load(path)
        self.tokens = torch.from_numpy(z["tokens"].astype(np.int64))
        self.starts = z["starts"]
        self.n = len(self.starts) - 1
        self.dev = dev

    def batch(self, idx, ctx, gen=None, drop_prefix=0.0):
        rows = [self.tokens[self.starts[i]:self.starts[i + 1]] for i in idx]
        T = min(ctx, max(len(r) for r in rows))
        out = torch.full((len(rows), T), PAD, dtype=torch.long)
        for i, r in enumerate(rows):
            r = r[:T]
            out[i, : len(r)] = r
        if drop_prefix > 0:
            mask = torch.rand(len(rows), generator=gen) < drop_prefix
            out[mask, 0] = NEUTRAL_REG
            out[mask, 1] = OTHER_FIELD
        return out.to(self.dev)


def evaluate(model, data, ctx, dev):
    model.eval()
    total, count = 0.0, 0
    with torch.no_grad():
        for a in range(0, min(data.n, 8000), 256):
            idx = range(a, min(data.n, a + 256))
            x = data.batch(idx, ctx)
            logits = model(x[:, :-1])
            tgt = x[:, 1:]
            # The prefix tokens are given, not predicted: score from <s> on.
            tgt = tgt.clone()
            tgt[:, :3] = PAD
            loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), tgt.reshape(-1), ignore_index=PAD, reduction="sum")
            total += loss.item()
            count += (tgt != PAD).sum().item()
    model.train()
    return total / max(1, count)


def main():
    data_dir, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    torch.manual_seed(o["seed"])
    gen = torch.Generator().manual_seed(o["seed"])
    vocab = sum(1 for _ in open(os.path.join(data_dir, "vocab.txt"), encoding="utf-8"))
    train = Data(os.path.join(data_dir, "train.npz"), dev)
    held = Data(os.path.join(data_dir, "heldout.npz"), dev)
    model = WordLm(vocab, d=o["d"], layers=o["layers"], heads=o["heads"], ff=o["ff"], ctx=o["ctx"]).to(dev)
    print("params", sum(p.numel() for p in model.parameters()), "train sentences", train.n, "vocab", vocab, flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=o["lr"], weight_decay=0.1, betas=(0.9, 0.95))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1, s / o["steps"]))))
    t0, run, best = time.time(), 0.0, 1e9
    for step in range(1, o["steps"] + 1):
        idx = torch.randint(0, train.n, (o["batch"],), generator=gen).tolist()
        x = train.batch(idx, o["ctx"], gen, o["drop_prefix"])
        logits = model(x[:, :-1])
        tgt = x[:, 1:].clone()
        tgt[:, :3] = PAD
        loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), tgt.reshape(-1), ignore_index=PAD)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item() if step > 1 else loss.item()
        if step % 100 == 0:
            print(f"step {step} loss {run:.3f} ppl {math.exp(run):.1f} lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
        if step % o["eval_every"] == 0 or step == o["steps"]:
            h = evaluate(model, held, o["ctx"], dev)
            print(f"eval step {step} heldout loss {h:.4f} ppl {math.exp(h):.1f}", flush=True)
            state = {"model": model.state_dict(), "config": model.config(), "opts": o, "step": step, "heldout": h}
            torch.save(state, os.path.join(out, "last.pt"))
            if h < best:
                best = h
                torch.save(state, os.path.join(out, "best.pt"))


if __name__ == "__main__":
    main()
