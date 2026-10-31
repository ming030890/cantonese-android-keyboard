#!/usr/bin/env python3
"""Fit a full-lexicon noisy-channel policy on TRAIN calibration only."""
import argparse,itertools,json,math
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import metrics,eligible
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-noisy-channel-20261030';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--data',type=Path,default=root/'data');parser.add_argument('--output',type=Path,default=root/'study');args=parser.parse_args();d=args.data;out=args.output;out.mkdir(exist_ok=False)
 data=np.load(d/'features.npz');x={k:data[k] for k in data.files};cases=core.rows(d/'cases.jsonl');before=core.rows(d/'before.jsonl');slices=json.load(open(d/'slices.json'));words=[l.split('\t')[0] for l in (root/'lexicon.tsv').read_text().splitlines()]
 norm=lambda w:core.norm(w) if w else ''
 targets=np.array([norm(c.get('target')) for c in cases]);typed=np.array([norm(c['typed']) for c in cases]);typo=targets!=typed;labelled=targets!='';basefix=np.array([norm(o.get('correction')) for o in before]);basechanged=(basefix!='')&(basefix!=typed);basetp=basechanged&typo&(basefix==targets);basefp=basechanged&~basetp;clean=~typo&labelled
 wordid={w:i for i,w in enumerate(words)};gold=np.array([wordid.get(t,-2) for t in targets]);cal=slice(*slices['calibration']);n=len(cases[cal]);cx={k:v[cal] for k,v in x.items()};valid=cx['ids']>=0
 # Threshold calibration uses separate source-family clean and wrong-correction
 # budgets. A low-risk family cannot pay for mistakes in another family.
 families=np.array([str(c.get('source',c.get('panel','unknown'))) for c in cases[cal]]);groups=sorted(set(families));gmask=[families==g for g in groups]
 def propose(xx,beta,tau,floor,rho,proper):
  scores=xx['log_frequency']+beta*xx['gain']-tau*xx['cost'];scores=np.where(xx['ids']>=0,scores,-1e6);j=scores.argmax(1);best=scores[np.arange(len(j)),j];lf=np.where(xx['literal_frequency']>=0,xx['literal_frequency'],floor);lf=lf*np.where(xx['proper_case'],proper,1.);keep=np.log(np.maximum(lf+rho*xx['prefix_mass'],1e-30))+beta*xx['literal_gain'];mx=np.maximum(best,keep);others=np.exp(keep-mx)+np.exp(scores-mx[:,None]).sum(1)-np.exp(best-mx);confidence=best-mx-np.log(np.maximum(others,1e-30));return xx['ids'][np.arange(len(j)),j],confidence
 def calibrate(ids,z,mode):
  ok=cx['routed']&(ids>=0)&labelled[cal]
  if mode=='rescue':ok&=~basechanged[cal]
  correct=ids==gold[cal];order=np.flatnonzero(ok&(z>=0));order=order[np.argsort(-z[order],kind='stable')]
  if not len(order):return None
  newtp=correct[order]&typo[cal][order];newfp=~newtp;oldtp=basetp[cal][order];oldfp=basefp[cal][order];dt=np.cumsum(newtp.astype(int)-oldtp);df=np.cumsum(newfp.astype(int)-oldfp);dc=np.cumsum((newfp&clean[cal][order]).astype(int)-(oldfp&clean[cal][order]).astype(int));allowed=np.ones(len(order),bool)
  for mask in gmask:
   allowed&=np.cumsum((newfp&mask[order]).astype(int)-(oldfp&mask[order]).astype(int))<=0
   allowed&=np.cumsum((newfp&clean[cal][order]&mask[order]).astype(int)-(oldfp&clean[cal][order]&mask[order]).astype(int))<=0
  allowed&=(df<=0)&(dc<=0)
  tp=basetp[cal].sum()+dt;fp=basefp[cal].sum()+df;score=1.25*tp/(tp+fp+.25*(typo[cal]&labelled[cal]).sum());score[~allowed]=-1
  # Equal confidence values cannot be separated by a threshold.
  score[:-1][z[order[:-1]]==z[order[1:]]]=-1
  j=int(score.argmax())
  if score[j]<0:return None
  cut=float(z[order[j]]);return float(score[j]),cut,int(tp[j]),int(fp[j])
 rankings={'rescue':[],'replace':[]};total=0
 for beta,tau in itertools.product((0.,.25,.5,1.,2.),(.25,.5,.75,1.,1.5)):
  for floor,rho,proper in itertools.product((.001,.01,.1,1.),(0.,.0001,.001),(1.,10.)):
   ids,z=propose(cx,beta,tau,floor,rho,proper)
   for mode in rankings:
    value=calibrate(ids,z,mode)
    if value:rankings[mode].append((value,dict(beta=beta,tau=tau,unknown_floor=floor,prefix_weight=rho,proper_multiplier=proper,mode=mode,cutoff=value[1])))
   total+=1
  print('CALIBRATED',total,flush=True)
 report={'calibration_only':True,'confirmation_used':False,'models':[]};baseobj=None
 for mode,ranked in rankings.items():
  ranked.sort(key=lambda v:v[0][0],reverse=True)
  for rank,(calmetrics,p) in enumerate(ranked[:12]):
   ids,z=propose(x,p['beta'],p['tau'],p['unknown_floor'],p['prefix_weight'],p['proper_multiplier']);ok=x['routed']&(ids>=0)&(z>=p['cutoff'])
   if mode=='rescue':ok&=~basechanged
   predictions=[dict(correction=words[j] if apply else b.get('correction')) for b,j,apply in zip(before,ids,ok)]
   values={};bases={}
   for name,(a,b) in slices.items():
    cs=cases[a:b]
    if name=='objective':cs=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in cs]
    if name=='regressions':values[name]={'passed':all(norm(o.get('correction') or c['typed'])==norm(c['expected']) for c,o in zip(cs,predictions[a:b]))};continue
    values[name]=metrics(cs,predictions[a:b]) if name!='calibration' else core.metrics(cs,predictions[a:b]);bases[name]=metrics(cs,before[a:b]) if name!='calibration' else core.metrics(cs,before[a:b])
   score=lambda m:sum(m[k]['f05'] for k in ('historical_full_mobile','real_touch_observed','authored_hard'))/3
   s=score(values['objective']);baseobj=score(bases['objective']);qualifies=s>baseobj and eligible(values['development'],bases['development']) and values['regressions']['passed'];label=f'{mode}-calrank{rank}';core.write_json(out/(label+'.json'),p);report['models'].append(dict(name=label,policy=p,calibration=calmetrics,score=s,qualified=qualifies,metrics=values));core.jsonl(out/(label+'-outputs.jsonl'),predictions)
   print(label,s,qualifies,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in values['objective'].items()],flush=True)
 report['baseline_score']=baseobj;report['baseline_metrics']=bases;core.write_json(out/'report.json',report)
if __name__=='__main__':main()
