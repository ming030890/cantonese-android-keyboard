#!/usr/bin/env python3
"""Recorded-stream immediate correction and diagnostic upstream-error repair.

The real replay uses observed words and its own committed output only. Separate
oracle probes repair labelled PREVIOUS words, never the focal input, to estimate
which remaining errors can be influenced by earlier mistakes. No fitting.
"""
import argparse
import collections
import json
import os
from pathlib import Path
import statistics
import subprocess

import autocorrect_eval_v2 as core

ROOT = Path(__file__).resolve().parents[1]


def surface(word, typed):
    if word is None:
        return typed
    if typed.isupper():
        return word.upper()
    return word[:1].upper() + word[1:] if typed[:1].isupper() else word


def prefix(words):
    return (' '.join(words) + (' ' if words else ''))[-512:]


def error_type(case, committed):
    if case['target'] is None:
        return 'unlabelled'
    if core.norm(committed) == core.norm(case['target']):
        return 'correct'
    return 'missed' if core.norm(committed) == core.norm(case['typed']) else 'wrong_edit'


def repaired_prefix(previous, mode):
    """Oracle helper, used only after the ordinary replay decision is committed."""
    words = []
    for case, committed in previous:
        kind = error_type(case, committed)
        repair = kind in ('missed', 'wrong_edit') if mode == 'all' else kind == mode
        words.append(case['target'] if repair else committed)
    return prefix(words)


def identify_attempts(cases, trials):
    """Preserve repeated recorded attempts, whose source IDs name prompts.

    Preparation writes each trial's event rows consecutively. Bind on that order
    and verify every observed span, rather than overwriting colliding IDs.
    """
    counts = collections.Counter(t['id'] for t in trials)
    occurrences = collections.Counter()
    new_cases, new_trials = [], []
    offset = 0
    for trial in trials:
        original = trial['id']
        attempt = occurrences[original]
        occurrences[original] += 1
        identifier = original if counts[original] == 1 else f'{original}#attempt{attempt}'
        members = cases[offset:offset+len(trial['words'])]
        assert len(members) == len(trial['words'])
        words = []
        for index,(case,expected) in enumerate(zip(members,trial['words'],strict=True)):
            assert case['id'] == expected
            assert trial['observed'][case['start']:case['end']] == case['typed']
            word_id = case['id'] if counts[original] == 1 else f'{identifier}:{index}'
            new_cases.append(dict(case,id=word_id,source_id=case['id'],trial=identifier))
            words.append(word_id)
        new_trials.append(dict(trial,id=identifier,source_id=original,words=words))
        offset += len(members)
    assert offset == len(cases)
    assert len({c['id'] for c in new_cases}) == len(new_cases)
    assert len({t['id'] for t in new_trials}) == len(new_trials)
    return new_cases,new_trials,dict(source_trial_ids_with_multiple_attempts=sum(v>1 for v in counts.values()),
        attempts_reidentified=sum(v for v in counts.values() if v>1),events_preserved=len(cases))


class Probe:
    def __init__(self, binary, model, output):
        self.stderr = (output/'native-stderr.log').open('w')
        self.process = subprocess.Popen([str(binary.resolve()), str(model.resolve())],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr, text=True)
        self.saved = (output/'native-requests.jsonl').open('w')
        self.cache = {}
        self.timings = []

    def predict(self, left, typed, limit=8):
        request = dict(left=left[-512:], typed=typed, spelling_limit=limit, timing=True)
        key = json.dumps(request, sort_keys=True)
        if key in self.cache:
            return self.cache[key]
        self.process.stdin.write(json.dumps(request)+'\n')
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError('Native probe stopped')
        result = json.loads(line)
        self.cache[key] = result
        self.timings.append(result['elapsedMicros']/1000)
        self.saved.write(json.dumps(dict(request=request, output=result))+'\n')
        return result

    def close(self):
        self.process.stdin.close()
        if self.process.wait(timeout=30):
            raise RuntimeError('Native probe failed')
        self.saved.close()
        self.stderr.close()


