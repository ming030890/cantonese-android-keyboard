#!/usr/bin/env python3
"""Simple revisiting policies tuned on intact recorded streams, no fake futures.

Confidence/correction thresholds use exact calibration breakpoints; revisit ages
come from observed stream lengths. Full closed-loop selection chooses the policy.
No separate queue capacity, candidate-count knob, learned model or mixture weight.
"""
import argparse
import collections
import json
from pathlib import Path

import numpy as np
import autocorrect_eval_v2 as core
import experiment_revisit_policy as feature
import replay_context_revision as old


def admit(seed, rule):
    if seed is None or rule is None:
        return False
    if rule['state'] != 'both' and seed['accepted'] != (rule['state'] == 'accepted'):
        return False
    return rule['confidence_band'] is None or abs(seed['margin']) <= rule['confidence_band']


def choice(seed, event, scoring):
    if not event['alternatives']:
        return None
    # Joint likelihood is prefix gain + conditional suffix gain, no fitted weight.
    def score(c):
        return c['gain'] + (c['features'][len(seed['features'])] if scoring == 'joint' else 0.)
    candidate = max(event['alternatives'], key=score)
    return dict(word=candidate['word'], score=score(candidate))


def final(record, rule):
    if admit(record['seed'], rule):
        for event in record['events']:
            if event['lag'] > rule['max_following']:
                break
            c = choice(record['seed'], event, rule['scoring'])
            if c and c['score'] >= rule['threshold']:
                return c['word']
    return record['initial']


def collect(probe, acceptance, cases, trials):
    by_id = {c['id']: c for c in cases}
    records = []
    for i, trial in enumerate(trials):
        committed, outputs = [], []
        for identifier in trial['words']:
            c = by_id[identifier]
            out = probe.predict(' '.join(committed) + (' ' if committed else ''), c['typed'])
            outputs.append(out)
            committed.append(old.surface(out['correction'], c['typed']) if out['correction'] else c['typed'])
        for j, identifier in enumerate(trial['words']):
            c = by_id[identifier]
            seed = feature.seed_for(acceptance, c['typed'], outputs[j])
            events = []
            if seed:
                # No collection confidence band. All available subsequent words,
                # including their actual observed errors, enter the replay.
                groups = []
                for k in range(j + 1, len(committed)):
                    groups.append(' '.join(committed[j + 1:k + 1]).split())
                if groups:
                    left = ' '.join(committed[:j]) + (' ' if j else '')
                    events = feature.evidence(probe, seed, left, groups)
            records.append(dict(id=identifier, initial=committed[j], seed=seed, events=events))
        if (i + 1) % 100 == 0:
            print('Recorded development trials', i + 1, flush=True)
    return records


