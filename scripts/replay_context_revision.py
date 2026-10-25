#!/usr/bin/env python3
"""Offline delayed-correction replay with native batched suffix likelihoods.

Parameters use existing mobile TRAIN-calibration/selection groups only. Frozen
evaluation membership and weights stay fixed. Touch sentences use observed input;
mobile snapshots simulate clean subsequent prompt words, explicitly separately.
"""
import argparse
import collections
import csv
import importlib.util
import itertools
import json
import math
from pathlib import Path
import re
import statistics
import subprocess

import numpy as np
import autocorrect_eval_v2 as core
import autocorrect_objective as objective

PROJECT = core.PROJECT
NATIVE = PROJECT.parent / 'QwenASR/crates/cantonese-ngram/src'
WORD = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*")
GRID = dict(band_width=[1., 2., 4.], future_weight=[.25, .5, 1., 2.],
            max_following=[1, 2, 3], revision_margin=[0., 1., 2., 4., 8.])


def surface(word, typed):
    if typed == typed.upper():
        return word.upper()
    return word[:1].upper() + word[1:] if typed[:1].isupper() else word


def future_from_prompt(case, reference):
    """Use labels only to locate the focal span, never to propose a correction."""
    left = case['left']
    if not reference.casefold().startswith(left.casefold()):
        return []
    matches = list(WORD.finditer(reference, len(left)))
    count = len(case['target'].split())
    if not matches or matches[0].start() != len(left) or len(matches) < count:
        return []
    matched = ' '.join(m.group().casefold() for m in matches[:count])
    if matched != case['target'].casefold():
        return []
    end = matches[count - 1].end()
    result = []
    for m in matches[count:count + 3]:
        if reference[end:m.start()].strip():
            break  # Do not carry uncertainty across punctuation/sentence boundaries.
        result.append(m.group())
        end = m.end()
    return result


class Probe:
    def __init__(self, binary, model, output):
        self.binary, self.model = binary, model
        self.process = subprocess.Popen([str(binary.resolve()), str(model.resolve())], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=(output / 'probe-stderr.log').open('w'), text=True)
        self.log = (output / 'native-cache.jsonl').open('w')
        self.cache = {}
        self.calls = collections.Counter()
        self.times = collections.defaultdict(list)

    def query(self, request):
        request = dict(request, timing=True)
        key = json.dumps(request, sort_keys=True)
        if key in self.cache:
            return self.cache[key]
        self.process.stdin.write(json.dumps(request) + '\n')
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError('Native probe ended unexpectedly; inspect probe-stderr.log')
        result = json.loads(line)
        self.cache[key] = result
        kind = 'spelling' if 'typed' in request else 'revision_sequences'
        self.calls[kind] += 1
        self.times[kind].append(result['elapsedMicros'] / 1000)
        self.log.write(json.dumps(dict(request=request, output=result)) + '\n')
        return result

    def predict(self, left, typed):
        return self.query(dict(left=left[-512:], typed=typed, spelling_limit=8))

    def close(self):
        self.process.stdin.close()
        if self.process.wait(timeout=30) != 0:
            raise RuntimeError('Native probe failed')
        self.log.close()


