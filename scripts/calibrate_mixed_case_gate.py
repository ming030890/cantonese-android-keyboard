import sys,math,json,collections
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path('scripts').resolve()))
import autocorrect_eval_v2 as c
from experiment_mixed_case_gate import extra,predict
from experiment_bilingual_acceptance import extract
from experiment_clean_acceptance import load_features,ENGINE
from experiment_conditional_english_scores import metrics,eligible,quality
from build_independent_autocorrect_eval import HAN,stable
r=Path('build/bilingual-residual-20261030');study=r/'frozen-literal-study';out=r/'safety-calibration';out.mkdir()
parent=json.load(open(ENGINE/'english_acceptance.json'));f=load_features(parent,Path('../gboard-probe/improve-v3/features.py'))
t=c.rows(r/'data/cases.jsonl');to=c.rows(study/'train-outputs.jsonl');tx,ta,tw,tr=extract(t,to,parent,f);tx=np.column_stack([tx,[extra(x) for x in t]])
cal=np.array([x['source_kind'].startswith('mixed_') and int(stable('mixed-case:'+x['group'])[:8],16)%4==0 for x in t]);ci=np.flatnonzero(cal)
cc=[t[i] for i in ci];budget=c.metrics(cc,[to[i] for i in ci]);print('calibration budget',budget,flush=True)
# Only development labels are read here. New confirmation is still unopened.
d=c.rows(r/'fresh-eval/development.jsonl');do=c.rows(r/'fresh-development-baseline.jsonl')
# Mass diagnostics from accepted-radius prototype; mass is independent of candidate generation.
from experiment_conditional_english_scores import infer
mass=infer(Path('build/bilingual-acceptance-20261030/column-study/target/release/ngram_probe'),Path('app/src/main/assets/language_model/model.cng'),d,'candidate',0)
dx,da,dw,dr=extract(d,mass,parent,f);dx=np.column_stack([dx,[extra(x) for x in d]]);mixed=[bool(HAN.search(x['left'])) for x in d]
base=metrics(d,do);report={'budget':budget,'baseline':base,'profiles':{}};best=None
for path in sorted(study.glob('lr*.json')):
 p=json.load(open(path));tz=predict(p,tx[cal]);dz=predict(p,dx)
 for fraction in (.25,.5,.75,1.):
  options=[]
  for cut in np.unique(np.r_[math.log(p['threshold']/(1-p['threshold'])),np.quantile(tz,np.linspace(0,1,501))]):
   oo=[{'correction':tr[i] or (tw[i] if ta[i] and z>=cut else None)} for i,z in zip(ci,tz)]
   met=c.metrics(cc,oo)
   if met['fp']<=math.floor(budget['fp']*fraction) and met['clean_changes']<=math.floor(budget['clean_changes']*fraction):options.append((met['tp'],-met['fp'],float(cut)))
  if not options:continue
  cut=max(options)[2]
  oo=[dict(o,correction=(rr or (w if a and z>=cut else None)) if mix else o.get('correction')) for o,a,w,rr,z,mix in zip(mass,da,dw,dr,dz,mixed)]
  met=metrics(d,oo);ok=eligible(met,base);name=path.stem+f'-budget{fraction}';report['profiles'][name]={'metrics':met,'eligible':ok,'cutoff':cut}
  print(name,ok,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in met.items()],flush=True)
  if ok and (best is None or quality(met)>best[0]):
   best=(quality(met),name);q=dict(p,threshold=1/(1+math.exp(-min(30,max(-30,cut)))));c.write_json(out/'selected-policy.json',q)
report['winner']=best;c.write_json(out/'report.json',report)
