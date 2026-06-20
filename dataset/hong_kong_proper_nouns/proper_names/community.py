"""Community detection and seed-enrichment statistics."""

from __future__ import annotations

from statistics import median

import networkx as nx


def build_undirected_graph(node_ids: list[int], edges: list[dict]) -> nx.Graph:
    graph = nx.Graph()
    graph.add_nodes_from(sorted(node_ids))
    for edge in edges:
        src, dst, weight = int(edge["src"]), int(edge["dst"]), float(edge["weight"])
        graph.add_edge(src, dst, weight=graph.get_edge_data(src, dst, {}).get("weight", 0.0) + weight)
    return graph


def _partition(graph: nx.Graph) -> dict[int, int]:
    try:
        import community as community_louvain

        raw = community_louvain.best_partition(graph, weight="weight", random_state=0)
        groups: dict[int, list[int]] = {}
        for node, label in raw.items():
            groups.setdefault(label, []).append(node)
        ordered = sorted((sorted(nodes) for nodes in groups.values()), key=lambda nodes: (nodes[0], len(nodes)))
    except (ImportError, ValueError, nx.NetworkXError):
        try:
            ordered = [sorted(group) for group in nx.algorithms.community.asyn_lpa_communities(graph, weight="weight", seed=0)]
            ordered.sort(key=lambda nodes: (nodes[0], len(nodes)))
        except (nx.NetworkXError, ZeroDivisionError):
            ordered = [sorted(group) for group in nx.connected_components(graph)]
            ordered.sort(key=lambda nodes: nodes[0])
    return {node: community_id for community_id, nodes in enumerate(ordered) for node in nodes}


def detect_communities(node_ids: list[int], edges: list[dict], seed_ids: set[int]) -> tuple[list[dict], list[dict]]:
    graph = build_undirected_graph(node_ids, edges)
    partition = _partition(graph)
    groups: dict[int, list[int]] = {}
    for node in sorted(node_ids):
        groups.setdefault(partition[node], []).append(node)
    median_size = float(median([len(nodes) for nodes in groups.values()])) if groups else 1.0
    global_density = len(seed_ids) / max(1, len(node_ids))
    stats = []
    score_by_community = {}
    for community_id in sorted(groups):
        nodes = groups[community_id]
        anchors = sum(node in seed_ids for node in nodes)
        density = anchors / len(nodes)
        smoothed = (anchors + global_density * median_size) / (len(nodes) + median_size)
        score_by_community[community_id] = smoothed
        stats.append({
            "community": community_id,
            "size": len(nodes),
            "seed_anchor_nodes": anchors,
            "seed_density": density,
            "community_hk_score": smoothed,
            "smoothing_size": median_size,
        })
    rows = [{"id": node, "community": partition[node], "community_hk_score": score_by_community[partition[node]]} for node in sorted(node_ids)]
    return rows, stats
