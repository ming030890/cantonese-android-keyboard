#!/usr/bin/env python3
"""Isolate revisit distance on recorded streams; keep other policy settings fixed.

Select distance on the existing recorded selection fold. Frozen evaluation
comparisons (1/2/3/unbounded plus selected) are diagnostic, never selection data.
"""
import argparse
import collections
import json
from pathlib import Path

import autocorrect_eval_v2 as core
import experiment_revisit_policy as feature
import replay_context_revision as old


class Counter:
    def __init__(self, client):
        self.client = client
        self.calls = collections.Counter()

    def query(self, request):
        self.calls['spelling' if 'typed' in request else 'revisit'] += 1
        return self.client.query(request)

    def predict(self, left, typed):
        return self.query(dict(left=left[-512:], typed=typed, spelling_limit=8))


def replay(probe, acceptance, cases, policy, distance):
    if distance is not None and distance < 1:
        raise ValueError('Distance counts following words and must be positive')
    committed, queue, revisions = [], [], []
    for i, case in enumerate(cases):
        left = ' '.join(committed) + (' ' if committed else '')
        out = probe.predict(left, case['typed'])
        word = old.surface(out['correction'], case['typed']) if out['correction'] else case['typed']
        committed.append(word)
        for q in queue:
            q['age'] += len(word.split())
        queue = [q for q in queue if distance is None or q['age'] <= distance]
        seed = acceptance.seed(case['typed'], out)
        if seed and seed['logit'] >= acceptance.threshold - policy['band_width']:
            queue.append(dict(index=i, age=0, seed=seed))
        queue = queue[-2:]  # Hold the existing queue capacity fixed to isolate distance.
        for q in queue[:]:
            j = q['index']
            if j == i:
                continue
            prefix = ' '.join(committed[:j]) + (' ' if j else '')
            following = ' '.join(committed[j + 1:]).split()
            event = old.evidence(probe, q['seed'], prefix, following)
            rule = dict(policy, max_following=distance if distance is not None else len(following))
            fix = old.propose(q['seed'], event, rule, acceptance.threshold)
            if fix:
                revisions.append(dict(id=cases[j]['id'], before=committed[j], after=fix, lag=len(following)))
                committed[j] = fix
                queue = [p for p in queue if p['index'] < j]
                break
    return committed, revisions


def run_trials(client, acceptance, trials, by_id, policy, distance):
    probe = Counter(client)
    words, revisions, cc = {}, [], []
    for t in trials:
        cases = [by_id[k] for k in t['words']]
        ww, changed = replay(probe, acceptance, cases, policy, distance)
        words.update(zip(t['words'], ww))
        revisions.extend(changed)
        cc.extend(cases)
    return dict(distance=distance, metrics=feature.metrics(cc, [words[c['id']] for c in cc]),
                requests=dict(probe.calls), revisions=len(revisions),
                revision_lags=dict(collections.Counter(r['lag'] for r in revisions))), words, revisions


def run(a):
    a.output.mkdir(parents=True, exist_ok=False)
    source = json.loads((a.previous / 'report.json').read_text())
    policy = source['incumbent_rule']
    data_manifest = json.loads((a.data / 'manifest.json').read_text())
    for name in ['cases', 'trials']:
        assert core.digest(a.data / (name + '.jsonl')) == data_manifest[name + '_sha256']
    for name, p in [('probe',a.probe),('model',a.model),('policy',a.policy)]:
        assert core.digest(p) == source['protocol']['source_hashes'][name]
    trials = core.rows(a.data / 'trials.jsonl')
    cases = core.rows(a.data / 'cases.jsonl')
    by_id = {c['id']: c for c in cases}
    # Range depends on observed TRAIN/calibration sentence lengths, not evaluation.
    maximum = max(len(t['words'])-1 for t in trials if t['split'] in ['fit','calibration'])
    distances = list(range(1,maximum+1)) + [None]
    protocol = dict(schema='revisit-distance-ablation-v1', script_sha256=core.digest(__file__),
                    data_manifest_sha256=core.digest(a.data/'manifest.json'),
                    fixed_policy=policy, fixed_pending_capacity=2, distances=distances,
                    source_hashes={name:core.digest(p) for name,p in [('probe',a.probe),('model',a.model),('policy',a.policy)]},
                    selection='Existing real recorded selection split; max F0.5 then fewer sequence requests then shorter distance',
                    frozen_diagnostics=[1,2,3,None],
                    scope='Only distance changes. No synthetic future. Mobile/hard frozen decisions unchanged. Unbounded still respects sentence/cursor scope and two pending candidates.',
                    production_changed=False)
    core.write_json(a.output/'protocol.json',protocol)
    client=old.Probe(a.probe,a.model,a.output)
    for directory in a.cache:
        with (directory/'native-cache.jsonl').open() as stream:
            for row in map(json.loads,stream):
                client.cache[json.dumps(row['request'],sort_keys=True)]=row['output']
    acceptance=old.Acceptance(a.policy)
    try:
        selected_trials=[t for t in trials if t['split']=='selection']
        selection=[]
        for distance in distances:
            result,_,_=run_trials(client,acceptance,selected_trials,by_id,policy,distance)
            selection.append(result)
            print('Selection',json.dumps(result),flush=True)
        key=lambda r:(r['metrics']['f05'],-r['requests'].get('revisit',0),-(r['distance'] if r['distance'] is not None else maximum+1))
        winner=max(selection,key=key)
        core.write_json(a.output/'selection.json',dict(trials=selection,winner=winner,evaluation_used=False))
        print('Frozen distance',winner['distance'],flush=True)
        # Fixed diagnostics and selected distance declared before evaluation.
        evaluate=list(dict.fromkeys([1,2,3,None,winner['distance']]))
        frozen_cases=core.rows(a.suite/'cases.jsonl')
        frozen_trials=core.rows(a.suite/'trials.jsonl')
        frozen_by_id={c['id']:c for c in frozen_cases}
        base_words=json.loads((a.previous/'predictions.json').read_text())['baseline']
        baseline=old.overall(frozen_cases,[base_words[c['id']] for c in frozen_cases])
        results=[]
        for distance in evaluate:
            result,words,revisions=run_trials(client,acceptance,frozen_trials,frozen_by_id,policy,distance)
            all_words=dict(base_words);all_words.update(words)
            result['overall']=old.overall(frozen_cases,[all_words[c['id']] for c in frozen_cases])
            result['delta_vs_no_revisit']=result['overall']['score']-baseline['score']
            results.append(result)
            name='unbounded' if distance is None else str(distance)
            core.jsonl(a.output/('revisions-'+name+'.jsonl'),revisions)
            core.write_json(a.output/('predictions-'+name+'.json'),words)
            print('Frozen evaluation',json.dumps(result),flush=True)
        core.write_json(a.output/'report.json',dict(protocol=protocol,selected_distance=winner['distance'],
                        selection=selection,baseline=baseline,evaluation=results,production_changed=False))
    finally:
        client.close()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data',type=Path,required=True)
    p.add_argument('--previous',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--policy',type=Path,required=True)
    p.add_argument('--cache',type=Path,action='append',default=[])
    p.add_argument('--model',type=Path,default=core.PROJECT/'app/src/main/assets/language_model/model.cng')
    p.add_argument('--suite',type=Path,default=core.PROJECT/'build/autocorrect-eval-v4/frozen')
    run(p.parse_args())
