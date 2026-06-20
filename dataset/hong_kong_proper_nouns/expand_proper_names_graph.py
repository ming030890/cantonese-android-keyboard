#!/usr/bin/env python3
"""Expand equal-weight HK anchors and export graph-ranked proper names."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from proper_names.community import detect_communities
from proper_names.export import export_ranked, write_jsonl
from proper_names.graph_ranker import equal_anchor_pagerank, percentile_ranks, score_nodes
from proper_names.paths import data_path, link_alias
from proper_names.seeds import load_seeds
from proper_names.title_filter import is_candidate_title


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--graph-dir", default="dataset/wiki_yue_graph")
    parser.add_argument("--out", default="dataset/proper_names_graph.jsonl")
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--min-percentile", type=float, default=0.95)
    parser.add_argument("--promote-percentile", type=float, default=0.98)
    parser.add_argument("--top-n", type=int)
    parser.add_argument("--seed-jsonl", action="append", default=[])
    parser.add_argument("--seed-out", help="default: <graph-dir>/expanded_seeds.jsonl")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.iterations < 1 or not 0 <= args.min_percentile <= 1 or not 0 <= args.promote_percentile <= 1:
        raise SystemExit("iterations must be positive and percentiles must be in [0, 1]")
    graph_dir, graph_alias = data_path(args.graph_dir)
    out_path, out_alias = data_path(args.out)
    if args.seed_out:
        seed_out, seed_alias = data_path(args.seed_out)
    else:
        seed_out, seed_alias = graph_dir / "expanded_seeds.jsonl", None
    nodes = read_jsonl(graph_dir / "nodes.jsonl")
    edges = read_jsonl(graph_dir / "edges.jsonl")
    articles = read_jsonl(graph_dir / "articles.jsonl")
    title_to_id = {row["title"]: int(row["id"]) for row in nodes}
    seed_paths = [data_path(path)[0] for path in args.seed_jsonl]
    initial_seeds = load_seeds(seed_paths)
    active_seeds = sorted(seed for seed in initial_seeds if seed in title_to_id)
    if not active_seeds:
        raise SystemExit("No built-in or external seed maps to a graph title")
    promoted_rows: list[dict] = []
    scores: list[dict] = []
    communities: list[dict] = []
    community_stats: list[dict] = []
    pagerank: dict[int, float] = {}
    for iteration in range(1, args.iterations + 1):
        seed_ids = {title_to_id[seed] for seed in active_seeds}
        pagerank = equal_anchor_pagerank([int(row["id"]) for row in nodes], edges, seed_ids)
        communities, community_stats = detect_communities([int(row["id"]) for row in nodes], edges, seed_ids)
        scores = score_nodes(nodes, edges, articles, pagerank, communities, active_seeds)
        final_ranks = percentile_ranks([row["final_score"] for row in scores])
        for row, rank in zip(scores, final_ranks):
            row["final_percentile"] = rank
        if iteration == args.iterations:
            break
        promote = [
            row for row in scores
            if row["final_percentile"] >= args.promote_percentile
            and row["text"] not in active_seeds
            and is_candidate_title(row["text"], active_seeds)
        ]
        promote.sort(key=lambda row: (-row["final_score"], row["text"]))
        for row in promote:
            promoted_rows.append({"text": row["text"], "iteration": iteration})
        active_seeds = sorted(set(active_seeds).union(row["text"] for row in promote))

    candidates = [
        row for row in scores
        if (row["final_percentile"] >= args.min_percentile or row["text"] in initial_seeds)
        and is_candidate_title(row["text"], active_seeds)
    ]
    exported_count = export_ranked(out_path, candidates, args.top_n)
    link_alias(out_path, out_alias)
    write_jsonl(seed_out, promoted_rows)
    link_alias(seed_out, seed_alias)
    write_jsonl(graph_dir / "communities.jsonl", communities)
    write_jsonl(graph_dir / "community_stats.jsonl", community_stats)
    debug_rows = sorted(scores, key=lambda row: (-row["final_score"], row["text"]))[:1000]
    write_jsonl(graph_dir / "top_final_scores.jsonl", debug_rows)
    write_jsonl(graph_dir / "top_graph_scores.jsonl", sorted(scores, key=lambda row: (-row["hk_pagerank"], row["text"]))[:1000])
    write_jsonl(graph_dir / "promoted_each_iteration.jsonl", promoted_rows)
    link_alias(graph_dir, graph_alias)
    print(f"Wrote {exported_count} candidates to {out_path}")


if __name__ == "__main__":
    main()
