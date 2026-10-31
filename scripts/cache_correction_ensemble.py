#!/usr/bin/env python3
"""Out-of-fit calibration predictions for a conservative contextual retry gate."""
import json,math
from pathlib import Path
import numpy as np
import torch
from bert_tiny_pair import PairRanker
from train_bert_correction import tensors
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-bert-20261030';out=root/'ensemble-cache';out.mkdir(exist_ok=False);device=torch.device('mps');assert torch.backends.mps.is_available();torch.set_num_threads(4)
 jr=ROOT/'build/english-listwise-20261030';cs=core.rows(jr/'joint-features/cases.jsonl');cal=np.array([i for i,c in enumerate(cs) if c['fit_split']=='calibration']);core.write_json(out/'calibration-indices.json',cal.tolist())
 for epoch in (1,2,3,4):
  state=torch.load(root/'study'/f'epoch{epoch}.pt',map_location='cpu',weights_only=True);model=PairRanker();model.load_state_dict(state['state']);model.to(device).eval()
  for name,file in [('calibration','train'),('objective','objective'),('development','development'),('regressions','regressions')]:
   data=np.load(root/'data'/(file+'.npz'));d=tensors(data,device)
   if name=='calibration':d={k:v[cal] for k,v in d.items()}
   result=[]
   with torch.no_grad():
    for at in range(0,len(d['ids']),64):
     ix=slice(at,at+64);z,_=model(*(d[k][ix] for k in ('ids','segments','mask','numeric','base')));z=z.masked_fill(~d['eligible'][ix],-1e4);result.append(z.cpu().numpy())
   np.save(out/f'bert-e{epoch}-{name}.npy',np.concatenate(result));print('CACHED',epoch,name,flush=True)
   del d
  del model
 core.write_json(out/'protocol.json',dict(schema='correction-retry-cache-v1',calibration_indices_sha256=core.digest(out/'calibration-indices.json'),script_sha256=core.digest(__file__),confirmation_used=False))
if __name__=='__main__':main()
