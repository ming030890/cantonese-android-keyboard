#!/usr/bin/env python3
"""Fit a context-specific acceptance NN on TRAIN only; original English policy frozen."""
import argparse,collections,copy,json,math,os
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from experiment_bilingual_acceptance import extract
from experiment_clean_acceptance import load_features,ENGINE
from experiment_conditional_english_scores import infer,metrics,eligible,quality
from build_independent_autocorrect_eval import HAN,stable
ROOT=Path(__file__).resolve().parents[1]

def extra(c):
    w=c['typed']
    return [float(w[:1].isupper()),float(w.isupper()),float(any(ch.isupper() for ch in w[1:]))]

def predict(p,x):
    h=np.maximum(((x-p['means'])/p['scales'])@np.array(p['w1']).T+p['b1'],0)
    return h@p['w2']+p['b2']

def fit(p,x,y,weights,lr):
    x=(x-p['means'])/p['scales'];weights=weights/weights.sum()
    a={k:np.array(p[k],float) for k in ('w1','b1','w2','b2')};orig=copy.deepcopy(a)
    m={k:np.zeros_like(v) for k,v in a.items()};v=copy.deepcopy(m)
    for epoch in range(1,401):
        h=x@a['w1'].T+a['b1'];act=np.maximum(h,0);z=act@a['w2']+a['b2']
        dz=weights*(1/(1+np.exp(-np.clip(z,-40,40)))-y)
        dh=dz[:,None]*a['w2']*(h>0)
        grads=dict(w1=dh.T@x,b1=dh.sum(0),w2=act.T@dz,b2=dz.sum())
        grads['w1'][:,p.get('disabled_input_indices',[])]=0.
        for k,g in grads.items():
            g=g+.0002*(a[k]-orig[k]);m[k]=.9*m[k]+.1*g;v[k]=.999*v[k]+.001*g*g
            a[k]-=lr*(m[k]/(1-.9**epoch))/(np.sqrt(v[k]/(1-.999**epoch))+1e-8)
        if epoch in (50,100,200,400):
            q=copy.deepcopy(p);q.update({k:t.tolist() for k,t in a.items()});yield epoch,q

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--training',type=Path,required=True);ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--cache',type=Path)
    args=ap.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=False)
    parent=json.loads((ENGINE/'english_acceptance.json').read_text());f=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
    probe=ROOT/'build/bilingual-acceptance-20261030/column-study/target/release/ngram_probe'
    suite=ROOT/'build/independent-autocorrect-eval-20261030/frozen-v2'
    train=core.rows(args.training/'cases.jsonl');dev=core.rows(suite/'development.jsonl')
    allx=[]
    for name,cases in [('train',train),('development',dev)]:
        oo=core.rows(args.cache/(name+'-outputs.jsonl')) if args.cache else infer(probe,ROOT/'app/src/main/assets/language_model/model.cng',cases,'candidate',0.)
        core.jsonl(out/(name+'-outputs.jsonl'),oo)
        x,a,w,r=extract(cases,oo,parent,f);x=np.column_stack([x,np.array([extra(c) for c in cases])]);allx.append((x,a,w,r,oo))
    tx,ta,tw,tr,to=allx[0];dx,da,dw,dr,do=allx[1]
    # Source/document groups stay together. Legacy is retained only for English invariance diagnostics.
    fitmask=np.array([c['source_kind'].startswith('mixed_') and int(stable('mixed-case:'+c['group'])[:8],16)%4!=0 for c in train])
    calmask=np.array([c['source_kind'].startswith('mixed_') for c in train])&~fitmask
    y=np.array([float(c['typed'].casefold()!=c['target'].casefold() and (w or '').casefold()==c['target'].casefold()) for c,w in zip(train,tw)])
    counts=collections.Counter(c['source_kind'] for c,m in zip(train,fitmask) if m)
    weights=np.array([1/counts.get(c['source_kind'],1) for c in train])*(ta & np.array([not bool(r) for r in tr]))
    initial=copy.deepcopy(parent);initial['means']+= [0.]*4;initial['scales'] += [max(float(tx[fitmask,22].std()),1.),1.,1.,1.]
    for row in initial['w1']:row.extend([0.]*4)
    mixed=np.array([bool(HAN.search(c['left'])) for c in dev]);base=metrics(dev,do)
    report={'baseline':base,'training_sources':dict(collections.Counter(c['source'] for c,m in zip(train,fitmask) if m)), 'models':{}}
    best=None
    for lr in (.001,.003,.01):
        for epoch,p in fit(initial,tx[fitmask],y[fitmask],weights[fitmask],lr):
            z=predict(p,tx[calmask]);ci=np.flatnonzero(calmask)
            # Select a cutoff on TRAIN calibration only, preserving parent error budget.
            budget=core.metrics([train[i] for i in ci],[to[i] for i in ci]);choices=[]
            for cut in np.unique(np.r_[math.log(parent['threshold']/(1-parent['threshold'])),np.quantile(z,np.linspace(0,1,301))]):
                pred=[{'correction':tr[i] or (tw[i] if ta[i] and zz>=cut else None)} for i,zz in zip(ci,z)]
                met=core.metrics([train[i] for i in ci],pred)
                if met['fp']<=budget['fp'] and met['clean_changes']<=budget['clean_changes']:choices.append((met['tp'],-met['fp'],float(cut)))
            if not choices:continue
            cut=max(choices)[2];p['threshold']=1/(1+math.exp(-min(30,max(-30,cut))))
            dz=predict(p,dx)
            oo=[dict(o,correction=(r or (w if a and zz>=cut else None)) if mix else o.get('correction')) for o,a,w,r,zz,mix in zip(do,da,dw,dr,dz,mixed)]
            met=metrics(dev,oo);ok=eligible(met,base);name=f'lr{lr}-e{epoch}'
            report['models'][name]=dict(metrics=met,eligible=ok,cutoff=cut)
            core.write_json(out/(name+'.json'),p)
            print(name,ok,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in met.items()],flush=True)
            if ok and (best is None or quality(met)>best[0]):best=(quality(met),name)
    report['winner']=best;core.write_json(out/'report.json',report)
if __name__=='__main__':main()
