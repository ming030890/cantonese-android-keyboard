#!/usr/bin/env python3
"""Word-prior contrast, original case and completion evidence for joint scoring."""
import collections,json,struct,subprocess
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE,load_features
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-listwise-20261030';src=root/'joint-features';out=root/'context-features';out.mkdir(exist_ok=False)
 model=ROOT/'app/src/main/assets/language_model/model.cng'
 with model.open('rb') as f:h=f.read(12);meta=json.loads(f.read(struct.unpack('<I',h[8:12])[0]))
 req=json.dumps(dict(left='',candidate_ids=list(range(meta['vocab_size'])),distribution=True))+'\n';p=subprocess.run(['/private/tmp/keyboard-release74-saved/ngram_probe',str(model)],input=req,text=True,capture_output=True,check=True);values=json.loads(p.stdout)['log_probs'];prior={}
 for i in meta['english_word_ids']:
  word=meta['tokens'][i].lower();prior[word]=max(prior.get(word,-1e9),values[i])
 core.write_json(out/'word-priors.json',dict(schema='english-empty-context-priors-v1',model_sha256=core.digest(model),context_ids=json.loads(p.stdout)['context_ids'],prior=prior))
 parent=json.load(open(ENGINE/'english_acceptance.json'));helper=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
 datasets=[('train',core.rows(src/'cases.jsonl'),core.rows(root/'train-outputs.jsonl')+core.rows(root/'real-author-data/outputs.jsonl')),('objective',core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'),core.rows(root/'objective-outputs.jsonl')),('development',core.rows(ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'),core.rows(root/'development-outputs.jsonl'))]
 cs=core.rows(ROOT/'config/product_eval_v1/native_regressions.jsonl');p=subprocess.run(['/private/tmp/keyboard-release74-saved/ngram_probe',str(model)],input=core.native_requests(cs),text=True,capture_output=True,check=True);datasets.append(('regressions',cs,[json.loads(l) for l in p.stdout.splitlines()]))
 for name,cs,oo in datasets:
  dd=np.load(src/(name+'.npz'));dec=json.load(open(src/(name+'-decoded.json')));x=dd['raw'].astype(np.float64);new=np.zeros((*x.shape[:2],9));
  for i,(c,o) in enumerate(zip(cs,oo)):
   typed=c['typed'].replace('’',"'").lower();case=c['typed'];tprior=prior.get(typed,0.);lit=o.get('literalScore');completions=[r for r in o['candidates'] if r['completedChars']>0];best=max(completions,key=lambda r:r['score']) if completions else None
   for j,word in enumerate(dec['words'][i]):
    parts=word.lower().split(' ');cp=prior.get(parts[0],0.)+(.5*prior.get(parts[1],0.) if len(parts)==2 else 0.)
    raw=x[i,j,0];gain=(raw-cp)-(lit-tprior) if lit is not None else 0.
    new[i,j]=[cp,tprior,gain,float(case[:1].isupper()),float(case.isupper()),float(any(ch.isupper() for ch in case[1:])),float(best is not None),max(-20.,min(20.,best['score']-raw)) if best else 0.,float(min(32,best['completedChars'])) if best else 0.]
  np.savez(out/(name+'.npz'),raw=np.concatenate([x,new],axis=2).astype(np.float32),eligible=dd['eligible'],labels=dd['labels']);print(name,'complete',flush=True)
 core.write_json(out/'protocol.json',dict(schema='joint-context-gain-v1',source_sha256=core.digest(__file__),priors_sha256=core.digest(out/'word-priors.json'),features=['candidate_empty_context_logp','literal_empty_context_logp','candidate_vs_literal_context_gain','typed_initial_upper','typed_all_upper','typed_internal_upper','has_completion','best_completion_over_candidate_raw','best_completion_chars'],no_eval_contexts_or_labels_used_for_priors=True))
if __name__=='__main__':main()
