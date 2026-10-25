#!/usr/bin/env python3
"""Cheap confidence/length admission gates: recorded quality vs revisit requests.

Fit gate values at observed calibration breakpoints. Select on recorded full
selection streams, then freeze before evaluation. All other revisit policy
settings stay fixed, including the selected one-following-word distance.
"""
import argparse
import collections
import json
from pathlib import Path

import autocorrect_eval_v2 as core
import experiment_revisit_distance as distance
import replay_context_revision as old


class CachedAcceptance:
    def __init__(self, base):
        self.base, self.threshold = base, base.threshold
        self.dictionary = base.dictionary
        self.cache, self.observed = {}, []

    def seed(self, typed, output):
        key = (typed, id(output))
        if key not in self.cache:
            self.cache[key] = self.base.seed(typed, output)
        seed = self.cache[key]
        if seed:
            self.observed.append((self.threshold-seed['logit'], len(typed)))
        return seed


class Gate:
    def __init__(self, base, rule):
        self.base, self.rule, self.threshold = base, rule, base.threshold

    def seed(self, typed, output):
        if self.rule.get('off'):
            return None
        seed = self.base.seed(typed, output)
        valid = typed.lower().replace('’', "'") in self.base.dictionary
        return seed if seed and allows(self.threshold-seed['logit'], len(typed), self.rule,
                                       output['known'], valid) else None


def allows(confidence_distance, length, rule, known=None, dictionary=None):
    return (not rule.get('off') and confidence_distance <= rule['band_width'] and
            (rule['minimum_length'] is None or length >= rule['minimum_length']) and
            (rule['maximum_length'] is None or length <= rule['maximum_length']) and
            (rule.get('known_required') is None or known == rule['known_required']) and
            (rule.get('dictionary_required') is None or dictionary == rule['dictionary_required']))


def complexity(rule):
    return (int(rule['band_width'] != 4.) + int(rule['minimum_length'] is not None) +
            int(rule['maximum_length'] is not None) + int(rule.get('known_required') is not None) +
            int(rule.get('dictionary_required') is not None))


def front(rows, metric):
    ordered=sorted(rows,key=lambda r:(r[metric]['requests'].get('revisit',0),-r[metric]['metrics']['f05'],complexity(r['gate'])))
    result=[];best=-1.
    for r in ordered:
        score=r[metric]['metrics']['f05']
        if score>best:
            result.append(r);best=score
    return result


