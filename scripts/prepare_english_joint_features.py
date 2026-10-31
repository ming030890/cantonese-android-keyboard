#!/usr/bin/env python3
"""All-candidate NN features, with KEEP labels; candidate pools never use intent."""
import collections,inspect,json,subprocess
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE,load_features
ROOT=Path(__file__).resolve().parents[1];K=64

def prepare(cases,outputs,parent,helper):
 dictionary=set((ENGINE/'english_valid_words.txt').read_text().splitlines());n=len(cases)
 x=np.zeros((n,K,22),dtype=np.float32);allowed=np.zeros((n,K),dtype=bool);labels=np.zeros(n,dtype=np.int64);words=[];restored=[];coverage=collections.Counter()
 src=inspect.getsource(helper.extract);lines=src.splitlines();lines=[line[:len(line)-len(line.lstrip())]+"ranked=output['candidates']" if line.lstrip().startswith('ranked=sorted(') else line for line in lines];ns={};exec('\n'.join(lines),helper.__dict__,ns);extract=ns['extract']
 for i,(c,o) in enumerate(zip(cases,outputs)):
  typed=c['typed'].replace('’',"'").lower();surfaces={e['word'] for e in o.get('apostropheEvidence',[])}
  ranked=sorted([r for r in o['candidates'] if r['word'] not in surfaces],key=lambda r:(-r['score'],r['word']))
  restore=helper.restored(o)
  if o.get('correction') and o['correction'].casefold()==c['typed'].casefold():restore=o['correction']
  restored.append(restore)
  pool=[r for r in ranked if r['word'].casefold()!=typed][:K];words.append([r['word'] for r in pool])
  for j,row in enumerate(pool):
   rival=next((r for r in ranked if r is not row),None)
   fake=dict(o,candidates=[row]+([rival] if rival else []));f,ok,_=extract(c,fake);word=row['word'].lower()
   x[i,j]=list(f)+[float(typed in dictionary),float(bool(word) and all(w in dictionary for w in word.split(' ')))];allowed[i,j]=ok
   if ok and c.get('target') and row['word'].casefold()==c['target'].casefold() and typed!=c['target'].casefold():labels[i]=j+1
  if c.get('target') and typed!=c['target'].casefold():coverage['typos']+=1;coverage['gold_in_pool']+=labels[i]>0
  if i%2000==0:print('features',i,n,flush=True)
 return x,allowed,labels,words,restored,{k:int(v) for k,v in coverage.items()}

def main():
 root=ROOT/'build/english-listwise-20261030';out=root/'joint-features';out.mkdir(exist_ok=True);parent=json.load(open(ENGINE/'english_acceptance.json'));helper=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
 train=core.rows(root/'cases.jsonl');trainout=core.rows(root/'train-outputs.jsonl');author=core.rows(root/'real-author-data/cases.jsonl')
 request=''.join(json.dumps(dict(left=c['left'],typed=c['typed'],spelling_limit=128),ensure_ascii=False)+'\n' for c in author)
 cache=root/'real-author-data/outputs.jsonl'
 if not cache.exists():
  p=subprocess.run(['/private/tmp/keyboard-release74-saved/ngram_probe',str(ROOT/'app/src/main/assets/language_model/model.cng')],input=request,text=True,capture_output=True,check=True,timeout=300);cache.write_text(p.stdout)
 authorout=core.rows(cache);train+=author;trainout+=authorout
 core.jsonl(out/'cases.jsonl',train)
 regressions=core.rows(ROOT/'config/product_eval_v1/native_regressions.jsonl')
 request=core.native_requests(regressions);p=subprocess.run(['/private/tmp/keyboard-release74-saved/ngram_probe',str(ROOT/'app/src/main/assets/language_model/model.cng')],input=request,text=True,capture_output=True,check=True);regout=[json.loads(l) for l in p.stdout.splitlines()]
 for name,cs,oo in [('regressions',regressions,regout),('train',train,trainout),('objective',core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'),core.rows(root/'objective-outputs.jsonl')),('development',core.rows(ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'),core.rows(root/'development-outputs.jsonl'))]:
  print('PREPARE',name,len(cs),flush=True);x,a,y,w,r,cov=prepare(cs,oo,parent,helper);np.savez(out/(name+'.npz'),raw=x,eligible=a,labels=y);core.write_json(out/(name+'-decoded.json'),dict(words=w,restorations=r,coverage=cov));print('COMPLETE',name,cov,flush=True)
 core.write_json(out/'protocol.json',dict(schema='english-listwise-features-v1',parent_sha256=core.digest(ENGINE/'english_acceptance.json'),candidate_limit=K,fit_input_overlap=0,gold_used_only_for_labels=True,source_sha256=core.digest(__file__)))
if __name__=='__main__':main()
