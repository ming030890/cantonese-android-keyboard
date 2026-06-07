#!/usr/bin/env python3
"""
Split a single safetensors file into N shards that each fit under Play's
512 MB per-asset-pack limit, using the HuggingFace
`model-XXXXX-of-YYYYY.safetensors` naming convention.

The native loader (qwen-asr / src/safetensors.rs `MultiSafetensors::open`)
scans the model directory for `model-*.safetensors` files, sorts them, and
mmaps each as an independent safetensors file. No concatenation, no
duplicated bytes at any point.

Tokenizer files (vocab.json, merges.txt) are copied alongside shard 1
since the loader expects them next to the model.

Usage:
    pip install safetensors torch
    python3 scripts/reshard_model.py \\
        --source /Volumes/SSD/qwen-asr-m4-lmhead-int8 \\
        --pack-a model_pack_a/src/main/assets/qwen-asr-m4-lmhead-int8 \\
        --pack-b model_pack_b/src/main/assets/qwen-asr-m4-lmhead-int8

Re-run is idempotent: if the output shards already exist with matching
total size, it skips work.
"""

import argparse
import os
import shutil
import sys
from pathlib import Path

# Per-pack budget. Play hard limit is 512 MiB; 480 MiB leaves headroom
# for the safetensors header and any future tensor reordering.
SHARD_LIMIT_BYTES = 480 * 1024 * 1024

TOKENIZER_FILES = ["vocab.json", "merges.txt"]


def bin_pack(tensors, limit):
    """
    Greedy first-fit-decreasing bin pack of tensors into shards <= limit bytes.
    Returns a list of dicts {name: tensor}. Larger tensors go first so a
    single oversized tensor (>limit) fails loudly instead of silently
    spilling.
    """
    items = sorted(
        tensors.items(),
        key=lambda kv: kv[1].numel() * kv[1].element_size(),
        reverse=True,
    )
    shards: list[tuple[dict, int]] = []  # (tensors, size)
    for name, t in items:
        sz = t.numel() * t.element_size()
        if sz > limit:
            raise RuntimeError(
                f"Tensor '{name}' is {sz / 1024 / 1024:.1f} MiB, larger than "
                f"per-shard limit {limit / 1024 / 1024:.0f} MiB. "
                "Increase SHARD_LIMIT_BYTES or split into more packs."
            )
        placed = False
        for shard, current in shards:
            if current + sz <= limit:
                shard[name] = t
                shards[shards.index((shard, current))] = (shard, current + sz)
                placed = True
                break
        if not placed:
            shards.append(({name: t}, sz))
    return [s for s, _ in shards]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, help="Directory containing the original model.safetensors + tokenizer files")
    ap.add_argument("--pack-a", required=True, help="Output dir for shard 1 + tokenizer files (inside model_pack_a)")
    ap.add_argument("--pack-b", required=True, help="Output dir for shard 2 (inside model_pack_b)")
    ap.add_argument("--limit-mib", type=int, default=480, help="Per-shard byte budget in MiB (default 480)")
    ap.add_argument("--force", action="store_true", help="Regenerate even if outputs look up to date")
    args = ap.parse_args()

    limit = args.limit_mib * 1024 * 1024
    source = Path(args.source)
    pack_a = Path(args.pack_a)
    pack_b = Path(args.pack_b)

    src_model = source / "model.safetensors"
    if not src_model.exists():
        sys.exit(f"error: {src_model} not found")

    shard1_path = pack_a / "model-00001-of-00002.safetensors"
    shard2_path = pack_b / "model-00002-of-00002.safetensors"

    # Idempotency check: total bytes across shards should match source.
    if not args.force and shard1_path.exists() and shard2_path.exists():
        src_size = src_model.stat().st_size
        out_size = shard1_path.stat().st_size + shard2_path.stat().st_size
        # Each shard adds its own header, so out_size will be slightly larger;
        # if it's within 2 MiB we treat it as up to date.
        if 0 <= out_size - src_size < 2 * 1024 * 1024:
            print(f"shards already present and consistent ({out_size / 1024 / 1024:.1f} MiB total). Use --force to rebuild.")
            _copy_tokenizer(source, pack_a)
            return

    try:
        from safetensors import safe_open
        from safetensors.torch import save_file
        import torch  # noqa: F401  (safetensors.torch needs torch present)
    except ImportError as e:
        sys.exit(
            f"error: {e}\n"
            "Install dependencies first:\n"
            "    pip install safetensors torch --index-url https://download.pytorch.org/whl/cpu"
        )

    print(f"loading {src_model} ...")
    tensors = {}
    with safe_open(str(src_model), framework="pt") as f:
        for key in f.keys():
            tensors[key] = f.get_tensor(key)
    total = sum(t.numel() * t.element_size() for t in tensors.values())
    print(f"  {len(tensors)} tensors, {total / 1024 / 1024:.1f} MiB total")

    shards = bin_pack(tensors, limit)
    if len(shards) != 2:
        sys.exit(
            f"error: bin-pack produced {len(shards)} shards, expected 2. "
            f"Adjust --limit-mib (currently {args.limit_mib} MiB)."
        )

    pack_a.mkdir(parents=True, exist_ok=True)
    pack_b.mkdir(parents=True, exist_ok=True)

    # Sort shards deterministically: largest first goes to shard 1 so the
    # smaller one is in pack_b (matches typical fast-follow ordering).
    shards.sort(key=lambda s: -sum(t.numel() * t.element_size() for t in s.values()))

    print(f"writing {shard1_path} ({len(shards[0])} tensors)...")
    save_file(shards[0], str(shard1_path))
    print(f"  -> {shard1_path.stat().st_size / 1024 / 1024:.1f} MiB")

    print(f"writing {shard2_path} ({len(shards[1])} tensors)...")
    save_file(shards[1], str(shard2_path))
    print(f"  -> {shard2_path.stat().st_size / 1024 / 1024:.1f} MiB")

    _copy_tokenizer(source, pack_a)

    print("done.")


def _copy_tokenizer(source: Path, pack_a: Path):
    """Tokenizer files live alongside shard 1 in pack_a."""
    pack_a.mkdir(parents=True, exist_ok=True)
    for name in TOKENIZER_FILES:
        src = source / name
        if not src.exists():
            print(f"warning: {src} not found, skipping")
            continue
        dst = pack_a / name
        if dst.exists() and dst.stat().st_size == src.stat().st_size:
            continue
        print(f"copying {name} -> {dst}")
        shutil.copy2(src, dst)


if __name__ == "__main__":
    main()