def replay_trial(probe, cases):
    previous, records = [], []
    for case in cases:
        left = prefix([committed for _, committed in previous])
        # This is the only path which changes the ordinary simulated editor.
        actual = probe.predict(left, case['typed'])
        committed = surface(actual.get('correction'), case['typed'])
        static = probe.predict(case['left'], case['typed'])
        record = dict(id=case['id'], left=left, committed=committed,
            actual=actual, static=static,
            prior_errors=dict(collections.Counter(error_type(c, w) for c,w in previous)))
        record['oracle_probes'] = {mode: probe.predict(repaired_prefix(previous, mode), case['typed'])
                                   for mode in ('missed', 'wrong_edit', 'all')}
        if case['target'] is not None and core.norm(case['typed']) != core.norm(case['target']) and \
                core.norm(committed) != core.norm(case['target']):
            target = core.norm(case['target'])
            words = [core.norm(r['word']) for r in actual['candidates']]
            if target in words:
                record['miss_stage'] = 'target_top1_rejected' if words.index(target) == 0 else 'target_rank2_to8'
            else:
                expanded = probe.predict(left, case['typed'], 4096)
                assert len(expanded['candidates']) < 4096
                record['miss_stage'] = 'target_below8' if target in [core.norm(r['word']) for r in expanded['candidates']] else 'target_not_generated'
        records.append(record)
        # Oracle results never enter this state, including on ambiguous words.
        previous.append((case, committed))
    return records


