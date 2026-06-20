#!/usr/bin/env python3
"""Build a deterministic title-mention graph from Cantonese Wikipedia."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterator

from tqdm import tqdm

from proper_names.export import write_jsonl
from proper_names.graph_builder import build_edges, prepare_articles
from proper_names.paths import data_path, link_alias


LOCAL_DATASET = Path("/Volumes/SSD/hk-proper-nouns/wiki-yue-high-quality/data/train-00000-of-00001.parquet")


def iter_rows(dataset_name: str, split: str, limit: int | None) -> Iterator[dict]:
    count = 0
    if dataset_name == "ming030890/wiki-yue-high-quality" and LOCAL_DATASET.exists():
        import pyarrow.parquet as parquet

        parquet_file = parquet.ParquetFile(LOCAL_DATASET)
        for batch in parquet_file.iter_batches(batch_size=1024, columns=["title", "text"]):
            for row in batch.to_pylist():
                if limit is not None and count >= limit:
                    return
                count += 1
                yield row
        return
    from datasets import load_dataset

    dataset = load_dataset(dataset_name, split=split, streaming=True)
    for row in dataset:
        if limit is not None and count >= limit:
            return
        count += 1
        yield {"title": row.get("title", ""), "text": row.get("text", "")}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-name", default="ming030890/wiki-yue-high-quality")
    parser.add_argument("--split", default="train")
    parser.add_argument("--out-dir", default="dataset/wiki_yue_graph")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--min-title-len", type=int, default=2)
    parser.add_argument("--max-title-len", type=int, default=30)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.limit is not None and args.limit <= 0:
        raise SystemExit("--limit must be positive")
    if args.min_title_len < 1 or args.max_title_len < args.min_title_len:
        raise SystemExit("invalid title length range")
    out_dir, alias = data_path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = tqdm(iter_rows(args.dataset_name, args.split, args.limit), total=args.limit, desc="Loading articles")
    articles = prepare_articles(rows, args.min_title_len, args.max_title_len)
    nodes = [{"id": node_id, "title": article.title} for node_id, article in enumerate(articles)]
    edges, mention_stats, article_rows = build_edges(articles)
    write_jsonl(out_dir / "nodes.jsonl", nodes)
    with (out_dir / "title_to_id.json").open("w", encoding="utf-8") as handle:
        json.dump({row["title"]: row["id"] for row in nodes}, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        handle.write("\n")
    write_jsonl(out_dir / "edges.jsonl", edges)
    write_jsonl(out_dir / "mention_stats.jsonl", mention_stats)
    write_jsonl(out_dir / "articles.jsonl", article_rows)
    link_alias(out_dir, alias)
    print(f"Wrote {len(nodes)} nodes and {len(edges)} edges to {out_dir}")


if __name__ == "__main__":
    main()
