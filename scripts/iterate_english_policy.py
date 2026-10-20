#!/usr/bin/env python3
"""Fit small English ranking/acceptance changes with prospectively grouped splits.

All source data is development data with possible historical exposure. Evaluation
groups are excluded from this run's fitting/selection; no fresh-test claim.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
from audit_candidate_pipeline import mobile_cases, probe, digest, choose_current
from audit_prediction_context import decode_context

SEED = "english-iteration-20261020-v1"


def hashed(text):
    return hashlib.sha256((SEED + str(text)).encode()).hexdigest()


def split(group):
    value = int(hashed(group)[:8], 16) % 10
    return "train" if value < 6 else "selection" if value < 8 else "evaluation"


def prefixes(source, per_language):
    buckets = collections.defaultdict(dict)
    for line in source.open():
        r = json.loads(line)
        if r["task"] != "typed_prefix_completion" or not r["prefix"].isascii() or not r["prefix"].isalpha():
            continue
        group = "_".join(r["id"].split("_")[:3])
        row = dict(id=r["id"], group=group, left=decode_context(r["context_tokens"]),
            typed=r["prefix"], target=r["target_visible_word"].lower(), category="prefix_" + r["language"])
        prior = buckets[r["language"]].get(group)
        if prior is None or hashed(row["id"]) < hashed(prior["id"]):
            buckets[r["language"]][group] = row
    return [r for group in buckets.values() for r in sorted(group.values(), key=lambda r: hashed(r["id"]))[:per_language]]


def rerank(rows, completion_cost):
    return sorted((dict(r, score=r["score"] + (.5-completion_cost)*r["completedChars"]) for r in rows),
        key=lambda r: (-r["score"], r["word"]))


def f05(correct, wrong, missed):
    denominator = 1.25*correct + wrong + .25*missed
    return 1.25*correct/denominator if denominator else 0.


def metric(cases, outputs, rankings, corrections, partition, prefix=False):
    s = collections.Counter()
    for c, out, ranked, fix in zip(cases, outputs, rankings, corrections):
        if split(c["group"]) != partition:
            continue
        s["cases"] += 1
        words = [r["word"].lower() for r in ranked[:8]]
        rank = words.index(c["target"]) + 1 if c["target"] in words else math.inf
        s["top1"] += rank == 1
        s["top8"] += rank <= 8
        s["mrr"] += 1/rank
        if prefix:
            continue
        typo = c["typed"].lower() != c["target"]
        s["typos" if typo else "preservation"] += 1
        if fix and fix.lower() != c["typed"].lower():
            s["applied"] += 1
            s["correct" if typo and fix.lower() == c["target"] else "wrong"] += 1
            s["false_changes"] += int(not typo)
    return dict(s, f05=f05(s["correct"], s["wrong"], s["typos"]-s["correct"]), precision=s["correct"]/s["applied"] if s["applied"] else 0.,
        recall=s["correct"]/s["typos"] if s["typos"] else 0.,
        false_change_rate=s["false_changes"]/s["preservation"] if s["preservation"] else 0.,
        top8_rate=s["top8"]/s["cases"] if s["cases"] else 0.,
        mrr_rate=s["mrr"]/s["cases"] if s["cases"] else 0.)


def gate_features(cases, outputs, rankings):
    features = []
    for c, out, ranked in zip(cases, outputs, rankings):
        if out["known"] or not ranked or len(c["typed"]) < 3:
            features.append(None); continue
        b = ranked[0]
        # Completion is offered explicitly; automatic expansion remains disabled.
        # Aalto's current extraction cannot qualify multiword split labels.
        if b["completedChars"] or len(b["word"]) < 3 or " " in b["word"]:
            features.append(None); continue
        margin = b["score"] - ranked[1]["score"] if len(ranked) > 1 else math.inf
        features.append((b["word"], b["score"], margin, b["edits"]))
    return features


def decisions(features, floor, margin, max_edits):
    return [f[0] if f and f[1] >= floor and f[2] >= margin and f[3] <= max_edits else None for f in features]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--mobile-count", type=int, default=6000)
    parser.add_argument("--prefix-per-language", type=int, default=750)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    project = Path(__file__).resolve().parents[1]
    ssd = Path("/Volumes/SSD/cantonese_ngram")
    model = project/"app/src/main/assets/language_model/model.cng"
    source = ssd/"data/shared-multilingual-v1-stage/eval/finalist_suite.jsonl"
    cases, intake = mobile_cases(ssd/"raw/aalto-ite-v88", args.mobile_count)
    prefix_cases = prefixes(source, args.prefix_per_language)
    all_cases = cases + prefix_cases
    requests = [dict(left=c["left"], typed=c["typed"], spelling_limit=4096, timing=True) for c in all_cases]
    binding = dict(model=digest(model), probe=digest(args.probe),
        requests=hashlib.sha256(json.dumps(requests, sort_keys=True).encode()).hexdigest())
    cache = args.out/"native-cache.json"
    if cache.exists() and json.loads(cache.read_text())["binding"] == binding:
        all_outputs = json.loads(cache.read_text())["outputs"]
        print("Reused content-bound native predictions", flush=True)
    else:
        print(f"Scoring {len(cases)} mobile/control and {len(prefix_cases)} prefix cases", flush=True)
        all_outputs = probe(args.probe, model, requests)
        cache.write_text(json.dumps(dict(binding=binding, outputs=all_outputs)))
    outputs, prefix_outputs = all_outputs[:len(cases)], all_outputs[len(cases):]
    partitions = ("train", "selection", "evaluation")
    # Verify native decision parity before comparing changes.
    for c, o in zip(cases, outputs):
        assert choose_current(c["typed"].lower(), c["left"], o["candidates"], o["known"]) == o["correction"], c["id"]
    baseline_rank = [o["candidates"] for o in outputs]
    baseline_fix = [o["correction"] for o in outputs]
    baseline = {p: metric(cases, outputs, baseline_rank, baseline_fix, p) for p in partitions}
    ranking_trials = []
    # Fixed, small family; selection never sees evaluation labels.
    for cost in (0., .1, .25, .5, 1.):
        ranked = [rerank(o["candidates"], cost) for o in outputs]
        fixes = [choose_current(c["typed"].lower(), c["left"], r, o["known"]) for c, r, o in zip(cases, ranked, outputs)]
        pref_rank = [rerank(o["candidates"], cost) for o in prefix_outputs]
        # No evaluation metrics are computed until the cost is selected.
        trial = dict(completion_cost=cost,
            mobile={p: metric(cases, outputs, ranked, fixes, p) for p in ("train", "selection")},
            prefix={p: metric(prefix_cases, prefix_outputs, pref_rank, [None]*len(pref_rank), p, True) for p in ("train", "selection")})
        ranking_trials.append(trial)
    base_trial = next(t for t in ranking_trials if t["completion_cost"] == .5)
    eligible = [t for t in ranking_trials if t['mobile']['selection']['f05'] >= baseline['selection']['f05']]
    champion = max(eligible, key=lambda t: (t['mobile']['selection']['f05'], t['prefix']['selection']['top8_rate'])) if eligible else base_trial
    cost = champion["completion_cost"]
    rankings = [rerank(o["candidates"], cost) for o in outputs]
    fixes = [choose_current(c["typed"].lower(), c["left"], r, o["known"]) for c, r, o in zip(cases, rankings, outputs)]
    features = gate_features(cases, outputs, rankings)
    candidates = []
    for floor in (-6., -8., -10., -12., -16., -20.):
        for margin in (0.5, 1., 1.5, 2., 3., 4., 5., 6., 8.):
            for edits in (1, 2, 3):
                decisions_ = decisions(features, floor, margin, edits)
                train = metric(cases, outputs, rankings, decisions_, "train")
                selection = metric(cases, outputs, rankings, decisions_, "selection")
                candidates.append(dict(floor=floor, margin=margin, max_edits=edits, train=train, selection=selection))
    eligible_gates = [t for t in candidates if t['selection']['f05'] > baseline['selection']['f05']]
    gate = max(eligible_gates, key=lambda t: t['selection']['f05']) if eligible_gates else None
    # Finalists are frozen before evaluation metrics are revealed.
    pref_rank = [rerank(o["candidates"], cost) for o in prefix_outputs]
    report = dict(seed=SEED, scope="Prompt/document-disjoint for this iteration; historical development exposure possible",
        binding=binding, intake=intake, case_counts=dict(collections.Counter(split(c["group"]) for c in cases)),
        ranking_trials=ranking_trials, selected_completion_cost=cost, baseline_mobile=baseline,
        candidate_mobile={p: metric(cases, outputs, rankings, fixes, p) for p in partitions},
        baseline_prefix={p: metric(prefix_cases, prefix_outputs, [o["candidates"] for o in prefix_outputs], [None]*len(prefix_cases), p, True) for p in partitions},
        candidate_prefix={p: metric(prefix_cases, prefix_outputs, pref_rank, [None]*len(pref_rank), p, True) for p in partitions},
        selected_gate=gate, gates_passing_train=len(candidates), gates_passing_selection=len(eligible_gates))
    if gate:
        final = decisions(features, gate["floor"], gate["margin"], gate["max_edits"])
        result = metric(cases, outputs, rankings, final, "evaluation")
        report["gate_evaluation"] = result
        report["gate_qualified"] = result["f05"] > baseline["evaluation"]["f05"]
    else:
        report["gate_qualified"] = False
    (args.out/"report.json").write_text(json.dumps(report, indent=2)+"\n")
    (args.out/"cases.jsonl").write_text("".join(json.dumps(dict(c, partition=split(c["group"])), ensure_ascii=False)+"\n" for c in all_cases))
    print(json.dumps({k: report[k] for k in ("selected_completion_cost", "baseline_mobile", "candidate_mobile", "baseline_prefix", "candidate_prefix", "selected_gate", "gate_qualified")}, indent=2))


if __name__ == "__main__":
    main()
