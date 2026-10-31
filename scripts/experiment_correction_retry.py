#!/usr/bin/env python3
"""Retry only declined contextual corrections; stack semantic and edit evidence.

Base decoders fit TRAIN. Meta fit and threshold use separate source groups of
TRAIN calibration. Existing accepted corrections remain unchanged.
"""
import collections,json,math,subprocess
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from build_independent_autocorrect_eval import stable,HAN
from experiment_conditional_english_scores import metrics,eligible
ROOT=Path(__file__).resolve().parents[1]

def route(c):return bool(c['left'].strip()) and not HAN.search(c['left'])
def margin(scores,index,keep=True):
 mx=np.maximum(scores.max(1),0. if keep else -1e30);total=(np.exp(-mx) if keep else 0.)+np.exp(scores-mx[:,None]).sum(1);best=scores[np.arange(len(scores)),index];return best-mx-np.log(np.maximum(total-np.exp(best-mx),1e-300))
def logistic_fit(x,y,weights,ridge):
 mean=x.mean(0);scale=np.maximum(x.std(0),.05);a=np.column_stack([np.ones(len(x)),(x-mean)/scale]);w=np.zeros(a.shape[1]);penalty=np.eye(a.shape[1])*ridge;penalty[0,0]=1e-6;weights=weights/weights.sum()
 for _ in range(60):
  p=1/(1+np.exp(-np.clip(a@w,-40,40)));g=a.T@(weights*(p-y))+penalty@w;h=(a.T*(weights*p*(1-p)))@a+penalty;step=np.linalg.solve(h,g);w-=step
  if np.linalg.norm(step)<1e-7:break
 return dict(means=mean.tolist(),scales=scale.tolist(),weights=w.tolist())
def logits(policy,x):return np.column_stack([np.ones(len(x)),(x-policy['means'])/policy['scales']])@policy['weights']