def summarize(cases, records, trials):
    actual = [r['actual'] for r in records]
    static = [r['static'] for r in records]
    result = dict(words=len(cases), trials=len(trials), static=core.metrics(cases, static),
                  closed_loop=core.metrics(cases, actual))
    result['oracle_upstream_repair'] = {mode: core.metrics(cases, [r['oracle_probes'][mode] for r in records])
                                       for mode in ('missed','wrong_edit','all')}
    attribution = collections.Counter()
    examples = []
    for c,r in zip(cases,records,strict=True):
        if c['target'] is None:
            continue
        target = core.norm(c['target'])
        def correct(out):
            return core.norm(out.get('correction') or c['typed']) == target
        ok, original_ok = correct(r['actual']), correct(r['static'])
        attribution['feedback_recovers_static_error'] += ok and not original_ok
        attribution['feedback_loses_static_correct'] += original_ok and not ok
        if ok:
            continue
        attribution['remaining_incorrect_words'] += 1
        typo = core.norm(c['typed']) != target
        attribution['remaining_typos' if typo else 'clean_damage'] += 1
        prior = r['prior_errors']
        bad = prior.get('missed',0) + prior.get('wrong_edit',0)
        if bad:
            attribution['remaining_with_upstream_labelled_errors'] += 1
        elif prior.get('unlabelled',0):
            attribution['remaining_no_labelled_upstream_error_but_ambiguous_history'] += 1
        else:
            attribution['remaining_with_all_previous_words_correct'] += 1
        modes = [mode for mode,out in r['oracle_probes'].items() if correct(out)]
        for mode in modes:
            attribution['remaining_recovered_repairing_'+mode] += 1
        if 'all' in modes:
            examples.append(dict(id=c['id'], typed=c['typed'], target=c['target'],
                left=r['left'], actual=r['actual'].get('correction'), recovered_by=modes,
                prior_errors=prior, stage=r.get('miss_stage')))
    result['attribution'] = dict(attribution)
    result['remaining_typo_stages'] = dict(collections.Counter(r['miss_stage'] for r in records if 'miss_stage' in r))
    if trials:
        corrections = [r['committed'] if core.norm(r['committed']) != core.norm(c['typed']) else None
                       for c,r in zip(cases,records,strict=True)]
        result['sentence_cer'] = core.stream_metrics(trials, cases, corrections)['overall']
    result['upstream_repair_examples'] = examples[:12]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe',type=Path,required=True)
    parser.add_argument('--model',type=Path,default=ROOT/'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--suite',type=Path,default=ROOT/'build/autocorrect-eval-v4/frozen')
    parser.add_argument('--recorded-data',type=Path,default=ROOT/'build/real-revisit-20261025/data3')
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=False)
    paths = dict(probe=args.probe, model=args.model,
        policy=ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_acceptance.json')
    binding = dict(schema='closed-loop-component-audit-v1', script_sha256=core.digest(__file__),
        hashes={name:core.digest(path) for name,path in paths.items()}, parameters_fitted=False,
        experimental_radius_profile=os.environ.get('KEYBOARD_RADIUS_PILOT','parent'),
        ordinary_inference_fields=['left_from_own_committed_output','typed_observation','spelling_limit','timing'],
        oracle_fields='Separate diagnostic previous-word label repairs; never focal/future targets or editor state')
    cohorts = []
    for name,path in [('frozen_touch',args.suite),('recorded_development',args.recorded_data)]:
        manifest = json.loads((path/'manifest.json').read_text())
        for field in ('cases','trials'):
            assert core.digest(path/(field+'.jsonl')) == manifest[field+'_sha256']
        cases, trials = core.rows(path/'cases.jsonl'),core.rows(path/'trials.jsonl')
        if name == 'frozen_touch':
            cases = [c for c in cases if c['panel'] == 'real_touch_observed']
        cases,trials,identity = identify_attempts(cases,trials)
        core.jsonl(args.output/(name+'-cases.jsonl'),cases)
        core.jsonl(args.output/(name+'-trials.jsonl'),trials)
        binding[name+'_attempt_identity'] = identity
        cohorts.append((name,cases,trials))
        binding[name+'_manifest_sha256'] = core.digest(path/'manifest.json')
    core.write_json(args.output/'binding.json',binding)
    probe = Probe(args.probe,args.model,args.output)
    report = dict(binding=binding,cohorts={})
    try:
        for name,cases,trials in cohorts:
            by_id = {c['id']:c for c in cases}
            records = []
            for i,trial in enumerate(trials):
                records.extend(replay_trial(probe,[by_id[k] for k in trial['words']]))
                if (i+1)%100 == 0:
                    print(f'{name}: {i+1}/{len(trials)} trials',flush=True)
            by_record = {r['id']:r for r in records}
            records = [by_record[c['id']] for c in cases]
            core.jsonl(args.output/(name+'-records.jsonl'),records)
            report['cohorts'][name] = summarize(cases,records,trials)
            if name == 'recorded_development':
                report['recorded_by_split'] = {}
                for split in ('fit','calibration','selection','evaluation'):
                    cc,rr = zip(*[(c,r) for c,r in zip(cases,records) if c['split']==split])
                    tt = [t for t in trials if t['split']==split]
                    report['recorded_by_split'][split] = summarize(list(cc),list(rr),tt)
    finally:
        probe.close()
    for name,path in paths.items():
        assert core.digest(path) == binding['hashes'][name],name+' changed during replay'
    report['native_work'] = dict(unique_calls=len(probe.timings),median_ms=statistics.median(probe.timings),
        p95_ms=core.percentile(probe.timings,.95),max_ms=max(probe.timings),
        scope='Sequential cached native Mac calls, includes diagnostic queries; not phone latency')
    report['limitations'] = [
        'Recorded reconstructed copy typing; no user response to hypothetical replacements or phone deadlines.',
        'Oracle probes use earlier labels and supply diagnostic upper bounds, not deployable correction.',
        'Oracle attribution overlaps; a replacement can improve or harm later decisions.',
        'Ambiguous prior words remain as actually committed even in the all-repaired probe.',
        'Development-exposed English streams; no complete natural bilingual typing streams are available here.',
        'Mobile correction snapshots and hard cases are not invented into complete streams.',
    ]
    core.write_json(args.output/'report.json',report)
    print(json.dumps({name:{k:v for k,v in value.items() if k not in ('upstream_repair_examples','oracle_upstream_repair')}
                      for name,value in report['cohorts'].items()},indent=2))


if __name__ == '__main__':
    main()
