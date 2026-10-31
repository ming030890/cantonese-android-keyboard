#!/usr/bin/env python3
"""Cache production-native evidence; candidate choice never sees target labels."""
import json,subprocess
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-keep-20261031';out=root/'features';out.mkdir(exist_ok=False);probe=Path('/private/tmp/keyboard-keep-diagnostics/release/ngram_probe');model=ROOT/'app/src/main/assets/language_model/model.cng';schema=json.load(open(root/'diagnostic-features.json'));report={'schema':schema,'probe_sha256':core.digest(probe),'model_sha256':core.digest(model),'cohorts':{}}
 sources=[('train',root/'training/cases.jsonl'),('objective',ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'),('development',ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'),('chat_development',root/'fresh-chat-v2/development.jsonl'),('regressions',ROOT/'config/product_eval_v1/native_regressions.jsonl')]
 for name,path in sources:
  cs=core.rows(path);request=''.join(json.dumps(dict(left=c['left'],typed=c['typed'],acceptance_diagnostic=True),ensure_ascii=False)+'\n' for c in cs);print('PREPARE',name,len(cs),flush=True);p=subprocess.run([str(probe),str(model)],input=request,text=True,capture_output=True,check=True,timeout=1200);(out/(name+'-outputs.jsonl')).write_text(p.stdout);oo=[json.loads(l) for l in p.stdout.splitlines()];assert len(oo)==len(cs);n=len(cs);x=np.zeros((n,len(schema['features'])),np.float64);allowed=np.zeros(n,bool);y=np.zeros(n,bool);proposal=[];before=[]
  for i,(c,o) in enumerate(zip(cs,oo)):
   before.append(o['prediction']);word=o.get('proposal');proposal.append(word)
   if not o['eligible']:continue
   x[i]=o['features'];allowed[i]=True;y[i]=bool(c.get('target') and c['target'].casefold()!=c['typed'].casefold() and word.casefold()==c['target'].casefold())
  assert np.isfinite(x).all();np.savez(out/(name+'.npz'),x=x,allowed=allowed,y=y);core.jsonl(out/(name+'-cases.jsonl'),cs);core.jsonl(out/(name+'-before.jsonl'),before);core.write_json(out/(name+'-proposals.json'),proposal);report['cohorts'][name]=dict(cases=n,eligible=int(allowed.sum()),proposal_correct=int(y.sum()),case_sha256=core.digest(path));print('COMPLETE',name,report['cohorts'][name],flush=True)
 core.write_json(out/'protocol.json',report)
if __name__=='__main__':main()
