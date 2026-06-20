#!/usr/bin/env python3
"""Collect equal-weight Chinese and English MTR station title seeds."""

from __future__ import annotations

import argparse
import csv
import io
from pathlib import Path
from urllib.request import Request, urlopen

from proper_names.export import write_jsonl
from proper_names.paths import data_path, link_alias
from proper_names.text_cleaning import clean_title


DEFAULT_URL = "https://opendata.mtr.com.hk/data/mtr_lines_and_stations.csv"


def find_column(fieldnames: list[str], language: str) -> str:
    lowered = {name: name.lower().replace("_", " ") for name in fieldnames}
    language_words = ("chinese", "chi", "中文") if language == "zh" else ("english", "eng", "英文")
    candidates = [name for name, value in lowered.items() if "station" in value and any(word in value for word in language_words)]
    if not candidates:
        candidates = [name for name, value in lowered.items() if "name" in value and any(word in value for word in language_words)]
    if not candidates:
        raise ValueError(f"Cannot find {language} station-name column in {fieldnames}")
    return sorted(candidates)[0]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--input-csv")
    parser.add_argument("--out", default="dataset/seeds_mtr.jsonl")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.input_csv:
        text = Path(args.input_csv).read_text(encoding="utf-8-sig")
    else:
        request = Request(args.url, headers={"User-Agent": "cantonese-voice-keyboard/1.0"})
        with urlopen(request, timeout=60) as response:
            text = response.read().decode("utf-8-sig")
    reader = csv.DictReader(io.StringIO(text))
    fields = reader.fieldnames or []
    zh_column, en_column = find_column(fields, "zh"), find_column(fields, "en")
    terms: set[str] = set()
    for row in reader:
        zh, en = clean_title(row.get(zh_column, "")), clean_title(row.get(en_column, ""))
        if zh:
            terms.add(zh)
            terms.add(zh if zh.endswith("站") else f"{zh}站")
        if en:
            terms.add(en)
            terms.add(en if en.lower().endswith(" station") else f"{en} station")
    out, alias = data_path(args.out)
    write_jsonl(out, ({"text": term} for term in sorted(terms)))
    link_alias(out, alias)
    print(f"Wrote {len(terms)} MTR seeds to {out}")


if __name__ == "__main__":
    main()
