#!/usr/bin/env python3
"""Frozen-parent English confidence plus a small TRAIN-fitted residual MLP."""
import json,copy,math,collections
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_english_char_gate import append,logits,ROOT,ENGINE
from experiment_clean_acceptance import load_features
from experiment_bilingual_acceptance import extract
from experiment_conditional_english_scores import metrics
r=ROOT/'build/english-char-gate-20261030';out=r/'residual-study';out.mkdir(exist_ok=False)
src=ROOT/'build/short-nn-v2-20261029/input-clean-data';cs=core.rows(src/'cases.jsonl');data=np.load(src/'features.npz');dec=json.load(open(src/'decisions.json'));encoder=json.load(open(r/'column-study-v2/char-model.json'));parent=json.load(open(ENGINE/'english_acceptance.json'))
f=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py');x=append(data['raw'],cs,dec['words'],encoder)
fm=np.array([c['fit_split']=='fit' and e and not rest for c,e,rest in zip(cs,data['eligible'],dec['restorations'])]);counts=collections.Counter(c['source'] for c,m in zip(cs,fm) if m)
mass={'real':1.,'generated':.25,'blank':.1,'gboard_word':.1,'gboard_context':.16,'gboard_touch':.19,'clean_dictionary':.25,'clean_dropout':.25,'clean_short':.1,'generated_short':.25,'authentic_short_clean':.5,'authentic_short_typo':.5,'title_abbreviation_clean':.25}
w=np.array([(.125 if c.get('noise') else mass[c['source']])/counts[c['source']] if m else 0 for c,m in zip(cs,fm)])[fm];w/=w.sum();y=data['labels'][fm]
means=x[fm].mean(0);scales=np.maximum(x[fm].std(0),.05);xx=(x[fm]-means)/scales;base=logits(parent,x[fm,:22])
dev=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl')];oo=core.rows(ROOT/'build/bilingual-residual-20261030/final-objective/baseline-outputs.jsonl');dx,da,dw,dr=extract(dev,oo,parent,f);dx=append(dx,dev,dw,encoder);db=logits(parent,dx[:,:22]);dx=(dx-means)/scales;cut=math.log(parent['threshold']/(1-parent['threshold']))
score=lambda m:sum(m[k]['f05'] for k in ('historical_full_mobile','real_touch_observed','authored_hard'))/3
report={'baseline_score':score(metrics(dev,oo)),'models':{}};best=(report['baseline_score'],'parent')
for lr in (.001,.003,.01):
 rng=np.random.default_rng(20261030);p={'w1':rng.normal(0,.08,(32,30)),'b1':np.zeros(32),'w2':np.zeros(32),'b2':np.array(0.)};orig=copy.deepcopy(p);m={k:np.zeros_like(v) for k,v in p.items()};v=copy.deepcopy(m)
 for step in range(1,401):
  h=xx@p['w1'].T+p['b1'];a=np.maximum(h,0);delta=a@p['w2']+p['b2'];z=base+delta;dz=w*(1/(1+np.exp(-np.clip(z,-40,40)))-y+.2*delta);dh=dz[:,None]*p['w2']*(h>0)
  gg={'w1':dh.T@xx,'b1':dh.sum(0),'w2':a.T@dz,'b2':dz.sum()}
  for k,g in gg.items():
   g=g+.002*(p[k]-orig[k]);m[k]=.9*m[k]+.1*g;v[k]=.999*v[k]+.001*g*g;p[k]-=lr*(m[k]/(1-.9**step))/(np.sqrt(v[k]/(1-.999**step))+1e-8)
  if step in (50,100,200,400):
   zz=db+np.maximum(dx@p['w1'].T+p['b1'],0)@p['w2']+p['b2'];outputs=[dict(o,correction=rest or (word if ok and z>=cut else None)) for o,rest,word,ok,z in zip(oo,dr,dw,da,zz)];met=metrics(dev,outputs);s=score(met);name=f'lr{lr}-s{step}';report['models'][name]={'score':s,'metrics':met};model={k:t.tolist() for k,t in p.items()};model.update(means=means.tolist(),scales=scales.tolist(),parent_sha256=core.digest(ENGINE/'english_acceptance.json'));core.write_json(out/(name+'.json'),model);print(name,s,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in met.items()],flush=True)
   if s>best[0]:best=(s,name)
report['winner']=best;core.write_json(out/'report.json',report)
