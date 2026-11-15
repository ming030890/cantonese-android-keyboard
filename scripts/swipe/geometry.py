"""Device-size and aspect-ratio invariant keyboard geometry and trajectory featurization.

Must remain numerically identical to QwenASR/crates/cantonese-ngram/src/swipe.rs.
"""
from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Tuple

import numpy as np
import torch

NUM_STEPS = 64
FEATURE_DIM = 60  # 8 kinematic/aspect features + 52 key-center relative offsets
ALPHABET = "abcdefghijklmnopqrstuvwxyz"
BLANK_IDX = 26
NUM_CTC_CLASSES = 27

ROW0 = "qwertyuiop"
ROW1 = "asdfghjkl"
ROW2 = "zxcvbnm"


def load_futo_qwerty_unit_centers(qwerty_json_path: Path) -> np.ndarray:
    """Return [26, 2] unit-canvas (cx, cy) for a..z from FUTO qwerty.json."""
    raw = json.loads(qwerty_json_path.read_text())
    centers = np.zeros((26, 2), dtype=np.float32)
    seen = set()
    for entry in raw["keys"]:
        ch = entry["letter"]
        if len(ch) == 1 and "a" <= ch <= "z":
            idx = ord(ch) - ord("a")
            centers[idx, 0] = float(entry["cx"])
            centers[idx, 1] = float(entry["cy"])
            seen.add(ch)
    if len(seen) != 26:
        raise ValueError(f"Expected 26 letters in {qwerty_json_path}, got {len(seen)}")
    return centers


def our_alphanum_centers_dp(
    available_width_dp: float = 404.0,
    row_height_dp: float = 57.0,
    bottom_row_spacing_dp: float = 5.0,
    function_key_weight: float = 1.56,
) -> np.ndarray:
    """Return [26, 2] isotropic dp key centers matching AlphaNumPanel in KeyboardTextPanels.kt."""
    centers = np.zeros((26, 2), dtype=np.float32)
    std_w = available_width_dp / 10.0

    for i, ch in enumerate(ROW0):
        idx = ord(ch) - ord("a")
        centers[idx, 0] = (i + 0.5) * std_w
        centers[idx, 1] = 0.5 * row_height_dp

    for i, ch in enumerate(ROW1):
        idx = ord(ch) - ord("a")
        centers[idx, 0] = (i + 1.0) * std_w
        centers[idx, 1] = 1.5 * row_height_dp

    # BottomLetterRow: Shift(1.56), 7 letters(1.0), Delete(1.56) with 8 * 5dp gaps
    total_gaps = 8.0 * bottom_row_spacing_dp
    usable = max(available_width_dp - total_gaps, 10.0)
    total_weight = function_key_weight * 2.0 + 7.0
    unit_w = usable / total_weight
    shift_w = function_key_weight * unit_w
    for j, ch in enumerate(ROW2):
        idx = ord(ch) - ord("a")
        left = shift_w + bottom_row_spacing_dp + j * (unit_w + bottom_row_spacing_dp)
        centers[idx, 0] = left + 0.5 * unit_w
        centers[idx, 1] = 2.5 * row_height_dp

    return centers


def key_centers_to_pitch_space(
    centers_xy: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray, float, float, float, float, float]:
    """Normalize [26, 2] isotropic-pixel key centers into dimensionless key-pitch space."""
    if centers_xy.shape != (26, 2):
        raise ValueError(f"Expected (26, 2) centers, got {centers_xy.shape}")
    x_min = float(np.min(centers_xy[:, 0]))
    x_max = float(np.max(centers_xy[:, 0]))
    y_min = float(np.min(centers_xy[:, 1]))
    y_max = float(np.max(centers_xy[:, 1]))
    pitch_x = max((x_max - x_min) / 9.0, 1e-4)
    pitch_y = max((y_max - y_min) / 2.0, 1e-4)
    log_aspect = float(np.clip(math.log(pitch_y / pitch_x), -1.5, 1.5))
    u_k = ((centers_xy[:, 0] - x_min) / pitch_x).astype(np.float32)
    v_k = ((centers_xy[:, 1] - y_min) / pitch_y).astype(np.float32)
    return u_k, v_k, x_min, y_min, pitch_x, pitch_y, log_aspect


