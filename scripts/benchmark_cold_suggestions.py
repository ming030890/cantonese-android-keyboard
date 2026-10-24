#!/usr/bin/env python3
"""Fresh process per request: no cross-request or typing-prefix cache warmup.

Native elapsed_ms excludes process/model loading, and includes the first actual
inference. This measures cold inference on the host, not Android key-to-frame time.
"""
import argparse
import hashlib
import json
import platform
import subprocess
from pathlib import Path
from benchmark_suggestion_latency import CASES, summary, without_timing


def measure(binary, assets, repeats):
    rows = []
    for _ in range(repeats):
        for code, left in CASES:
            for version in (3, 5):
                for preview in (True, False):
                    query = dict(code=code, left=left, version=version, preview=preview)
                    result = subprocess.run([str(binary.resolve()), str(assets.resolve())],
                        input=json.dumps(query, ensure_ascii=False) + "\n", text=True,
                        capture_output=True, check=True)
                    outputs = [json.loads(line) for line in result.stdout.splitlines()]
                    if len(outputs) != 1:
                        raise ValueError("Fresh process must return exactly one request")
                    rows.append(dict(query=query, result=outputs[0]))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--assets", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    before = measure(args.baseline, args.assets, args.repeats)
    print("Fresh-process baseline complete", flush=True)
    after = measure(args.candidate, args.assets, args.repeats)
    mismatches = [dict(query=a["query"], before=a["result"], after=b["result"])
                  for a, b in zip(before, after)
                  if without_timing(a["result"]) != without_timing(b["result"])]
    report = dict(host=platform.platform(), repeats=args.repeats,
        cold_definition="Fresh process/model inference state per request; load excluded",
        baseline_sha256=hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
        candidate_sha256=hashlib.sha256(args.candidate.read_bytes()).hexdigest(),
        model_sha256=hashlib.sha256((args.assets / "language_model/model.cng").read_bytes()).hexdigest(),
        before=summary(before), after=summary(after), mismatches=mismatches,
        baseline_rows=before, candidate_rows=after)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    for old, new in zip(report["before"], report["after"]):
        print(json.dumps(dict(code=old["code"], left=old["left"],
            preview_ms=[round(old["preview"]["median_ms"], 2), round(new["preview"]["median_ms"], 2)],
            full_ms=[round(old["full"]["median_ms"], 2), round(new["full"]["median_ms"], 2)]), ensure_ascii=False))
    print(f"Exact output mismatches: {len(mismatches)} / {len(after)}")
    if mismatches:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
