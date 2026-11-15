"""Compile FUTO swipe JSONL splits into compact binary pitch-space arrays on SSD."""
from __future__ import annotations

import argparse
import json
import multiprocessing as mp
import re
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

from scripts.swipe.geometry import (
    NUM_STEPS,
    key_centers_to_pitch_space,
    load_futo_qwerty_unit_centers,
    resample_points_pitch,
)

STRIP_PUNCT_RE = re.compile(r"^[^A-Za-z0-9']+|[^A-Za-z0-9']+$")
MAX_WORD_LEN = 28

_UNIT_CENTERS: Optional[np.ndarray] = None


def _init_worker(qwerty_path: str) -> None:
    global _UNIT_CENTERS
    _UNIT_CENTERS = load_futo_qwerty_unit_centers(Path(qwerty_path))


def clean_surface_and_letters(raw_word: str) -> Optional[Tuple[str, bytes]]:
    w = raw_word.replace("’", "'").strip()
    w = STRIP_PUNCT_RE.sub("", w)
    if not w:
        return None
    letters = bytes(ord(c) - ord("a") for c in w.lower() if "a" <= c <= "z")
    if not (1 <= len(letters) <= MAX_WORD_LEN):
        return None
    # Skip words containing digits
    if any("0" <= c <= "9" for c in w):
        return None
    return w, letters


def _process_chunk(lines: List[str]) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, List[Dict]]:
    assert _UNIT_CENTERS is not None
    uvt_list = []
    aspect_list = []
    letters_list = []
    len_list = []
    meta_list = []

    for line in lines:
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        cleaned = clean_surface_and_letters(row.get("word", ""))
        if cleaned is None:
            continue
        surface, letters = cleaned
        pts = row.get("data")
        if not pts or len(pts) < 2:
            continue
        cw = float(row.get("canvas_width") or 400.0)
        ch = float(row.get("canvas_height") or 170.0)
        if cw <= 10.0 or ch <= 10.0:
            cw, ch = 400.0, 170.0

        centers_px = np.empty((26, 2), dtype=np.float32)
        centers_px[:, 0] = _UNIT_CENTERS[:, 0] * cw
        centers_px[:, 1] = _UNIT_CENTERS[:, 1] * ch
        _, _, x_min, y_min, pitch_x, pitch_y, log_aspect = key_centers_to_pitch_space(centers_px)

        xs = np.fromiter((float(p["x"]) * cw for p in pts), dtype=np.float64, count=len(pts))
        ys = np.fromiter((float(p["y"]) * ch for p in pts), dtype=np.float64, count=len(pts))
        ts = np.fromiter((float(p["t"]) for p in pts), dtype=np.float64, count=len(pts))

        uvt, total_arc = resample_points_pitch(xs, ys, ts, x_min, y_min, pitch_x, pitch_y, NUM_STEPS)
        # Filter accidental micro-taps on multi-letter words
        if len(letters) >= 2 and total_arc < 0.15:
            continue

        padded = np.zeros(MAX_WORD_LEN, dtype=np.uint8)
        padded[: len(letters)] = np.frombuffer(letters, dtype=np.uint8)

        sent = row.get("sentence") or ""
        w_idx = int(row.get("word_idx") or 0)
        toks = sent.split()
        left = (" ".join(toks[:w_idx]) + " ") if 0 < w_idx <= len(toks) else ""

        uvt_list.append(uvt.astype(np.float16))
        aspect_list.append(np.float16(log_aspect))
        letters_list.append(padded)
        len_list.append(np.uint8(len(letters)))
        meta_list.append(
            {
                "surface": surface,
                "left": left,
                "cw": round(cw, 2),
                "ch": round(ch, 2),
            }
        )

    if not uvt_list:
        return (
            np.zeros((0, NUM_STEPS, 3), dtype=np.float16),
            np.zeros((0,), dtype=np.float16),
            np.zeros((0, MAX_WORD_LEN), dtype=np.uint8),
            np.zeros((0,), dtype=np.uint8),
            [],
        )

    return (
        np.stack(uvt_list, axis=0),
        np.array(aspect_list, dtype=np.float16),
        np.stack(letters_list, axis=0),
        np.array(len_list, dtype=np.uint8),
        meta_list,
    )