def resample_points_pitch(
    xs: np.ndarray,
    ys: np.ndarray,
    ts_ms: np.ndarray,
    x_min: float,
    y_min: float,
    pitch_x: float,
    pitch_y: float,
    num_steps: int = NUM_STEPS,
) -> Tuple[np.ndarray, float]:
    """Map raw isotropic-pixel (x, y, t_ms) into pitch space and resample to T equidistant points."""
    n = len(xs)
    if n == 0:
        raise ValueError("Empty swipe trajectory")
    u = np.clip((xs.astype(np.float64) - x_min) / pitch_x, -2.0, 11.0)
    v = np.clip((ys.astype(np.float64) - y_min) / pitch_y, -2.0, 4.0)
    tau = np.maximum.accumulate(np.maximum((ts_ms.astype(np.float64) - float(ts_ms[0])) * 1e-3, 0.0))

    if n == 1:
        out = np.zeros((num_steps, 3), dtype=np.float32)
        out[:, 0] = u[0]
        out[:, 1] = v[0]
        return out, 0.0

    du = np.diff(u)
    dv = np.diff(v)
    ds = np.hypot(du, dv)
    keep = np.empty(n, dtype=bool)
    keep[0] = True
    keep[1:] = ds > 1e-5
    if keep.sum() < 2:
        out = np.zeros((num_steps, 3), dtype=np.float32)
        out[:, 0] = u[0]
        out[:, 1] = v[0]
        out[:, 2] = np.linspace(0.0, float(tau[-1]), num_steps)
        return out, 0.0

    u = u[keep]
    v = v[keep]
    tau = tau[keep]
    seg = np.hypot(np.diff(u), np.diff(v))
    s = np.concatenate(([0.0], np.cumsum(seg)))
    total_arc = float(s[-1])

    targets = np.linspace(0.0, total_arc, num_steps)
    u_res = np.interp(targets, s, u)
    v_res = np.interp(targets, s, v)
    tau_res = np.interp(targets, s, tau)
    uvt = np.stack([u_res, v_res, tau_res], axis=-1).astype(np.float32)
    return uvt, total_arc


def featurize_resampled_numpy(
    uvt: np.ndarray,
    u_k: np.ndarray,
    v_k: np.ndarray,
    log_aspect: float,
) -> np.ndarray:
    """Build [T, 60] features from resampled pitch-space [T, 3] (u, v, tau) + [26] key centers."""
    t_steps = uvt.shape[0]
    u = uvt[:, 0].astype(np.float32)
    v = uvt[:, 1].astype(np.float32)
    tau = uvt[:, 2].astype(np.float32)

    u_prev = np.concatenate([u[:1], u[:-1]])
    u_next = np.concatenate([u[1:], u[-1:]])
    v_prev = np.concatenate([v[:1], v[:-1]])
    v_next = np.concatenate([v[1:], v[-1:]])
    tau_prev = np.concatenate([tau[:1], tau[:-1]])

    du = u - u_prev
    dv = v - v_prev
    d2u = u_next - 2.0 * u + u_prev
    d2v = v_next - 2.0 * v + v_prev

    ds = np.sqrt(du * du + dv * dv + 1e-8)
    dt = np.maximum(tau - tau_prev, 1e-3)
    log_speed = np.clip(np.log(ds / dt + 1.0) * 0.25, 0.0, 2.5)
    if t_steps > 1:
        log_speed[0] = log_speed[1]

    feats = np.empty((t_steps, FEATURE_DIM), dtype=np.float32)
    feats[:, 0] = u / 9.0
    feats[:, 1] = v / 2.0
    feats[:, 2] = du
    feats[:, 3] = dv
    feats[:, 4] = d2u
    feats[:, 5] = d2v
    feats[:, 6] = log_speed
    feats[:, 7] = float(log_aspect)

    # [T, 1] - [1, 26] -> [T, 26] interleaved as (dx_a, dy_a, dx_b, dy_b, ...)
    dx = (u[:, None] - u_k[None, :]) * 0.25
    dy = (v[:, None] - v_k[None, :]) * 0.25
    feats[:, 8::2] = dx
    feats[:, 9::2] = dy
    return feats


def featurize_resampled_torch(
    uvt: torch.Tensor,
    uk_vk: torch.Tensor,
    log_aspect: torch.Tensor,
) -> torch.Tensor:
    """Vectorized GPU version of featurize_resampled_numpy.

    Args:
        uvt: [B, T, 3] float32 (u, v, tau)
        uk_vk: [B, 26, 2] or [1, 26, 2] float32 (u_k, v_k)
        log_aspect: [B] float32
    Returns:
        [B, T, 60] float32
    """
    u = uvt[:, :, 0]
    v = uvt[:, :, 1]
    tau = uvt[:, :, 2]

    u_prev = torch.cat([u[:, :1], u[:, :-1]], dim=1)
    u_next = torch.cat([u[:, 1:], u[:, -1:]], dim=1)
    v_prev = torch.cat([v[:, :1], v[:, :-1]], dim=1)
    v_next = torch.cat([v[:, 1:], v[:, -1:]], dim=1)
    tau_prev = torch.cat([tau[:, :1], tau[:, :-1]], dim=1)

    du = u - u_prev
    dv = v - v_prev
    d2u = u_next - 2.0 * u + u_prev
    d2v = v_next - 2.0 * v + v_prev

    ds = torch.sqrt(du * du + dv * dv + 1e-8)
    dt = torch.clamp(tau - tau_prev, min=1e-3)
    log_speed = torch.clamp(torch.log(ds / dt + 1.0) * 0.25, 0.0, 2.5)
    if uvt.shape[1] > 1:
        log_speed = torch.cat([log_speed[:, 1:2], log_speed[:, 1:]], dim=1)

    aspect = log_aspect[:, None].expand_as(u)
    head = torch.stack([u / 9.0, v / 2.0, du, dv, d2u, d2v, log_speed, aspect], dim=-1)

    u_k = uk_vk[:, :, 0]  # [B, 26]
    v_k = uk_vk[:, :, 1]  # [B, 26]
    dx = (u[:, :, None] - u_k[:, None, :]) * 0.25  # [B, T, 26]
    dy = (v[:, :, None] - v_k[:, None, :]) * 0.25  # [B, T, 26]
    rel = torch.stack([dx, dy], dim=-1).reshape(uvt.shape[0], uvt.shape[1], 52)
    return torch.cat([head, rel], dim=-1)