class Acceptance:
    def __init__(self, policy_path):
        self.policy = json.loads(policy_path.read_text())
        path = PROJECT.parent / 'gboard-probe/improve-v3/features.py'
        spec = importlib.util.spec_from_file_location('replay_acceptance_features', path)
        self.f = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.f)
        self.f.POLICY = self.policy
        text = (NATIVE / 'cnn_spelling.rs').read_text()
        self.f.COST = {k: np.float32(float(re.search(r'const ' + k + r': f32 = ([0-9.]+);', text)[1]))
                       for k in self.f.COST}
        old = self.f.adjacent
        self.f.adjacent = lambda a, b: b in 'cvbnm' if a == ' ' else a in 'cvbnm' if b == ' ' else old(a, b)
        self.dictionary = set((NATIVE / 'english_valid_words.txt').read_text().splitlines())
        self.threshold = math.log(self.policy['threshold'] / (1 - self.policy['threshold']))

    def seed(self, typed, result, include_accepted=False):
        if (result['correction'] and not include_accepted) or not WORD.fullmatch(typed):
            return None
        x, eligible, word = self.f.extract(dict(typed=typed), result)
        if not eligible or not word:
            return None
        lower = word.lower()
        values = list(x) + [float(typed.lower().replace('’', "'") in self.dictionary),
                            float(all(w in self.dictionary for w in lower.split(' ')))]
        normalized = (np.array(values) - self.policy['means']) / self.policy['scales']
        logit = float(self.f.predict_logits(self.f.parameters(self.policy), normalized[None, :])[0])
        # This band is calibrated in logit units, not asserted probability calibration.
        if logit < self.threshold - max(GRID['band_width']) or (not include_accepted and logit >= self.threshold):
            return None
        surfaces = {e['word'] for e in result.get('apostropheEvidence', [])}
        ranked = [r for r in result['candidates'] if r['word'] not in surfaces]
        if not ranked or ranked[0]['word'] != word or ranked[0]['completedChars'] or not WORD.fullmatch(word):
            return None
        alternatives = []
        for r in ranked:
            if r['completedChars'] or not WORD.fullmatch(r['word']) or r['word'].casefold() == typed.casefold():
                continue
            alternatives.append(dict(word=surface(r['word'], typed), score_gap=r['score'] - ranked[0]['score']))
            if len(alternatives) == 3:
                break
        return dict(typed=typed, logit=logit, alternatives=alternatives) if alternatives else None


def evidence(probe, seed, left, following):
    words = [seed['typed']] + [r['word'] for r in seed['alternatives']]
    suffix = ' '.join(following)
    result = probe.query(dict(left=left[-512:], sequences=words + [w + ' ' + suffix for w in words]))
    rows = result['evidence']
    n = len(words)
    if not rows[0]['supported'] or not rows[n]['supported']:
        return None
    original_tail = rows[n]['logProbability'] - rows[0]['logProbability']
    alternatives = []
    for i, candidate in enumerate(seed['alternatives'], 1):
        if rows[i]['supported'] and rows[n + i]['supported']:
            tail = rows[n + i]['logProbability'] - rows[i]['logProbability']
            alternatives.append(dict(candidate, future_gain=tail - original_tail))
    return dict(lag=len(following), logit=seed['logit'], alternatives=alternatives)


def propose(seed, event, rule, threshold):
    if rule is None or event is None or event['lag'] > rule['max_following']:
        return None
    if seed['logit'] < threshold - rule['band_width']:
        return None
    choices = [(seed['logit'] - threshold + r['score_gap'] + rule['future_weight'] * r['future_gain'], r['word'])
               for r in event['alternatives'] if r['future_gain'] > 0]
    if not choices:
        return None
    score, word = max(choices, key=lambda v: (v[0], v[1]))
    return word if score >= rule['revision_margin'] else None


def episode(probe, acceptance, case, following):
    result = probe.predict(case['left'], case['typed'])
    initial = surface(result['correction'], case['typed']) if result['correction'] else case['typed']
    seed = acceptance.seed(case['typed'], result)
    record = dict(id=case['id'], initial=initial, seed=seed, events=[], following_words=len(following))
    if seed is None:
        return record
    completed = []
    for typed in following[:3]:
        left = case['left'] + initial + ' ' + ' '.join(completed) + (' ' if completed else '')
        out = probe.predict(left, typed)
        completed.extend((surface(out['correction'], typed) if out['correction'] else typed).split())
        if len(completed) > 3:
            break
        record['events'].append(evidence(probe, seed, case['left'], completed))
    return record


def final_word(record, rule, threshold):
    if record['seed']:
        for event in record['events']:
            word = propose(record['seed'], event, rule, threshold)
            if word:
                return word
    return record['initial']


