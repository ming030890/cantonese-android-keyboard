#!/usr/bin/env python3
"""Calibrate a few edit-count/type buckets, without extra inference.

Fit cutoffs on real fit/calibration labels. Separate selection cohorts check
precision. The repository's exposed objective compares already-frozen families;
it is explicitly development data, not independent generalization evidence.
"""
import argparse
import collections
import json
from pathlib import Path
import autocorrect_eval_v2 as core
import replay_context_revision as old
import experiment_short_acceptance as short


def describe(adapter, c, out):
    d=dict(adapter.describe(c['typed'],out));x,_,_=adapter.base.f.extract(c,out)
    d.update(edit_count=int(x[9]+x[19]),spelling_edits=int(x[9]),neighbors=int(x[11]),
             substitution=bool(x[11]+x[12]) and not bool(x[13]+x[14]+x[15]+x[19]))
    d['scope']=(d['length'] in (2,3) and d['eligible'] and bool(d['word']) and
        d['word'].casefold()!=c['typed'].casefold() and
        (not out['correction'] or out['correction'].casefold()==d['word'].casefold()))
    return d


def bucket(d,family):
    if family=='shared':return 'all'
    if family=='edit_count':return str(d['edit_count'])
    if family=='substitution':return str(d['substitution'])
    if family=='one_neighbor':return str(d['spelling_edits']==1 and d['neighbors']==1 and d['substitution'])
    raise ValueError(family)


def predict(d,rule):
    if rule is None or not d['scope']:return d['initial']
    threshold=rule['thresholds'].get(bucket(d,rule['family']),rule['default_threshold'])
    return old.surface(d['word'],d['typed']) if d['logit']>=threshold else d['typed']


def fit(cc,dd,family,threshold):
    groups=collections.defaultdict(list)
    for c,d in zip(cc,dd):
        if d['scope']:groups[bucket(d,family)].append((c,d))
    base=short.assess(cc,[d['initial'] for d in dd])['short']
    outside=[(c,d) for c,d in zip(cc,dd) if len(c['typed']) in (2,3) and not d['scope']]
    fixed=short.measure([c for c,d in outside],[d['initial'] for c,d in outside])
    budget=base['fp']-fixed['fp'];clean_budget=base['clean_changes']-fixed['clean_changes']
    # Exact Pareto DP: best TP at each FP/clean-damage count, no quality weights.
    states={(0,0):(0,{},0.)}
    for name,pairs in sorted(groups.items()):
        options=[];cutoffs=sorted({d['logit'] for c,d in pairs}|{threshold})
        cutoffs.append(max(cutoffs)+1.)  # Reject-all option, never a shipped constant.
        for cutoff in cutoffs:
            tp=fp=clean=0
            for c,d in pairs:
                if c['target'] is None or d['logit']<cutoff:continue
                correct=d['word'].casefold()==c['target'].casefold()
                tp+=correct;fp+=not correct;clean+=not correct and c['typed'].casefold()==c['target'].casefold()
            if fp<=budget and clean<=clean_budget:options.append((tp,fp,clean,cutoff))
        nxt={}
        for (fp0,cl0),(tp0,rules0,distance0) in states.items():
            for tp,fp,cl,cutoff in options:
                key=(fp0+fp,cl0+cl)
                if key[0]>budget or key[1]>clean_budget:continue
                val=(tp0+tp,dict(rules0,**{name:cutoff}),distance0+abs(cutoff-threshold))
                if key not in nxt or (val[0],-val[2])>(nxt[key][0],-nxt[key][2]):nxt[key]=val
        states=nxt
    winner=None
    for (fp,cl),(tp,rules,distance) in states.items():
        total_tp=tp+fixed['tp'];total_fp=fp+fixed['fp']
        precision=total_tp/(total_tp+total_fp) if total_tp+total_fp else 0
        if precision<(base['precision'] or 0):continue
        score=1.25*total_tp/(total_tp+total_fp+.25*base['typos'])
        key=(score,-sum(v!=threshold for v in rules.values()),-distance)
        if winner is None or key>winner[0]:winner=(key,rules)
    rule=dict(family=family,thresholds=winner[1],default_threshold=threshold)
    return dict(family=family,rule=rule,tuning=short.assess(cc,[predict(d,rule) for d in dd]))


def replay(client,adapter,trials,by_id,rule):
    cc=[];ww=[]
    for t in trials:
        committed=[]
        for k in t['words']:
            c=dict(by_id[k],left=' '.join(committed)+(' ' if committed else ''))
            d=describe(adapter,c,client.predict(c['left'],c['typed']))
            word=predict(d,rule);committed.append(word);cc.append(c);ww.append(word)
    return short.assess(cc,ww)


