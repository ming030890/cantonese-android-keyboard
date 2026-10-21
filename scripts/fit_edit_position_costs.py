#!/usr/bin/env python3
"""Small edit-cost experiment fitted on recorded TRAIN errors, not fake positions.

Candidate generation and the accepted six-feature rule stay fixed. Exploratory
scores reconstruct LM evidence from the frozen candidate scores. Native reruns
are required before any deployment because float32 subtraction loses low bits.
"""
import argparse
from collections import Counter
import json
from pathlib import Path

import numpy as np

from audit_candidate_pipeline import digest, penalty
from benchmark_single_edit import POSITIONS
from analyze_single_edit_root_cause import metrics, restoration
from iterate_english_policy import split, hashed, f05
from iterate_unambiguous_neighbors import load_real, unique_neighbor

NAMES=['neighbor_replacement','other_replacement','swap_or_alternation','extra_character',
       'missing_character','additional_same_length_edit','additional_length_change_edit','first_character_change']
BASE=np.array([1.,2.5,1.,4.5,4.5,3.,4.5,0.])


def cost_features(typed, row):
    word=row['word'].lower()
    f=np.zeros(8)
    if row['completedChars'] or ' ' in word:
        return f
    edits=row['edits']
    if len(typed)==len(word):
        differences=[(a,b) for a,b in zip(typed,word) if a!=b]
        if len(differences)==edits:
            for a,b in differences:
                adjacent=a in POSITIONS and b in POSITIONS and all(abs(x-y)<=1 for x,y in zip(POSITIONS[a],POSITIONS[b]))
                f[0 if adjacent else 1]+=1
        else:
            f[2]=edits
            f[1]=sum(ch not in typed for ch in word)
        f[5]=max(0,edits-1)
    else:
        assert abs(len(typed)-len(word))==1
        f[3 if len(typed)>len(word) else 4]=1
        f[6]=max(0,edits-1)
    f[7]=bool(typed and word and typed[0]!=word[0])
    return f


def prepare(cases, outputs, policy, apostrophe):
    features=[];lm=[];groups=[]
    for c,o in zip(cases,outputs):
        surfaces={e['word'] for e in o.get('apostropheEvidence',[])}
        rows=sorted((r for r in o['candidates'] if r['word'] not in surfaces),key=lambda r:(-r['score'],r['word']))
        a=len(lm)
        for row in rows:
            f=cost_features(c['typed'].lower(),row)
            old=float(f@BASE)
            if not row['completedChars'] and ' ' not in row['word']:
                assert old==penalty(c['typed'].lower(),row['word'].lower(),row['edits'])
            features.append(f);lm.append(row['score']+old)
        lexical={word:i for i,word in enumerate(sorted(r['word'] for r in rows))}
        groups.append(dict(case=c,output=o,rows=rows,a=a,b=len(lm),apostrophe=apostrophe,
                           restored=restoration(apostrophe,o),
                           lexical=np.array([lexical[r['word']] for r in rows])))
    return np.array(features),np.array(lm),groups


def fit(features,lm,groups,family,reg):
    training=[]
    for g in groups:
        c=g['case']
        if split(c['group'])!='train' or int(hashed('calibration:'+c['group'])[:8],16)%5==0:
            continue
        if c['typed'].lower()==c['target'] or not any(r['word'].lower()==c['target'] for r in g['rows']):
            continue
        training.append(g)
    ids=np.concatenate([np.arange(g['a'],g['b']) for g in training])
    starts=np.cumsum([0]+[g['b']-g['a'] for g in training[:-1]])
    lengths=np.array([g['b']-g['a'] for g in training])
    gold=np.array([r['word'].lower()==g['case']['target'] for g in training for r in g['rows']],dtype=float)
    if family=='scale':
        design=(features[ids]@BASE)[:,None];parameters=np.ones(1);fixed=np.zeros(len(ids));prior=parameters.copy()
    else:
        active=list(range(5)) + ([7] if family=='operations_and_first' else [])
        scale=np.maximum(BASE[active],1.)
        design=features[ids][:,active]*scale
        fixed=features[ids]@BASE-design@np.array([1.]*5+([0.] if len(active)==6 else []))
        parameters=np.array([1.]*5+([0.] if len(active)==6 else []));prior=parameters.copy()
    evidence=lm[ids]-fixed
    for epoch in range(600):
        score=evidence-design@parameters
        score-=np.repeat(np.maximum.reduceat(score,starts),lengths)
        probs=np.exp(score)
        probs/=np.repeat(np.add.reduceat(probs,starts),lengths)
        gradient=design.T@(gold-probs)/len(training)+reg*(parameters-prior)
        parameters=np.maximum(0.,parameters-.075/(1+epoch/300)*gradient)
    if family=='scale':
        result=BASE*parameters[0]
    else:
        result=BASE.copy();result[active]=parameters*scale
    return result,len(training)