def tune(cases, records):
    """Exact threshold search; calibration only. No arbitrary score grid."""
    keep = [i for i, c in enumerate(cases) if c['split'] == 'calibration' and c['target'] is not None]
    cc, rr = [cases[i] for i in keep], [records[i] for i in keep]
    typed = np.array([feature.normalized(c['typed']) for c in cc], dtype=object)
    target = np.array([feature.normalized(c['target']) for c in cc], dtype=object)
    initial = np.array([feature.normalized(r['initial']) for r in rr], dtype=object)
    confidence = np.array([abs(r['seed']['margin']) if r['seed'] else np.inf for r in rr])
    accepted = np.array([bool(r['seed'] and r['seed']['accepted']) for r in rr])
    has_seed = np.array([r['seed'] is not None for r in rr])
    changed = initial != typed
    old_tp = changed & (initial == target)
    old_fp = changed & ~old_tp
    tp0, fp0 = int(old_tp.sum()), int(old_fp.sum())
    typos = int((typed != target).sum())
    horizon = max((e['lag'] for r in rr for e in r['events']), default=0)
    trials = []
    for scoring in ['suffix', 'joint']:
        scores = np.full((len(rr), horizon), -np.inf)
        words = np.repeat(initial[:, None], horizon, axis=1)
        for i, record in enumerate(rr):
            for e in record['events']:
                c = choice(record['seed'], e, scoring)
                if c:
                    scores[i, e['lag'] - 1] = c['score']
                    words[i, e['lag'] - 1] = feature.normalized(c['word'])
        for age in range(1, horizon + 1):
            finite = scores[:, :age][np.isfinite(scores[:, :age])]
            for state in ['kept', 'accepted', 'both']:
                state_mask = has_seed & (True if state == 'both' else accepted == (state == 'accepted'))
                for banded in [False, True]:
                    best = None
                    for cutoff in sorted(set(finite.tolist())):
                        passed = scores[:, :age] >= cutoff
                        first = passed.argmax(axis=1)
                        proposals = np.where(passed.any(axis=1), words[np.arange(len(rr)), first], initial)
                        proposal_changed = proposals != typed
                        new_tp = proposal_changed & (proposals == target)
                        new_fp = proposal_changed & ~new_tp
                        delta_tp = new_tp.astype(int) - old_tp
                        delta_fp = new_fp.astype(int) - old_fp
                        ii = np.flatnonzero(state_mask)
                        if banded:
                            ii = ii[np.argsort(confidence[ii], kind='stable')]
                            if not len(ii):
                                continue
                            tp = tp0 + np.cumsum(delta_tp[ii])
                            fp = fp0 + np.cumsum(delta_fp[ii])
                            revisions = np.cumsum(proposals[ii] != initial[ii])
                            f05 = 1.25 * tp / (tp + fp + .25 * typos)
                            ends = np.r_[confidence[ii][1:] != confidence[ii][:-1], True]
                            candidates = np.flatnonzero(ends)
                            at = max(candidates, key=lambda k: (f05[k], -revisions[k], -(k + 1)))
                            band, n = float(confidence[ii[at]]), int(at + 1)
                            m = dict(f05=float(f05[at]), tp=int(tp[at]), fp=int(fp[at]), revisions=int(revisions[at]), eligible=n)
                        else:
                            tp, fp = tp0 + int(delta_tp[ii].sum()), fp0 + int(delta_fp[ii].sum())
                            band = None
                            m = dict(f05=1.25*tp/(tp+fp+.25*typos), tp=tp, fp=fp,
                                     revisions=int((proposals[ii] != initial[ii]).sum()), eligible=len(ii))
                        key = (m['f05'], -m['revisions'], -m['eligible'], cutoff)
                        if best is None or key > best[0]:
                            best = (key, dict(scoring=scoring, state=state, confidence_band=band,
                                              threshold=float(cutoff), max_following=age), m)
                    if best:
                        trials.append(dict(rule=best[1], calibration=best[2]))
            print('Calibrated', scoring, 'age', age, flush=True)
    return trials


def replay(probe, acceptance, cases, rule):
    committed, queue, revisions = [], [], []
    for i, c in enumerate(cases):
        left = ' '.join(committed) + (' ' if committed else '')
        out = probe.predict(left, c['typed'])
        word = old.surface(out['correction'], c['typed']) if out['correction'] else c['typed']
        committed.append(word)
        if rule is None:
            continue
        queue = [q for q in queue if len(' '.join(committed[q['index'] + 1:]).split()) <= rule['max_following']]
        seed = feature.seed_for(acceptance, c['typed'], out)
        if admit(seed, rule):
            queue.append(dict(index=i, seed=seed))
        # The selected age bounds retention. No independent magic queue capacity.
        for q in queue[:]:
            j = q['index']
            if i == j:
                continue
            following = ' '.join(committed[j + 1:]).split()
            prefix = ' '.join(committed[:j]) + (' ' if j else '')
            events = feature.evidence(probe, q['seed'], prefix, [following])
            proposed = choice(q['seed'], events[0], rule['scoring']) if events else None
            if proposed and proposed['score'] >= rule['threshold']:
                revisions.append(dict(id=cases[j]['id'], before=committed[j], after=proposed['word'],
                                      accepted=q['seed']['accepted'], lag=len(following)))
                committed[j] = proposed['word']
                queue = [old_q for old_q in queue if old_q['index'] < j]
                break  # Rewrites invalidate later confidence snapshots.
    return committed, revisions


