#!/usr/bin/env python3
"""Fit a small linear replacement rule; no fitting/selection on evaluation groups."""
import argparse
import collections
import csv
import json
import math
from pathlib import Path
from iterate_english_policy import rerank, split, hashed, metric, f05
from audit_candidate_pipeline import digest
from align_mobile_targets import reference_target

FEATURES = ["winner_score", "runner_margin"]


def evidence(typed, best, ranked):
    margin = best["score"] - ranked[1]["score"] if len(ranked) > 1 else 20.
    return [best['score'], min(margin, 20.)]


def sigmoid(z):
    z = max(-40., min(40., z))
    return 1./(1.+math.exp(-z))


def fit(rows, size, regularization):
    mean = [sum(x[i] for x, _, _ in rows)/len(rows) for i in range(size)]
    scale = [max(.1, (sum((x[i]-mean[i])**2 for x, _, _ in rows)/len(rows))**.5) for i in range(size)]
    normalized = [([1.] + [(x[i]-mean[i])/scale[i] for i in range(size)], y) for x, y, _ in rows]
    weights = [0.]*(size+1)
    for epoch in range(300):
        gradient = [0.]*(size+1)
        for x, y in normalized:
            error = sigmoid(sum(a*b for a, b in zip(x, weights))) - y
            for i in range(size+1):
                gradient[i] += error*x[i]
        rate = .25/(1+epoch/150)
        for i in range(size+1):
            weights[i] -= rate*(gradient[i]/len(rows) + (regularization*weights[i] if i else 0.))
    return dict(features=FEATURES[:size],
        means=mean, scales=scale, weights=weights, regularization=regularization)


def probability(model, x):
    values = [1.] + [(x[i]-model["means"][i])/model["scales"][i] for i in range(len(model["means"]))]
    return sigmoid(sum(a*b for a, b in zip(values, model["weights"])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--iteration", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--selection-only", action="store_true")
    args = parser.parse_args()
    case_file = args.iteration/"cases.jsonl"
    cache_file = args.iteration/"native-cache.json"
    cases = [json.loads(l) for l in case_file.open() if not json.loads(l)["category"].startswith("prefix_")]
    outputs = json.loads(cache_file.read_text())["outputs"][:len(cases)]
    references_path = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    references = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(references_path.open())}
    aligned = []
    alignment = collections.Counter()
    for c, o in zip(cases, outputs):
        if c['category'] == 'source_preservation':
            target = c['target']
        else:
            target = reference_target(c, references[c['group']])
        if target is None:
            alignment['excluded_ambiguous_or_unmatched_reference'] += 1
            continue
        alignment['retained'] += 1
        alignment['multiword_targets'] += ' ' in target
        aligned.append((dict(c, target=target), o))
    cases, outputs = map(list, zip(*aligned))
    rankings = [rerank(o["candidates"], 0.) for o in outputs]
    rows = []
    features = []
    for c, o, ranked in zip(cases, outputs, rankings):
        best = ranked[0] if ranked else None
        if o["known"] or len(c["typed"]) < 3 or not best or best["completedChars"] or len(best["word"]) < 3:
            features.append(None); continue
        x = evidence(c['typed'], best, ranked)
        y = int(best["word"].lower() == c["target"] and c["typed"].lower() != c["target"])
        features.append(x)
        if split(c["group"]) == "train" and int(hashed("calibration:"+c["group"])[:8], 16)%5 != 0:
            rows.append((x, y, c["group"]))
    trials = []
    # Training and cutoff calibration use separate prompt groups inside TRAIN.
    calibration = [i for i, c in enumerate(cases) if split(c["group"]) == "train" and int(hashed("calibration:"+c["group"])[:8], 16)%5 == 0]
    typo_count = sum(cases[i]["typed"].lower() != cases[i]["target"] for i in calibration)
    for reg in (.001, .01, .1):
        model = fit(rows, 2, reg)
        probabilities = [probability(model, x) if x is not None else -1. for x in features]
        ordered = sorted((i for i in calibration if features[i] is not None), key=lambda i: -probabilities[i])
        correct = wrong = 0
        cutoffs = []
        for pos, i in enumerate(ordered):
            c = cases[i]
            hit = rankings[i][0]["word"].lower() == c["target"] and c["typed"].lower() != c["target"]
            correct += hit
            wrong += not hit
            if pos+1 < len(ordered) and probabilities[ordered[pos+1]] == probabilities[i]:
                continue
            cutoffs.append((f05(correct, wrong, typo_count-correct), probabilities[i]))
        calibration_f05, threshold = max(cutoffs)
        model = dict(model, threshold=threshold, cutoff_metric="f0.5", calibration_f05=calibration_f05)
        decisions = [r[0]["word"] if r and p >= threshold else None for r, p in zip(rankings, probabilities)]
        selection = metric(cases, outputs, rankings, decisions, "selection")
        trials.append(dict(model=model, cutoff_found=True, selection=selection))
        print(f"L2 {reg}: selection F0.5={selection['f05']:.6f}", flush=True)
    champion = max(trials, key=lambda t: t["selection"]["f05"])
    report = dict(scope="Linear fit/cutoff/selection on separate prompt groups; source has historical development exposure",
        input_sha256={str(p): digest(p) for p in (case_file, cache_file, Path(__file__), references_path, Path(__file__).with_name('align_mobile_targets.py'))},
        alignment=dict(alignment),
        fit_rows=len(rows), fit_groups=len({r[2] for r in rows}), calibration_cases=len(calibration), trials=trials,
        champion=champion, qualified=False)
    if champion and not args.selection_only:
        model = champion["model"]
        probabilities = [probability(model, x) if x is not None else -1. for x in features]
        decisions = [r[0]["word"] if r and p >= model["threshold"] else None for r, p in zip(rankings, probabilities)]
        final = metric(cases, outputs, rankings, decisions, "evaluation")
        report["evaluation"] = final
        report["primary_metric"] = "f0.5"
    if not args.selection_only:
        baseline = [o['correction'] for o in outputs]
        report['baseline_evaluation'] = metric(cases, outputs, rankings, baseline, 'evaluation')
        report['qualified'] = report['evaluation']['f05'] > report['baseline_evaluation']['f05']
    args.output.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps({k: report[k] for k in report if k not in ("trials", "input_sha256")}, indent=2))


if __name__ == "__main__":
    main()