def run(output):
    root=core.PROJECT;output.mkdir(parents=True,exist_ok=False)
    source=root/'build/short-acceptance-20261025/run3';parent=json.loads((source/'report.json').read_text())
    native=root/'build/short-error-analysis-20261025/native'
    binary=native/'baseline-probe';policy=native/'baseline-acceptance.json'
    model=root/'app/src/main/assets/language_model/model.cng'
    for k,p in [('probe',binary),('model',model),('policy',policy)]:assert core.digest(p)==parent['protocol']['source_hashes'][k]
    mobile_path=root.parent/'gboard-probe/improve-v3/cases.jsonl'
    data=root/'build/real-revisit-20261025/data3';suite=root/'build/autocorrect-eval-v4/frozen'
    core.write_json(output/'protocol.json',dict(script_sha256=core.digest(__file__),source_hashes=parent['protocol']['source_hashes'],
        mobile_source_sha256=core.digest(mobile_path),stream_manifest_sha256=core.digest(data/'manifest.json'),
        fit='Exact observed cutoffs per count/type bucket; no added FP or clean damage or precision loss',
        selection='Separate mobile and recorded-stream cohorts; no precision loss in either',
        objective='All four frozen families, exposed development comparison; not independent confirmation',
        additional_inference=0,word_specific_rules=False))
    client=old.Probe(binary,model,output);adapter=short.Adapter(policy)
    for folder in [source,root/'build/short-error-analysis-20261025/run2',root/'build/short-edit-counts-20261025/run1']:
        for r in map(json.loads,(folder/'native-cache.jsonl').open()):client.cache[json.dumps(r['request'],sort_keys=True)]=r['output']
    try:
        cases=core.rows(data/'cases.jsonl');trials=core.rows(data/'trials.jsonl');by_id={c['id']:c for c in cases}
        mobile=[c for c in core.rows(mobile_path) if c['source']=='real' and c['fit_split'] in ('fit','calibration','selection') and len(c['typed']) in (2,3)]
        frozen=core.rows(suite/'cases.jsonl')
        assert not {c['group'] for c in mobile}&{c['group'].removeprefix('aalto:') for c in frozen if c['panel'].startswith('mobile_')}
        train=[c for c in cases if c['split'] in ('fit','calibration')]+[c for c in mobile if c['fit_split'] in ('fit','calibration')]
        selection=[c for c in mobile if c['fit_split']=='selection']
        assert not {c['group'] for c in mobile if c['fit_split'] in ('fit','calibration')}&{c['group'] for c in selection}
        dd=[describe(adapter,c,client.predict(c['left'],c['typed'])) for c in train]
        md=[describe(adapter,c,client.predict(c['left'],c['typed'])) for c in selection]
        sel=[t for t in trials if t['split']=='selection']
        base=replay(client,adapter,sel,by_id,None);mbase=short.assess(selection,[d['initial'] for d in md])
        finalists=[]
        for family in ['shared','edit_count','substitution','one_neighbor']:
            row=fit(train,dd,family,adapter.base.threshold)
            row['selection']=replay(client,adapter,sel,by_id,row['rule'])
            row['mobile_selection']=short.assess(selection,[predict(d,row['rule']) for d in md])
            row['passes']=all(short.safe(row['selection'][k],base[k]) and short.safe(row['mobile_selection'][k],mbase[k]) for k in ['all','short'])
            finalists.append(row)
        core.write_json(output/'frozen-families.json',dict(finalists=finalists,baseline=base,mobile_baseline=mbase))
        fd=[describe(adapter,c,client.predict(c['left'],c['typed'])) for c in frozen]
        for row in finalists:
            ww=[predict(d,row['rule']) for d in fd]
            row['objective']=old.overall(frozen,ww);row['evaluation']=short.assess(frozen,ww)
            row['changes']=[dict(id=c['id'],typed=c['typed'],target=c['target'],after=w) for c,d,w in zip(frozen,fd,ww) if d['initial'].casefold()!=w.casefold()]
            print(row['family'],row['rule'],row['objective']['score'],flush=True)
        core.write_json(output/'report.json',dict(finalists=finalists,baseline=base,mobile_baseline=mbase,tuning_words=len(train),selection_words=len(selection)+sum(len(t['words']) for t in sel)))
    finally:client.close()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True)
    run(p.parse_args().output)
