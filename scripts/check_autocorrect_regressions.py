#!/usr/bin/env python3
"""Cheap existing-behavior preflight, to run before exposing confirmation."""
import argparse
import json
import os
from pathlib import Path
import subprocess

import autocorrect_eval_v2 as core

ROOT=Path(__file__).resolve().parents[1]
CASES=ROOT/'config/product_eval_v1/native_regressions.jsonl'


def evaluate(cases,outputs):
    if len(cases)!=len(outputs):raise ValueError('Regression membership changed')
    changes=[]
    for c,o in zip(cases,outputs,strict=True):
        actual=o.get('correction') or c['typed']
        if actual.casefold()!=c['expected'].casefold():
            changes.append(dict(id=c['id'],typed=c['typed'],expected=c['expected'],actual=actual))
    return dict(passed=not changes,cases=len(cases),failures=changes)


def check(binary,model,env=None):
    cases=core.rows(CASES)
    proc=subprocess.run([str(binary),str(model)],input=core.native_requests(cases),text=True,
        capture_output=True,check=True,env=env,timeout=60)
    report=evaluate(cases,[json.loads(line) for line in proc.stdout.splitlines()])
    report.update(cases_sha256=core.digest(CASES),probe_sha256=core.digest(binary))
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--model',type=Path,default=ROOT/'app/src/main/assets/language_model/model.cng')
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    report=check(args.probe.resolve(),args.model.resolve())
    args.output.parent.mkdir(parents=True,exist_ok=True);core.write_json(args.output,report)
    print(json.dumps(report,indent=2))
    if not report['passed']:raise SystemExit(1)


if __name__=='__main__':main()
