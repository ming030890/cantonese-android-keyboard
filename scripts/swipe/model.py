"""Static-shape L=32 Conv-Transformer CTC swipe encoder + KBSWP01 INT8 exporter."""
from __future__ import annotations

import json
import math
import struct
from pathlib import Path
from typing import Dict, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from scripts.swipe.geometry import BLANK_IDX, FEATURE_DIM, NUM_CTC_CLASSES, NUM_STEPS

NUM_FRAMES = NUM_STEPS // 2  # 32


class RMSNorm(nn.Module):
    def __init__(self, dim: int, eps: float = 1e-5) -> None:
        super().__init__()
        self.eps = eps
        self.weight = nn.Parameter(torch.ones(dim))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        rms = torch.rsqrt(x.pow(2).mean(dim=-1, keepdim=True) + self.eps)
        return x * rms * self.weight


class ConvTransformerBlock(nn.Module):
    """Pre-Norm Multi-Head Self-Attention (32x32) + Depthwise-Conv FFN.
    97% of FLOPs are dense INT8 GEMMs; 32x32 global attention calibrates per-swipe slant/offset.
    """

    def __init__(self, d_model: int = 256, n_heads: int = 4, ffn_dim: int = 512, dropout: float = 0.05) -> None:
        super().__init__()
        assert d_model % n_heads == 0
        self.d_model = d_model
        self.n_heads = n_heads
        self.head_dim = d_model // n_heads

        self.attn_norm = RMSNorm(d_model)
        self.qkv = nn.Linear(d_model, 3 * d_model, bias=False)
        self.out_proj = nn.Linear(d_model, d_model, bias=False)
        self.resid_drop = nn.Dropout(dropout)

        self.ffn_norm = RMSNorm(d_model)
        self.ffn_up = nn.Linear(d_model, ffn_dim, bias=False)
        self.dw_conv = nn.Conv1d(ffn_dim, ffn_dim, kernel_size=5, padding=2, groups=ffn_dim, bias=True)
        self.ffn_down = nn.Linear(ffn_dim, d_model, bias=False)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B, L, D]
        bsz, seq_len, _ = x.shape
        h = self.attn_norm(x)
        qkv = self.qkv(h).view(bsz, seq_len, 3, self.n_heads, self.head_dim)
        q = qkv[:, :, 0].transpose(1, 2)  # [B, H, L, hd]
        k = qkv[:, :, 1].transpose(1, 2)
        v = qkv[:, :, 2].transpose(1, 2)

        attn_out = F.scaled_dot_product_attention(q, k, v)  # [B, H, L, hd]
        attn_out = attn_out.transpose(1, 2).contiguous().view(bsz, seq_len, self.d_model)
        x = x + self.resid_drop(self.out_proj(attn_out))

        h2 = self.ffn_norm(x)
        u = F.silu(self.ffn_up(h2))  # [B, L, ffn_dim]
        u = self.dw_conv(u.transpose(1, 2)).transpose(1, 2)
        u = F.silu(u)
        x = x + self.resid_drop(self.ffn_down(u))
        return x


