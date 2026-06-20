"""Deterministic title mention graph construction."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from typing import Iterable, Iterator

import ahocorasick

from .text_cleaning import clean_text_for_matching, clean_title
from .title_filter import is_candidate_title


@dataclass(frozen=True)
class Article:
    title: str
    text: str


def prepare_articles(rows: Iterable[dict], min_title_len: int, max_title_len: int) -> list[Article]:
    by_title: dict[str, str] = {}
    for row in rows:
        raw_title = str(row.get("title") or "")
        title = clean_title(raw_title)
        if not (min_title_len <= len(title) <= max_title_len) or not is_candidate_title(raw_title):
            continue
        # Exact cleaned-title duplicates merge deterministically, retaining the
        # lexicographically smallest text rather than input-order state.
        text = str(row.get("text") or "")
        previous = by_title.get(title)
        if previous is None or text < previous:
            by_title[title] = text
    return [Article(title, by_title[title]) for title in sorted(by_title)]


def make_automaton(titles: Iterable[str]) -> ahocorasick.Automaton:
    automaton = ahocorasick.Automaton()
    for title in sorted(titles):
        automaton.add_word(title, title)
    automaton.make_automaton()
    return automaton


def longest_non_overlapping_matches(automaton: ahocorasick.Automaton, text: str) -> list[tuple[int, int, str]]:
    matches = []
    for end, title in automaton.iter(text):
        start = end - len(title) + 1
        matches.append((start, end + 1, title))
    matches.sort(key=lambda item: (item[0], -(item[1] - item[0]), item[2]))
    selected: list[tuple[int, int, str]] = []
    cursor = 0
    for match in matches:
        if match[0] >= cursor:
            selected.append(match)
            cursor = match[1]
    return selected


def split_regions(text: str) -> tuple[str, str, str]:
    cleaned = clean_text_for_matching(text)
    paragraph_end = cleaned.find(" ")
    intro_end = min(len(cleaned), max(500, paragraph_end if paragraph_end >= 0 else 500))
    return cleaned[:intro_end], cleaned[intro_end:intro_end + 2000], cleaned[intro_end + 2000:]


def build_edges(articles: list[Article]) -> tuple[list[dict], list[dict], list[dict]]:
    title_to_id = {article.title: node_id for node_id, article in enumerate(articles)}
    automaton = make_automaton(title_to_id)
    edges: dict[tuple[int, int], dict[str, float | int]] = {}
    incoming = defaultdict(int)
    article_rows = []
    for src, article in enumerate(articles):
        intro, early, later = split_regions(article.text)
        article_rows.append({"id": src, "title": article.title, "intro": intro, "early_body": early, "later_body": later})
        offset = 0
        for region, position_weight in ((intro, 3.0), (early, 2.0), (later, 1.0)):
            for start, _end, title in longest_non_overlapping_matches(automaton, region):
                dst = title_to_id[title]
                if src == dst:
                    continue
                key = (src, dst)
                edge = edges.setdefault(key, {"src": src, "dst": dst, "weight": 0.0, "mention_count": 0, "first_position": offset + start})
                edge["weight"] = float(edge["weight"]) + position_weight
                edge["mention_count"] = int(edge["mention_count"]) + 1
                edge["first_position"] = min(int(edge["first_position"]), offset + start)
                incoming[dst] += 1
            offset += len(region)
    edge_rows = [edges[key] for key in sorted(edges)]
    stats = [{"id": node_id, "title": article.title, "incoming_mentions": incoming[node_id]} for node_id, article in enumerate(articles)]
    return edge_rows, stats, article_rows
