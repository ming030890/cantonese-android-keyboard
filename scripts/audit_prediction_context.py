#!/usr/bin/env python3
"""Shared next-word and English-prefix context ablation on SSD development data."""
import argparse
import collections
import json
import re
from pathlib import Path
from audit_candidate_pipeline import digest, key, probe


def decode_context(tokens):
    parts, raw = [], bytearray()
    for token in tokens:
        if re.fullmatch(r"\[byte:[0-9a-fA-F]{2}\]", token):
            raw.append(int(token[6:8], 16))
        else:
            if raw:
                parts.append(raw.decode("utf-8", errors="strict")); raw.clear()
            parts.append(token)
    if raw:
        parts.append(raw.decode("utf-8", errors="strict"))
    return " ".join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--per-panel", type=int, default=150)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    source = Path("/Volumes/SSD/cantonese_ngram/data/shared-multilingual-v1-stage/eval/finalist_suite.jsonl")
    model = project/"app/src/main/assets/language_model/model.cng"
    buckets = collections.defaultdict(dict)
    for line in source.open():
        row = json.loads(line)
        task, language = row["task"], row["language"]
        if task == "contextual_next_word":
            panel = "shared_next_" + language
        elif task == "typed_prefix_completion" and re.fullmatch(r"[A-Za-z']+", row["prefix"]):
            panel = "english_prefix_" + language
        else:
            continue
        # IDs encode partition, language and source-document hash. Cap one case
        # per document within each panel; this is development, never a test claim.
        group = "_".join(row["id"].split("_")[:3])
        prior = buckets[panel].get(group)
        if prior is None or key(row["id"]) < key(prior["id"]):
            buckets[panel][group] = row
    rows = []
    for panel, groups in sorted(buckets.items()):
        for r in sorted(groups.values(), key=lambda r: key(r["id"]))[:args.per_panel]:
            rows.append(dict(id=r["id"], panel=panel, target=r["target_visible_word"],
                prefix=r["prefix"], left=decode_context(r["context_tokens"])))
    requests = []
    for context in (True, False):
        for r in rows:
            req = dict(left=r["left"] if context else "", timing=True)
            if r["panel"].startswith("english_prefix"):
                req["typed"] = r["prefix"]
            else:
                req["limit"] = 8
            requests.append(req)
    hashes = {str(p): digest(p) for p in [source, model, args.probe, Path(__file__)]}
    outputs = probe(args.probe, model, requests)
    stats = collections.defaultdict(collections.Counter)
    for i, out in enumerate(outputs):
        r = rows[i % len(rows)]
        mode = "context" if i < len(rows) else "empty_context"
        candidates = ([c["word"] for c in out["candidates"]] if "candidates" in out
            else [c["token"] for c in out["predictions"]])
        # Matching visible output case-insensitively follows the English adapter;
        # Chinese targets are compared exactly by the same transformation.
        candidates = [s.lower() for s in candidates]
        rank = candidates.index(r["target"].lower()) + 1 if r["target"].lower() in candidates else float("inf")
        s = stats[(r["panel"], mode)]
        s["cases"] += 1
        for n in (1, 3, 8):
            s[f"top{n}"] += rank <= n
        s["mrr"] += 1/rank
    report = dict(scope="Development only; canonical context tokens reconstructed with whitespace; not an independent held-out test",
        input_sha256=hashes, metrics={})
    for (panel, mode), s in stats.items():
        report["metrics"].setdefault(panel, {})[mode] = dict(s,
            **{f"{k}_rate": s[k]/s["cases"] for k in ("top1", "top3", "top8", "mrr")})
    if any(digest(p) != value for p, value in hashes.items()):
        raise ValueError("Inputs changed during evaluation")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps(report["metrics"], indent=2))


if __name__ == "__main__":
    main()