def _iter_line_chunks(path: Path, chunk_size: int = 4096):
    buf: List[str] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            buf.append(line)
            if len(buf) >= chunk_size:
                yield buf
                buf = []
    if buf:
        yield buf


def compile_split(
    jsonl_path: Path,
    qwerty_path: Path,
    out_dir: Path,
    split_name: str,
    save_meta: bool,
    workers: int = 8,
) -> Dict:
    t0 = time.time()
    out_dir.mkdir(parents=True, exist_ok=True)
    uvt_parts = []
    aspect_parts = []
    letters_parts = []
    len_parts = []
    meta_out = (out_dir / f"{split_name}_meta.jsonl").open("w", encoding="utf-8") if save_meta else None

    total = 0
    with mp.Pool(processes=workers, initializer=_init_worker, initargs=(str(qwerty_path),)) as pool:
        for i, (uvt, aspect, letters, lengths, metas) in enumerate(
            pool.imap(_process_chunk, _iter_line_chunks(jsonl_path, 4096), chunksize=2)
        ):
            if len(lengths) == 0:
                continue
            uvt_parts.append(uvt)
            aspect_parts.append(aspect)
            letters_parts.append(letters)
            len_parts.append(lengths)
            total += len(lengths)
            if meta_out is not None:
                for m in metas:
                    meta_out.write(json.dumps(m, ensure_ascii=False) + "\n")
            if (i + 1) % 25 == 0:
                print(f"  [{split_name}] processed {total:,} valid swipes ({time.time() - t0:.1f}s)")

    if meta_out is not None:
        meta_out.close()

    uvt_all = np.concatenate(uvt_parts, axis=0)
    aspect_all = np.concatenate(aspect_parts, axis=0)
    letters_all = np.concatenate(letters_parts, axis=0)
    len_all = np.concatenate(len_parts, axis=0)

    np.save(out_dir / f"{split_name}_uvt.npy", uvt_all)
    np.save(out_dir / f"{split_name}_aspect.npy", aspect_all)
    np.save(out_dir / f"{split_name}_letters.npy", letters_all)
    np.save(out_dir / f"{split_name}_lengths.npy", len_all)

    # Save the exact normalized FUTO pitch-space key centers as reference
    unit = load_futo_qwerty_unit_centers(qwerty_path)
    centers_px = np.stack([unit[:, 0] * 400.0, unit[:, 1] * 170.0], axis=-1)
    u_k, v_k, *_ = key_centers_to_pitch_space(centers_px)
    np.save(out_dir / "futo_uk_vk.npy", np.stack([u_k, v_k], axis=-1))

    dt = time.time() - t0
    summary = {
        "split": split_name,
        "count": int(total),
        "seconds": round(dt, 2),
    }
    print(f"Finished {split_name}: {total:,} swipes in {dt:.1f}s")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--futo-dir", type=Path, default=Path("/Volumes/SSD/swipe-typing/futo"))
    parser.add_argument("--out-dir", type=Path, default=Path("/Volumes/SSD/swipe-typing/prepared"))
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    qwerty = args.futo_dir / "swipe-5/layouts/qwerty.json"
    stats = []
    for split, keep_meta in [("dev", True), ("test", True), ("train", False)]:
        stats.append(
            compile_split(
                args.futo_dir / f"{split}.jsonl",
                qwerty,
                args.out_dir,
                split,
                save_meta=keep_meta,
                workers=args.workers,
            )
        )
    (args.out_dir / "manifest.json").write_text(json.dumps(stats, indent=2) + "\n")


if __name__ == "__main__":
    main()
