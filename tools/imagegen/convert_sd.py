#!/usr/bin/env python3
"""Converts a Stable Diffusion 1.x/2.x checkpoint into a WM Keyboard sticker model bundle.

The keyboard's sticker generator runs models on MNN. It never converts a
checkpoint on the phone; this script does it on a computer, and the folder it
writes (or a zip of it) is what Settings > Stickers > Create stickers >
Import model takes.

Input is any of:
  * a Hugging Face repo id in diffusers layout (IDKiro/sdxs-512-dreamshaper),
  * a local diffusers folder (model_index.json, unet/, text_encoder/, vae/),
  * a single .safetensors or .ckpt file (loaded with from_single_file; needs
    the matching base config, which diffusers fetches from Hugging Face).

Output folder:
  text_encoder.mnn   CLIP text encoder: ids [1,77] int32 -> h [1,77,D]
  unet.mnn           x [1,4,H/8,W/8], t [1] float, e [1,77,D] (+ w [1,C] for LCM) -> y, same shape as x
  vae_decoder.mnn    z [1,4,H/8,W/8] in the UNet's latent space -> img [1,3,H,W] in 0..1
  vocab.json, merges.txt   the CLIP BPE tokenizer
  wmimage.json       what the app needs to drive the three (see below)

The decoder is the model's own VAE decoder unless --taesd is given, which
swaps in madebyollin's TAESD (MIT, 1.2M parameters): at 512 the full SD VAE
decoder costs as much as 3.7 UNet calls, TAESD a twentieth of one, and at
sticker size the two look the same.

Weights are stored as float16 (--int8 halves the files again) and always run
in float32: MNN's float16 compute gives wrong UNet output, so the app never
asks for it. MNN's --transformerFuse is never used either. Its fused attention
is no faster here and moves the UNet's noise prediction just enough (cosine
0.998) that a one-step model, which multiplies that prediction by ~14.6 to get
the image, draws a different picture. Measured on SDXS against diffusers on
the same noise: PSNR 27 dB with float16 weights, 19 dB with int8 (same
picture, finer details differ), 6 dB with the fuse (another picture).

Requirements (a fresh virtualenv is best):
  pip install torch diffusers transformers safetensors onnx MNN

Examples:
  tools/imagegen/convert_sd.py IDKiro/sdxs-512-dreamshaper out/sdxs --steps 1 --timesteps 999 --guidance 0
  tools/imagegen/convert_sd.py dreamshaper_8_lcm.safetensors out/ds8 --steps 4 --scheduler lcm --taesd
"""
import argparse
import json
import os
import shutil
import sys
import tempfile

import torch

TOKENS = 77


def load(source, dtype, revision=None):
    from diffusers import StableDiffusionPipeline
    if os.path.isfile(source):
        return StableDiffusionPipeline.from_single_file(source, torch_dtype=dtype, safety_checker=None)
    return StableDiffusionPipeline.from_pretrained(source, torch_dtype=dtype, safety_checker=None, revision=revision)


class TextEncoder(torch.nn.Module):
    def __init__(self, te):
        super().__init__()
        self.te = te

    def forward(self, ids):
        return self.te(ids, return_dict=False)[0]


class Unet(torch.nn.Module):
    def __init__(self, unet, lcm):
        super().__init__()
        self.unet = unet
        self.lcm = lcm

    def forward(self, x, t, e, w=None):
        if self.lcm:
            return self.unet(x, t, e, timestep_cond=w, return_dict=False)[0]
        return self.unet(x, t, e, return_dict=False)[0]


class Decoder(torch.nn.Module):
    """Takes the UNet's latents as they are and returns RGB in 0..1."""

    def __init__(self, vae, scale):
        super().__init__()
        self.vae = vae
        self.scale = scale

    def forward(self, z):
        img = self.vae.decode(z / self.scale, return_dict=False)[0]
        return ((img + 1) * 0.5).clamp(0, 1)


def export(module, args, path, names, outputs, dynamic):
    torch.onnx.export(module, args, path, input_names=names, output_names=outputs,
                      dynamic_axes=dynamic, opset_version=17, dynamo=False)


def mnn(onnx_path, mnn_path, int8):
    # The converter library itself, not the `mnnconvert` script around it: the
    # script reports every conversion (model id, sizes, arguments, a machine
    # id) to Alibaba Cloud's log service, and installs a package to do so.
    try:
        import _tools
    except ImportError:
        sys.exit("MNN's converter not found: pip install MNN")
    _tools.mnnconvert(["mnnconvert", "-f", "ONNX", "--modelFile", onnx_path, "--MNNModel", mnn_path,
                       "--bizCode", "wmkeyboard"] + (["--weightQuantBits", "8"] if int8 else ["--fp16"]))
    if not os.path.isfile(mnn_path):
        sys.exit(f"MNN could not convert {onnx_path}")