def predict(features,lm,groups,costs,policy,threshold=None):
    rounded=costs.astype(np.float32)
    cost=np.zeros(len(lm),dtype=np.float32)
    for i in range(8):
        cost+=features[:,i].astype(np.float32)*rounded[i]
    score=(lm.astype(np.float32)-cost).astype(np.float32)
    logits=[];best=[];allowed=[];restores=[]
    for g in groups:
        c,o=g['case'],g['output']
        order=np.lexsort((g['lexical'],-score[g['a']:g['b']]))
        if not len(order):logits.append(-np.inf);best.append(None);allowed.append(False);restores.append(None);continue
        b=g['rows'][order[0]];s=float(score[g['a']+order[0]])
        evidence=[]
        for e in o.get('apostropheEvidence',[]):
            values=list(e['features'])
            values[1]=float(np.float32(e['score'])-np.float32(s))
            evidence.append(dict(e,features=values))
        restores.append(restoration(g['apostrophe'],dict(apostropheEvidence=evidence)))
        margin=min(s-float(score[g['a']+order[1]]),20.) if len(order)>1 else 20.
        literal=o['literalScore']
        values=[s,margin,int(o['known']),literal or 0.,s-literal if literal is not None else 0.,
                int("'" in c['typed'] or '’' in c['typed'])]
        logit=policy['weights'][0]+sum(w*(v-m)/z for w,v,m,z in zip(policy['weights'][1:],values,policy['means'],policy['scales']))
        logits.append(logit);best.append(b['word'])
        allowed.append(len(c['typed'])>=3 and len(b['word'])>=3 and not b['completedChars']
                       and not(o['known'] and literal is None))
    cutoff=np.log(policy['threshold']/(1-policy['threshold'])) if threshold is None else threshold
    fixes=[r or (b if ok and z>=cutoff else None) for r,b,ok,z in zip(restores,best,allowed,logits)]
    return fixes,np.array(logits),best,np.array(allowed),restores


def calibrate(groups,logits,best,allowed,restores):
    ids=[i for i,g in enumerate(groups) if split(g['case']['group'])=='train'
         and int(hashed('calibration:'+g['case']['group'])[:8],16)%5==0]
    typos=sum(groups[i]['case']['typed'].lower()!=groups[i]['case']['target'] for i in ids)
    tp=fp=0
    for i in ids:
        g=groups[i];fix=restores[i];c=g['case']
        if fix and fix.lower()!=c['typed'].lower():
            correct=fix.lower()==c['target'];tp+=correct;fp+=not correct
    order=sorted((i for i in ids if allowed[i] and not restores[i]),key=lambda i:-logits[i])
    options=[(f05(tp,fp,typos-tp),float('inf'))]
    for pos,i in enumerate(order):
        c=groups[i]['case'];correct=best[i].lower()==c['target'] and c['typed'].lower()!=c['target']
        tp+=correct;fp+=not correct
        if pos+1<len(order) and logits[order[pos+1]]==logits[i]:continue
        cutoff=float(logits[i])
        if pos+1<len(order):cutoff=(cutoff+float(logits[order[pos+1]]))/2
        options.append((f05(tp,fp,typos-tp),cutoff))
    return max(options)[1]


