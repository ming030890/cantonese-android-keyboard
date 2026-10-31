#!/usr/bin/env python3
"""Expose alignment-aware error probabilities to the joint neural decoder."""
import json,subprocess
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-listwise-20261030';src=root/'context-features';out=root/'channel-features';out.mkdir(exist_ok=False)
 cs=core.rows(root/'joint-features/cases.jsonl')
 cases={'train':cs,'objective':core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'),'development':core.rows(ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'),'regressions':core.rows(ROOT/'config/product_eval_v1/native_regressions.jsonl')}
 for name,cc in cases.items():
  dd=np.load(src/(name+'.npz'));raw=dd['raw'];eligible=dd['eligible'];labels=dd['labels'];dec=json.load(open(root/'joint-features'/(name+'-decoded.json')));request=[];positions=[]
  for i,(c,words) in enumerate(zip(cc,dec['words'])):
   for j,w in enumerate(words):
    if len(c['typed'])>32 or len(w)>32:continue
    request.append(c['typed'].replace('’',"'").lower()+'\t'+w.lower()+'\n');positions.append((i,j))
  p=subprocess.run([str(root/'character-channel/score')],input=''.join(request),text=True,capture_output=True,check=True);values=np.fromstring(p.stdout,sep='\n');assert len(values)==len(positions)
  costs=np.zeros(eligible.shape,dtype=np.float32)
  for (i,j),v in zip(positions,values):costs[i,j]=v
  extra=np.zeros((*costs.shape,4),dtype=np.float32)
  for i,(c,words) in enumerate(zip(cc,dec['words'])):
   for j,w in enumerate(words):
    rival=1 if j==0 and len(words)>1 else 0
    extra[i,j]=[costs[i,j],costs[i,j]/max(1,len(w)),costs[i,j]-costs[i,rival] if len(words)>1 else 0.,costs[i,j]/max(1,raw[i,j,9])]
  np.savez(out/(name+'.npz'),raw=np.concatenate([raw,extra],axis=2),eligible=eligible,labels=labels);print(name,'complete',len(positions),flush=True)
 core.write_json(out/'protocol.json',dict(schema='joint-character-channel-v1',channel_sha256=core.digest(root/'character-channel/model.json'),extra_features=['channel_cost','cost_per_candidate_character','cost_over_original_runner','cost_per_osa_edit'],script_sha256=core.digest(__file__),evals_fit_channel=False))
if __name__=='__main__':main()
