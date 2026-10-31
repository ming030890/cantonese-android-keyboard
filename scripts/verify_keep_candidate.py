#!/usr/bin/env python3
"""Verify native tree features, probabilities, decisions and visible surfaces."""
import argparse,json,subprocess
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from train_keep_trees import vocab_features,portable_scores,quantize
import collections,struct
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-keep-20261031';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--source',type=Path,default=root/'candidate-source-stable');parser.add_argument('--study',type=Path,default=root/'tree-study-v3');parser.add_argument('--output',type=Path,default=root/'native-parity-stable');parser.add_argument('--probe',type=Path,default=Path('/private/tmp/keyboard-keep-stable/release/ngram_probe'));args=parser.parse_args();out=args.output;out.mkdir(exist_ok=False);fd=root/'features';policy=json.load(open(args.source/'src/english_keep_trees.json'));probe=args.probe;model=ROOT/'app/src/main/assets/language_model/model.cng'
 with model.open('rb') as f:h=f.read(12);meta=json.loads(f.read(struct.unpack('<I',h[8:])[0]))
 variants=collections.defaultdict(list)
 for i in meta['english_word_ids']:variants[meta['tokens'][i].lower()].append(meta['tokens'][i])
 report=dict(probe_sha256=core.digest(probe),policy_sha256=core.digest(args.source/'src/english_keep_trees.json'),cohorts={},mismatches=[])
 for name in ('objective','development','chat_development','regressions'):
  cs=core.rows(fd/(name+'-cases.jsonl'));d=np.load(fd/(name+'.npz'));proposals=json.load(open(fd/(name+'-proposals.json')));xx=quantize(np.column_stack([d['x'],vocab_features(cs,proposals,variants)]) if policy['use_vocabulary_case'] else d['x']);expected=core.rows(args.study/(name+'-outputs.jsonl'));prob=portable_scores(policy,xx);request=''.join(json.dumps(dict(left=c['left'],typed=c['typed'],acceptance_diagnostic=True))+'\n' for c in cs);p=subprocess.run([str(probe),str(model)],input=request,text=True,capture_output=True,check=True,timeout=600);(out/(name+'-diagnostics.jsonl')).write_text(p.stdout);oo=[json.loads(l) for l in p.stdout.splitlines()];maxfeature=0.;maxprob=0.;actual=[]
  for i,(c,o,e) in enumerate(zip(cs,oo,expected,strict=True)):
   prediction=o['prediction'];actual.append(prediction)
   if bool(o['eligible'])!=bool(d['allowed'][i]):report['mismatches'].append(dict(cohort=name,id=c['id'],reason='eligibility'));continue
   if o['eligible']:
    maxfeature=max(maxfeature,float(np.max(np.abs(np.array(o['features'])-xx[i]))));maxprob=max(maxprob,abs(o['probability']-prob[i]))
   if (prediction.get('correction') or c['typed']).casefold()!=(e.get('correction') or c['typed']).casefold():report['mismatches'].append(dict(cohort=name,id=c['id'],expected=e.get('correction'),actual=prediction.get('correction')))
   fix=prediction.get('correction')
   if fix and fix.casefold()!=c['typed'].casefold() and fix not in [row['word'] for row in prediction['candidates']]:report['mismatches'].append(dict(cohort=name,id=c['id'],reason='correction absent from visible candidates'))
  core.jsonl(out/(name+'-outputs.jsonl'),actual);report['cohorts'][name]=dict(cases=len(cs),max_feature_difference=maxfeature,max_probability_difference=maxprob);print(name,report['cohorts'][name],flush=True)
  assert maxfeature<1e-7 and maxprob<1e-12
 core.write_json(out/'report.json',report);assert not report['mismatches'],report['mismatches'][:5]
if __name__=='__main__':main()