def position_counts(cases):
    result={}
    for lo,hi in ((1,2),(3,3),(4,5),(6,7),(8,32)):
        n=first=0;uniform=0.
        for c in cases:
            if split(c['group'])!='train':continue
            a,b=c['typed'].lower(),c['target'].lower()
            if len(a)!=len(b) or not lo<=len(a)<=hi or not a.isascii() or not b.isascii() or not a.isalpha() or not b.isalpha():continue
            diffs=[i for i,(x,y) in enumerate(zip(a,b)) if x!=y]
            if len(diffs)!=1:continue
            n+=1;first+=diffs[0]==0;uniform+=1/len(a)
        result[f'{lo}-{hi}']=dict(single_replacements=n,first_character=first,uniform_first_expected=uniform)
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--evaluate',action='store_true')
    p.add_argument('--frozen-selection',type=Path)
    p.add_argument('--source',type=Path,default=Path('/Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt'))
    args=p.parse_args()
    if args.evaluate and not args.frozen_selection:p.error('freeze selection before evaluation')
    project=Path(__file__).resolve().parents[1]
    policy_file=project.parent/'QwenASR/crates/cantonese-ngram/src/english_acceptance.json'
    apostrophe_file=policy_file.with_name('english_apostrophe_acceptance.json')
    policy=json.loads(policy_file.read_text());apostrophe=json.loads(apostrophe_file.read_text())
    cases,outputs,cache_file,cases_file,refs_file=load_real(project)
    print('Preparing frozen real candidate pools',flush=True)
    features,lm,groups=prepare(cases,outputs,policy,apostrophe)
    baseline,_,_,_,_=predict(features,lm,groups,BASE,policy)
    # Check reconstructed scores reproduce the accepted current rule exactly.
    expected_file=project/'build/acceptance-refit-2026-10-21/accepted-native-parity.predictions.json'
    accepted=json.loads(expected_file.read_text())
    assert baseline==[o['correction'] for o in accepted['outputs']]
    # Separate primary panel; dictionaries are evaluation-only, never a runtime gate.
    gen_file=project/'build/acceptance-refit-2026-10-21/cases.jsonl'
    generated=[json.loads(line) for line in gen_file.open() if json.loads(line)['source']=='generated']
    gen_cache=project/'build/acceptance-refit-2026-10-21/generated-native.json'
    gen_outputs=json.loads(gen_cache.read_text())['outputs']
    lexicon={line.split()[0] for line in args.source.open() if len(line.split()[0])<=32}
    retained=[(c,o) for c,o in zip(generated,gen_outputs) if split(c['group']) in ('selection','evaluation')
              and (c['typed']==c['target'] or unique_neighbor(c['typed'],lexicon)==c['target'])]
    gc,go=map(list,zip(*retained));gx,gl,gg=prepare(gc,go,policy,apostrophe)
    gen_base,_,_,_,_=predict(gx,gl,gg,BASE,policy)
    def primary(fixes,partition,short=False):
        ids=[i for i,c in enumerate(gc) if split(c['group'])==partition and (not short or c['typed']!=c['target'] and 4<=len(c['typed'])<=5)]
        return metrics([gc[i] for i in ids],[fixes[i] for i in ids])
    def metric(fixes,partition):
        ids=[i for i,c in enumerate(cases) if split(c['group'])==partition]
        return metrics([cases[i] for i in ids],[fixes[i] for i in ids])
    trials=[]
    for family in ('scale','operations','operations_and_first'):
        for reg in (.01,.1):
            costs,n=fit(features,lm,groups,family,reg)
            fixes,logits,best,allowed,restores=predict(features,lm,groups,costs,policy)
            cutoff=calibrate(groups,logits,best,allowed,restores)
            calibrated=[r or (b if ok and z>=cutoff else None) for r,b,ok,z in zip(restores,best,allowed,logits)]
            for recalibrate,decisions in ((False,fixes),(True,calibrated)):
                gen_fix,_,_,_,_=predict(gx,gl,gg,costs,policy,cutoff if recalibrate else None)
                trial=dict(family=family,regularization=reg,costs=dict(zip(NAMES,costs.tolist())),fit_cases=n,
                           recalibrate_cutoff=recalibrate,cutoff_logit=cutoff if recalibrate else float(np.log(policy['threshold']/(1-policy['threshold']))),
                           selection=metric(decisions,'selection'),selection_primary=primary(gen_fix,'selection'),
                           selection_primary_4_5=primary(gen_fix,'selection',True))
                trials.append(trial)
                print(f'{family} reg={reg} recalibrate={recalibrate}: selection F0.5={trial["selection"]["f05"]:.6f} first_cost={costs[-1]:.4f}',flush=True)
    baseline_selection=metric(baseline,'selection')
    base_primary=primary(gen_base,'selection')
    eligible=[t for t in trials if t['selection']['f05']>baseline_selection['f05'] and t['selection_primary']['f05']>=base_primary['f05']]
    winner=max(eligible,key=lambda t:(t['selection']['f05'],t['family']=='scale',not t['recalibrate_cutoff'])) if eligible else None
    report=dict(scope='Recorded real TRAIN fit, TRAIN calibration and fixed selection. No synthetic position fitting. '
                'Six fitted cost models, each with frozen and recalibrated shared cutoff. No production changes.',
                position_counts=position_counts(cases),baseline_costs=dict(zip(NAMES,BASE.tolist())),
                baseline_selection=baseline_selection,baseline_selection_primary=base_primary,
                baseline_selection_primary_4_5=primary(gen_base,'selection',True),trials=trials,champion=winner,
                hashes={str(f):digest(f) for f in (Path(__file__),policy_file,apostrophe_file,cache_file,cases_file,refs_file,expected_file,args.source,gen_file,gen_cache)})
    if args.evaluate:
        frozen=json.loads(args.frozen_selection.read_text());assert frozen==report
        if winner:
            costs=np.array([winner['costs'][k] for k in NAMES])
            decisions,_,_,_,_=predict(features,lm,groups,costs,policy,winner['cutoff_logit'])
            gen_fix,_,_,_,_=predict(gx,gl,gg,costs,policy,winner['cutoff_logit'])
            report.update(evaluation=metric(decisions,'evaluation'),baseline_evaluation=metric(baseline,'evaluation'),
                          evaluation_primary=primary(gen_fix,'evaluation'),baseline_evaluation_primary=primary(gen_base,'evaluation'),
                          evaluation_primary_4_5=primary(gen_fix,'evaluation',True),baseline_evaluation_primary_4_5=primary(gen_base,'evaluation',True))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(baseline=baseline_selection,champion=winner),indent=2))


if __name__=='__main__':main()
