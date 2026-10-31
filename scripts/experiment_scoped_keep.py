#!/usr/bin/env python3
"""Calibrate representable KEEP comparisons without trading away TRAIN fixes."""
import json,math
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import metrics,eligible
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-keep-20261031';out=root/'scoped-study';out.mkdir(exist_ok=False);old=ROOT/'build/english-noisy-channel-20261030';d=np.load(old/'repaired-data/features.npz');x={k:d[k] for k in d.files};cs=core.rows(old/'repaired-data/cases.jsonl');before=core.rows(old/'repair-verification/development_calibration_regressions-outputs.jsonl');sl=json.load(open(old/'repaired-data/slices.json'));words=[l.split('\t')[0] for l in (old/'lexicon.tsv').read_text().splitlines()];wordid={w:i for i,w in enumerate(words)};p=json.load(open(old/'repaired-study/replace-calrank0.json'))
 ids=x['ids'];valid=ids>=0;scores=x['log_frequency']+x['gain']-x['cost'];scores[~valid]=-1e6;mx=scores.max(1);others=mx+np.log(np.maximum(np.exp(scores-mx[:,None]).sum(1),1e-30));keep=np.log(np.where(x['literal_frequency']>=0,x['literal_frequency'],p['unknown_floor']))+x['literal_gain'];z=keep-others
 parent=np.array([wordid.get((o.get('correction') or '').lower(),-2) for o in before]);pj=(ids==parent[:,None]).argmax(1);represented=(ids==parent[:,None]).any(1);pairwise=keep-scores[np.arange(len(cs)),pj]
 hasfix=np.array([bool(o.get('correction')) and o['correction'].casefold()!=c['typed'].casefold() for c,o in zip(cs,before)]);ordinary=np.array([bool(o.get('correction')) and o['correction'].isascii() and o['correction'].isalpha() and c['typed'].isascii() and c['typed'].isalpha() for c,o in zip(cs,before)]);known=np.array([c['typed'].lower() in wordid for c in cs]);correct=np.array([bool(o.get('correction')) and o['correction'].casefold()==(c.get('target') or '').casefold() and c['typed'].casefold()!=(c.get('target') or '').casefold() for c,o in zip(cs,before)]);clean=np.array([c.get('target') and c['typed'].casefold()==c['target'].casefold() for c in cs],bool)
 a,b=sl['calibration'];cal=np.zeros(len(cs),bool);cal[a:b]=True;report={'selection':'Lowest nonnegative TRAIN-calibration KEEP cutoff losing zero existing correct fixes; no evaluation labels used to fit thresholds','models':{}}
 for evidence,confidence in [('posterior',z),('pairwise',pairwise)]:
  for scope,scopeok in [('dictionary',known),('literal_evidence',x['literal_frequency']>0)]:
   ok=x['routed']&hasfix&ordinary&represented&scopeok;bad=confidence[ok&cal&correct];cut=max(0.,float(bad.max())+1e-5) if len(bad) else 0.;apply=ok&(confidence>=cut);after=[dict(correction=None if yes else o.get('correction')) for yes,o in zip(apply,before)];values={};bases={}
   for name,(a,b) in sl.items():
    if name=='regressions':values[name]={'passed':all((o.get('correction') or c['typed']).casefold()==c['expected'].casefold() for c,o in zip(cs[a:b],after[a:b]))};continue
    if name=='calibration':values[name]=core.metrics(cs[a:b],after[a:b]);bases[name]=core.metrics(cs[a:b],before[a:b]);continue
    cohort=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in cs[a:b]];values[name]=metrics(cohort,after[a:b]);bases[name]=metrics(cohort,before[a:b])
   score=lambda m:sum(m[k]['f05'] for k in ('historical_full_mobile','real_touch_observed','authored_hard'))/3
   label=evidence+'-'+scope;policy=dict(evidence=evidence,scope=scope,cutoff=cut,require_parent_in_pool=True,ordinary_single_words_only=True,training_lost_correct_fixes=0);report['models'][label]=dict(policy=policy,score=score(values['objective']),delta=score(values['objective'])-score(bases['objective']),qualified=score(values['objective'])>score(bases['objective']) and eligible(values['development'],bases['development']) and values['regressions']['passed'],metrics=values,baseline=bases)
   core.write_json(out/(label+'.json'),policy);core.jsonl(out/(label+'-outputs.jsonl'),after);print(label,'CUT',cut,'SCORE',score(values['objective']),'CAL',values['calibration']['tp'],values['calibration']['fp'],'OBJECTIVE',[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in values['objective'].items()],flush=True)
 core.write_json(out/'report.json',report)
if __name__=='__main__':main()
