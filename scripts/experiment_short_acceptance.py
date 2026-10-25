#!/usr/bin/env python3
"""Precision-constrained short-word threshold ablation; no extra LM inference.

Calibrate exact observed cutoffs, select with closed-loop recorded streams,
then freeze before the existing exposed objective suite. Never change native
accepted decisions. New gates use existing LM margins, no new dictionary rule.
"""
import argparse
import collections
import json
from pathlib import Path

import numpy as np
import autocorrect_eval_v2 as core
import replay_context_revision as old


class Adapter:
    def __init__(self, policy):
        self.base = old.Acceptance(policy)
        self.cache = {}

    def describe(self, typed, out):
        key = (typed, id(out))  # Probe retains all outputs for this run.
        if key in self.cache:
            return self.cache[key]
        b = self.base
        x, eligible, word = b.f.extract(dict(typed=typed), out)
        values = list(x) + [float(typed.lower().replace('’', "'") in b.dictionary),
                               float(all(w in b.dictionary for w in (word or '').lower().split()))]
        z = float(b.f.predict_logits(b.f.parameters(b.policy),
                  ((np.array(values)-b.policy['means'])/b.policy['scales'])[None, :])[0])
        expected = b.f.restored(out) or (word if eligible and z >= b.threshold else None)
        assert (expected or typed).casefold() == (out['correction'] or typed).casefold(), (typed, expected, out)
        d = dict(typed=typed, initial=out['correction'] or typed, word=word, logit=z,
                 eligible=bool(eligible), literal_advantage=float(x[2]), runner_margin=float(x[4]),
                 length=len(typed), native_accepted=bool(out['correction']))
        self.cache[key] = d
        return d


def predict(d, rule):
    if rule is None or d['native_accepted'] or not d['eligible'] or d['length'] not in (2, 3):
        return d['initial']
    threshold = rule['thresholds'][str(d['length'])]
    if d['logit'] >= threshold and (rule['feature'] is None or d[rule['feature']] >= rule['minimum']):
        return old.surface(d['word'], d['typed'])
    return d['initial']


def measure(cases, words):
    return {k:v for k,v in old.measure(cases, words).items() if k not in ('target_in_top8','target_top1','miss_absent_from_top8','miss_ranking_or_acceptance')}


def safe(candidate, baseline):
    return (candidate['fp'] <= baseline['fp'] and candidate['clean_changes'] <= baseline['clean_changes']
            and (candidate['precision'] or 0) >= (baseline['precision'] or 0))


def assess(cases, words):
    short = [i for i,c in enumerate(cases) if len(c['typed']) in (2,3)]
    return dict(all=measure(cases, words), short=measure([cases[i] for i in short], [words[i] for i in short]))


def replay(client, adapter, trials, by_id, rule):
    cc, ww = [], []
    for t in trials:
        committed = []
        for k in t['words']:
            c = by_id[k]
            left = ' '.join(committed) + (' ' if committed else '')
            out = client.predict(left, c['typed'])
            word = predict(adapter.describe(c['typed'],out),rule)
            committed.append(word)
            cc.append(c); ww.append(word)
    return assess(cc, ww), dict(zip((c['id'] for c in cc), ww))


def tune(cases, descriptions, threshold):
    baseline = assess(cases,[d['initial'] for d in descriptions])
    indices = [i for i,d in enumerate(descriptions) if d['length'] in (2,3) and d['eligible'] and not d['native_accepted']]
    eligible = [descriptions[i] for i in indices]
    cutoffs = sorted({d['logit'] for d in eligible if d['logit'] < threshold} | {threshold})
    z = np.array([d['logit'] for d in eligible])
    lengths = np.array([d['length'] for d in eligible])
    correct = np.array([cases[i]['target'] is not None and descriptions[i]['word'].casefold()==cases[i]['target'].casefold()
                        and descriptions[i]['word'].casefold()!=cases[i]['typed'].casefold() for i in indices])
    wrong = np.array([cases[i]['target'] is not None and descriptions[i]['word'].casefold()!=cases[i]['target'].casefold()
                      and descriptions[i]['word'].casefold()!=cases[i]['typed'].casefold() for i in indices])
    finalists = []
    for feature in [None, 'literal_advantage', 'runner_margin']:
        minima = [None] if feature is None else sorted({d[feature] for d in eligible})
        feature_values = np.array([d[feature] for d in eligible]) if feature else None
        best = None
        for minimum in minima:
            gate = np.ones(len(z), dtype=bool) if feature is None else feature_values >= minimum
            for cutoff in cutoffs:
                mask = gate & (z >= cutoff)
                if np.any(mask & wrong):
                    continue
                key = (int(np.sum(mask & correct)),cutoff,minimum or 0)
                if best is None or key > best[0]:
                    best = (key,dict(thresholds={'2':cutoff,'3':cutoff},feature=feature,minimum=minimum))
        if best:
            rule=best[1]
            finalists.append(dict(family='shared' if feature is None else feature,rule=rule,
                                  calibration=assess(cases,[predict(d,rule) for d in descriptions])))
    thresholds = {}
    for length in (2,3):
        best = (0, threshold)
        for cutoff in cutoffs:
            mask = (lengths == length) & (z >= cutoff)
            if not np.any(mask & wrong):
                best = max(best,(int(np.sum(mask & correct)),cutoff))
        thresholds[str(length)] = best[1]
    rule = dict(thresholds=thresholds,feature=None,minimum=None)
    finalists.append(dict(family='separate_lengths',rule=rule,
                         calibration=assess(cases,[predict(d,rule) for d in descriptions])))
    return baseline, finalists


