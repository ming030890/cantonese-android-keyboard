#!/usr/bin/env python3
"""Test a symmetric KEEP veto, with zero TRAIN-calibration lost fixes."""
import json
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import metrics
ROOT=Path(__file__).resolve().parents[1]

def main():
 r=ROOT/'build/english-noisy-channel-20261030';d=np.load(r/'data/features.npz');x={k:d[k] for k in d.files};cs=core.rows(r/'data/cases.jsonl');before=core.rows(r/'study/replace-calrank0-outputs.jsonl');sl=json.load(open(r/'data/slices.json'));p=json.load(open(r/'study/replace-calrank0.json'))
 scores=x['log_frequency']+p['beta']*x['gain']-p['tau']*x['cost'];scores[x['ids']<0]=-1e6;mx=scores.max(1);other=mx+np.log(np.maximum(np.exp(scores-mx[:,None]).sum(1),1e-30));keep=np.log(np.where(x['literal_frequency']>=0,x['literal_frequency'],p['unknown_floor']))+p['beta']*x['literal_gain'];margin=keep-other
 correct=np.array([bool(o.get('correction')) and core.norm(o['correction'])==core.norm(c.get('target') or '') and core.norm(c['typed'])!=core.norm(c.get('target') or '') for c,o in zip(cs,before)])
 a,b=sl['calibration'];cut=max(0.,float(margin[a:b][correct[a:b]&x['routed'][a:b]].max())+1e-5)
 after=[dict(correction=None if route and m>=cut else o.get('correction')) for route,m,o in zip(x['routed'],margin,before)];report={'cutoff':cut,'calibration':'Lowest positive KEEP log-odds cutoff losing zero correct fixes on TRAIN calibration','metrics':{}}
 for name,(a,b) in sl.items():
  if name=='calibration':report['metrics'][name]={'before':core.metrics(cs[a:b],before[a:b]),'after':core.metrics(cs[a:b],after[a:b])};continue
  if name=='regressions':report['metrics'][name]={'passed':all(core.norm(o.get('correction') or c['typed'])==core.norm(c['expected']) for c,o in zip(cs[a:b],after[a:b]))};continue
  cohort=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in cs[a:b]];report['metrics'][name]={'before':metrics(cohort,before[a:b]),'after':metrics(cohort,after[a:b])}
 core.write_json(r/'keep-veto-study.json',report);print('CUT',cut)
 for name in ('objective','development'):print(name,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in report['metrics'][name]['after'].items()])
 print('GUARD',report['metrics']['regressions'])
if __name__=='__main__':main()