def main():
 root=ROOT/'build/english-listwise-20261030';br=ROOT/'build/english-bert-20261030';out=root/'retry-study-v3';out.mkdir(exist_ok=False);parent=json.load(open(ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_acceptance.json'));channel=json.load(open(root/'channel-study-v2/balance0.5-lr0.0003-e30.json'));ci=json.load(open(br/'ensemble-cache/calibration-indices.json'));allcases=core.rows(root/'joint-features/cases.jsonl');allbefore=core.rows(root/'train-outputs.jsonl')+core.rows(root/'real-author-data/outputs.jsonl');calcases=[allcases[i] for i in ci];calbefore=[allbefore[i] for i in ci]
 sets=[('calibration',calcases,calbefore,'train'),('objective',core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'),core.rows(root/'objective-outputs.jsonl'),'objective'),('development',core.rows(ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'),core.rows(root/'development-outputs.jsonl'),'development')]
 regcases=core.rows(ROOT/'config/product_eval_v1/native_regressions.jsonl');proc=subprocess.run(['/private/tmp/keyboard-release74-saved/ngram_probe',str(ROOT/'app/src/main/assets/language_model/model.cng')],input=core.native_requests(regcases),text=True,capture_output=True,check=True);sets.append(('regressions',regcases,[json.loads(l) for l in proc.stdout.splitlines()],'regressions'))
 cohorts={}
 for name,cs,before,file in sets:
  data=np.load(root/'channel-features'/(file+'.npz'));x=data['raw'];a=data['eligible'];words=json.load(open(root/'joint-features'/(file+'-decoded.json')))['words'];bscore=np.load(br/'ensemble-cache'/f'bert-e3-{name}.npy')
  if name=='calibration':x=x[ci];a=a[ci];words=[words[i] for i in ci]
  norm=(x-channel['means'])/channel['scales'];cscore=np.maximum(norm@np.array(channel['w1']).T+channel['b1'],0)@channel['w2']+channel['b2'];cscore[~a]=-1e4;bj=bscore[:,1:].argmax(1);cj=cscore.argmax(1);bm=margin(bscore,bj+1,False);cm=margin(cscore,bj);oldx=(x[:,0,:22]-parent['means'])/parent['scales'];oldlogit=np.maximum(oldx@np.array(parent['w1']).T+parent['b1'],0)@parent['w2']+parent['b2']-math.log(parent['threshold']/(1-parent['threshold']))
  columns=[];targets=[];valid=[];proposal=[]
  for i,(c,b,j) in enumerate(zip(cs,before,bj)):
   word=words[i][j] if j<len(words[i]) else None;proposal.append(word);valid.append(not b.get('correction') and route(c) and bool(word) and bool(a[i,j]));targets.append(float(bool(word and c.get('target') and c['typed'].casefold()!=c['target'].casefold() and word.casefold()==c['target'].casefold())))
   left=c['left'].rstrip();mid=bool(c['typed'][:1].isupper() and left and left[-1] not in '.!?。！？')
   columns.append([bm[i],cm[i],float(bj[i]==cj[i]),oldlogit[i],float(x[i,j,31]),float(x[i,j,5]),float(x[i,j,20]),float(mid),float(x[i,j,24])])
  if name=='objective':cs=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in cs]
  cohorts[name]=dict(cases=cs,before=before,x=np.array(columns),y=np.array(targets),valid=np.array(valid),proposal=proposal,bm=bm,cm=cm,agree=bj==cj)
 baseobj=metrics(cohorts['objective']['cases'],cohorts['objective']['before']);basedev=metrics(cohorts['development']['cases'],cohorts['development']['before']);score=lambda m:sum(m[k]['f05'] for k in ('historical_full_mobile','real_touch_observed','authored_hard'))/3
 report=dict(baseline_score=score(baseobj),baseline=baseobj,models={});best=(score(baseobj),'parent');cal=cohorts['calibration'];group=lambda c:str(c.get('group',c['id']));mf=np.array([int(stable('retry-fit:'+group(c))[:8],16)%2==0 for c in cal['cases']]);fit=mf&cal['valid'];hold=~mf&cal['valid'];print('META FIT',fit.sum(),'positive',cal['y'][fit].sum(),'CAL',hold.sum(),'positive',cal['y'][hold].sum(),flush=True)
 # Zero new wrong corrections on the independent meta-calibration groups.
 for family,columns in [('semantic',[0,3,5,6,7]),('stacked',list(range(9)))]:
  for ridge in (.001,.01,.1):
   policy=logistic_fit(cal['x'][fit][:,columns],cal['y'][fit],np.ones(fit.sum()),ridge);z=logits(policy,cal['x'][:,columns]);bad=z[hold&(cal['y']==0)];cut=float(bad.max()+1e-6) if len(bad) else 0.;policy.update(cutoff_logit=cut,columns=columns,bert_epoch=3,channel_policy_sha256=core.digest(root/'channel-study-v2/balance0.5-lr0.0003-e30.json'))
   values={}
   for name,d in cohorts.items():
    zz=logits(policy,d['x'][:,columns]);pred=[dict(correction=b.get('correction') or (word if ok and value>=cut else None)) for b,word,ok,value in zip(d['before'],d['proposal'],d['valid'],zz)];values[name]=({'calibration':core.metrics(d['cases'],pred)} if name=='calibration' else {'passed':all((o['correction'] or c['typed']).casefold()==c['expected'].casefold() for c,o in zip(d['cases'],pred))} if name=='regressions' else metrics(d['cases'],pred))
   s=score(values['objective']);devok=eligible(values['development'],basedev) and values['regressions']['passed'];name=f'{family}-ridge{ridge}';report['models'][name]=dict(score=s,eligible=s>report['baseline_score'] and devok,metrics=values);core.write_json(out/(name+'.json'),policy);print(name,s,'DEV',devok,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in values['objective'].items()],flush=True)
   if s>best[0] and devok:best=(s,name)
 report['winner']=best;core.write_json(out/'report.json',report)
 for name,d in cohorts.items():np.savez(out/(name+'-features.npz'),x=d['x'],y=d['y'],valid=d['valid'])
if __name__=='__main__':main()
