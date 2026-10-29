#!/usr/bin/env python3
"""Supplemental user-reported preservation cases; never training examples.

The user intends the Tuesday abbreviation in every case. Contexts are diagnostic
examples from the investigation, not a recording of the user's original text.
Does not modify the frozen autocorrect objective or its weights.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PROJECT = Path(__file__).resolve().parents[1]
CASES = [dict(left=left, typed='tue', target='tue')
         for left in ['', 'on ', 'see you on ', 'next ', 'today is ']]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def evaluate(probe, model):
    requests = ''.join(json.dumps(dict(left=c['left'], typed=c['typed'])) + '\n'
                       for c in CASES)
    proc = subprocess.run([str(probe.resolve()), str(model.resolve())],
                          input=requests, text=True, capture_output=True,
                          check=True, timeout=60)
    outputs = [json.loads(line) for line in proc.stdout.splitlines()]
    if len(outputs) != len(CASES):
        raise ValueError('Native output count mismatch')
    rows = [dict(**case, correction=out['correction'],
                 passed=(out['correction'] or case['typed']).casefold() == case['target'],
                 output=out) for case, out in zip(CASES, outputs, strict=True)]
    return dict(probe_sha256=digest(probe), passed=sum(r['passed'] for r in rows),
                total=len(rows), cases=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--baseline-probe', type=Path)
    parser.add_argument('--model', type=Path,
                        default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = dict(scope=__doc__, model_sha256=digest(args.model),
                  candidate=evaluate(args.probe, args.model))
    if args.baseline_probe:
        report['baseline'] = evaluate(args.baseline_probe, args.model)
    with args.output.open('x') as file:
        json.dump(report, file, indent=2)
        file.write('\n')
    print(json.dumps({name: f"{report[name]['passed']}/{report[name]['total']}"
                      for name in ['baseline', 'candidate'] if name in report}))


if __name__ == '__main__':
    main()