def measure(cases, words):
    return core.metrics(cases, [dict(correction=w if w.casefold() != c['typed'].casefold() else None)
                              for c, w in zip(cases, words, strict=True)])


def overall(cases, words):
    panels = {}
    for name in ['historical_full_mobile', 'real_touch_observed', 'authored_hard']:
        ids = [i for i, c in enumerate(cases) if (c['panel'].startswith('mobile_') if name == 'historical_full_mobile' else c['panel'] == name)]
        panels[name] = measure([cases[i] for i in ids], [words[i] for i in ids])
    return dict(score=sum(objective.OBJECTIVE['weights'][k] * v['f05'] for k, v in panels.items()), panels=panels)


def replay_trial(probe, acceptance, cases, rule, refresh_right='drop'):
    """No reference or target enters the state machine or inference request."""
    if refresh_right not in ['drop', 'strict', 'ordinary']:
        raise ValueError('Unknown right refresh rule')
    committed, queue, revisions = [], [], []
    for index, case in enumerate(cases):
        left = ' '.join(committed) + (' ' if committed else '')
        result = probe.predict(left, case['typed'])
        committed.append(surface(result['correction'], case['typed']) if result['correction'] else case['typed'])
        if rule is None:
            continue
        for q in queue:
            q['age'] += len(committed[-1].split())
        queue = [q for q in queue if q['age'] <= rule['max_following']]
        seed = acceptance.seed(case['typed'], result)
        if seed and seed['logit'] >= acceptance.threshold - rule['band_width']:
            queue.append(dict(index=index, seed=seed, age=0))
        queue = queue[-2:]
        for q in queue[:]:
            if q['index'] == index:
                continue
            prefix = ' '.join(committed[:q['index']]) + (' ' if q['index'] else '')
            following = ' '.join(committed[q['index'] + 1:]).split()
            ev = evidence(probe, q['seed'], prefix, following)
            fix = propose(q['seed'], ev, rule, acceptance.threshold)
            if fix:
                revisions.append(dict(id=cases[q['index']]['id'], before=committed[q['index']], after=fix, lag=len(following)))
                committed[q['index']] = fix
                # A prefix rewrite invalidates confidence snapshots of later words.
                older = [old for old in queue if old['index'] < q['index']]
                if refresh_right != 'drop':
                    # One bounded extra pass over only the remaining queued words.
                    # Never re-use their stale confidence or revisit a revised word.
                    for old in queue:
                        j = old['index']
                        if j <= q['index']:
                            continue
                        prefix = ' '.join(committed[:j]) + (' ' if j else '')
                        fresh = probe.predict(prefix, committed[j])
                        right_fix = None
                        if refresh_right == 'ordinary' and fresh['correction']:
                            right_fix = surface(fresh['correction'], committed[j])
                        else:
                            new_seed = acceptance.seed(committed[j], fresh, include_accepted=True)
                            if new_seed and new_seed['logit'] >= acceptance.threshold - rule['band_width']:
                                following = ' '.join(committed[j + 1:]).split()
                                if following:
                                    ev = evidence(probe, new_seed, prefix, following)
                                    right_fix = propose(new_seed, ev, rule, acceptance.threshold)
                                if not right_fix:
                                    older.append(dict(old, seed=new_seed))
                        if right_fix:
                            revisions.append(dict(id=cases[j]['id'], before=committed[j], after=right_fix,
                                                  lag=len(' '.join(committed[j + 1:]).split()), refreshed=True))
                            committed[j] = right_fix
                queue = older
                break  # At most one rewrite at each word boundary.
    return committed, revisions