class SwipeConformerEncoder(nn.Module):
    def __init__(
        self,
        in_features: int = FEATURE_DIM,
        d_model: int = 256,
        n_layers: int = 6,
        n_heads: int = 4,
        ffn_dim: int = 512,
        dropout: float = 0.05,
    ) -> None:
        super().__init__()
        self.in_features = in_features
        self.d_model = d_model
        self.n_layers = n_layers
        self.n_heads = n_heads
        self.ffn_dim = ffn_dim

        self.stem = nn.Conv1d(in_features, d_model, kernel_size=3, stride=2, padding=1, bias=True)
        self.pos_emb = nn.Parameter(torch.zeros(1, NUM_FRAMES, d_model))
        nn.init.normal_(self.pos_emb, std=0.02)

        self.blocks = nn.ModuleList(
            [ConvTransformerBlock(d_model, n_heads, ffn_dim, dropout) for _ in range(n_layers)]
        )
        self.final_norm = RMSNorm(d_model)
        self.ctc_head = nn.Linear(d_model, NUM_CTC_CLASSES, bias=True)
        self.start_head = nn.Linear(d_model, 26, bias=True)
        self.end_head = nn.Linear(d_model, 26, bias=True)

    def forward(self, feats: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """
        Args:
            feats: [B, 64, 60]
        Returns:
            ctc_logp: [B, 32, 27]
            start_logp: [B, 26]
            end_logp: [B, 26]
        """
        x = F.silu(self.stem(feats.transpose(1, 2))).transpose(1, 2)  # [B, 32, D]
        x = x + self.pos_emb
        for blk in self.blocks:
            x = blk(x)
        x = self.final_norm(x)
        ctc_logp = F.log_softmax(self.ctc_head(x), dim=-1)
        start_logp = F.log_softmax(self.start_head(x[:, 0, :]), dim=-1)
        end_logp = F.log_softmax(self.end_head(x[:, -1, :]), dim=-1)
        return ctc_logp, start_logp, end_logp


def _quantize_per_row_int8(weight: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Quantize [rows, cols] float32 matrix to symmetric per-row int8 + float32 row scales."""
    assert weight.ndim == 2
    max_abs = np.max(np.abs(weight), axis=1)
    scales = np.maximum(max_abs / 127.0, 1e-8).astype(np.float32)
    q = np.clip(np.round(weight / scales[:, None]), -127, 127).astype(np.int8)
    return q, scales


def export_kbswp_int8(model: SwipeConformerEncoder, out_path: Path) -> Dict:
    """Export SwipeConformerEncoder into KBSWP01\\0 binary format for Rust cantonese-ngram."""
    state = {k: v.detach().cpu().float().numpy() for k, v in model.state_dict().items()}

    tensors_meta = []
    payload = bytearray()

    def align4() -> None:
        rem = len(payload) % 4
        if rem:
            payload.extend(b"\x00" * (4 - rem))

    def add_f32(name: str, arr: np.ndarray) -> None:
        align4()
        arr_c = np.ascontiguousarray(arr, dtype="<f4")
        raw = arr_c.tobytes()
        tensors_meta.append(
            {
                "name": name,
                "dtype": "f32",
                "shape": list(arr_c.shape),
                "offset": len(payload),
                "bytes": len(raw),
            }
        )
        payload.extend(raw)

    def add_row_i8(name: str, arr: np.ndarray) -> None:
        q, scales = _quantize_per_row_int8(arr)
        align4()
        q_c = np.ascontiguousarray(q, dtype=np.int8)
        raw_q = q_c.tobytes()
        tensors_meta.append(
            {
                "name": name,
                "dtype": "i8",
                "shape": list(q_c.shape),
                "offset": len(payload),
                "bytes": len(raw_q),
            }
        )
        payload.extend(raw_q)
        add_f32(f"{name}.scale", scales)

    # Stem Conv1d [D, 60, 3] -> store as [D, 3, 60] so frame slice [3, 60] is contiguous!
    stem_w = np.transpose(state["stem.weight"], (0, 2, 1)).reshape(model.d_model, 3 * model.in_features)
    add_f32("stem.weight", stem_w)
    add_f32("stem.bias", state["stem.bias"])
    add_f32("pos_emb", state["pos_emb"][0])

    for i in range(model.n_layers):
        p = f"blocks.{i}"
        add_f32(f"{p}.attn_norm.weight", state[f"{p}.attn_norm.weight"])
        add_row_i8(f"{p}.qkv.weight", state[f"{p}.qkv.weight"])
        add_row_i8(f"{p}.out_proj.weight", state[f"{p}.out_proj.weight"])
        add_f32(f"{p}.ffn_norm.weight", state[f"{p}.ffn_norm.weight"])
        add_row_i8(f"{p}.ffn_up.weight", state[f"{p}.ffn_up.weight"])
        # dw_conv: [ffn_dim, 1, 5] -> [ffn_dim, 5]
        add_f32(f"{p}.dw_conv.weight", state[f"{p}.dw_conv.weight"][:, 0, :])
        add_f32(f"{p}.dw_conv.bias", state[f"{p}.dw_conv.bias"])
        add_row_i8(f"{p}.ffn_down.weight", state[f"{p}.ffn_down.weight"])

    add_f32("final_norm.weight", state["final_norm.weight"])
    add_f32("ctc_head.weight", state["ctc_head.weight"])
    add_f32("ctc_head.bias", state["ctc_head.bias"])
    add_f32("start_head.weight", state["start_head.weight"])
    add_f32("start_head.bias", state["start_head.bias"])
    add_f32("end_head.weight", state["end_head.weight"])
    add_f32("end_head.bias", state["end_head.bias"])

    meta = {
        "format": "KBSWP_INT8_V1",
        "num_steps": NUM_STEPS,
        "num_frames": NUM_FRAMES,
        "in_features": model.in_features,
        "d_model": model.d_model,
        "n_layers": model.n_layers,
        "n_heads": model.n_heads,
        "ffn_dim": model.ffn_dim,
        "num_ctc_classes": NUM_CTC_CLASSES,
        "blank_idx": BLANK_IDX,
        "tensors": tensors_meta,
    }
    meta_bytes = json.dumps(meta, separators=(",", ":")).encode("utf-8")
    header = b"KBSWP01\x00" + struct.pack("<I", len(meta_bytes)) + meta_bytes
    pad_len = ((len(header) + 63) // 64) * 64 - len(header)
    blob = header + (b"\x00" * pad_len) + bytes(payload)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(blob)
    return {"bytes": len(blob), "n_tensors": len(tensors_meta)}


def forward_kbswp_int8_numpy(blob: bytes, feats: np.ndarray) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Reference INT8 forward pass matching Rust swipe.rs for exact parity verification."""
    assert blob[:8] == b"KBSWP01\x00"
    meta_len = struct.unpack("<I", blob[8:12])[0]
    meta = json.loads(blob[12 : 12 + meta_len])
    base = ((12 + meta_len + 63) // 64) * 64

    tensors = {}
    for t in meta["tensors"]:
        raw = blob[base + t["offset"] : base + t["offset"] + t["bytes"]]
        if t["dtype"] == "f32":
            tensors[t["name"]] = np.frombuffer(raw, dtype="<f4").reshape(t["shape"]).astype(np.float32)
        else:
            tensors[t["name"]] = np.frombuffer(raw, dtype=np.int8).reshape(t["shape"])

    def silu(x: np.ndarray) -> np.ndarray:
        return x / (1.0 + np.exp(-x))

    def rms_norm(x: np.ndarray, w: np.ndarray) -> np.ndarray:
        rms = 1.0 / np.sqrt(np.mean(x * x, axis=-1, keepdims=True) + 1e-5)
        return x * rms * w

    def i8_linear(x: np.ndarray, name: str) -> np.ndarray:
        q = tensors[name].astype(np.float32)
        s = tensors[f"{name}.scale"]
        return (x @ q.T) * s[None, :]

    def log_softmax(x: np.ndarray) -> np.ndarray:
        m = np.max(x, axis=-1, keepdims=True)
        e = np.exp(x - m)
        return (x - m) - np.log(np.sum(e, axis=-1, keepdims=True))

    # Stem: pad 1 frame on each side of [64, 60] -> [66, 60]
    padded = np.zeros((NUM_STEPS + 2, meta["in_features"]), dtype=np.float32)
    padded[1 : NUM_STEPS + 1] = feats
    patches = np.stack([padded[2 * m : 2 * m + 3].reshape(-1) for m in range(NUM_FRAMES)], axis=0)
    x = silu(patches @ tensors["stem.weight"].T + tensors["stem.bias"]) + tensors["pos_emb"]

    n_heads = meta["n_heads"]
    d_model = meta["d_model"]
    hd = d_model // n_heads
    scale = 1.0 / math.sqrt(hd)

    for i in range(meta["n_layers"]):
        p = f"blocks.{i}"
        h = rms_norm(x, tensors[f"{p}.attn_norm.weight"])
        qkv = i8_linear(h, f"{p}.qkv.weight").reshape(NUM_FRAMES, 3, n_heads, hd)
        q = np.transpose(qkv[:, 0], (1, 0, 2))  # [H, L, hd]
        k = np.transpose(qkv[:, 1], (1, 0, 2))
        v = np.transpose(qkv[:, 2], (1, 0, 2))

        scores = np.matmul(q, np.transpose(k, (0, 2, 1))) * scale
        scores = scores - np.max(scores, axis=-1, keepdims=True)
        probs = np.exp(scores)
        probs /= np.sum(probs, axis=-1, keepdims=True)
        attn = np.matmul(probs, v)  # [H, L, hd]
        attn = np.transpose(attn, (1, 0, 2)).reshape(NUM_FRAMES, d_model)
        x = x + i8_linear(attn, f"{p}.out_proj.weight")

        h2 = rms_norm(x, tensors[f"{p}.ffn_norm.weight"])
        u = silu(i8_linear(h2, f"{p}.ffn_up.weight"))  # [L, ffn_dim]
        u_pad = np.zeros((NUM_FRAMES + 4, meta["ffn_dim"]), dtype=np.float32)
        u_pad[2 : NUM_FRAMES + 2] = u
        dw_w = tensors[f"{p}.dw_conv.weight"]  # [ffn_dim, 5]
        dw_out = sum(u_pad[k_i : k_i + NUM_FRAMES] * dw_w[:, k_i] for k_i in range(5)) + tensors[f"{p}.dw_conv.bias"]
        u = silu(dw_out)
        x = x + i8_linear(u, f"{p}.ffn_down.weight")

    x = rms_norm(x, tensors["final_norm.weight"])
    ctc_logp = log_softmax(x @ tensors["ctc_head.weight"].T + tensors["ctc_head.bias"])
    start_logp = log_softmax(x[0] @ tensors["start_head.weight"].T + tensors["start_head.bias"])
    end_logp = log_softmax(x[-1] @ tensors["end_head.weight"].T + tensors["end_head.bias"])
    return ctc_logp, start_logp, end_logp
