"""Stable JSONL helpers and final output projection."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable

def normalize_export_text(text: str) -> str:
    if len(text) > 1 and text.endswith("站"):
        text = text[:-1]
    if len(text) > 4 and text.startswith("香港"):
        text = text[2:]
    return text


def entity_rows_from_scored(rows: Iterable[dict], top_n: int | None = None) -> list[dict]:
    by_text: dict[str, dict] = {}
    for row in rows:
        text = normalize_export_text(row["text"])
        scored = {"text": text, "score": float(row["score"])}
        previous = by_text.get(text)
        if previous is None or scored["score"] > previous["score"]:
            by_text[text] = scored
    ordered = sorted(by_text.values(), key=lambda row: (-row["score"], row["text"]))
    if top_n is not None:
        ordered = ordered[:top_n]
    return [{"text": row["text"]} for row in ordered]


def write_jsonl(path: str | Path, rows: Iterable[dict]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def export_ranked(path: str | Path, rows: list[dict], top_n: int | None = None) -> int:
    projected = [
        {"text": row["text"], "score": round(row["final_score"], 4)}
        for row in rows
    ]
    entities = entity_rows_from_scored(projected, top_n)
    write_jsonl(path, entities)
    return len(entities)
