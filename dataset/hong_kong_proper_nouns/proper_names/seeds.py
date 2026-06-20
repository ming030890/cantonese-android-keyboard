"""Trusted, equal-weight Hong Kong graph anchors."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable

from .text_cleaning import clean_title


# Categories are deliberately not represented here: every anchor has exactly
# the same weight in personalized PageRank.
BUILTIN_SEEDS = (
    "香港", "九龍", "新界", "港島", "維多利亞港", "中環", "金鐘", "灣仔",
    "銅鑼灣", "旺角", "尖沙咀", "深水埗", "觀塘", "荃灣", "沙田", "大埔",
    "元朗", "屯門", "將軍澳", "港鐵", "九巴", "城巴", "天星小輪",
    "香港電台", "無綫電視", "商業電台", "明報", "蘋果日報", "東方日報",
    "香港大學", "香港中文大學", "香港科技大學", "醫院管理局", "香港賽馬會",
    "香港政府", "立法會", "廉政公署", "警務處", "國泰航空", "滙豐銀行",
    "李嘉誠", "劉德華", "張學友", "周潤發", "梅艷芳", "張國榮", "陳奕迅",
)


def load_seed_jsonl(path: str | Path) -> list[str]:
    values: list[str] = []
    with Path(path).open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            text = clean_title(str(row.get("text", "")))
            if not text:
                raise ValueError(f"{path}:{line_number}: missing non-empty text")
            values.append(text)
    return values


def load_seeds(extra_paths: Iterable[str | Path] = ()) -> list[str]:
    values = [clean_title(value) for value in BUILTIN_SEEDS]
    for path in extra_paths:
        values.extend(load_seed_jsonl(path))
    return sorted({value for value in values if value})