def save_tokenizer(tok, out, prefix):
    """vocab.json and merges.txt, whichever form this transformers version saves."""
    with tempfile.TemporaryDirectory() as tmp:
        tok.save_pretrained(tmp)
        if os.path.isfile(f"{tmp}/vocab.json") and os.path.isfile(f"{tmp}/merges.txt"):
            shutil.copy(f"{tmp}/vocab.json", os.path.join(out, prefix + "vocab.json"))
            shutil.copy(f"{tmp}/merges.txt", os.path.join(out, prefix + "merges.txt"))
            return
        model = json.load(open(f"{tmp}/tokenizer.json"))["model"]
        with open(os.path.join(out, prefix + "vocab.json"), "w") as f:
            json.dump(model["vocab"], f, ensure_ascii=False)
        with open(os.path.join(out, prefix + "merges.txt"), "w") as f:
            f.write("#version: 0.2\n")
            for m in model["merges"]:
                f.write((m if isinstance(m, str) else " ".join(m)) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("source", help="Hugging Face repo id, diffusers folder, or .safetensors/.ckpt file")
    p.add_argument("out", help="output folder (created)")
    p.add_argument("--name", help="name shown in the app (default: the folder name)")
    p.add_argument("--revision", help="Hugging Face commit to convert, for a reproducible bundle")
    p.add_argument("--steps", type=int, default=4, help="default denoising steps")
    p.add_argument("--timesteps", help="comma-separated fixed timesteps, for one-step models (e.g. 999)")
    p.add_argument("--guidance", type=float, default=0.0, help="default classifier-free guidance (0 or 1 = off)")
    p.add_argument("--scheduler", choices=["euler", "lcm", "dpmpp2m"], default="euler")
    p.add_argument("--size", type=int, default=512, help="native resolution")
    p.add_argument("--sizes", default="384,512", help="sizes the app offers")
    p.add_argument("--taesd", action="store_true", help="use TAESD instead of the model's VAE decoder")
    p.add_argument("--licence", default="creativeml-openrail-m", help="licence id shown in the app")
    p.add_argument("--prefix", default="", help="prefix every output file name (for release assets)")
    p.add_argument("--int8", action="store_true", help="8-bit weights: half the size, slightly further from the original")
    a = p.parse_args()

    os.makedirs(a.out, exist_ok=True)
    pipe = load(a.source, torch.float32, a.revision)
    unet, te, vae, tok = pipe.unet.eval(), pipe.text_encoder.eval().float(), pipe.vae.eval(), pipe.tokenizer
    sched = dict(pipe.scheduler.config)
    lcm_dim = unet.config.get("time_cond_proj_dim")
    text_dim = te.config.hidden_size
    scale = vae.config.scaling_factor
    if a.taesd:
        from diffusers import AutoencoderTiny
        vae = AutoencoderTiny.from_pretrained("madebyollin/taesd", torch_dtype=torch.float32).eval()
        scale = 1.0
    elif getattr(vae.config, "_class_name", "") == "AutoencoderTiny" or type(vae).__name__ == "AutoencoderTiny":
        scale = 1.0
    with torch.no_grad(), tempfile.TemporaryDirectory() as tmp:
        lat = a.size // 8
        export(TextEncoder(te), (torch.zeros(1, TOKENS, dtype=torch.long),), f"{tmp}/te.onnx", ["ids"], ["h"], None)
        mnn(f"{tmp}/te.onnx", os.path.join(a.out, a.prefix + "text_encoder.mnn"), a.int8)
        os.remove(f"{tmp}/te.onnx")
        uargs = [torch.randn(1, 4, lat, lat), torch.tensor([999.0]), torch.randn(1, TOKENS, text_dim)]
        names = ["x", "t", "e"]
        if lcm_dim:
            uargs.append(torch.randn(1, lcm_dim))
            names.append("w")
        export(Unet(unet, bool(lcm_dim)), tuple(uargs), f"{tmp}/unet.onnx", names, ["y"],
               {"x": {2: "h", 3: "w"}, "y": {2: "h", 3: "w"}})
        del unet
        mnn(f"{tmp}/unet.onnx", os.path.join(a.out, a.prefix + "unet.mnn"), a.int8)
        for f in os.listdir(tmp):
            os.remove(os.path.join(tmp, f))
        export(Decoder(vae, scale), (torch.randn(1, 4, lat, lat),), f"{tmp}/vae.onnx", ["z"], ["img"],
               {"z": {2: "h", 3: "w"}, "img": {2: "H", 3: "W"}})
        mnn(f"{tmp}/vae.onnx", os.path.join(a.out, a.prefix + "vae_decoder.mnn"), a.int8)
    save_tokenizer(tok, a.out, a.prefix)
    meta = {
        "format": 1,
        "runtime": "mnn",
        "name": a.name or os.path.basename(os.path.normpath(a.out)),
        "licence": a.licence,
        "source": a.source if not os.path.exists(a.source) else os.path.basename(a.source),
        "text_dim": text_dim,
        "lcm_dim": lcm_dim or 0,
        "size": a.size,
        "sizes": [int(s) for s in a.sizes.split(",")],
        "steps": a.steps,
        "timesteps": [int(t) for t in a.timesteps.split(",")] if a.timesteps else [],
        "guidance": a.guidance,
        "scheduler": a.scheduler,
        "prediction": sched.get("prediction_type", "epsilon"),
        "train_steps": sched.get("num_train_timesteps", 1000),
        "beta_start": sched.get("beta_start", 0.00085),
        "beta_end": sched.get("beta_end", 0.012),
        "beta_schedule": sched.get("beta_schedule", "scaled_linear"),
        "bos": tok.bos_token_id,
        "eos": tok.eos_token_id,
        "pad": tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id,
    }
    with open(os.path.join(a.out, a.prefix + "wmimage.json"), "w") as f:
        json.dump(meta, f, indent=2)
    for f in sorted(os.listdir(a.out)):
        print(f"{os.path.getsize(os.path.join(a.out, f)):>12,}  {f}")


if __name__ == "__main__":
    main()
