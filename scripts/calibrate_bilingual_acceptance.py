#!/usr/bin/env python3
"""Second gate iteration: group-separated fit/calibration, fixed dev guard."""
import argparse
import collections
import copy
import json
import math
from pathlib import Path

import numpy as np

import autocorrect_eval_v2 as core
from build_independent_autocorrect_eval import stable
from experiment_bilingual_acceptance import extract,predictions,fit_column
from experiment_clean_acceptance import load_features,ENGINE
from experiment_conditional_english_scores import metrics,eligible,quality

ROOT=Path(__file__).resolve().parents[1]


def fit_full(policy,x,y,weights,anchor_mask,column,learning_rate):
    """Fine-tune the tiny FFN, anchored to its original legacy decisions."""
    x=(x[:,:len(policy['means'])]-policy['means'])/policy['scales']
    params={k:np.array(policy[k],dtype=float) for k in ('w1','b1','w2','b2')}
    original={k:v.copy() for k,v in params.items()}
    before=np.maximum(x@params['w1'].T+params['b1'],0)@params['w2']+params['b2']
    anchor=weights*anchor_mask;anchor/=max(anchor.sum(),1e-12)
    weights=weights/weights.sum()
    first={k:np.zeros_like(v) for k,v in params.items()};second={k:np.zeros_like(v) for k,v in params.items()}
    checkpoints={}
    for epoch in range(1,101):
        hidden=x@params['w1'].T+params['b1'];active=np.maximum(hidden,0)
        logits=active@params['w2']+params['b2']
        prob=1/(1+np.exp(-np.clip(logits,-50,50)))
        dz=weights*(prob-y)+2.*anchor*(logits-before)
        dh=dz[:,None]*params['w2']*(hidden>0)
        gradients=dict(w1=dh.T@x,b1=dh.sum(0),w2=active.T@dz,b2=dz.sum())
        for key in params:
            gradient=gradients[key]+.002*(params[key]-original[key])
            first[key]=.9*first[key]+.1*gradient;second[key]=.999*second[key]+.001*gradient*gradient
            params[key]-=learning_rate*(first[key]/(1-.9**epoch))/(np.sqrt(second[key]/(1-.999**epoch))+1e-8)
        if epoch in (30,100):
            result=copy.deepcopy(policy)
            result.update({k:v.tolist() for k,v in params.items()})
            checkpoints[epoch]=result
    return checkpoints