def run(args):
    args.output.mkdir(parents=True, exist_ok=False)
    manifest = json.loads((args.suite / 'manifest.json').read_text())
    if core.digest(args.suite / 'cases.jsonl') != manifest['cases_sha256'] or core.digest(args.suite / 'trials.jsonl') != manifest['trials_sha256']:
        raise ValueError('Frozen suite changed')
    refs = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(args.sentences.open())}
    binding = dict(schema='context-revision-replay-v1', grid=GRID, max_pending=2, alternatives=3,
                   selection='Calibrate revision margin on original real TRAIN-calibration; select band/weight/lag on original real selection F0.5; parent eligible; ties fewer revisions then smaller workload',
                   objective=objective.OBJECTIVE, frozen_suite_sha256=core.digest(args.suite / 'manifest.json'),
                   script_sha256=core.digest(__file__), probe_sha256=core.digest(args.probe),
                   model_sha256=core.digest(args.model), policy_sha256=core.digest(args.policy),
                   replay_source_sha256=core.digest(args.development), sentence_source_sha256=core.digest(args.sentences),
                   future_sources=dict(mobile='Explicitly simulated clean prompt continuation after the aligned focal span',
                                       touch='Recorded observed words, never the reference', authored='No following text; keep initial decision'),
                   boundaries='Completed words including simulated final-word completion; no wall-clock deadline or editor integration simulated',
                   revision_score='initial_acceptance_logit - immediate_threshold + candidate_initial_score_gap + fitted_weight * following_words_log_likelihood_gain_over_original',
                   limitations=['Development data has historical exposure; no fresh test claim', 'Mobile continuation is simulated clean typing, not recorded future keystrokes',
                                'Word-boundary replay does not simulate deletion/undo, cursor moves, wall-clock cancellation, or phone latency',
                                'Known misspellings outside the near-threshold band, absent alternatives and multiword revisions are not recovered'])
    core.write_json(args.output / 'protocol.json', binding)
    acceptance = Acceptance(args.policy)
    probe = Probe(args.probe, args.model, args.output)
    try:
        development = [c for c in core.rows(args.development) if c['source'] == 'real' and c['fit_split'] in ['calibration', 'selection']]
        # No evaluation row is scored, augmented, or used in parameter choice here.
        records = []
        for i, case in enumerate(development):
            records.append(episode(probe, acceptance, case, future_from_prompt(case, refs[case['group']])))
            if (i + 1) % 500 == 0:
                print('Development episodes', i + 1, flush=True)
        core.jsonl(args.output / 'development-episodes.jsonl', records)
        ids = {fold: [i for i, c in enumerate(development) if c['fit_split'] == fold] for fold in ['calibration', 'selection']}
        def assess(rule, fold):
            ii = ids[fold]
            words = [final_word(records[i], rule, acceptance.threshold) for i in ii]
            result = measure([development[i] for i in ii], words)
            result['revisions'] = sum(w.casefold() != records[i]['initial'].casefold() for i, w in zip(ii, words))
            return result
        trials = [dict(rule=None, calibration=assess(None, 'calibration'), selection=assess(None, 'selection'))]
        for band, beta, lag in itertools.product(GRID['band_width'], GRID['future_weight'], GRID['max_following']):
            candidates = []
            for margin in GRID['revision_margin']:
                rule = dict(band_width=band, future_weight=beta, max_following=lag, revision_margin=margin)
                candidates.append(dict(rule=rule, calibration=assess(rule, 'calibration')))
            chosen = max(candidates, key=lambda r: (r['calibration']['f05'], -r['calibration']['revisions'], r['rule']['revision_margin']))
            chosen['selection'] = assess(chosen['rule'], 'selection')
            trials.append(chosen)
        def key(t):
            rule = t['rule'] or dict(max_following=0, band_width=0, future_weight=0)
            return t['selection']['f05'], -t['selection']['revisions'], -rule['max_following'], -rule['band_width'], -rule['future_weight']
        winner = max(trials, key=key)
        core.write_json(args.output / 'selection.json', dict(baseline=trials[0], trials=trials, winner=winner, evaluation_used=False))
        rule = winner['rule']
        print('Frozen selected rule', json.dumps(winner), flush=True)
        # Only now reveal the unchanged frozen evaluation labels/outcomes.
        cases = core.rows(args.suite / 'cases.jsonl')
        trial_rows = core.rows(args.suite / 'trials.jsonl')
        by_id = {c['id']: c for c in cases}
        future = {}
        for trial in trial_rows:
            for index, identifier in enumerate(trial['words']):
                future[identifier] = [by_id[k]['typed'] for k in trial['words'][index + 1:index + 4]]
        snapshots, before, after = [], [], []
        for i, case in enumerate(cases):
            if case['panel'].startswith('mobile_'):
                following = future_from_prompt(case, refs[case['group'].removeprefix('aalto:')])
            else:
                following = future.get(case['id'], [])
            rec = episode(probe, acceptance, case, following)
            snapshots.append(rec)
            before.append(rec['initial'])
            after.append(final_word(rec, rule, acceptance.threshold))
            if (i + 1) % 1000 == 0:
                print('Frozen episodes', i + 1, flush=True)
        core.jsonl(args.output / 'evaluation-episodes.jsonl', snapshots)
        fixed = dict(baseline=overall(cases, before), candidate=overall(cases, after))
        fixed['delta'] = fixed['candidate']['score'] - fixed['baseline']['score']
        live_before, live_after = dict(zip(by_id, before)), dict(zip(by_id, after))
        revisions = []
        for trial in trial_rows:
            cc = [by_id[k] for k in trial['words']]
            aa, _ = replay_trial(probe, acceptance, cc, None)
            bb, changed = replay_trial(probe, acceptance, cc, rule)
            live_before.update(zip(trial['words'], aa))
            live_after.update(zip(trial['words'], bb))
            revisions.extend(changed)
        baseline_live = [live_before[c['id']] for c in cases]
        candidate_live = [live_after[c['id']] for c in cases]
        live = dict(baseline=overall(cases, baseline_live), candidate=overall(cases, candidate_live))
        live['delta'] = live['candidate']['score'] - live['baseline']['score']
        touch = [c for c in cases if c['panel'] == 'real_touch_observed']
        def touch_cer(words):
            return core.stream_metrics(trial_rows, touch, [words[c['id']] if words[c['id']] != c['typed'] else None for c in touch])
        live['touch_sentence_cer'] = dict(baseline=touch_cer(live_before), candidate=touch_cer(live_after))
        changes = []
        for c, b, a in zip(cases, baseline_live, candidate_live):
            if b.casefold() != a.casefold():
                changes.append(dict(id=c['id'], panel=c['panel'], typed=c['typed'], target=c['target'], before=b, after=a))
        core.jsonl(args.output / 'changes.jsonl', changes)
        core.jsonl(args.output / 'touch-revisions.jsonl', revisions)
        coverage = {panel: dict(cases=sum(c['panel'] == panel for c in cases),
                               with_following=sum(c['panel'] == panel and r['following_words'] > 0 for c, r in zip(cases, snapshots)),
                               near_threshold=sum(c['panel'] == panel and r['seed'] is not None for c, r in zip(cases, snapshots)))
                    for panel in sorted({c['panel'] for c in cases})}
        latency = {kind: dict(calls=len(values), median_ms=statistics.median(values), p95_ms=core.percentile(values, .95))
                   for kind, values in probe.times.items()}
        report = dict(binding=binding, selected_rule=rule, fixed_snapshot_episodes=fixed,
                      observed_touch_closed_loop=live, coverage=coverage, native_latency=latency,
                      changed_cases=len(changes), touch_revisions=len(revisions), production_changed=False)
        core.write_json(args.output / 'report.json', report)
        print(json.dumps(dict(fixed_snapshot=fixed, closed_loop=live, coverage=coverage, changed_cases=len(changes)), indent=2), flush=True)
    finally:
        probe.close()
    if core.digest(args.probe) != binding['probe_sha256'] or core.digest(args.model) != binding['model_sha256']:
        raise ValueError('Probe or model changed during replay')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path, default=PROJECT / 'build/autocorrect-eval-v4/frozen')
    parser.add_argument('--development', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/cases.jsonl')
    parser.add_argument('--sentences', type=Path, default=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv'))
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--policy', type=Path, required=True)
    parser.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args())
