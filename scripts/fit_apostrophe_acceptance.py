#!/usr/bin/env python3
"""Fit restoration on TRAIN prompt examples; never use selection/eval labels."""
import argparse
import csv
import hashlib
import json
import re
from pathlib import Path
import numpy as np
from align_mobile_targets import reference_target
from audit_candidate_pipeline import digest, probe
from iterate_english_policy import split, hashed, f05
from iterate_literal_acceptance import fit


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--policy',type=Path,required=True)
    a=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    case_path=root/'build/english-iteration-2026-10-20/cases.jsonl'
    forms_path=root.parent/'QwenASR/crates/cantonese-ngram/src/english_apostrophe_forms.json'
    forms=json.loads(forms_path.read_text()); bases={f['word'].replace("'",'') for f in forms}
    cases=[c for c in map(json.loads,case_path.open()) if not c['category'].startswith('prefix_') and split(c['group'])=='train']
    groups={c['group'] for c in cases}
    source=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    refs={r['SENTENCE_ID']:r['SENTENCE'] for r in csv.DictReader(source.open()) if r['SENTENCE_ID'] in groups}
    rows={}
    def add(group,left,typed,target,origin):
        typed=typed.lower().replace('’',"'");target=target.lower().replace('’',"'")
        if typed not in bases:return
        rows[(group,left,typed,target)]=dict(group=group,left=left,typed=typed,target=target,origin=origin)
    for group,text in refs.items():
        for m in re.finditer(r"[A-Za-z]+(?:['’][A-Za-z]+)*",text):
            word=m.group().lower().replace('’',"'")
            add(group,text[:m.start()],word.replace("'",''),word,'TRAIN prompt omission/control')
    for c in cases:
        target=c['target'] if c['category']=='source_preservation' else reference_target(c,refs[c['group']])
        if target is not None:add(c['group'],c['left'],c['typed'],target,'TRAIN observed/control')
    rows=list(rows.values())
    model=root/'app/src/main/assets/language_model/model.cng'
    outputs=probe(a.probe,model,[dict(left=r['left'],typed=r['typed']) for r in rows])
    x=[];labels=[];expanded=[]
    for row,out in zip(rows,outputs):
        for e in out['apostropheEvidence']:
            expanded.append(dict(row,word=e['word'],features=e['features']))
            x.append(e['features']);labels.append(int(e['word']==row['target'] and row['target']!=row['typed']))
    x=np.array(x);y=np.array(labels)
    calibration=np.array([int(hashed('calibration:'+r['group'])[:8],16)%5==0 for r in expanded])
    training=~calibration
    trials=[]
    for reg in (.001,.01,.1):
        m=fit(x[training],y[training],reg)
        z=m['weights'][0]+((x-np.array(m['means']))/np.array(m['scales']))@np.array(m['weights'][1:])
        probs=1/(1+np.exp(-np.clip(z,-700,700)))
        order=sorted(np.flatnonzero(calibration),key=lambda i:-probs[i]);tp=fp=0;cuts=[]
        for pos,i in enumerate(order):
            tp+=int(y[i]);fp+=int(not y[i])
            if pos+1<len(order) and probs[order[pos+1]]==probs[i]:continue
            cuts.append((f05(tp,fp,int(y[calibration].sum())-tp),float(probs[i])))
        score,threshold=max(cuts)
        m.update(threshold=float(np.nextafter(threshold,-np.inf)),regularization=reg,calibration_f05=score)
        trials.append(m)
    champion=max(trials,key=lambda m:(m['calibration_f05'],m['regularization']))
    policy=dict(champion,features=['surface_logp_minus_literal','surface_logp_minus_best_word_edit_score','log1p_surface_count_minus_log1p_bare_count'],
                model_sha256=digest(model),forms_sha256=digest(forms_path),training_rows=int(training.sum()),calibration_rows=int(calibration.sum()),
                provenance='TRAIN only; original observed cases plus missing-apostrophe examples/clean controls from unique TRAIN reference prompts; prompt-group fit/calibration')
    a.policy.write_text(json.dumps(policy,indent=2)+'\n')
    report=dict(policy=policy,trials=trials,rows=expanded,labels=y.tolist(),
                source_sha256=digest(source),cases_sha256=digest(case_path),script_sha256=digest(Path(__file__)),feature_probe_sha256=digest(a.probe))
    a.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(policy,indent=2))


if __name__=='__main__':main()