def calibrate(cases,parent_outputs,policy,x,allowed,words,rest,features):
    """Exact calibration breakpoints under per-stratum parent harm budgets."""
    z=features.predict_logits(features.parameters(policy),(x[:,:len(policy['means'])]-policy['means'])/policy['scales'])
    kinds=sorted({c['source_kind'] for c in cases})
    budgets={k:core.metrics([c for c in cases if c['source_kind']==k],
        [o for c,o in zip(cases,parent_outputs) if c['source_kind']==k]) for k in kinds}
    counts={k:collections.Counter(tp=0,fp=0,clean_changes=0) for k in kinds}
    variable=[]
    def add(index,word):
        c=cases[index];target=c['target'];bucket=counts[c['source_kind']]
        changed=bool(word) and word.casefold()!=c['typed'].casefold()
        typo=c['typed'].casefold()!=target.casefold()
        correct=changed and typo and word.casefold()==target.casefold()
        bucket['tp']+=correct;bucket['fp']+=changed and not correct
        bucket['clean_changes']+=changed and not typo
    for i,(ok,word,restore) in enumerate(zip(allowed,words,rest)):
        if restore:add(i,restore)
        elif ok and word:variable.append(i)
    variable.sort(key=lambda i:-z[i])
    best=None;at=0
    def consider(cutoff):
        nonlocal best
        if any(counts[k]['fp']>budgets[k]['fp'] or counts[k]['clean_changes']>budgets[k]['clean_changes'] for k in kinds):return
        score=sum(counts[k]['tp']/max(1,budgets[k]['typos']) for k in kinds if budgets[k]['typos'])/sum(budgets[k]['typos']>0 for k in kinds)
        key=(score,-sum(c['fp'] for c in counts.values()),cutoff)
        if best is None or key>best[0]:best=(key,cutoff,{k:dict(v) for k,v in counts.items()})
    consider(float(max(z)+1))
    while at<len(variable):
        current=float(z[variable[at]])
        while at<len(variable) and float(z[variable[at]])==current:
            add(variable[at],words[variable[at]]);at+=1
        next_value=float(z[variable[at]]) if at<len(variable) else current-1
        consider((current+next_value)/2)
    if best is None:return None,dict(feasible=False,budgets=budgets)
    cutoff=best[1]
    threshold=1/(1+math.exp(-max(-700,min(700,cutoff))))
    if threshold<=0 or threshold>=1:return None,dict(feasible=False,reason='Unrepresentable sigmoid threshold')
    result=copy.deepcopy(policy);result['threshold']=threshold
    return result,dict(feasible=True,cutoff_logit=cutoff,threshold=threshold,budgets=budgets,selected_counts=best[2])


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite',type=Path,required=True)
    p.add_argument('--training',type=Path,required=True)
    p.add_argument('--cache',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--full-network',action='store_true')
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    parent=json.loads((ENGINE/'english_acceptance.json').read_text())
    train=core.rows(args.training/'cases.jsonl');dev=core.rows(args.suite/'development.jsonl')
    outputs=core.rows(args.cache/'train-outputs.jsonl');devout=core.rows(args.cache/'development-outputs.jsonl')
    group=lambda c:str(c.get('group','lexical:'+c['target'].casefold()))
    fit=np.array([int(stable('gate-cal:'+group(c))[:8],16)%4!=0 for c in train])
    assert not {group(c) for c,m in zip(train,fit) if m}&{group(c) for c,m in zip(train,fit) if not m}
    protocol=dict(schema='calibrated-bilingual-acceptance-v1',script_sha256=core.digest(__file__),
        training_sha256=core.digest(args.training/'cases.jsonl'),development_sha256=core.digest(args.suite/'development.jsonl'),
        cache_protocol_sha256=core.digest(args.cache/'protocol.json'),fit_rows=int(fit.sum()),calibration_rows=int((~fit).sum()),
        fit_calibration_group_overlap=0,calibration='Exact observed-score cutoffs; parent FP and clean-harm budgets per legacy/mixed stratum',
        initialization='Original accepted policy, not weights from the prior all-fit pilot',confirmation_used=False,
        families=['Matched 22-input data-only NN','23-input English-mass NN'] if args.full_network else ['24 raw-score weights','24 new English-mass weights'],
        fit_mode='Full tiny FFN, legacy logit anchoring1.0' if args.full_network else '24 weights only, legacy anchoring0.2',
        lr=[.0001,.0003,.001] if args.full_network else [.003,.01],epochs=[30,100],
        selection='Same supplemental development Pareto guard; no extra clean damage or wrong changes, neither typo panel loses TP; max mean recall')
    core.write_json(args.output/'protocol.json',protocol)
    features=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
    tx,ta,tw,tr=extract(train,outputs,parent,features);dx,da,dw,dr=extract(dev,devout,parent,features)
    labels=np.array([float(c['typed'].casefold()!=c['target'].casefold() and (w or '').casefold()==c['target'].casefold()) for c,w in zip(train,tw)])
    kinds=collections.Counter(c['source_kind'] for c,m in zip(train,fit) if m)
    weights=np.array([1/kinds[c['source_kind']] for c in train])*(ta & np.array([not bool(r) for r in tr]))
    anchor=np.array([c['source_kind']=='legacy_anchor' for c in train],dtype=float)
    baseline=metrics(dev,devout)
    report=dict(protocol=protocol,models={'parent':dict(metrics=baseline,eligible=True,quality=quality(baseline),epochs=0)})
    ci=np.flatnonzero(~fit);calcases=[train[i] for i in ci];calouts=[outputs[i] for i in ci]
    for family in ('data_only','english_mass'):
        initial=copy.deepcopy(parent);column=0
        if family=='english_mass':
            initial['means'].append(0.);initial['scales'].append(max(float(tx[fit,22].std()),1.))
            for row in initial['w1']:row.append(0.)
            column=22
        for lr in ((.0001,.0003,.001) if args.full_network else (.003,.01)):
            fitter=fit_full if args.full_network else fit_column
            fitted=fitter(initial,tx[fit],labels[fit],weights[fit],anchor[fit],column,lr)
            for epochs,policy in fitted.items():
                name=f'{family}-lr{lr:g}-e{epochs}'
                policy,cal=calibrate(calcases,calouts,policy,tx[ci],ta[ci],[tw[i] for i in ci],[tr[i] for i in ci],features)
                if policy is None:
                    report['models'][name]=dict(eligible=False,calibration=cal);continue
                fixes=predictions(policy,dx,da,dw,dr,features)
                current=[dict(o,correction=f) for o,f in zip(devout,fixes)]
                values=metrics(dev,current)
                result=dict(metrics=values,eligible=eligible(values,baseline),quality=quality(values),epochs=epochs,calibration=cal)
                report['models'][name]=result
                core.write_json(args.output/(name+'-policy.json'),policy)
                core.jsonl(args.output/(name+'-outputs.jsonl'),current)
                print(name,'eligible',result['eligible'],'quality',round(result['quality'],6),
                    [(k,v['tp'],v['fp'],v['clean_changes']) for k,v in values.items()],flush=True)
    choices=[(n,v) for n,v in report['models'].items() if v['eligible']]
    report['winner']=max(choices,key=lambda item:(item[1]['quality'],item[0]=='parent',-item[1]['epochs']))[0]
    core.write_json(args.output/'development-report.json',report)
    print('DEVELOPMENT WINNER:',report['winner'],flush=True)


if __name__=='__main__':main()