def stream_pass(probe, acceptance, trials, by_id, rule, incumbent=None):
    words, changes = {}, []
    for trial in trials:
        cc = [by_id[k] for k in trial['words']]
        if incumbent is not None:
            ww, rr = old.replay_trial(probe, acceptance, cc, incumbent)
        else:
            ww, rr = replay(probe, acceptance, cc, rule)
        words.update(zip(trial['words'], ww))
        changes.extend(rr)
    cc = [by_id[k] for t in trials for k in t['words']]
    return feature.metrics(cc, [words[c['id']] for c in cc]), words, changes


def run(a):
    a.output.mkdir(parents=True, exist_ok=False)
    manifest = json.loads((a.data / 'manifest.json').read_text())
    for name in ['cases', 'trials']:
        assert core.digest(a.data / (name + '.jsonl')) == manifest[name + '_sha256']
    cases = core.rows(a.data / 'cases.jsonl')
    trials = core.rows(a.data / 'trials.jsonl')
    by_id = {c['id']: c for c in cases}
    previous = json.loads((a.previous / 'report.json').read_text())
    binding = dict(schema='real-revisit-simple-v1', script_sha256=core.digest(__file__),
                   data_manifest_sha256=core.digest(a.data / 'manifest.json'),
                   source_hashes={k:core.digest(p) for k,p in [('probe',a.probe),('model',a.model),('policy',a.policy)]},
                   calibration='Exact distinct observed acceptance/confidence breakpoints and all observed ages',
                   selection='Full recorded-sentence replay; highest F0.5, ties simpler policy then fewer native calls',
                   simplifications=['No learned model', 'No fitted evidence weights', 'No confidence collection band',
                                    'No independent queue capacity', 'All whole-word alternatives in the existing native pool plus original spelling'],
                   limits=['Native pool generated by existing spelling_limit=8 interface', 'Recorded copy typing, not chat',
                           'Reconstructed post-edit streams, no counterfactual user response or cursor simulation'], production_changed=False)
    core.write_json(a.output / 'protocol.json', binding)
    acceptance = old.Acceptance(a.policy)
    probe = old.Probe(a.probe, a.model, a.output)
    if a.reuse:
        saved=json.loads((a.reuse/'protocol.json').read_text())
        assert saved['source_hashes']==binding['source_hashes']
        assert saved['data_manifest_sha256']==binding['data_manifest_sha256']
        binding['reused_episodes_sha256']=core.digest(a.reuse/'development-episodes.jsonl')
        binding['reused_protocol_sha256']=core.digest(a.reuse/'protocol.json')
        core.write_json(a.output/'protocol.json',binding)
        with (a.reuse/'native-cache.jsonl').open() as stream:
            for row in map(json.loads,stream):
                probe.cache[json.dumps(row['request'],sort_keys=True)]=row['output']
    try:
        # Do not expose reserved Google evaluation outcomes before selection.
        dev_trials = [t for t in trials if t['split'] != 'evaluation']
        records = core.rows(a.reuse/'development-episodes.jsonl') if a.reuse else collect(probe, acceptance, cases, dev_trials)
        record_by_id = {r['id']:r for r in records}
        development = [c for c in cases if c['split'] != 'evaluation']
        records = [record_by_id[c['id']] for c in development]
        core.jsonl(a.output / 'development-episodes.jsonl', records)
        candidates = tune(development, records)
        selected_trials = [t for t in dev_trials if t['split']=='selection']
        parents = []
        for name, chosen in [('no_revisit',None), ('incumbent',previous['incumbent_rule'])]:
            m, _, _ = stream_pass(probe, acceptance, selected_trials, by_id, None, chosen)
            parents.append(dict(name=name, metrics=m, rule=chosen))
        for i, candidate in enumerate(candidates):
            class Meter:
                calls=0
                def query(self,request):
                    self.calls+=1
                    return probe.query(request)
                def predict(self,left,typed):
                    return self.query(dict(left=left[-512:],typed=typed,spelling_limit=8))
            meter=Meter()
            m, _, _ = stream_pass(meter, acceptance, selected_trials, by_id, candidate['rule'])
            candidate['selection'] = m
            candidate['selection_native_requests'] = meter.calls
            if (i+1)%10==0:
                print('Full-stream selection',i+1,'/',len(candidates),flush=True)
        def key(c):
            r=c['rule']
            complexity = 1 + (r['confidence_band'] is not None) + (r['state']!='both') + (r['scoring']!='suffix')
            return c['selection']['f05'], -complexity, -c['selection_native_requests'], -r['max_following']
        best = max(candidates,key=key)
        parent = max(parents,key=lambda p:p['metrics']['f05'])
        family = 'simple' if best['selection']['f05'] > parent['metrics']['f05'] else parent['name']
        selection = dict(best_simple=best, parents=parents, selected_family=family, candidates=candidates, evaluation_used=False)
        core.write_json(a.output / 'selection.json',selection)
        core.write_json(a.output / 'frozen-policy.json',dict(rule=best['rule'],selected_family=family,binding=binding))
        print('Frozen winner',json.dumps(dict(best=best,parents=parents,selected_family=family)),flush=True)
        frozen_cases = core.rows(a.suite / 'cases.jsonl')
        frozen_trials = core.rows(a.suite / 'trials.jsonl')
        frozen_by_id = {c['id']:c for c in frozen_cases}
        # Mobile snapshots lack observed futures: retain immediate decisions for
        # every policy. Only the touch panel receives real recorded revisits.
        baseline_words = json.loads((a.previous / 'predictions.json').read_text())['baseline']
        results={}
        for name, chosen, incumbent in [('no_revisit',None,None), ('incumbent',None,previous['incumbent_rule']),('simple',best['rule'],None)]:
            tm, ww, changes = stream_pass(probe,acceptance,frozen_trials,frozen_by_id,chosen,incumbent)
            all_words=dict(baseline_words);all_words.update(ww)
            overall=old.overall(frozen_cases,[all_words[c['id']] for c in frozen_cases])
            overall['touch_metrics']=tm
            overall['touch_revisions']=len(changes)
            overall['touch_cer']=core.stream_metrics(frozen_trials,[c for c in frozen_cases if c['panel']=='real_touch_observed'],
                         [all_words[c['id']] if all_words[c['id']]!=c['typed'] else None for c in frozen_cases if c['panel']=='real_touch_observed'])
            results[name]=overall
            core.jsonl(a.output/(name+'-touch-revisions.jsonl'),changes)
            print('Recorded evaluation',name,overall['score'],tm,flush=True)
        heldout=[t for t in trials if t['split']=='evaluation']
        additional={}
        for name,chosen,incumbent in [('no_revisit',None,None),('incumbent',None,previous['incumbent_rule']),('simple',best['rule'],None)]:
            m,ww,changes=stream_pass(probe,acceptance,heldout,by_id,chosen,incumbent)
            additional[name]=dict(metrics=m,revisions=len(changes))
        report=dict(binding=binding,selected_family=family,rule=best['rule'],results=results,
                    additional_google_evaluation=additional,
                    score_delta_vs_no_revisit=results['simple']['score']-results['no_revisit']['score'],
                    score_delta_vs_incumbent=results['simple']['score']-results['incumbent']['score'],production_changed=False)
        core.write_json(a.output/'report.json',report)
    finally:
        probe.close()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data',type=Path,required=True)
    p.add_argument('--previous',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--policy',type=Path,required=True)
    p.add_argument('--reuse',type=Path)
    p.add_argument('--model',type=Path,default=core.PROJECT/'app/src/main/assets/language_model/model.cng')
    p.add_argument('--suite',type=Path,default=core.PROJECT/'build/autocorrect-eval-v4/frozen')
    run(p.parse_args())
