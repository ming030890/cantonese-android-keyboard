#!/usr/bin/env python3
"""Compare the joined native request with the previous pipeline on frozen inputs.

Host timings include native collection/ranking, not Android scheduling/rendering.
No fitting, threshold changes, or protected confirmation/test data are used.
"""
import argparse
import csv
import hashlib
import json
import statistics
import subprocess
from pathlib import Path

from align_mobile_targets import reference_target
from iterate_english_policy import metric, split


def probe(binary, assets, queries):
    result = subprocess.run([str(binary), str(assets)], input="".join(json.dumps(q) + "\n" for q in queries),
                            text=True, capture_output=True, check=True)
    rows = [json.loads(line) for line in result.stdout.splitlines()]
    assert len(rows) == len(queries)
    return rows


def latency(values):
    values = sorted(values)
    return {"samples": len(values), "median_ms": statistics.median(values),
            "p95_ms": values[min(len(values) - 1, int(.95 * len(values)))]}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--iteration", type=Path, default=Path("build/english-iteration-2026-10-20"))
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    assets = project / "app/src/main/assets"
    references = {r["SENTENCE_ID"]: r["SENTENCE"] for r in csv.DictReader(
        open("/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv"))}
    cases = []
    for line in (args.iteration / "cases.jsonl").open():
        case = json.loads(line)
        if case["category"].startswith("prefix_") or split(case["group"]) != "evaluation":
            continue
        target = case["target"] if case["category"] == "source_preservation" else reference_target(case, references[case["group"]])
        if target is not None:
            cases.append(dict(case, target=target))
    assert len(cases) == 2042, f"Frozen evaluation cohort changed: {len(cases)}"
    print(f"Checking correction and candidate parity on {len(cases)} frozen cases", flush=True)
    queries = [dict(version=0, code=c["typed"], left=c["left"], legacy=legacy)
               for c in cases for legacy in (True, False)]
    rows = probe(args.probe, assets, queries)
    before, after = rows[::2], rows[1::2]
    mismatches = [c["id"] for c, a, b in zip(cases, before, after) if a["english"] != b["english"]]
    predictions = [r["english"] for r in after]
    quality = metric(cases, predictions, [r["candidates"] for r in predictions],
                     [r["correction"] for r in predictions], "evaluation")
    print(f"Frozen parity mismatches: {len(mismatches)}; F0.5: {quality['f05']:.5f}", flush=True)
    contexts = [("", ""), ("I use ", ""), ("我想", ""), ("你", "好")]
    short_codes = ["d", "df", "of", "hi", "ab", "zz", "DF"]
    phrase_codes = ["humo", "continue", "keyboard", "ofhi", "hion", "gwme"]
    queries = []
    for repetition in range(2):
        for version in (3, 5):
            for code in short_codes + phrase_codes:
                for left, right in contexts:
                    for legacy in ((True, False) if repetition == 0 else (False, True)):
                        queries.append(dict(version=version, code=code, left=left, right=right, legacy=legacy))
    print(f"Checking {len(queries)//2} paired bilingual requests", flush=True)
    rows = probe(args.probe, assets, queries)
    panels = {name: {"before": [], "after": [], "coverage_changes": []} for name in ("short", "phrase")}
    one_distribution = []
    for i in range(0, len(rows), 2):
        q = queries[i]
        a, b = (rows[i], rows[i + 1]) if q["legacy"] else (rows[i + 1], rows[i])
        name = "short" if len(q["code"]) <= 2 else "phrase"
        panel = panels[name]
        panel["before"].append(a["elapsed_ms"])
        panel["after"].append(b["elapsed_ms"])
        lost = set(a["chinese"]) - set(b["chinese"])
        gained = set(b["chinese"]) - set(a["chinese"])
        if lost or gained:
            panel["coverage_changes"].append(dict(code=q["code"], version=q["version"], left=q["left"], right=q["right"],
                lost=sorted(lost), gained=sorted(gained), before_top8=a["display"][:8], after_top8=b["display"][:8]))
        if q["code"].lower() == "df":
            one_distribution.append(b["scoring"]["forwards"])
    assert not panels["short"]["coverage_changes"], "Short-code candidate coverage regressed"
    assert set(one_distribution) == {1}, f"df repeats inference: {one_distribution}"
    report = dict(scope="Host native pipeline only; phone input-to-display latency unmeasured",
        model_sha256=sha(assets / "language_model/model.cng"), probe_sha256=sha(args.probe),
        frozen_evaluation=dict(cases=len(cases), parity_mismatches=mismatches, quality=quality,
            before_latency=latency([r["elapsed_ms"] for r in before]), after_latency=latency([r["elapsed_ms"] for r in after])),
        short=dict(before_latency=latency(panels["short"]["before"]), after_latency=latency(panels["short"]["after"]),
                   candidate_sets_identical=True, df_forward_calls=1),
        phrase=dict(before_latency=latency(panels["phrase"]["before"]), after_latency=latency(panels["phrase"]["after"]),
                    coverage_changes=panels["phrase"]["coverage_changes"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "phrase"}, indent=2), flush=True)
    print("Phrase timing:", latency(panels["phrase"]["before"]), "->", latency(panels["phrase"]["after"]), flush=True)
    assert not mismatches, "Joined scoring changed English policy decisions/candidates"


if __name__ == "__main__":
    main()
