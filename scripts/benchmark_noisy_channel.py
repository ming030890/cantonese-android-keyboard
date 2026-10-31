#!/usr/bin/env python3
"""Paired native Mac latency; fixed input sampling, alternating process order."""
import argparse,hashlib,json,statistics,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]

def main():
 r=ROOT/'build/english-noisy-channel-20261030';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=r/'latency');parser.add_argument('--probe',type=Path,default=Path('/private/tmp/keyboard-channel-production/release/ngram_probe'));parser.add_argument('--baseline-probe',type=Path,default=Path('/private/tmp/keyboard-release74-saved/ngram_probe'));args=parser.parse_args();out=args.output;out.mkdir(exist_ok=False)
 cases=core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl');cases=sorted(cases,key=lambda c:hashlib.sha256(('channel-latency:'+c['id']).encode()).digest())[:1024];core.jsonl(out/'cases.jsonl',cases)
 binaries={'baseline':args.baseline_probe,'candidate':args.probe};model=ROOT/'app/src/main/assets/language_model/model.cng';timings={k:[] for k in binaries};runs=[]
 for repeat in range(3):
  for name in (('baseline','candidate') if repeat%2==0 else ('candidate','baseline')):
   p=subprocess.run([str(binaries[name]),str(model)],input=''.join(json.dumps(dict(left=c['left'],typed=c['typed'],spelling_limit=8,timing=True))+'\n' for c in cases),text=True,capture_output=True,check=True,timeout=300);outputs=[json.loads(l) for l in p.stdout.splitlines()];values=[v['elapsedMicros']/1000 for v in outputs];timings[name]+=values;runs.append(dict(name=name,repeat=repeat,median_ms=statistics.median(values),p95_ms=core.percentile(values,.95)));print(runs[-1],flush=True)
 report=dict(scope='Mac mini M4 native calls; includes generation/ranking and per-process cold proposal cache, excludes model load and UI. Not Android device latency.',sample_cases=1024,repeats=3,sample_sha256=core.digest(out/'cases.jsonl'),probe_sha256={k:core.digest(p) for k,p in binaries.items()},runs=runs,summary={k:dict(median_ms=statistics.median(v),p95_ms=core.percentile(v,.95),max_ms=max(v)) for k,v in timings.items()});core.write_json(out/'report.json',report)
if __name__=='__main__':main()
