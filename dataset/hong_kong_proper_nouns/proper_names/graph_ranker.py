"""Equal-anchor PageRank and percentile-based candidate ranking."""

from __future__ import annotations

from bisect import bisect_left, bisect_right
from collections import defaultdict
import networkx as nx
import numpy as np

from .graph_builder import longest_non_overlapping_matches, make_automaton
from .text_cleaning import clean_text_for_matching


def equal_anchor_pagerank(node_ids: list[int], edges: list[dict], seed_ids: set[int]) -> dict[int, float]:
    if not seed_ids:
        raise ValueError("No seed titles map to graph nodes")
    graph = nx.DiGraph()
    graph.add_nodes_from(sorted(node_ids))
    for edge in edges:
        graph.add_edge(int(edge["src"]), int(edge["dst"]), weight=float(edge["weight"]))
    probability = 1.0 / len(seed_ids)
    personalization = {node: (probability if node in seed_ids else 0.0) for node in node_ids}
    return nx.pagerank(graph, alpha=0.85, personalization=personalization, dangling=personalization, weight="weight", max_iter=200, tol=1.0e-12)


def percentile_ranks(values: list[float]) -> list[float]:
    """Empirical midranks; equal values always receive equal percentiles."""
    ordered = sorted(values)
    return [
        (bisect_left(ordered, value) + bisect_right(ordered, value)) / (2.0 * len(ordered))
        if ordered else 0.0
        for value in values
    ]


def spoken_title_score(title: str) -> float:
    compact = title.replace(" ", "")
    length = len(compact)
    if not compact:
        return 0.0
    score = 100.0
    score -= min(45.0, abs(length - 5) * 3.0)
    score -= min(20.0, sum(char.isdigit() for char in compact) * 5.0)
    if any(word in title for word in ("列表", "一覽", "歷史", "文化", "事件", "概念", "理論")):
        score -= 30.0
    if any(title.endswith(word) for word in ("站", "邨", "苑", "街", "道", "學校", "大學", "醫院", "中心", "商場", "公司", "集團", "會", "局", "署", "台")):
        score += 8.0
    if all(char.isascii() for char in compact) and length > 20:
        score -= 15.0
    return max(0.0, min(100.0, score))


def matched_seed_terms(article: dict, seed_automaton) -> list[str]:
    haystack = clean_text_for_matching(" ".join((article["title"], article["intro"], article["early_body"], article["later_body"])))
    return sorted({match[2] for match in longest_non_overlapping_matches(seed_automaton, haystack)})


def score_nodes(
    nodes: list[dict], edges: list[dict], articles: list[dict], pagerank: dict[int, float],
    communities: list[dict], seeds: list[str],
) -> list[dict]:
    incoming = defaultdict(int)
    for edge in edges:
        incoming[int(edge["dst"])] += int(edge.get("mention_count", 1))
    community_by_id = {int(row["id"]): row for row in communities}
    article_by_id = {int(row["id"]): row for row in articles}
    seed_automaton = make_automaton(seeds)
    base = []
    for node in nodes:
        node_id, title = int(node["id"]), node["title"]
        community = community_by_id[node_id]
        base.append({
            "id": node_id,
            "text": title,
            "hk_pagerank": float(pagerank.get(node_id, 0.0)),
            "community": int(community["community"]),
            "community_hk_score": float(community["community_hk_score"]),
            "incoming_mentions": int(incoming[node_id]),
            "spoken_score": spoken_title_score(title),
            "matched_seeds": matched_seed_terms(article_by_id[node_id], seed_automaton),
        })
    signals = {
        "hk_pagerank_percentile": [row["hk_pagerank"] for row in base],
        "community_hk_percentile": [row["community_hk_score"] for row in base],
        "incoming_mentions_percentile": [float(row["incoming_mentions"]) for row in base],
        "spoken_percentile": [row["spoken_score"] for row in base],
    }
    ranks = {name: percentile_ranks(values) for name, values in signals.items()}
    for index, row in enumerate(base):
        for name in signals:
            row[name] = ranks[name][index]
        row["final_score"] = 100.0 * float(np.mean([ranks[name][index] for name in signals]))
    return sorted(base, key=lambda row: (-row["final_score"], row["text"]))
