#!/usr/bin/env python3
"""Filter noisy text rows from a generated ASR dataset manifest."""

from __future__ import annotations

import argparse
import json
import os
from collections import Counter
from pathlib import Path
from typing import Any


CJK_RANGES = (
    (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF),
    (0xF900, 0xFAFF),
    (0x20000, 0x2FA1F),
    (0x30000, 0x323AF),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Keep only ASR-friendly CJK words and phrases in a dataset."
    )
    parser.add_argument("--dataset", type=Path, default=Path("dataset"))
    parser.add_argument("--min-chars", type=int, default=2)
    parser.add_argument("--max-chars", type=int, default=12)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Rewrite the manifest and delete rejected WAVs; otherwise only report.",
    )
    args = parser.parse_args()
    if args.min_chars < 1:
        parser.error("--min-chars must be at least 1")
    if args.max_chars < args.min_chars:
        parser.error("--max-chars must be at least --min-chars")
    return args


def is_cjk_character(character: str) -> bool:
    codepoint = ord(character)
    return any(start <= codepoint <= end for start, end in CJK_RANGES)


def rejection_reason(text: str, min_chars: int, max_chars: int) -> str | None:
    if not text:
        return "empty"
    if not all(is_cjk_character(character) for character in text):
        return "contains_non_cjk"
    if len(text) < min_chars:
        return "too_short"
    if len(text) > max_chars:
        return "too_long"
    return None


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_number}: row must be a JSON object")
            rows.append(row)
    return rows


def write_jsonl_atomic(path: Path, rows: list[dict[str, Any]]) -> None:
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    with temporary_path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    os.replace(temporary_path, path)


def checked_audio_path(dataset_dir: Path, relative_path: str) -> Path:
    audio_dir = (dataset_dir / "audio").resolve()
    audio_path = (dataset_dir / relative_path).resolve()
    if audio_path.parent != audio_dir or audio_path.suffix.lower() != ".wav":
        raise ValueError(f"unsafe audio_filepath: {relative_path!r}")
    return audio_path


def filter_rows(
    rows: list[dict[str, Any]], min_chars: int, max_chars: int
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    kept: list[dict[str, str]] = []
    removed: list[dict[str, str]] = []
    for index, row in enumerate(rows, 1):
        if set(row) != {"audio_filepath", "text"}:
            raise ValueError(f"manifest row {index} must contain exactly audio_filepath and text")
        audio_filepath = row["audio_filepath"]
        text = row["text"]
        if not isinstance(audio_filepath, str) or not isinstance(text, str):
            raise ValueError(f"manifest row {index} fields must be strings")
        reason = rejection_reason(text, min_chars, max_chars)
        if reason is None:
            kept.append({"audio_filepath": audio_filepath, "text": text})
        else:
            removed.append(
                {"audio_filepath": audio_filepath, "text": text, "reason": reason}
            )
    return kept, removed


def apply_filter(dataset_dir: Path, min_chars: int, max_chars: int, apply: bool) -> int:
    dataset_dir = dataset_dir.expanduser().resolve()
    manifest_path = dataset_dir / "manifest.jsonl"
    rows = read_jsonl(manifest_path)
    kept, removed = filter_rows(rows, min_chars, max_chars)
    counts = Counter(row["reason"] for row in removed)

    print(f"Scanned {len(rows)} rows: {len(kept)} kept, {len(removed)} rejected.")
    for reason, count in sorted(counts.items()):
        print(f"  {reason}: {count}")
    if not apply:
        print("Dry run only; pass --apply to rewrite the manifest and delete rejected WAVs.")
        return 0

    for row in removed:
        checked_audio_path(dataset_dir, row["audio_filepath"]).unlink(missing_ok=True)

    removed_path = dataset_dir / "removed.jsonl"
    prior_removed = read_jsonl(removed_path) if removed_path.exists() else []
    combined: dict[str, dict[str, Any]] = {
        str(row.get("audio_filepath")): row for row in prior_removed
    }
    combined.update({row["audio_filepath"]: row for row in removed})

    write_jsonl_atomic(manifest_path, kept)
    write_jsonl_atomic(removed_path, list(combined.values()))
    print(f"Updated {manifest_path} and deleted {len(removed)} rejected WAVs.")
    return 0


def main() -> int:
    args = parse_args()
    try:
        return apply_filter(args.dataset, args.min_chars, args.max_chars, args.apply)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
