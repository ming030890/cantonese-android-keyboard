"""Learned real-noise Conditional VAE trajectory generator + multi-device layout sampler."""
from __future__ import annotations

import math
from pathlib import Path
from typing import Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from scripts.swipe.geometry import (
    NUM_STEPS,
    ideal_word_polyline_torch,
    key_centers_to_pitch_space,
    our_alphanum_centers_dp,
)


class ResConvBlock1D(nn.Module):
    """Fast parallel 1D ConvNeXt-style block with depthwise dilated conv + pointwise MLP."""

    def __init__(self, dim: int, dilation: int = 1, kernel_size: int = 7) -> None:
        super().__init__()
        pad = (kernel_size - 1) * dilation // 2
        self.dw = nn.Conv1d(dim, dim, kernel_size=kernel_size, padding=pad, dilation=dilation, groups=dim)
        self.norm = nn.GroupNorm(1, dim)
        self.pw1 = nn.Conv1d(dim, dim * 2, kernel_size=1)
        self.pw2 = nn.Conv1d(dim * 2, dim, kernel_size=1)
        nn.init.zeros_(self.pw2.weight)
        nn.init.zeros_(self.pw2.bias)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B, C, T]
        h = self.dw(x)
        h = self.norm(h)
        h = F.silu(self.pw1(h))
        h = self.pw2(h)
        return x + h


def build_ideal_conditioning(ideal_poly: torch.Tensor, log_aspect: torch.Tensor) -> torch.Tensor:
    """Build [B, 10, T] conditioning features from [B, T, 4] ideal polyline + [B] log_aspect."""
    u = ideal_poly[:, :, 0]
    v = ideal_poly[:, :, 1]
    corner = ideal_poly[:, :, 2]
    rep = ideal_poly[:, :, 3]

    u_prev = torch.cat([u[:, :1], u[:, :-1]], dim=1)
    u_next = torch.cat([u[:, 1:], u[:, -1:]], dim=1)
    v_prev = torch.cat([v[:, :1], v[:, :-1]], dim=1)
    v_next = torch.cat([v[:, 1:], v[:, -1:]], dim=1)

    du = u - u_prev
    dv = v - v_prev
    d2u = u_next - 2.0 * u + u_prev
    d2v = v_next - 2.0 * v + v_prev

    bsz, t_steps = u.shape
    prog = torch.linspace(0.0, 1.0, t_steps, device=u.device)[None, :].expand(bsz, t_steps)
    aspect = log_aspect[:, None].expand(bsz, t_steps)

    # [B, 10, T]
    return torch.stack(
        [u / 9.0, v / 2.0, du, dv, d2u, d2v, corner, rep, prog, aspect],
        dim=1,
    )


