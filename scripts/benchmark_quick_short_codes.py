#!/usr/bin/env python3
"""Compare release ngram_probe binaries using the packaged Quick/LM assets.

Reports engine timings only, not Android input-to-display latency. Every result
list is compared in full, including both Quick versions and contextual ranking.
"""
import argparse
import json
import statistics
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--assets", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/main/assets")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=2)
    args = parser.parse_args()
    assets = args.assets
    paths = [assets / p for p in ("language_model/model.cng", "handwriting/lm_csr.bin",
        "handwriting/labels.txt", "quick/quick3.bin", "quick/quick5.bin")]
    if not paths[1].exists():
        paths[1] = "-"
    contexts = [("", ""), ("I use ", ""), ("我想", ""), ("你", "好")]
    codes = ["d", "df", "of", "hi", "ab", "zz", "DF"]
    requests = [dict(code=code, left=left, right=right, version=version, timing=True)
        for version in (3, 5) for left, right in contexts for code in codes]
    timings = {"before": [], "after": [], "preview": []}
    df = {"before": [], "after": [], "preview": []}
    comparisons = 0
    for run in range(args.rounds):
        outputs = {}
        # Alternate process order to reduce warm filesystem/CPU bias.
        for name in (("before", "after", "preview") if run % 2 == 0 else ("preview", "after", "before")):
            binary = args.after if name == "preview" else getattr(args, name)
            proc = subprocess.run([str(binary), *map(str, paths)],
                input="".join(json.dumps(dict(q, preview=name == "preview")) + "\n" for q in requests),
                text=True, capture_output=True, check=True, timeout=180)
            outputs[name] = [json.loads(line) for line in proc.stdout.splitlines()]
            assert len(outputs[name]) == len(requests)
            print(f"round {run + 1}: {name} complete", flush=True)
            for request, result in zip(requests, outputs[name]):
                timings[name].append(result["elapsedMicros"])
                if request["code"].lower() == "df":
                    df[name].append(result["elapsedMicros"])
        for request, before, after, preview in zip(requests, outputs["before"], outputs["after"], outputs["preview"]):
            assert before["candidates"] == after["candidates"], request
            assert sorted(before["candidates"]) == sorted(preview["candidates"]), request
            comparisons += 1

    def summary(values):
        return dict(median_us=statistics.median(values), p95_us=sorted(values)[int(len(values) * .95)])

    result = dict(method=f"Release host engine calls, {args.rounds} alternating process runs; not Android UI latency",
        candidate_lists_identical=comparisons,
        preview_candidate_sets_identical=comparisons,
        all_short_codes={name: summary(v) for name, v in timings.items()},
        df={name: summary(v) for name, v in df.items()})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