def audit(cases, descriptions):
    counts = collections.Counter()
    for c,d in zip(cases,descriptions):
        if len(c['typed']) not in (2,3) or c['target'] is None or c['typed'].casefold()==c['target'].casefold():
            continue
        counts['typos'] += 1
        if d['initial'].casefold()==c['target'].casefold(): counts['fixed'] += 1
        elif (d['word'] or '').casefold()==c['target'].casefold():
            counts['top_candidate_rejected' if d['eligible'] else 'top_candidate_ineligible'] += 1
        else: counts['ranking_or_generation_miss'] += 1
    return dict(counts)


def run(a):
    a.output.mkdir(parents=True,exist_ok=False)
    manifest=json.loads((a.data/'manifest.json').read_text())
    for name in ['cases','trials']:
        assert core.digest(a.data/(name+'.jsonl'))==manifest[name+'_sha256']
    protocol=dict(source_hashes={k:core.digest(p) for k,p in [('probe',a.probe),('policy',a.policy),('model',a.model)]},
                  script_sha256=core.digest(__file__),data_manifest_sha256=core.digest(a.data/'manifest.json'),
                  calibration='Fit plus calibration recorded cohorts, exact observed cutoffs; shared, separate lengths, or shared plus one existing LM margin cutoff',
                  selection='Closed-loop real selection streams; no extra FP or clean damage, no precision loss for short/all; max F0.5, ties simpler then baseline',
                  evaluation_used_for_tuning=False,production_changed=False)
    core.write_json(a.output/'protocol.json',protocol)
    cases=core.rows(a.data/'cases.jsonl'); by_id={c['id']:c for c in cases}
    trials=core.rows(a.data/'trials.jsonl')
    client=old.Probe(a.probe,a.model,a.output); adapter=Adapter(a.policy)
    try:
        cal=[c for c in cases if c['split'] in ('fit','calibration')]
        ds=[adapter.describe(c['typed'],client.predict(c['left'],c['typed'])) for c in cal]
        base,finalists=tune(cal,ds,adapter.base.threshold)
        core.write_json(a.output/'calibration.json',dict(baseline=base,audit=audit(cal,ds),finalists=finalists))
        print('Calibration',json.dumps(dict(audit=audit(cal,ds),finalists=finalists)),flush=True)
        sel=[t for t in trials if t['split']=='selection']
        baseline,_=replay(client,adapter,sel,by_id,None)
        winner=dict(family='baseline',rule=None,selection=baseline)
        for row in finalists:
            row['selection'],_=replay(client,adapter,sel,by_id,row['rule'])
            row['passes']=all(safe(row['selection'][k],baseline[k]) for k in ['all','short'])
            if row['passes'] and row['selection']['all']['f05']>winner['selection']['all']['f05']:
                winner=row
        core.write_json(a.output/'frozen-rule.json',dict(winner=winner,finalists=finalists,protocol=protocol))
        print('Frozen winner',json.dumps(winner),flush=True)
        frozen=core.rows(a.suite/'cases.jsonl')
        baseline_words=[]; words=[]; descriptions=[]; changes=[]
        for c in frozen:
            out=client.predict(c['left'],c['typed']); d=adapter.describe(c['typed'],out)
            before=d['initial']; after=predict(d,winner['rule'])
            baseline_words.append(before); words.append(after); descriptions.append(d)
            if before.casefold()!=after.casefold(): changes.append(dict(case=c,before=before,after=after))
        result=dict(baseline=old.overall(frozen,baseline_words),candidate=old.overall(frozen,words),
                    baseline_metrics=assess(frozen,baseline_words),candidate_metrics=assess(frozen,words),audit=audit(frozen,descriptions))
        result['delta']=result['candidate']['score']-result['baseline']['score']
        # Also test propagation through recorded streams. No simulated future.
        ft=core.rows(a.suite/'trials.jsonl'); fb={c['id']:c for c in frozen}
        result['closed_loop_baseline'],_=replay(client,adapter,ft,fb,None)
        result['closed_loop_candidate'],_=replay(client,adapter,ft,fb,winner['rule'])
        report=dict(protocol=protocol,winner=winner,finalists=finalists,evaluation=result,
                    native_calls=dict(client.calls),production_changed=False)
        core.jsonl(a.output/'changes.jsonl',changes);core.write_json(a.output/'report.json',report)
        print('Evaluation',json.dumps(result),flush=True)
    finally:
        client.close()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data',type=Path,default=core.PROJECT/'build/real-revisit-20261025/data3')
    p.add_argument('--suite',type=Path,default=core.PROJECT/'build/autocorrect-eval-v4/frozen')
    p.add_argument('--probe',type=Path,default=core.PROJECT.parent/'QwenASR/crates/cantonese-ngram/target/release/ngram_probe')
    p.add_argument('--policy',type=Path,default=old.NATIVE/'english_acceptance.json')
    p.add_argument('--model',type=Path,default=core.PROJECT/'app/src/main/assets/language_model/model.cng')
    p.add_argument('--output',type=Path,required=True)
    run(p.parse_args())