class SwipeNoiseCVAE(nn.Module):
    """Learns human swipe noise (corner-cutting, speed-curvature, double-letter loops/skips, hooks)
    directly from 1M+ real swipe.futo.org trajectories.
    """

    def __init__(self, z_dim: int = 16, hidden_dim: int = 192) -> None:
        super().__init__()
        self.z_dim = z_dim
        # Posterior encoder: [B, 10 + 3, T] -> z (mu, logvar)
        self.enc_in = nn.Conv1d(13, hidden_dim, kernel_size=3, padding=1)
        self.enc_blocks = nn.ModuleList(
            [
                ResConvBlock1D(hidden_dim, dilation=1),
                ResConvBlock1D(hidden_dim, dilation=2),
                ResConvBlock1D(hidden_dim, dilation=4),
            ]
        )
        self.enc_mu = nn.Linear(hidden_dim * 2, z_dim)
        self.enc_logvar = nn.Linear(hidden_dim * 2, z_dim)

        # Parallel Trajectory Decoder: [B, 10 + z_dim, T] -> [B, 3, T] (delta_u, delta_v, raw_dt)
        self.dec_in = nn.Conv1d(10 + z_dim, hidden_dim, kernel_size=3, padding=1)
        self.dec_blocks = nn.ModuleList(
            [
                ResConvBlock1D(hidden_dim, dilation=1),
                ResConvBlock1D(hidden_dim, dilation=2),
                ResConvBlock1D(hidden_dim, dilation=4),
                ResConvBlock1D(hidden_dim, dilation=2),
                ResConvBlock1D(hidden_dim, dilation=1),
            ]
        )
        self.dec_out = nn.Conv1d(hidden_dim, 3, kernel_size=1)
        nn.init.zeros_(self.dec_out.weight)
        nn.init.zeros_(self.dec_out.bias)

    def encode(self, real_uvt: torch.Tensor, ideal_poly: torch.Tensor, cond: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        res_u = (real_uvt[:, :, 0] - ideal_poly[:, :, 0]).clamp(-4.0, 4.0)
        res_v = (real_uvt[:, :, 1] - ideal_poly[:, :, 1]).clamp(-3.0, 3.0)
        tau = real_uvt[:, :, 2]
        dt = torch.cat([torch.zeros_like(tau[:, :1]), (tau[:, 1:] - tau[:, :-1]).clamp(0.0, 0.5)], dim=1)
        log_dt = torch.log(dt * 100.0 + 1.0)

        x = torch.cat([cond, torch.stack([res_u, res_v, log_dt], dim=1)], dim=1)
        h = F.silu(self.enc_in(x))
        for blk in self.enc_blocks:
            h = blk(h)
        pooled = torch.cat([h.mean(dim=-1), h.amax(dim=-1)], dim=-1)
        mu = self.enc_mu(pooled)
        logvar = self.enc_logvar(pooled).clamp(-6.0, 3.0)
        return mu, logvar

    def decode(self, ideal_poly: torch.Tensor, cond: torch.Tensor, z: torch.Tensor) -> torch.Tensor:
        t_steps = cond.shape[-1]
        z_rep = z[:, :, None].expand(-1, -1, t_steps)
        h = F.silu(self.dec_in(torch.cat([cond, z_rep], dim=1)))
        for blk in self.dec_blocks:
            h = blk(h)
        out = self.dec_out(h)  # [B, 3, T]
        delta_u = torch.tanh(out[:, 0, :]) * 2.5
        delta_v = torch.tanh(out[:, 1, :]) * 2.0
        dt_step = F.softplus(out[:, 2, :] - 2.5) * 0.05 + 1e-3  # seconds per step

        u = (ideal_poly[:, :, 0] + delta_u).clamp(-1.5, 10.5)
        v = (ideal_poly[:, :, 1] + delta_v).clamp(-1.5, 3.5)
        tau = torch.cat([torch.zeros_like(dt_step[:, :1]), torch.cumsum(dt_step[:, 1:], dim=1)], dim=1)
        return torch.stack([u, v, tau], dim=-1)

    def forward(
        self, real_uvt: torch.Tensor, ideal_poly: torch.Tensor, log_aspect: torch.Tensor
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        cond = build_ideal_conditioning(ideal_poly, log_aspect)
        mu, logvar = self.encode(real_uvt, ideal_poly, cond)
        std = torch.exp(0.5 * logvar)
        z = mu + std * torch.randn_like(std)
        pred_uvt = self.decode(ideal_poly, cond, z)
        return pred_uvt, mu, logvar

    @torch.no_grad()
    def sample_trajectories(
        self,
        letter_ids: torch.Tensor,
        lengths: torch.Tensor,
        uk_vk: torch.Tensor,
        log_aspect: torch.Tensor,
        temperature: float = 1.0,
    ) -> torch.Tensor:
        """Synthesize [B, T, 3] human-like noisy trajectories on GPU for arbitrary layouts."""
        ideal_poly = ideal_word_polyline_torch(letter_ids, lengths, uk_vk, NUM_STEPS)
        cond = build_ideal_conditioning(ideal_poly, log_aspect)
        z = torch.randn((letter_ids.shape[0], self.z_dim), device=letter_ids.device) * temperature
        return self.decode(ideal_poly, cond, z)


def cvae_loss(
    pred_uvt: torch.Tensor,
    real_uvt: torch.Tensor,
    mu: torch.Tensor,
    logvar: torch.Tensor,
    kl_weight: float = 0.015,
) -> Tuple[torch.Tensor, dict]:
    """Position + velocity + acceleration + step-timing matching loss + free-bits KL."""
    pu, pv, ptau = pred_uvt[:, :, 0], pred_uvt[:, :, 1], pred_uvt[:, :, 2]
    ru, rv, rtau = real_uvt[:, :, 0], real_uvt[:, :, 1], real_uvt[:, :, 2]

    pos_loss = F.smooth_l1_loss(pu, ru, beta=0.15) + F.smooth_l1_loss(pv, rv, beta=0.15)

    pdu, pdv = pu[:, 1:] - pu[:, :-1], pv[:, 1:] - pv[:, :-1]
    rdu, rdv = ru[:, 1:] - ru[:, :-1], rv[:, 1:] - rv[:, :-1]
    vel_loss = F.smooth_l1_loss(pdu, rdu, beta=0.08) + F.smooth_l1_loss(pdv, rdv, beta=0.08)

    pd2u, pd2v = pdu[:, 1:] - pdu[:, :-1], pdv[:, 1:] - pdv[:, :-1]
    rd2u, rd2v = rdu[:, 1:] - rdu[:, :-1], rdv[:, 1:] - rdv[:, :-1]
    acc_loss = F.smooth_l1_loss(pd2u, rd2u, beta=0.05) + F.smooth_l1_loss(pd2v, rd2v, beta=0.05)

    pdt = (ptau[:, 1:] - ptau[:, :-1]).clamp(0.0, 0.5)
    rdt = (rtau[:, 1:] - rtau[:, :-1]).clamp(0.0, 0.5)
    time_loss = F.smooth_l1_loss(pdt * 20.0, rdt * 20.0, beta=0.1)

    kl_per_dim = -0.5 * (1.0 + logvar - mu.pow(2) - logvar.exp())
    # Free-bits 0.05 nats/dim keeps latent user-style factors active
    kl = torch.clamp(kl_per_dim, min=0.05).sum(dim=-1).mean()

    total = pos_loss + 2.0 * vel_loss + 1.5 * acc_loss + 0.25 * time_loss + kl_weight * kl
    return total, {
        "pos": float(pos_loss.detach()),
        "vel": float(vel_loss.detach()),
        "acc": float(acc_loss.detach()),
        "kl": float(kl.detach()),
    }


class MultiDeviceLayoutBank:
    """Precomputes 512 realistic device layouts spanning narrow phones, wide phones,
    foldables, and landscape tablets (matching AlphaNumPanel + stagger variations)
    for zero-overhead GPU sampling.
    """

    def __init__(self, num_layouts: int = 512, seed: int = 42, device: torch.device = torch.device("cpu")) -> None:
        rng = np.random.default_rng(seed)
        uk_vk_all = np.zeros((num_layouts, 26, 2), dtype=np.float32)
        aspects = np.zeros((num_layouts,), dtype=np.float32)

        for i in range(num_layouts):
            mode = rng.choice(["portrait", "compact", "foldable", "landscape"], p=[0.60, 0.15, 0.12, 0.13])
            if mode == "portrait":
                w_dp = float(rng.uniform(350.0, 440.0))
                h_dp = float(rng.uniform(50.0, 64.0))
            elif mode == "compact":
                w_dp = float(rng.uniform(290.0, 355.0))
                h_dp = float(rng.uniform(42.0, 54.0))
            elif mode == "foldable":
                w_dp = float(rng.uniform(580.0, 780.0))
                h_dp = float(rng.uniform(48.0, 62.0))
            else:
                w_dp = float(rng.uniform(650.0, 960.0))
                h_dp = float(rng.uniform(38.0, 52.0))

            fn_weight = float(rng.uniform(1.42, 1.68))
            spacing_dp = float(rng.uniform(2.0, 6.5))
            centers = our_alphanum_centers_dp(w_dp, h_dp, spacing_dp, fn_weight)
            u_k, v_k, *_, log_aspect = key_centers_to_pitch_space(centers)
            uk_vk_all[i, :, 0] = u_k
            uk_vk_all[i, :, 1] = v_k
            aspects[i] = log_aspect

        self.uk_vk = torch.from_numpy(uk_vk_all).to(device)
        self.aspects = torch.from_numpy(aspects).to(device)

    def sample(self, batch_size: int) -> Tuple[torch.Tensor, torch.Tensor]:
        idx = torch.randint(0, self.uk_vk.shape[0], (batch_size,), device=self.uk_vk.device)
        return self.uk_vk[idx], self.aspects[idx]


class VocabAndMarkovSampler:
    """Samples target letter sequences from our 169k lexicon + unbiased character Markov chains."""

    def __init__(self, lexicon_tsv: Path, max_word_len: int = 28, seed: int = 123) -> None:
        self.max_word_len = max_word_len
        self.rng = np.random.default_rng(seed)

        words = []
        weights = []
        bigram = np.ones((27, 26), dtype=np.float64)  # 26 = start state -> 0..25

        for line in lexicon_tsv.read_text(encoding="utf-8").splitlines():
            if not line:
                continue
            w_str, freq_str = line.split("\t", 1)
            letters = [ord(c) - ord("a") for c in w_str.lower() if "a" <= c <= "z"]
            if not (2 <= len(letters) <= max_word_len):
                continue
            freq = max(float(freq_str), 1.0)
            words.append(letters)
            # Flattened power-law (freq^0.32) ensures strong tail & proper-noun coverage
            weights.append(freq ** 0.32)
            prev = 26
            for ch in letters:
                bigram[prev, ch] += 1.0
                prev = ch

        self.num_words = len(words)
        self.word_letters = np.zeros((self.num_words, max_word_len), dtype=np.uint8)
        self.word_lengths = np.zeros((self.num_words,), dtype=np.uint8)
        for i, let_seq in enumerate(words):
            self.word_letters[i, : len(let_seq)] = let_seq
            self.word_lengths[i] = len(let_seq)

        probs = np.array(weights, dtype=np.float64)
        self.word_probs = probs / probs.sum()
        self.word_cdf = np.cumsum(self.word_probs)
        self.word_cdf[-1] = 1.0

        self.bigram_cdf = np.cumsum(bigram / bigram.sum(axis=1, keepdims=True), axis=1)
        self.bigram_cdf[:, -1] = 1.0

    def sample_batch(
        self, batch_size: int, markov_fraction: float = 0.30, device: torch.device = torch.device("cpu")
    ) -> Tuple[torch.Tensor, torch.Tensor]:
        n_markov = int(round(batch_size * markov_fraction))
        n_lex = batch_size - n_markov

        # Fast O(B log V) inverse-CDF lexicon sampling
        u = self.rng.random(n_lex)
        lex_idx = np.searchsorted(self.word_cdf, u, side="right").clip(0, self.num_words - 1)
        letters = np.zeros((batch_size, self.max_word_len), dtype=np.int64)
        lengths = np.zeros((batch_size,), dtype=np.int64)
        letters[:n_lex] = self.word_letters[lex_idx]
        lengths[:n_lex] = self.word_lengths[lex_idx]

        if n_markov > 0:
            m_lens = self.rng.integers(2, 13, size=n_markov)
            lengths[n_lex:] = m_lens
            states = np.full((n_markov,), 26, dtype=np.int64)
            max_m = int(m_lens.max())
            rand_mat = self.rng.random((n_markov, max_m))
            for step in range(max_m):
                cdfs = self.bigram_cdf[states]  # [n_markov, 26]
                r = rand_mat[:, step : step + 1]
                nxt = (cdfs < r).sum(axis=1).clip(0, 25)
                active = step < m_lens
                letters[n_lex:, step] = np.where(active, nxt, 0)
                states = nxt

        return (
            torch.from_numpy(letters).to(device=device, dtype=torch.long),
            torch.from_numpy(lengths).to(device=device, dtype=torch.long),
        )
