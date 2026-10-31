#!/usr/bin/env python3
"""Freeze TRAIN/calibration candidate pools for joint correction/KEEP training."""
import collections,json,os,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
from build_independent_autocorrect_eval import stable
ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'build/english-listwise-20261030';out.mkdir(exist_ok=False)
src=ROOT/'build/short-nn-v2-20261029/input-clean-data';rows=core.rows(src/'cases.jsonl');blocked=set()
for suite in (ROOT/'build/autocorrect-eval-v4/frozen',ROOT/'build/independent-autocorrect-eval-20261030/frozen-v2',ROOT/'build/bilingual-residual-20261030/fresh-eval',ROOT/'build/english-char-gate-20261030/fresh-eval'):
 for file in ([suite/'cases.jsonl'] if (suite/'cases.jsonl').exists() else [suite/'development.jsonl',suite/'confirmation.jsonl']):
  for c in core.rows(file):blocked.add((c['left'],c['typed'].casefold()))
buckets=collections.defaultdict(list)
for c in rows:
 if (c['left'],c['typed'].casefold()) in blocked or c.get('noise'):continue
 buckets[(c['fit_split'],c['source'])].append(c)
chosen=[]
for (split,source),rs in sorted(buckets.items()):
 limit=5000 if source=='real' else 2000 if source=='authentic_short_typo' else 512
 chosen.extend(sorted(rs,key=lambda c:stable(c['id']))[:limit])
core.jsonl(out/'cases.jsonl',chosen)
model=ROOT/'app/src/main/assets/language_model/model.cng';probe=Path('/private/tmp/keyboard-release74-saved/ngram_probe')
core.write_json(out/'protocol.json',dict(schema='english-listwise-candidates-v1',case_sha256=core.digest(out/'cases.jsonl'),probe_sha256=core.digest(probe),model_sha256=core.digest(model),counts={str(k):min(len(v),5000 if k[1]=='real' else 2000 if k[1]=='authentic_short_typo' else 512) for k,v in buckets.items()},candidate_limit=128,input_overlap=0))
for name,cases in [('train',chosen),('objective',core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl')),('development',core.rows(ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'))]:
 print(name,len(cases),flush=True)
 request=''.join(json.dumps({'left':c['left'],'typed':c['typed'],'spelling_limit':128},ensure_ascii=False)+'\n' for c in cases)
 p=subprocess.run([str(probe),str(model)],input=request,text=True,capture_output=True,check=True,timeout=600)
 (out/(name+'-outputs.jsonl')).write_text(p.stdout)
 print(name,'completed',len(p.stdout),flush=True)