def ideal_word_polyline_torch(
    letter_ids: torch.Tensor,
    lengths: torch.Tensor,
    uk_vk: torch.Tensor,
    num_steps: int = NUM_STEPS,
) -> torch.Tensor:
    """Vectorized computation of ideal key-center trajectory [B, T, 4] on CPU/GPU.

    Channels: (u_ideal, v_ideal, corner_weight, repeat_marker)
    Handles repeated letters (e.g. 'll' in 'hello') by inserting a micro-dwell anchor
    so the conditional generator knows where double letters occur.
    """
    device = letter_ids.device
    bsz, max_len = letter_ids.shape
    if uk_vk.shape[0] == 1 and bsz > 1:
        uk_vk = uk_vk.expand(bsz, -1, -1)

    # Gather key centers for each letter in the word: [B, max_len, 2]
    safe_ids = letter_ids.clamp(0, 25)
    batch_idx = torch.arange(bsz, device=device)[:, None].expand(bsz, max_len)
    pts = uk_vk[batch_idx, safe_ids]  # [B, max_len, 2]

    pos_idx = torch.arange(max_len, device=device)[None, :]
    valid = pos_idx < lengths[:, None]

    # Segment lengths between consecutive valid letters; give repeated letters a small
    # virtual arc length (0.35 pitch) so double letters occupy real trajectory frames!
    next_idx = (pos_idx + 1).clamp(max=max_len - 1)
    pts_next = pts[:, next_idx[0]]
    raw_seg = torch.hypot(pts_next[:, :, 0] - pts[:, :, 0], pts_next[:, :, 1] - pts[:, :, 1])
    is_repeat = (safe_ids[:, next_idx[0]] == safe_ids) & (pos_idx < (lengths[:, None] - 1))
    seg_valid = pos_idx < (lengths[:, None] - 1)
    seg_len = torch.where(is_repeat, torch.full_like(raw_seg, 0.35), raw_seg)
    seg_len = torch.where(seg_valid, seg_len.clamp(min=1e-3), torch.zeros_like(seg_len))

    cum_s = torch.cat([torch.zeros((bsz, 1), device=device), torch.cumsum(seg_len[:, :-1], dim=1)], dim=1)
    total_s = cum_s.gather(1, (lengths - 1).clamp(min=0)[:, None]).clamp(min=1e-3)  # [B, 1]

    grid = torch.linspace(0.0, 1.0, num_steps, device=device)[None, :] * total_s  # [B, T]

    # Mask invalid positions in cum_s to +inf so searchsorted stays within [0, lengths-1]
    cum_s_masked = torch.where(valid, cum_s, torch.full_like(cum_s, 1e9))
    right = torch.searchsorted(cum_s_masked, grid, right=True)
    left = (right - 1).clamp(min=0)
    max_last = (lengths - 1).clamp(min=0)[:, None]
    right = right.clamp(max=max_last)
    left = left.clamp(max=max_last)

    s_left = cum_s.gather(1, left)
    s_right = cum_s.gather(1, right)
    denom = (s_right - s_left).clamp(min=1e-5)
    alpha = ((grid - s_left) / denom).clamp(0.0, 1.0)

    u_pts = pts[:, :, 0]
    v_pts = pts[:, :, 1]
    u_interp = u_pts.gather(1, left) * (1.0 - alpha) + u_pts.gather(1, right) * alpha
    v_interp = v_pts.gather(1, left) * (1.0 - alpha) + v_pts.gather(1, right) * alpha

    # Proximity to waypoint corners (1 at waypoints, 0 at segment midpoints)
    corner = 1.0 - 2.0 * torch.minimum(alpha, 1.0 - alpha)
    rep_flag = is_repeat.float().gather(1, left) * (1.0 - (2.0 * alpha - 1.0).abs())

    return torch.stack([u_interp, v_interp, corner, rep_flag], dim=-1)
