#!/usr/bin/env python3
"""Fit coordinate channel AND acceptance on synthetic taps, then freeze.

Text comes only from existing Aalto TRAIN prompt groups. The new public real
touch evaluation is never read here. All three synthetic splits group prompts.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from fit_tsi_touch import PROJECT, ENGINE, LETTERS, fit, touch_delta, metrics, accept, digest
from iterate_english_policy import split
from public_touch_eval import dump
from verify_tsi_touch import probe


def hashed(s):
    return hashlib.sha256(('synthetic-touch-v2:'+s).encode()).hexdigest()


def synthetic(source, count):
    keys={}
    for row,(letters,offset) in enumerate([('qwertyuiop',0.),('asdfghjkl',.5),('zxcvbnm',1.5)]):
        for col,c in enumerate(letters):
            keys[c]=dict(key_center_x=col+offset,key_center_y=row*1.3,key_width=1.,key_height=1.)
    geometry=[v for c in LETTERS for v in [keys[c]['key_center_x'],keys[c]['key_center_y'],1.,1.]]
    rows=[]; taps=[]
    for line in source.open():
        c=json.loads(line)
        if c['category'].startswith('prefix_') or split(c['group'])!='train':
            continue
        target=c['target'].lower()
        if not target.isascii() or not target.isalpha() or not 3<=len(target)<=16:
            continue
        rows.append(c)
    rows=sorted(rows,key=lambda c:hashed(c['id']))[:count]
    cases=[]
    for c in rows:
        rng=np.random.default_rng(int(hashed(c['id'])[:16],16))
        sigma=rng.choice([.12,.18,.24,.30])
        bias=rng.normal(0.,.07,2)
        intended=c['target'].lower();points=[];typed=[]
        group_bucket=int(hashed('split:'+c['group'])[:8],16)%10
        part='fit' if group_bucket<6 else 'calibration' if group_bucket<8 else 'selection'
        for letter in intended:
            # A small broad-noise component and varying per-trial bias model
            # imprecision. Parameters are assumptions, not estimated human rates.
            noise=rng.normal(0.,sigma,2)+bias
            if rng.random()<.02:
                noise+=rng.uniform(-1.,1.,2)
            k=keys[letter]
            x=k['key_center_x']+noise[0];y=k['key_center_y']+noise[1]
            points.extend([float(x),float(y)])
            typed.append(min(LETTERS,key=lambda a:(x-keys[a]['key_center_x'])**2+(y-keys[a]['key_center_y'])**2))
            if part=='fit':
                taps.append(dict(participant_id='user01',ref_char=letter,first_frame_touch_x=x,first_frame_touch_y=y))
        cases.append(dict(id=c['id'],group=c['group'],split=part,typed=''.join(typed),target=intended,
                          left=c['left'],points=points,geometry=geometry))
    return cases,fit(keys,taps)


def replacement_penalty(typed, word, edits, completed):
    word=word.lower()
    if completed or len(typed)!=len(word) or not set(typed+word)<=set(LETTERS) or sum(a!=b for a,b in zip(typed,word))!=edits:
        return None
    positions={c:(r,i) for r,row in enumerate(['qwertyuiop','asdfghjkl','zxcvbnm']) for i,c in enumerate(row)}
    value=0.
    for a,b in zip(typed,word):
        if a!=b:
            near=all(abs(x-y)<=1 for x,y in zip(positions[a],positions[b]))
            value+=.574436466482213 if near else 3.7162379435897606
    if edits>=2:
        value+=(edits-1)*3.
    return value


def rerank(case, result, model, weight):
    rows=[]
    for r in result['candidates']:
        row=dict(r)
        penalty=replacement_penalty(case['typed'],r['word'],r['edits'],r['completedChars'])
        if weight is not None and penalty is not None and not result['apostropheEvidence']:
            # Replace this path's fixed edit prior with touch evidence. Literal
            # likelihood is the zero reference, including when OOV has no LM token.
            d=touch_delta(model,case['typed'],r['word'],case['points'],case['geometry'],r['edits'],r['completedChars'])
            row['score']=float(np.float32(r['score'])+np.float32(penalty)+np.float32(weight)*np.float32(d))
        rows.append(row)
    return sorted(rows,key=lambda r:(-r['score'],r['word']))


def features(result, ranked):
    if not ranked:
        return [0.]*6
    best=ranked[0]['score'];literal=result['literalScore']
    return [best,min(best-ranked[1]['score'] if len(ranked)>1 else 20.,20.),float(result['known']),
            literal or 0.,best-literal if literal is not None else 0.,0.]


def logistic(x,y):
    x=np.asarray(x);y=np.asarray(y,dtype=float)
    mean=x.mean(0);scale=np.maximum(x.std(0),.05)
    design=np.column_stack([np.ones(len(x)),(x-mean)/scale])
    w=np.zeros(design.shape[1]);ridge=np.eye(len(w))*.01;ridge[0,0]=1e-6
    for _ in range(50):
        p=1/(1+np.exp(-np.clip(design@w,-40,40)))
        grad=design.T@(p-y)/len(y)+ridge@w
        h=(design.T*(p*(1-p)))@design/len(y)+ridge
        step=np.linalg.solve(h,grad);w-=step
        if np.linalg.norm(step)<1e-7:
            break
    return dict(means=mean.tolist(),scales=scale.tolist(),weights=w.tolist(),threshold=.5,
                margin_cap=20.,minimum_observed_length=3,minimum_candidate_length=3,preserve_known_words=False)


def decide(case,result,ranked,policy):
    if policy['threshold']>=1.:
        return None
    if result['apostropheEvidence']:
        return result['correction']
    return accept(policy,case['typed'],result,ranked)


def predict(cases,results,rankings,policy):
    return [decide(c,r,h,policy) for c,r,h in zip(cases,results,rankings)]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--source',type=Path,default=PROJECT/'build/english-iteration-2026-10-20/cases.jsonl')
    p.add_argument('--count',type=int,default=2400)
    p.add_argument('--output',type=Path,default=PROJECT/'build/touch-eval-public/synthetic')
    p.add_argument('--protocol',type=Path,default=PROJECT/'config/english_iteration/2026-10-22/public-touch/protocol.json')
    p.add_argument('--frozen',type=Path,default=PROJECT/'config/english_iteration/2026-10-22/public-touch/synthetic-frozen.json')
    a=p.parse_args()
    if a.frozen.exists():
        raise ValueError('Already frozen; use another output for a new experiment')
    a.output.mkdir(parents=True,exist_ok=True)
    cases,model=synthetic(a.source,a.count)
    for key in model['keys']:
        key.pop('samples',None)
    model['provenance']=dict(source='Synthetic coordinate noise only',real_taps_used=False,
                             text_source='Existing Aalto TRAIN prompt groups',text_source_sha256=digest(a.source),
                             noise='sigma uniform choice .12/.18/.24/.30 key units; trial bias N(0,.07); 2% uniform broad-noise component')
    (a.output/'cases.jsonl').write_text(''.join(json.dumps(c)+'\n' for c in cases))
    requests=[dict(left=c['left'],typed=c['typed'],spelling_limit=4096) for c in cases]
    results=probe(a.probe,requests)
    dump(a.output/'native-cache.json',results)
    indices={s:[i for i,c in enumerate(cases) if c['split']==s] for s in ['fit','calibration','selection']}
    attempts=[]
    for weight in [None,.125,.25,.5,1.,2.]:
        rankings=[rerank(c,r,model,weight) for c,r in zip(cases,results)]
        fit_ids=[i for i in indices['fit'] if rankings[i] and not rankings[i][0]['completedChars'] and not results[i]['apostropheEvidence']]
        x=[features(results[i],rankings[i]) for i in fit_ids]
        y=[rankings[i][0]['word'].lower()==cases[i]['target'] and cases[i]['typed']!=cases[i]['target'] for i in fit_ids]
        policy=logistic(x,y)
        cal=indices['calibration']
        cal_cases=[cases[i] for i in cal];cal_results=[results[i] for i in cal];cal_rankings=[rankings[i] for i in cal]
        cutoffs=[]
        for threshold in [*np.arange(.05,1.,.025),.99,.995,.999,1.]:
            policy['threshold']=float(threshold)
            cutoffs.append((metrics(cal_cases,predict(cal_cases,cal_results,cal_rankings,policy))['f05'],float(threshold)))
        _,policy['threshold']=max(cutoffs)
        sel=indices['selection']
        report=dict(weight=weight,policy=policy,
                    calibration=metrics(cal_cases,predict(cal_cases,cal_results,cal_rankings,policy)),
                    selection=metrics([cases[i] for i in sel],predict([cases[i] for i in sel],[results[i] for i in sel],[rankings[i] for i in sel],policy)))
        attempts.append(report)
        print(json.dumps(dict(weight=weight,selection=report['selection'])),flush=True)
    text=attempts[0]
    touch=max(attempts[1:],key=lambda r:(r['selection']['f05'],-r['weight']))
    model['weight']=touch['weight']
    frozen=dict(schema='synthetic-only-touch-experiment-v2',production_approved=False,
                real_evaluation_read=False,training_source='synthetic only',
                protocol_sha256=digest(a.protocol),source_sha256=digest(a.source),
                synthetic_cases_sha256=digest(a.output/'cases.jsonl'),
                native_cache_sha256=digest(a.output/'native-cache.json'),probe_sha256=digest(a.probe),
                lm_sha256=digest(PROJECT/'app/src/main/assets/language_model/model.cng'),
                production_policy_sha256=digest(ENGINE/'english_acceptance.json'),
                script_sha256=digest(Path(__file__)),split_counts={s:len(v) for s,v in indices.items()},
                ranking='Touch replaces fixed same-length substitution penalties; unsupported paths retain existing scores',
                attempts=attempts,text_control=text,touch=touch,touch_model=model,
                limitations=['Synthetic noise prevalence is assumed, not natural mobile usage',
                             'Single/same-length substitutions only; insertion/deletion/swap channel remains text-based',
                             'Word and context source is older TRAIN data; this is not training a new language model'])
    dump(a.frozen,frozen)


if __name__=='__main__':
    main()
