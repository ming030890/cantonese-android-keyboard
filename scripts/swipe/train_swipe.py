"""Train Stage 1 (SwipeNoiseCVAE) + Stage 2 (L=32 SwipeConformerEncoder) at scale,
then export symmetric per-row INT8 weights (KBSWP01) and verify INT8 parity.
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn.functional as F

from scripts.swipe.generator import (
    MultiDeviceLayoutBank,
    SwipeNoiseCVAE,
    VocabAndMarkovSampler,
    cvae_loss,
)
from scripts.swipe.geometry import (
    BLANK_IDX,
    NUM_STEPS,
    featurize_resampled_numpy,
    featurize_resampled_torch,
    ideal_word_polyline_torch,
)
from scripts.swipe.model import (
    NUM_FRAMES,
    SwipeConformerEncoder,
    export_kbswp_int8,
    forward_kbswp_int8_numpy,
)


def select_device() -> torch.device:
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


class PreparedFutoDataset:
    def __init__(self, data_dir: Path, split: str) -> None:
        self.uvt = np.load(data_dir / f"{split}_uvt.npy")  # [N, 64, 3] fp16
        self.aspect = np.load(data_dir / f"{split}_aspect.npy")  # [N] fp16
        self.letters = np.load(data_dir / f"{split}_letters.npy")  # [N, 28] u8
        self.lengths = np.load(data_dir / f"{split}_lengths.npy")  # [N] u8
        self.uk_vk = np.load(data_dir / "futo_uk_vk.npy").astype(np.float32)  # [26, 2]
        self.size = len(self.lengths)

    def sample_batch(
        self, rng: np.random.Generator, batch_size: int, device: torch.device
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        idx = rng.integers(0, self.size, size=batch_size)
        uvt = torch.from_numpy(self.uvt[idx].astype(np.float32)).to(device)
        aspect = torch.from_numpy(self.aspect[idx].astype(np.float32)).to(device)
        letters = torch.from_numpy(self.letters[idx].astype(np.int64)).to(device)
        lengths = torch.from_numpy(self.lengths[idx].astype(np.int64)).to(device)
        return uvt, aspect, letters, lengths


def augment_trajectory_and_layout(
    uvt: torch.Tensor,
    uk_vk: torch.Tensor,
    aspect: torch.Tensor,
    drift_std: float = 0.08,
) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    """Co-augment layout + trajectory across screen scales, shears, aspect ratios, and thumb drift."""
    bsz = uvt.shape[0]
    dev = uvt.device
    if uk_vk.shape[0] == 1:
        uk_vk = uk_vk.expand(bsz, -1, -1).clone()
    else:
        uk_vk = uk_vk.clone()
    uvt = uvt.clone()

    # 1. Co-scale & co-shear (applied identically to keys and trajectory)
    sx = torch.empty((bsz, 1), device=dev).uniform_(0.93, 1.07)
    sy = torch.empty((bsz, 1), device=dev).uniform_(0.93, 1.07)
    shear = torch.empty((bsz, 1), device=dev).normal_(0.0, 0.025)

    uk_u, uk_v = uk_vk[:, :, 0], uk_vk[:, :, 1]
    uk_vk[:, :, 0] = (uk_u - 4.5) * sx + 4.5 + shear * (uk_v - 1.0)
    uk_vk[:, :, 1] = (uk_v - 1.0) * sy + 1.0

    u, v, tau = uvt[:, :, 0], uvt[:, :, 1], uvt[:, :, 2]
    u_new = (u - 4.5) * sx + 4.5 + shear * (v - 1.0)
    v_new = (v - 1.0) * sy + 1.0

    # 2. Trajectory-only systematic user thumb offset + mild endpoint jitter
    du_bias = torch.empty((bsz, 1), device=dev).normal_(0.0, drift_std)
    dv_bias = torch.empty((bsz, 1), device=dev).normal_(0.0, drift_std)
    u_new = (u_new + du_bias).clamp(-2.0, 11.0)
    v_new = (v_new + dv_bias).clamp(-2.0, 4.0)

    # 3. Speed & aspect-ratio augmentation (simulates portrait/landscape/foldable variations)
    speed_scale = torch.exp(torch.empty((bsz, 1), device=dev).normal_(0.0, 0.25))
    tau_new = tau * speed_scale
    aspect_new = (aspect + torch.log(sy[:, 0] / sx[:, 0]) + torch.empty_like(aspect).normal_(0.0, 0.18)).clamp(
        -1.5, 1.5
    )

    return torch.stack([u_new, v_new, tau_new], dim=-1), uk_vk, aspect_new


def ctc_greedy_decode(ctc_logp: np.ndarray) -> List[str]:
    """Decode [B, L, 27] greedy CTC predictions into lowercase strings."""
    preds = np.argmax(ctc_logp, axis=-1)  # [B, L]
    out = []
    for row in preds:
        chars = []
        prev = -1
        for idx in row:
            if idx != prev and idx != BLANK_IDX:
                chars.append(chr(ord("a") + int(idx)))
            prev = int(idx)
        out.append("".join(chars))
    return out


@torch.no_grad()
def evaluate_greedy_dev(
    encoder: SwipeConformerEncoder, dev_ds: PreparedFutoDataset, device: torch.device, max_samples: int = 10000
) -> Dict[str, float]:
    encoder.eval()
    n = min(dev_ds.size, max_samples)
    uk_vk = torch.from_numpy(dev_ds.uk_vk[None, :, :]).to(device)
    exact = 0
    start_ok = 0
    end_ok = 0
    total = 0

    bs = 512
    for start in range(0, n, bs):
        stop = min(start + bs, n)
        uvt = torch.from_numpy(dev_ds.uvt[start:stop].astype(np.float32)).to(device)
        aspect = torch.from_numpy(dev_ds.aspect[start:stop].astype(np.float32)).to(device)
        letters = dev_ds.letters[start:stop]
        lengths = dev_ds.lengths[start:stop]

        feats = featurize_resampled_torch(uvt, uk_vk.expand(stop - start, -1, -1), aspect)
        ctc_lp, s_lp, e_lp = encoder(feats)
        decoded = ctc_greedy_decode(ctc_lp.cpu().numpy())
        s_pred = s_lp.argmax(dim=-1).cpu().numpy()
        e_pred = e_lp.argmax(dim=-1).cpu().numpy()

        for i, pred_w in enumerate(decoded):
            L = int(lengths[i])
            gt = "".join(chr(ord("a") + int(c)) for c in letters[i, :L])
            if pred_w == gt:
                exact += 1
            if int(s_pred[i]) == int(letters[i, 0]):
                start_ok += 1
            if int(e_pred[i]) == int(letters[i, L - 1]):
                end_ok += 1
            total += 1

    encoder.train()
    return {
        "greedy_word_acc": exact / max(total, 1),
        "start_key_acc": start_ok / max(total, 1),
        "end_key_acc": end_ok / max(total, 1),
        "evaluated": total,
    }


def train_cvae(
    train_ds: PreparedFutoDataset,
    out_dir: Path,
    device: torch.device,
    steps: int = 3500,
    batch_size: int = 1024,
    lr: float = 2e-3,
    seed: int = 42,
) -> SwipeNoiseCVAE:
    cvae_path = out_dir / "cvae.pt"
    cvae = SwipeNoiseCVAE(z_dim=16, hidden_dim=192).to(device)
    if cvae_path.exists():
        print(f"Loading existing CVAE from {cvae_path}")
        cvae.load_state_dict(torch.load(cvae_path, map_location=device, weights_only=True))
        cvae.eval()
        return cvae

    print(f"=== Stage 1: Training SwipeNoiseCVAE for {steps} steps (batch={batch_size}) on {device} ===")
    rng = np.random.default_rng(seed)
    uk_vk = torch.from_numpy(train_ds.uk_vk[None, :, :]).to(device)
    opt = torch.optim.AdamW(cvae.parameters(), lr=lr, weight_decay=1e-4)
    warmup = min(250, max(steps // 10, 1))

    def cvae_lr(step_idx: int) -> float:
        if step_idx < warmup:
            return (step_idx + 1) / warmup
        prog = (step_idx - warmup) / max(steps - warmup, 1)
        return 0.05 + 0.95 * 0.5 * (1.0 + math.cos(math.pi * prog))

    sched = torch.optim.lr_scheduler.LambdaLR(opt, cvae_lr)

    t0 = time.time()
    cvae.train()
    for step in range(1, steps + 1):
        uvt, aspect, letters, lengths = train_ds.sample_batch(rng, batch_size, device)
        ideal_poly = ideal_word_polyline_torch(letters, lengths, uk_vk, NUM_STEPS)
        kl_w = min(1.0, step / max(steps * 0.25, 1.0)) * 0.015

        pred_uvt, mu, logvar = cvae(uvt, ideal_poly, aspect)
        loss, parts = cvae_loss(pred_uvt, uvt, mu, logvar, kl_weight=kl_w)

        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(cvae.parameters(), 2.0)
        opt.step()
        sched.step()

        if step % 250 == 0 or step == 1 or step == steps:
            elapsed = time.time() - t0
            rate = (step * batch_size) / max(elapsed, 1e-3)
            print(
                f"  [CVAE {step:4d}/{steps}] loss={float(loss.detach()):.4f} "
                f"(pos={parts['pos']:.4f} vel={parts['vel']:.4f} kl={parts['kl']:.2f}) "
                f"{rate:,.0f} swipes/s ({elapsed:.1f}s)"
            )

    out_dir.mkdir(parents=True, exist_ok=True)
    torch.save(cvae.state_dict(), cvae_path)
    cvae.eval()
    return cvae


def train_encoder(
    train_ds: PreparedFutoDataset,
    dev_ds: PreparedFutoDataset,
    cvae: SwipeNoiseCVAE,
    lexicon_tsv: Path,
    out_dir: Path,
    device: torch.device,
    steps: int = 12000,
    batch_size: int = 1024,
    synth_fraction: float = 0.50,
    lr: float = 1.5e-3,
    d_model: int = 256,
    n_layers: int = 6,
    ffn_dim: int = 512,
    seed: int = 100,
) -> SwipeConformerEncoder:
    print(
        f"=== Stage 2: Training SwipeConformerEncoder (L=32, D={d_model}, layers={n_layers}) "
        f"for {steps} steps x {batch_size} = {steps * batch_size:,} swipes on {device} ==="
    )
    rng = np.random.default_rng(seed)
    cvae.eval()
    for p in cvae.parameters():
        p.requires_grad_(False)

    layout_bank = MultiDeviceLayoutBank(num_layouts=512, seed=seed + 1, device=device)
    vocab_sampler = VocabAndMarkovSampler(lexicon_tsv, seed=seed + 2)
    futo_uk_vk = torch.from_numpy(train_ds.uk_vk[None, :, :]).to(device)

    encoder = SwipeConformerEncoder(d_model=d_model, n_layers=n_layers, n_heads=4, ffn_dim=ffn_dim, dropout=0.05).to(
        device
    )
    n_params = sum(p.numel() for p in encoder.parameters())
    print(f"Encoder parameters: {n_params:,}")

    opt = torch.optim.AdamW(encoder.parameters(), lr=lr, weight_decay=1e-2)
    warmup = min(500, steps // 10)

    def lr_lambda(step_idx: int) -> float:
        if step_idx < warmup:
            return (step_idx + 1) / max(warmup, 1)
        prog = (step_idx - warmup) / max(steps - warmup, 1)
        return 0.03 + 0.97 * 0.5 * (1.0 + math.cos(math.pi * prog))

    sched = torch.optim.lr_scheduler.LambdaLR(opt, lr_lambda)

    n_synth = int(round(batch_size * synth_fraction))
    n_real = batch_size - n_synth
    ctc_on_cpu = device.type == "mps"
    in_lengths_cpu = torch.full((batch_size,), NUM_FRAMES, dtype=torch.long)

    best_acc = -1.0
    history = []
    t0 = time.time()
    encoder.train()

    for step in range(1, steps + 1):
        # 1. Real human swipes + multi-device co-augmentation
        r_uvt, r_aspect, r_letters, r_lengths = train_ds.sample_batch(rng, n_real, device)
        r_uvt, r_uk_vk, r_aspect = augment_trajectory_and_layout(r_uvt, futo_uk_vk, r_aspect, drift_std=0.07)

        # 2. On-the-fly GPU CVAE synthetic swipes on AlphaNumPanel multi-device layouts
        if n_synth > 0:
            s_letters, s_lengths = vocab_sampler.sample_batch(n_synth, markov_fraction=0.30, device=device)
            s_uk_vk, s_aspect = layout_bank.sample(n_synth)
            temp = float(rng.uniform(0.75, 1.20))
            s_uvt = cvae.sample_trajectories(s_letters, s_lengths, s_uk_vk, s_aspect, temperature=temp)
            s_uvt, s_uk_vk, s_aspect = augment_trajectory_and_layout(s_uvt, s_uk_vk, s_aspect, drift_std=0.06)

            uvt = torch.cat([r_uvt, s_uvt], dim=0)
            uk_vk = torch.cat([r_uk_vk, s_uk_vk], dim=0)
            aspect = torch.cat([r_aspect, s_aspect], dim=0)
            letters = torch.cat([r_letters, s_letters], dim=0)
            lengths = torch.cat([r_lengths, s_lengths], dim=0)
        else:
            uvt, uk_vk, aspect, letters, lengths = r_uvt, r_uk_vk, r_aspect, r_letters, r_lengths

        feats = featurize_resampled_torch(uvt, uk_vk, aspect)
        ctc_logp, start_logp, end_logp = encoder(feats)

        # Auxiliary endpoint supervision
        first_char = letters[:, 0]
        last_char = letters.gather(1, (lengths - 1).clamp(min=0)[:, None])[:, 0]
        aux_loss = 0.25 * F.nll_loss(start_logp, first_char) + 0.25 * F.nll_loss(end_logp, last_char)

        # CTC loss (transferred to CPU only on MPS where aten::_ctc_loss is unimplemented)
        if ctc_on_cpu:
            lp_t = ctc_logp.transpose(0, 1).cpu()
            tg_cpu = letters.cpu()
            len_cpu = lengths.cpu()
            loss_ctc = F.ctc_loss(lp_t, tg_cpu, in_lengths_cpu, len_cpu, blank=BLANK_IDX, zero_infinity=True).to(device)
        else:
            in_lens = torch.full((batch_size,), NUM_FRAMES, dtype=torch.long, device=device)
            loss_ctc = F.ctc_loss(
                ctc_logp.transpose(0, 1), letters, in_lens, lengths, blank=BLANK_IDX, zero_infinity=True
            )

        loss = loss_ctc + aux_loss
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(encoder.parameters(), 1.5)
        opt.step()
        sched.step()

        if step % 200 == 0 or step == 1:
            elapsed = time.time() - t0
            rate = (step * batch_size) / max(elapsed, 1e-3)
            print(
                f"  [ENC {step:5d}/{steps}] loss={float(loss.detach()):.4f} "
                f"(ctc={float(loss_ctc.detach()):.4f} aux={float(aux_loss.detach()):.4f}) "
                f"lr={sched.get_last_lr()[0]:.5f} {rate:,.0f} swipes/s ({elapsed:.1f}s)"
            )

        if step % 1000 == 0 or step == steps:
            metrics = evaluate_greedy_dev(encoder, dev_ds, device, max_samples=12000)
            metrics["step"] = step
            metrics["loss"] = round(float(loss.detach()), 4)
            history.append(metrics)
            print(
                f"  >>> DEV @{step}: greedy_raw_word_acc={metrics['greedy_word_acc']*100:.2f}% "
                f"(no lexicon/LM!) start_acc={metrics['start_key_acc']*100:.2f}% "
                f"end_acc={metrics['end_key_acc']*100:.2f}%"
            )
            if metrics["greedy_word_acc"] >= best_acc:
                best_acc = metrics["greedy_word_acc"]
                torch.save(encoder.state_dict(), out_dir / "encoder_best.pt")
            (out_dir / "train_history.json").write_text(json.dumps(history, indent=2) + "\n")

    encoder.load_state_dict(torch.load(out_dir / "encoder_best.pt", map_location=device, weights_only=True))
    encoder.eval()
    return encoder


def verify_and_export_int8(
    encoder: SwipeConformerEncoder, dev_ds: PreparedFutoDataset, out_bin: Path
) -> Dict:
    info = export_kbswp_int8(encoder, out_bin)
    blob = out_bin.read_bytes()
    encoder_cpu = encoder.cpu().eval()

    u_k = dev_ds.uk_vk[:, 0]
    v_k = dev_ds.uk_vk[:, 1]
    max_ctc_diff = 0.0
    top1_agree = 0
    n_check = 32
    with torch.no_grad():
        for i in range(n_check):
            feats = featurize_resampled_numpy(
                dev_ds.uvt[i].astype(np.float32), u_k, v_k, float(dev_ds.aspect[i])
            )
            fp_ctc, _, _ = encoder_cpu(torch.from_numpy(feats[None, :, :]))
            fp_ctc_np = fp_ctc[0].numpy()
            i8_ctc_np, _, _ = forward_kbswp_int8_numpy(blob, feats)
            max_ctc_diff = max(max_ctc_diff, float(np.max(np.abs(fp_ctc_np - i8_ctc_np))))
            if ctc_greedy_decode(fp_ctc_np[None])[0] == ctc_greedy_decode(i8_ctc_np[None])[0]:
                top1_agree += 1

    report = {
        **info,
        "max_int8_vs_fp32_logprob_diff": round(max_ctc_diff, 5),
        "greedy_decode_agreement": f"{top1_agree}/{n_check}",
    }
    print("Exported INT8 KBSWP01:", json.dumps(report))
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepared-dir", type=Path, default=Path("/Volumes/SSD/swipe-typing/prepared"))
    parser.add_argument("--out-dir", type=Path, default=Path("/Volumes/SSD/swipe-typing/checkpoints/v1"))
    parser.add_argument(
        "--lexicon-tsv",
        type=Path,
        default=Path(
            "/Users/tony/Documents/keyboard/QwenASR/crates/cantonese-ngram/src/english_channel_data/lexicon.tsv"
        ),
    )
    parser.add_argument("--cvae-steps", type=int, default=3500)
    parser.add_argument("--enc-steps", type=int, default=12000)
    parser.add_argument("--batch-size", type=int, default=1024)
    parser.add_argument("--d-model", type=int, default=256)
    parser.add_argument("--n-layers", type=int, default=6)
    parser.add_argument("--ffn-dim", type=int, default=512)
    args = parser.parse_args()

    torch.manual_seed(42)
    device = select_device()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    train_ds = PreparedFutoDataset(args.prepared_dir, "train")
    dev_ds = PreparedFutoDataset(args.prepared_dir, "dev")
    print(f"Loaded prepared splits: train={train_ds.size:,}, dev={dev_ds.size:,}")

    cvae = train_cvae(train_ds, args.out_dir, device, steps=args.cvae_steps, batch_size=args.batch_size)
    encoder = train_encoder(
        train_ds,
        dev_ds,
        cvae,
        args.lexicon_tsv,
        args.out_dir,
        device,
        steps=args.enc_steps,
        batch_size=args.batch_size,
        d_model=args.d_model,
        n_layers=args.n_layers,
        ffn_dim=args.ffn_dim,
    )
    report = verify_and_export_int8(encoder, dev_ds, args.out_dir / "english_swipe.bin")
    (args.out_dir / "export_report.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
