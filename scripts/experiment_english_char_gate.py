#!/usr/bin/env python3
"""TRAIN-only character plausibility encoder plus frozen-parent acceptance adapter.

Lexical counts use unique TRAIN spellings rather than token frequency. Append
four typed-word and four candidate-word character evidence values; fit only the
new 24x8 NN inputs. Parent weights and cutoff initially remain unchanged.
"""
import argparse,collections,copy,json,math
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE,load_features
from experiment_bilingual_acceptance import extract
from experiment_conditional_english_scores import metrics,eligible
ROOT=Path(__file__).resolve().parents[1]
BUCKETS=8192

def hashed(s):
    v=2166136261
    for c in s.encode('utf-8'):v=((v^c)*16777619)&0xffffffff
    return v%BUCKETS

def indices(word,n):
    w='^'+word.replace('’',"'").lower()+'$'
    return [hashed(w[i:i+n]) for i in range(max(0,len(w)-n+1))]

def build_encoder(cases,mask,dictionary):
    labels={}
    for c,fit in zip(cases,mask):
        if not fit:continue
        for word,valid in [(c['target'],True),(c['typed'],c['typed'].casefold()==c['target'].casefold() or c['typed'].casefold() in dictionary)]:
            w=word.replace('’',"'").lower()
            if w and w.isascii() and len(w)<=32:labels[w]=labels.get(w,False) or valid
    counts=np.ones((2,4,BUCKETS))*.5
    for w,y in labels.items():
        for j,n in enumerate(range(2,6)):
            ix=indices(w,n)
            if ix:np.add.at(counts[int(y),j],ix,1./len(ix))
    probabilities=counts/counts.sum(2,keepdims=True)
    weights=np.log(probabilities[1]/probabilities[0])
    return dict(schema='english-char-plausibility-v1',buckets=BUCKETS,hash='FNV1a32',ngrams=[2,3,4,5],boundary=['^','$'],weights=weights.tolist(),unique_fit_words=len(labels),positive_words=sum(labels.values()),model_sha256=core.digest(ROOT/'app/src/main/assets/language_model/model.cng'))

def encoding(word,encoder):
    return [sum(encoder['weights'][j][i] for i in ix)/len(ix) if ix else 0. for j,n in enumerate(range(2,6)) for ix in [indices(word,n)]]

def append(raw,cases,words,encoder):
    cache={w:encoding(w,encoder) for w in {c['typed'] for c in cases}|{w or '' for w in words}}
    return np.column_stack([raw[:,:22],np.array([cache[c['typed']]+cache[w or ''] for c,w in zip(cases,words)])])

def logits(p,x):return np.maximum(((x-p['means'])/p['scales'])@np.array(p['w1']).T+p['b1'],0)@p['w2']+p['b2']

def fit_columns(p,x,y,weights,lr):
    xx=(x-p['means'])/p['scales'];h=xx[:,:22]@np.array(p['w1'])[:,:22].T+p['b1'];new=xx[:,22:]
    w2=np.array(p['w2']);base=np.maximum(h,0)@w2+p['b2'];delta=np.zeros((24,new.shape[1]));m=np.zeros_like(delta);v=np.zeros_like(delta)
    weights=weights/weights.sum()
    for epoch in range(1,301):
        act=h+new@delta.T;z=np.maximum(act,0)@w2+p['b2'];prob=1/(1+np.exp(-np.clip(z,-40,40)))
        dz=weights*((prob-y)+.1*(z-base))
        gradient=(dz[:,None]*(act>0)*w2).T@new+.002*delta
        m=.9*m+.1*gradient;v=.999*v+.001*gradient*gradient;delta-=lr*(m/(1-.9**epoch))/(np.sqrt(v/(1-.999**epoch))+1e-8)
        if epoch in (30,100,300):
            q=copy.deepcopy(p)
            for i in range(24):q['w1'][i][22:]=delta[i].tolist()
            yield epoch,q

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);args=ap.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=False)
    src=ROOT/'build/short-nn-v2-20261029/input-clean-data';cases=core.rows(src/'cases.jsonl');data=np.load(src/'features.npz');decoded=json.loads((src/'decisions.json').read_text());words=decoded['words'];rest=decoded['restorations'];raw=data['raw'][:,:22];allowed=data['eligible'];labels=data['labels']
    parent=json.loads((ENGINE/'english_acceptance.json').read_text());f=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
    blocked=set()
    for suite in (ROOT/'build/autocorrect-eval-v4/frozen',ROOT/'build/independent-autocorrect-eval-20261030/frozen-v2',ROOT/'build/bilingual-residual-20261030/fresh-eval',ROOT/'build/english-char-gate-20261030/fresh-eval'):
        files=[suite/'cases.jsonl'] if (suite/'cases.jsonl').exists() else [suite/'development.jsonl',suite/'confirmation.jsonl']
        for file in files:
            for c in core.rows(file):blocked.add((c['left'],c['typed'].casefold()))
    fit=np.array([c['fit_split']=='fit' and (c['left'],c['typed'].casefold()) not in blocked for c in cases])
    encoder=build_encoder(cases,fit,set((ENGINE/'english_valid_words.txt').read_text().splitlines()));core.write_json(out/'char-model.json',encoder)
    x=append(raw,cases,words,encoder);initial=copy.deepcopy(parent);initial['means']+=x[fit,22:].mean(0).tolist();initial['scales']+=np.maximum(x[fit,22:].std(0),.05).tolist()
    for row in initial['w1']:row.extend([0.]*8)
    fm=fit&allowed&np.array([not bool(v) for v in rest]);counts=collections.Counter(c['source'] for c,m in zip(cases,fm) if m)
    masses={'real':1.,'generated':.25,'blank':.1,'gboard_word':.1,'gboard_context':.16,'gboard_touch':.19,'clean_dictionary':.25,'clean_dropout':.25,'clean_short':.1,'generated_short':.25,'authentic_short_clean':.5,'authentic_short_typo':.5,'title_abbreviation_clean':.25}
    weights=np.array([(.125 if c.get('noise') else masses[c['source']])/max(1,counts[c['source']]) for c in cases])
    # The unchanged frozen suite is exposed development by repository policy.
    dev=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl')];oo=core.rows(ROOT/'build/bilingual-residual-20261030/final-objective/baseline-outputs.jsonl');dx,da,dw,dr=extract(dev,oo,parent,f);dx=append(dx,dev,dw,encoder)
    baseline=metrics(dev,oo);panels=['historical_full_mobile','real_touch_observed','authored_hard'];score=lambda m:sum(m[p]['f05'] for p in panels)/3
    assert abs(score(baseline)-.8736146656392934)<1e-12
    report={'baseline':baseline,'baseline_score':score(baseline),'models':{},'fit_rows':int(fm.sum()),'fit_encoder_words':encoder['unique_fit_words'],'confirmation_used':False}
    best=(score(baseline),'parent')
    for lr in (.003,.01,.03):
        for epoch,p in fit_columns(initial,x[fm],labels[fm],weights[fm],lr):
            z=logits(p,dx);cut=math.log(p['threshold']/(1-p['threshold']));pred=[dict(o,correction=r or (w if a and zz>=cut else None)) for o,zz,a,w,r in zip(oo,z,da,dw,dr)];met=metrics(dev,pred);s=score(met);name=f'lr{lr}-e{epoch}'
            report['models'][name]={'score':s,'metrics':met};core.write_json(out/(name+'.json'),p)
            print(name,s,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in met.items()],flush=True)
            if s>best[0]:best=(s,name)
    report['winner']=best;core.write_json(out/'report.json',report)
if __name__=='__main__':main()
