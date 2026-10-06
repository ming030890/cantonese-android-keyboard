#!/usr/bin/env python3
"""
Split all model safetensors files into N shards that each fit under Play's
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
import hashlib
import json
import os
import shutil
import struct
import sys
from pathlib import Path

# Per-pack budget. Play hard limit is 512 MiB; 480 MiB leaves headroom
# for the safetensors header and any future tensor reordering.
SHARD_LIMIT_BYTES = 480 * 1024 * 1024

TOKENIZER_FILES = [
    "vocab.json",
    "merges.txt",
    "cantonese_zhconv_adapter.json",
    "cantonese_zhconv_adapter.safetensors",
]
RESHARD_MANIFEST = ".reshard_manifest.json"


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


def bin_pack_raw(tensors, limit):
    """
    Dependency-free version of bin_pack for raw safetensors entries.
    tensors maps name -> (meta, raw_bytes).
    """
    items = sorted(
        tensors.items(),
        key=lambda kv: len(kv[1][1]),
        reverse=True,
    )
    shards: list[tuple[dict, int]] = []
    for name, (meta, raw) in items:
        sz = len(raw)
        if sz > limit:
            raise RuntimeError(
                f"Tensor '{name}' is {sz / 1024 / 1024:.1f} MiB, larger than "
                f"per-shard limit {limit / 1024 / 1024:.0f} MiB. "
                "Increase SHARD_LIMIT_BYTES or split into more packs."
            )
        placed = False
        for idx, (shard, current) in enumerate(shards):
            if current + sz <= limit:
                shard[name] = (meta, raw)
                shards[idx] = (shard, current + sz)
                placed = True
                break
        if not placed:
            shards.append(({name: (meta, raw)}, sz))
    return [s for s, _ in shards]


def load_safetensors_raw(path: Path):
    with path.open("rb") as f:
        header_size = struct.unpack("<Q", f.read(8))[0]
        header = json.loads(f.read(header_size).decode("utf-8"))
        data_start = 8 + header_size
        tensors = {}
        for name, meta in header.items():
            if name == "__metadata__":
                continue
            start, end = meta["data_offsets"]
            f.seek(data_start + start)
            raw = f.read(end - start)
            tensors[name] = (meta, raw)
    return tensors


def save_safetensors_raw(tensors, path: Path):
    header = {}
    offset = 0
    for name, (meta, raw) in tensors.items():
        next_offset = offset + len(raw)
        header[name] = {
            "dtype": meta["dtype"],
            "shape": meta["shape"],
            "data_offsets": [offset, next_offset],
        }
        offset = next_offset

    header_json = json.dumps(header, separators=(",", ":")).encode("utf-8")
    with path.open("wb") as f:
        f.write(struct.pack("<Q", len(header_json)))
        f.write(header_json)
        for _, raw in tensors.values():
            f.write(raw)


def sha256_file(path: Path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def source_model_paths(source: Path):
    paths = sorted(source.glob("model*.safetensors"))
    if not paths:
        sys.exit(f"error: no model*.safetensors files found in {source}")
    return paths


def build_source_manifest(source: Path, model_paths: list[Path], limit: int):
    files = {path.name: sha256_file(path) for path in model_paths}
    for name in TOKENIZER_FILES:
        path = source / name
        if path.exists():
            files[name] = sha256_file(path)
    return {
        "version": 1,
        "limit_bytes": limit,
        "files": files,
    }


def read_manifest(path: Path):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def write_manifest(path: Path, manifest):
    path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")


def reshard_raw(src_models: list[Path], pack_dirs: list[Path], limit: int):
    print("loading source shards without safetensors/torch ...")
    tensors = {}
    for src_model in src_models:
        print(f"  {src_model}")
        loaded = load_safetensors_raw(src_model)
        duplicates = tensors.keys() & loaded.keys()
        if duplicates:
            names = ", ".join(sorted(duplicates)[:3])
            sys.exit(f"error: duplicate tensors across source shards: {names}")
        tensors.update(loaded)
    total = sum(len(raw) for _, raw in tensors.values())
    print(f"  {len(tensors)} tensors, {total / 1024 / 1024:.1f} MiB total")

    shards = bin_pack_raw(tensors, limit)
    num_shards = len(shards)
    num_packs = len(pack_dirs)
    if num_shards > num_packs:
        sys.exit(
            f"error: bin-pack produced {num_shards} shards, expected at most {num_packs}. "
            f"Adjust --limit-mib ({limit // 1024 // 1024} MiB) or provide more packs."
        )

    for p in pack_dirs:
        p.mkdir(parents=True, exist_ok=True)
        for old_shard in p.glob("model-*.safetensors"):
            old_shard.unlink()
    shards.sort(key=lambda s: -sum(len(raw) for _, raw in s.values()))

    for i, shard in enumerate(shards):
        shard_path = pack_dirs[i] / f"model-{i+1:05d}-of-{num_shards:05d}.safetensors"
        print(f"writing {shard_path} ({len(shard)} tensors)...")
        save_safetensors_raw(shard, shard_path)
        print(f"  -> {shard_path.stat().st_size / 1024 / 1024:.1f} MiB")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, help="Directory containing the original model.safetensors + tokenizer files")
    ap.add_argument("--pack-a", help="Output dir for shard 1 + tokenizer files (inside model_pack_a)")
    ap.add_argument("--pack-b", help="Output dir for shard 2 (inside model_pack_b)")
    ap.add_argument("--packs", nargs="+", help="Output dirs for all asset packs in order")
    ap.add_argument("--limit-mib", type=int, default=480, help="Per-shard byte budget in MiB (default 480)")
    ap.add_argument("--force", action="store_true", help="Regenerate even if outputs look up to date")
    args = ap.parse_args()

    limit = args.limit_mib * 1024 * 1024
    source = Path(args.source)
    if args.packs:
        pack_dirs = [Path(p) for p in args.packs]
    elif args.pack_a and args.pack_b:
        pack_dirs = [Path(args.pack_a), Path(args.pack_b)]
    else:
        sys.exit("error: must provide either --packs or both --pack-a and --pack-b")

    src_models = source_model_paths(source)
    manifest_path = pack_dirs[0] / RESHARD_MANIFEST
    source_manifest = build_source_manifest(source, src_models, limit)

    num_packs = len(pack_dirs)
    expected_paths = [
        pack_dirs[i] / f"model-{i+1:05d}-of-{num_packs:05d}.safetensors"
        for i in range(num_packs)
    ]
    all_exist = all(p.exists() for p in expected_paths)

    # Idempotency check: preserve existing shard bytes for code-only releases.
    # A forced rewrite makes Play treat the fast-follow asset packs as changed,
    # causing already-installed users to receive a huge asset-pack update.
    if not args.force and all_exist:
        existing_manifest = read_manifest(manifest_path)
        if existing_manifest == source_manifest:
            print("shards already match recorded source fingerprint. Use --force to rebuild.")
            _copy_tokenizer(source, pack_dirs[0])
            return

        src_size = sum(path.stat().st_size for path in src_models)
        out_size = sum(p.stat().st_size for p in expected_paths)
        if abs(out_size - src_size) < 2 * 1024 * 1024:
            print(f"shards already present and size-consistent ({out_size / 1024 / 1024:.1f} MiB total).")
            print(f"recording source fingerprint in {manifest_path}. Use --force to rebuild.")
            pack_dirs[0].mkdir(parents=True, exist_ok=True)
            write_manifest(manifest_path, source_manifest)
            _copy_tokenizer(source, pack_dirs[0])
            return

    try:
        from safetensors import safe_open
        from safetensors.torch import save_file
        import torch  # noqa: F401  (safetensors.torch needs torch present)
    except ImportError as e:
        print(f"warning: {e}; falling back to raw safetensors sharding")
        reshard_raw(src_models, pack_dirs, limit)
        _copy_tokenizer(source, pack_dirs[0])
        write_manifest(manifest_path, source_manifest)
        print("done.")
        return

    print("loading source shards ...")
    tensors = {}
    for src_model in src_models:
        print(f"  {src_model}")
        with safe_open(str(src_model), framework="pt") as f:
            duplicates = tensors.keys() & set(f.keys())
            if duplicates:
                names = ", ".join(sorted(duplicates)[:3])
                sys.exit(f"error: duplicate tensors across source shards: {names}")
            for key in f.keys():
                tensors[key] = f.get_tensor(key)
    total = sum(t.numel() * t.element_size() for t in tensors.values())
    print(f"  {len(tensors)} tensors, {total / 1024 / 1024:.1f} MiB total")

    shards = bin_pack(tensors, limit)
    num_shards = len(shards)
    if num_shards > num_packs:
        sys.exit(
            f"error: bin-pack produced {num_shards} shards, expected at most {num_packs}. "
            f"Adjust --limit-mib (currently {args.limit_mib} MiB) or provide more packs."
        )

    for p in pack_dirs:
        p.mkdir(parents=True, exist_ok=True)
        for old_shard in p.glob("model-*.safetensors"):
            old_shard.unlink()

    # Sort shards deterministically: largest first
    shards.sort(key=lambda s: -sum(t.numel() * t.element_size() for t in s.values()))

    for i, shard in enumerate(shards):
        shard_path = pack_dirs[i] / f"model-{i+1:05d}-of-{num_shards:05d}.safetensors"
        print(f"writing {shard_path} ({len(shard)} tensors)...")
        save_file(shard, str(shard_path))
        print(f"  -> {shard_path.stat().st_size / 1024 / 1024:.1f} MiB")

    _copy_tokenizer(source, pack_dirs[0])
    write_manifest(manifest_path, source_manifest)

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
        if dst.exists() and dst.stat().st_size == src.stat().st_size and sha256_file(dst) == sha256_file(src):
            continue
        print(f"copying {name} -> {dst}")
        shutil.copy2(src, dst)


if __name__ == "__main__":
    main()
