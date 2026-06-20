"""Stable JSONL helpers and final output projection."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable

from . import SOURCE_NAME


def write_jsonl(path: str | Path, rows: Iterable[dict]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def export_ranked(path: str | Path, rows: list[dict], top_n: int | None = None) -> int:
    by_text: dict[str, dict] = {}
    for row in rows:
        text = row["text"][:-1] if len(row["text"]) > 1 and row["text"].endswith("站") else row["text"]
        projected = {"text": text, "score": round(row["final_score"], 4), "source": SOURCE_NAME}
        previous = by_text.get(text)
        if previous is None or projected["score"] > previous["score"]:
            by_text[text] = projected
    ordered = sorted(by_text.values(), key=lambda row: (-row["score"], row["text"]))
    if top_n is not None:
        ordered = ordered[:top_n]
    write_jsonl(path, ordered)
    return len(ordered)