def run(a):
    a.output.mkdir(parents=True,exist_ok=False)
    parent=json.loads((a.previous/'report.json').read_text())
    policy=parent['protocol']['fixed_policy']
    for name,p in [('probe',a.probe),('model',a.model),('policy',a.policy)]:
        assert core.digest(p)==parent['protocol']['source_hashes'][name]
    manifest=json.loads((a.data/'manifest.json').read_text())
    for name in ['cases','trials']:
        assert core.digest(a.data/(name+'.jsonl'))==manifest[name+'_sha256']
    cases=core.rows(a.data/'cases.jsonl');by_id={c['id']:c for c in cases}
    trials=core.rows(a.data/'trials.jsonl')
    cal=[t for t in trials if t['split']=='calibration']
    sel=[t for t in trials if t['split']=='selection']
    baseline=dict(band_width=policy['band_width'],minimum_length=None,maximum_length=None)
    protocol=dict(schema='revisit-eligibility-cost-v1',script_sha256=core.digest(__file__),
                  data_manifest_sha256=core.digest(a.data/'manifest.json'),
                  source_hashes=parent['protocol']['source_hashes'],fixed_policy=policy,distance=1,
                  parameter_values='Confidence cutoffs from actual calibration seeds; length boundaries from observed TRAIN/calibration lengths',
                  candidate_selection='Calibration Pareto frontier per gate family plus baseline/off; full closed-loop selection F0.5, ties fewer revisit requests then fewer parameters',
                  scope='Only already-available confidence, typed length, known-word and dictionary flags; no extra inference for gating, no learned model, no invented quality-cost coefficient',production_changed=False)
    core.write_json(a.output/'protocol.json',protocol)
    client=old.Probe(a.probe,a.model,a.output)
    for directory in a.cache:
        with (directory/'native-cache.jsonl').open() as stream:
            for row in map(json.loads,stream):client.cache[json.dumps(row['request'],sort_keys=True)]=row['output']
    acceptance=CachedAcceptance(old.Acceptance(a.policy))
    def assess(ts,gate,ids=by_id):
        return distance.run_trials(client,Gate(acceptance,gate),ts,ids,policy,1)[0]
    try:
        baseline_result=assess(cal,baseline)
        cutoffs=sorted({x for x,n in acceptance.observed if 0<=x<=policy['band_width']}|{policy['band_width']})
        lengths=sorted({len(c['typed']) for c in cases if c['split'] in ['fit','calibration']})
        gates=[]
        for width in cutoffs:
            for low,high in [(None,None)]+[(None,n) for n in lengths]+[(n,None) for n in lengths]:
                # One lexical signal at a time; avoid unnecessary interactions.
                for known,valid in [(None,None),(True,None),(False,None),(None,True),(None,False)]:
                    gate=dict(band_width=width,minimum_length=low,maximum_length=high)
                    family=('confidence' if width!=policy['band_width'] else 'base')+(' + maximum length' if high is not None else ' + minimum length' if low is not None else '')
                    if known is not None:
                        gate['known_required']=known;family+=' + known='+str(known)
                    if valid is not None:
                        gate['dictionary_required']=valid;family+=' + dictionary='+str(valid)
                    gates.append(dict(gate=gate,family=family,calibration=assess(cal,gate)))
        off=dict(baseline,off=True)
        gates.append(dict(gate=off,family='off',calibration=assess(cal,off)))
        core.write_json(a.output/'calibration.json',dict(cutoffs=cutoffs,lengths=lengths,trials=gates))
        shortlist={json.dumps(baseline,sort_keys=True):dict(gate=baseline,family='baseline',calibration=baseline_result)}
        for family in {r['family'] for r in gates}:
            for r in front([r for r in gates if r['family']==family],'calibration'):
                shortlist[json.dumps(r['gate'],sort_keys=True)]=r
        shortlist=list(shortlist.values())
        for r in shortlist:
            r['selection']=assess(sel,r['gate'])
        key=lambda r:(r['selection']['metrics']['f05'],-r['selection']['requests'].get('revisit',0),-complexity(r['gate']))
        winner=max(shortlist,key=key)
        frontier=front(shortlist,'selection')
        selection=dict(winner=winner,pareto=frontier,trials=shortlist,evaluation_used=False)
        core.write_json(a.output/'selection.json',selection)
        print('Frozen winner',json.dumps(winner),flush=True)
        core.write_json(a.output/'frozen-gate.json',dict(gate=winner['gate'],protocol=protocol,evaluation_used=False))
        frozen_cases=core.rows(a.suite/'cases.jsonl');frozen_by_id={c['id']:c for c in frozen_cases}
        frozen_trials=core.rows(a.suite/'trials.jsonl')
        base_words=json.loads((a.baseline/'predictions.json').read_text())['baseline']
        diagnostics={json.dumps(r['gate'],sort_keys=True):r['gate'] for r in frontier}
        diagnostics[json.dumps(baseline,sort_keys=True)]=baseline
        diagnostics[json.dumps(winner['gate'],sort_keys=True)]=winner['gate']
        evaluation=[]
        for i,gate in enumerate(diagnostics.values()):
            result,words,revisions=distance.run_trials(client,Gate(acceptance,gate),frozen_trials,frozen_by_id,policy,1)
            all_words=dict(base_words);all_words.update(words)
            result.update(gate=gate,overall=old.overall(frozen_cases,[all_words[c['id']] for c in frozen_cases]))
            evaluation.append(result)
            core.jsonl(a.output/f'revisions-{i}.jsonl',revisions)
            print('Evaluation',json.dumps(dict(gate=gate,metrics=result['metrics'],requests=result['requests'],overall=result['overall']['score'])),flush=True)
        report=dict(protocol=protocol,winner=winner,selection_frontier=frontier,evaluation=evaluation,production_changed=False)
        core.write_json(a.output/'report.json',report)
    finally:
        client.close()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data',type=Path,required=True)
    p.add_argument('--previous',type=Path,required=True)
    p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--policy',type=Path,required=True)
    p.add_argument('--cache',type=Path,action='append',default=[])
    p.add_argument('--model',type=Path,default=core.PROJECT/'app/src/main/assets/language_model/model.cng')
    p.add_argument('--suite',type=Path,default=core.PROJECT/'build/autocorrect-eval-v4/frozen')
    run(p.parse_args())
