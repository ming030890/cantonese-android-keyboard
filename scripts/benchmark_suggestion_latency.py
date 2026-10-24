#!/usr/bin/env python3
"""Measure native joined suggestions and demand exact baseline output parity.

Each stage uses an independent engine process, matching the JNI preview/full
state separation. Model loading is outside the per-request native timer.
This is a host benchmark, not Android key-to-frame latency.
"""
import argparse
import hashlib
import platform
import json
import statistics
import subprocess
from pathlib import Path

CASES = [
    ("problem", ""), ("problem", "I have a "),
    ("hion", ""), ("hion", "今日返工之後"),
    ("humomomo", ""), ("hionhionhionhion", ""),
    ("hion", "I went to the shops after work and then came home to see "),
    ("exmaole", "this is an "),
]


def measure(binary, assets, repeats):
    rows = []
    for preview in (True, False, "typing"):
        queries = [dict(code=code, left=left, version=version, preview=preview)
                   for _ in range(repeats) for code, left in CASES for version in (3, 5)]
        if preview == "typing":
            queries = [dict(code=word[:n], left=left, version=version, preview=True, trace=word)
                       for _ in range(repeats)
                       for word, left in [("problem", ""), ("humomomo", ""), ("hionhionhionhion", ""), ("problem", "I have a ")]
                       for version in (3, 5) for n in range(1, len(word) + 1)]
        result = subprocess.run([str(binary.resolve()), str(assets.resolve())],
                                input="".join(json.dumps(q, ensure_ascii=False) + "\n" for q in queries),
                                text=True, capture_output=True, check=True)
        outputs = [json.loads(line) for line in result.stdout.splitlines()]
        if len(outputs) != len(queries):
            raise ValueError("Probe returned an incomplete stream")
        rows.extend(dict(query=q, result=r) for q, r in zip(queries, outputs))
    return rows


def without_timing(row):
    return {k: v for k, v in row.items() if k not in ("elapsed_ms", "scoring")}


def summary(rows):
    output = []
    for code, left in CASES:
        item = dict(code=code, left=left)
        for preview in (True, False):
            values = sorted(row["result"]["elapsed_ms"] for row in rows
                            if "trace" not in row["query"] and row["query"]["code"] == code and row["query"]["left"] == left
                            and row["query"]["preview"] == preview)
            item["preview" if preview else "full"] = dict(
                median_ms=statistics.median(values),
                p95_ms=values[min(len(values) - 1, int(.95 * len(values)))], samples=len(values))
        output.append(item)
    return output


def typing_summary(rows):
    result = []
    for word, left in [("problem", ""), ("humomomo", ""), ("hionhionhionhion", ""), ("problem", "I have a ")]:
        values = sorted(row["result"]["elapsed_ms"] for row in rows
                        if row["query"].get("trace") == word and row["query"]["left"] == left)
        result.append(dict(code=word, left=left, median_ms=statistics.median(values),
                           p95_ms=values[min(len(values)-1, int(.95*len(values)))], samples=len(values)))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--assets", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    before = measure(args.baseline, args.assets, args.repeats)
    print("Baseline timing complete", flush=True)
    after = measure(args.candidate, args.assets, args.repeats)
    mismatches = [dict(query=a["query"], before=a["result"], after=b["result"])
                  for a, b in zip(before, after)
                  if without_timing(a["result"]) != without_timing(b["result"])]
    report = dict(host=platform.platform(), repeats=args.repeats,
                  baseline_sha256=hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
                  candidate_sha256=hashlib.sha256(args.candidate.read_bytes()).hexdigest(),
                  model_sha256=hashlib.sha256((args.assets / "language_model/model.cng").read_bytes()).hexdigest(),
                  before=summary(before), after=summary(after),
                  typing_before=typing_summary(before), typing_after=typing_summary(after), mismatches=mismatches,
                  baseline_rows=before, candidate_rows=after)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    for old, new in zip(report["before"], report["after"]):
        print(json.dumps(dict(code=old["code"], left=old["left"],
            preview_ms=[round(old["preview"]["median_ms"], 2), round(new["preview"]["median_ms"], 2)],
            full_ms=[round(old["full"]["median_ms"], 2), round(new["full"]["median_ms"], 2)]), ensure_ascii=False))
    for old, new in zip(report["typing_before"], report["typing_after"]):
        print(json.dumps(dict(typing=old["code"], left=old["left"],
              median_ms=[round(old["median_ms"], 2), round(new["median_ms"], 2)],
              p95_ms=[round(old["p95_ms"], 2), round(new["p95_ms"], 2)]), ensure_ascii=False))
    print(f"Exact candidate/score mismatches: {len(mismatches)} / {len(after)}")
    if mismatches:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
