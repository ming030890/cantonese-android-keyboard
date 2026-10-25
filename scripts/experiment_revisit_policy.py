#!/usr/bin/env python3
"""Joint offline eligibility/revisit learning. Immediate decisions never wait.

Fit on TRAIN, calibrate on disjoint calibration inputs, select on selection,
freeze before evaluating. References enter alignment and scoring only. Native
requests contain observed input and generated hypotheses, never target labels.
"""
import argparse
import collections
import csv
import json
from pathlib import Path

import numpy as np
import autocorrect_eval_v2 as core
import replay_context_revision as old

MAX_REJECTED_BAND = 12.
MAX_ACCEPTED_BAND = 8.
RIDGES = [1., 100., 10000.]
GATES = ['rejected_1', 'rejected_4', 'rejected_12', 'both_1', 'both_4',
         'both_wide', 'accepted_4', 'learned_25', 'learned_50', 'learned_75']
UNCOVERED = ['target_in_top8', 'target_top1', 'miss_absent_from_top8', 'miss_ranking_or_acceptance']


def normalized(s):
    return s.casefold().replace('’', "'")


def metrics(cases, words):
    m = old.measure(cases, words)
    return {k: v for k, v in m.items() if k not in UNCOVERED}


def seed_for(acceptance, typed, output):
    initial = old.surface(output['correction'], typed) if output['correction'] else typed
    if not old.WORD.fullmatch(typed) or not old.WORD.fullmatch(initial):
        return None  # No retroactive span changes in this experiment.
    x, eligible, top = acceptance.f.extract(dict(typed=typed), output)
    if top is None:
        return None
    values = list(x) + [float(normalized(typed) in acceptance.dictionary),
                        float(all(w in acceptance.dictionary for w in top.lower().split()))]
    logit = float(acceptance.f.predict_logits(acceptance.f.parameters(acceptance.policy),
                  ((np.array(values) - acceptance.policy['means']) / acceptance.policy['scales'])[None, :])[0])
    margin = logit - acceptance.threshold
    accepted = normalized(initial) != normalized(typed)
    candidates, seen = [], set()
    # Original spelling is explicitly available to undo a mistaken autocorrection.
    for word, rank, score, edits in [(typed, -1, output.get('literalScore'), 0),
                                     (initial, -1, None, 0)] + [
            (old.surface(r['word'], typed), i, r['score'], r['edits'])
            for i, r in enumerate(output['candidates']) if not r['completedChars'] and old.WORD.fullmatch(r['word'])]:
        key = normalized(word)
        if key in seen:
            if score is not None:
                next(c for c in candidates if normalized(c['word']) == key)['score'] = score
            continue
        seen.add(key)
        candidates.append(dict(word=word, rank=rank, score=score, edits=edits,
                               dictionary=float(key in acceptance.dictionary)))
    if len(candidates) < 2:
        return None
    # Queue features use immediate evidence only, available without extra inference.
    features = values + [float(accepted), float(eligible), len(candidates), margin,
                         max(0., -margin), max(0., margin), float(normalized(initial) in acceptance.dictionary)]
    return dict(typed=typed, initial=initial, margin=margin, accepted=accepted,
                features=features, candidates=candidates,
                broad=(-MAX_REJECTED_BAND <= margin <= MAX_ACCEPTED_BAND))


def evidence(probe, seed, left, following_groups):
    """Batch all hypothesis prefixes and suffix lengths in one shared-prefix call."""
    options = seed['candidates']
    sequences = [c['word'] for c in options]
    for following in following_groups:
        sequences.extend(c['word'] + ' ' + ' '.join(following) for c in options)
    rows = probe.query(dict(left=left[-512:], sequences=sequences))['evidence']
    n = len(options)
    current = next(i for i, c in enumerate(options) if normalized(c['word']) == normalized(seed['initial']))
    result = []
    for lag_index, following in enumerate(following_groups):
        offset = (lag_index + 1) * n
        if not rows[current]['supported'] or not rows[offset + current]['supported']:
            continue
        current_tail = rows[offset + current]['logProbability'] - rows[current]['logProbability']
        alternatives = []
        for i, c in enumerate(options):
            if i == current or not rows[i]['supported'] or not rows[offset + i]['supported']:
                continue
            tail = rows[offset + i]['logProbability'] - rows[i]['logProbability']
            raw_gain = rows[i]['logProbability'] - rows[current]['logProbability']
            gain = tail - current_tail
            initial_score = options[current]['score']
            score_gain = c['score'] - initial_score if c['score'] is not None and initial_score is not None else 0.
            features = seed['features'] + [raw_gain, gain, gain / len(following), max(0., gain), min(0., gain),
                         score_gain, float(normalized(c['word']) == normalized(seed['typed'])),
                         c['rank'], c['edits'], len(c['word']) - len(seed['initial']), c['dictionary'],
                         len(following), gain * seed['margin'], raw_gain * gain,
                         gain * float(seed['accepted']), gain * c['dictionary']]
            alternatives.append(dict(word=c['word'], rank=c['rank'], gain=gain, features=features))
        result.append(dict(lag=len(following), alternatives=alternatives))
    return result


def episode(probe, acceptance, case, following):
    out = probe.predict(case['left'], case['typed'])
    initial = old.surface(out['correction'], case['typed']) if out['correction'] else case['typed']
    seed = seed_for(acceptance, case['typed'], out)
    record = dict(id=case['id'], initial=initial, seed=seed, events=[])
    if seed is None or not seed['broad'] or not following:
        return record
    completed, groups = [], []
    for typed in following[:3]:
        left = case['left'] + initial + ' ' + ' '.join(completed) + (' ' if completed else '')
        next_out = probe.predict(left, typed)
        completed.extend((old.surface(next_out['correction'], typed) if next_out['correction'] else typed).split())
        if len(completed) > 3:
            break
        groups.append(list(completed))
    if groups:
        record['events'] = evidence(probe, seed, case['left'], groups)
    return record


def outcome(typed, target, word):
    changed = normalized(word) != normalized(typed)
    tp = changed and normalized(word) == normalized(target)
    return float(tp), float(changed and not tp)


def gain_label(case, initial, proposal, baseline_f05):
    a, b = outcome(case['typed'], case['target'], initial)
    c, d = outcome(case['typed'], case['target'], proposal)
    # Local numerator-minus-F*denominator utility for the exact corpus F0.5.
    return (1.25 - baseline_f05) * (c - a) - baseline_f05 * (d - b)


def fit_ridge(x, y, weights, ridge):
    x, y, weights = np.asarray(x), np.asarray(y), np.asarray(weights)
    mean = np.average(x, axis=0, weights=weights)
    scale = np.sqrt(np.average((x - mean) ** 2, axis=0, weights=weights))
    scale = np.maximum(scale, .01)
    z = np.column_stack([np.ones(len(x)), np.clip((x - mean) / scale, -20., 20.)])
    regularizer = np.eye(z.shape[1]) * ridge
    regularizer[0, 0] = .000001
    coef = np.linalg.solve(z.T @ (weights[:, None] * z) + regularizer, z.T @ (weights * y))
    return dict(means=mean.tolist(), scales=scale.tolist(), coefficients=coef.tolist(), ridge=ridge)


def predict(model, x):
    z = np.clip((np.asarray(x) - model['means']) / model['scales'], -20., 20.)
    return z @ np.asarray(model['coefficients'][1:]) + model['coefficients'][0]


def admitted(seed, rule, value_model):
    if seed is None or not seed['broad']:
        return False
    gate = rule['gate']
    if gate.startswith('learned_'):
        return float(predict(value_model, seed['features'])) >= rule['queue_cutoff']
    if gate == 'both_wide':
        return True
    family, band = gate.rsplit('_', 1)
    return abs(seed['margin']) <= float(band) and (family == 'both' or (seed['accepted'] == (family == 'accepted')))


def best_event(event, seed, rule, model):
    if event['lag'] > rule['max_following']:
        return None
    options = [c for c in event['alternatives'] if c['rank'] < rule['candidate_limit'] and
               (not rule['positive_future'] or c['gain'] > 0)]
    if not options:
        return None
    scores = predict(model, [c['features'] for c in options])
    j = int(np.argmax(scores))
    return dict(word=options[j]['word'], score=float(scores[j]))


def final_word(record, rule, model, value_model):
    if rule is not None and admitted(record['seed'], rule, value_model):
        for event in record['events']:
            choice = best_event(event, record['seed'], rule, model)
            if choice and choice['score'] >= rule['threshold']:
                return choice['word']
    return record['initial']


def train(cases, records):
    fit = [i for i, c in enumerate(cases) if c['fit_split'] == 'fit']
    f05 = metrics([cases[i] for i in fit], [records[i]['initial'] for i in fit])['f05']
    x, y, weights, qx, qy = [], [], [], [], []
    for i in fit:
        record, case = records[i], cases[i]
        options = [c for e in record['events'] for c in e['alternatives']]
        if not options:
            continue
        labels = [gain_label(case, record['initial'], c['word'], f05) for c in options]
        x.extend(c['features'] for c in options)
        y.extend(labels)
        weights.extend([1. / len(options)] * len(options))
        qx.append(record['seed']['features'])
        qy.append(max(0., max(labels)))
    models = [fit_ridge(x, y, weights, ridge) for ridge in RIDGES]
    value = fit_ridge(qx, qy, np.ones(len(qy)), 100.)
    values = predict(value, qx)
    cuts = {f'learned_{q}': float(np.percentile(values, q)) for q in [25, 50, 75]}
    return models, value, cuts, dict(fit_baseline_f05=f05, proposal_rows=len(y), queue_rows=len(qy),
                                    beneficial_rows=sum(v > 0 for v in y), harmful_rows=sum(v < 0 for v in y))


def tune(cases, records, models, value, cuts, incumbent_words):
    initial = np.array([normalized(r['initial']) for r in records], dtype=object)
    typed = np.array([normalized(c['typed']) for c in cases], dtype=object)
    targets = np.array([normalized(c['target']) for c in cases], dtype=object)
    cal = np.array([c['fit_split'] == 'calibration' for c in cases])
    sel = np.array([c['fit_split'] == 'selection' for c in cases])
    def assess(words, mask):
        changed = words[mask] != typed[mask]
        hits = changed & (words[mask] == targets[mask])
        tp, fp = int(hits.sum()), int((changed & ~hits).sum())
        typos = int((typed[mask] != targets[mask]).sum())
        return dict(f05=1.25 * tp / (tp + fp + .25 * typos), tp=tp, fp=fp,
                    clean_changes=int((changed & (typed[mask] == targets[mask])).sum()),
                    revisions=int((words[mask] != initial[mask]).sum()))
    trials = []
    for model_index, model in enumerate(models):
        for limit in [3, 8]:
            for positive in [True, False]:
                base_rule = dict(candidate_limit=limit, positive_future=positive, max_following=3)
                scores = np.full((len(cases), 3), -np.inf)
                words = np.repeat(initial[:, None], 3, axis=1)
                for i, record in enumerate(records):
                    for event in record['events']:
                        choice = best_event(event, record['seed'], base_rule, model)
                        if choice:
                            scores[i, event['lag'] - 1] = choice['score']
                            words[i, event['lag'] - 1] = normalized(choice['word'])
                for gate in GATES:
                    rule = dict(base_rule, gate=gate, queue_cutoff=cuts.get(gate))
                    admission = np.array([admitted(r['seed'], rule, value) for r in records])
                    for lag in [1, 2, 3]:
                        available = scores[cal & admission, :lag]
                        finite = available[np.isfinite(available)]
                        if not len(finite):
                            continue
                        thresholds = sorted(set(np.quantile(finite, np.linspace(0, 1, 41)).tolist() + [float(finite.max() + 1.)]))
                        def decisions(threshold):
                            passing = (scores[:, :lag] >= threshold) & admission[:, None]
                            first = passing.argmax(axis=1)
                            return np.where(passing.any(axis=1), words[np.arange(len(cases)), first], initial)
                        best = None
                        for threshold in thresholds:
                            m = assess(decisions(threshold), cal)
                            key = (m['f05'], -m['revisions'], threshold)
                            if best is None or key > best[0]:
                                best = (key, threshold, m)
                        threshold = best[1]
                        prediction = decisions(threshold)
                        rule = dict(rule, max_following=lag, threshold=threshold)
                        t = dict(rule=rule, model_index=model_index, calibration=best[2], selection=assess(prediction, sel))
                        t['selection']['eligible_cases'] = int((admission & sel).sum())
                        trials.append(t)
        print('Tuned model', model_index, flush=True)
    key = lambda t: (t['selection']['f05'], -t['selection']['revisions'], -t['selection']['eligible_cases'], -t['rule']['max_following'])
    winner = max(trials, key=key)
    parents = dict(no_revisit=assess(initial, sel), incumbent=assess(np.array([normalized(w) for w in incumbent_words], dtype=object), sel))
    selected = max([(m['f05'], -m['revisions'], name) for name, m in parents.items()] +
                   [(winner['selection']['f05'], -winner['selection']['revisions'], 'learned')])[2]
    return dict(winner=winner, selected_family=selected, parents=parents, trials=trials, evaluation_used=False)


def replay_trial(probe, acceptance, cases, rule, model, value):
    committed, queue, revisions = [], [], []
    for i, case in enumerate(cases):
        prefix = ' '.join(committed) + (' ' if committed else '')
        out = probe.predict(prefix, case['typed'])
        initial = old.surface(out['correction'], case['typed']) if out['correction'] else case['typed']
        committed.append(initial)
        for q in queue:
            q['age'] += len(initial.split())
        queue = [q for q in queue if q['age'] <= rule['max_following']]
        seed = seed_for(acceptance, case['typed'], out)
        if admitted(seed, rule, value):
            queue.append(dict(index=i, seed=seed, age=0))
        queue = queue[-2:]
        for q in queue[:]:
            j = q['index']
            if j == i:
                continue
            left = ' '.join(committed[:j]) + (' ' if j else '')
            following = ' '.join(committed[j + 1:]).split()
            events = evidence(probe, q['seed'], left, [following])
            choice = best_event(events[0], q['seed'], rule, model) if events else None
            if choice and choice['score'] >= rule['threshold']:
                revisions.append(dict(id=cases[j]['id'], before=committed[j], after=choice['word'],
                                      initially_accepted=q['seed']['accepted'], lag=len(following)))
                committed[j] = choice['word']
                queue = [entry for entry in queue if entry['index'] < j]
                break
    return committed, revisions


def coverage(cases, records, rule, model, value, old_acceptance):
    audit = collections.Counter()
    for c, r in zip(cases, records):
        if c['target'] is None:
            continue
        wrong = normalized(r['initial']) != normalized(c['target'])
        s = r['seed']
        audit['initial_wrong'] += wrong
        audit['initial_wrong_accepted' if s and s['accepted'] else 'initial_wrong_kept'] += wrong
        if not wrong:
            continue
        if s is None:
            audit['unsupported_span_or_empty_pool'] += 1
            continue
        pool = {normalized(w['word']) for w in s['candidates']}
        if normalized(c['target']) not in pool:
            audit['target_missing_from_whole_word_pool'] += 1
            continue
        audit['recoverable_target_in_pool'] += 1
        audit['recoverable_outside_broad_band'] += not s['broad']
        if not r['events']:
            audit['recoverable_without_scored_future'] += 1
            continue
        if not admitted(s, rule, value):
            audit['recoverable_excluded_by_selected_eligibility'] += 1
        elif normalized(final_word(r, rule, model, value)) != normalized(c['target']):
            audit['recoverable_admitted_but_not_corrected'] += 1
        else:
            audit['recoverable_corrected_in_focal_replay'] += 1
    return dict(audit)


def load_cache(probe, directories, expected):
    for directory in directories:
        report = json.loads((directory / 'report.json').read_text())
        saved = report.get('binding') or report['protocol']['source_hashes']
        for name in ['probe', 'model', 'policy']:
            assert saved.get(name + '_sha256', saved.get(name)) == expected[name]
        with (directory / 'native-cache.jsonl').open() as stream:
            for row in map(json.loads, stream):
                probe.cache[json.dumps(row['request'], sort_keys=True)] = row['output']


def run(a):
    a.output.mkdir(parents=True, exist_ok=False)
    previous = json.loads((a.previous / 'report.json').read_text())
    for name, p in [('probe', a.probe), ('policy', a.policy), ('model', a.model)]:
        assert core.digest(p) == previous['protocol']['source_hashes'][name]
    manifest = json.loads((a.suite / 'manifest.json').read_text())
    for name in ['cases', 'trials']:
        assert core.digest(a.suite / (name + '.jsonl')) == manifest[name + '_sha256']
    cases = core.rows(a.previous / 'development.jsonl')
    cal_inputs = {core.input_key(c) for c in cases if c['fit_split'] == 'calibration'}
    before = len(cases)
    cases = [c for c in cases if c['fit_split'] != 'fit' or core.input_key(c) not in cal_inputs]
    core.jsonl(a.output / 'development.jsonl', cases)
    with a.sentences.open() as stream:
        refs = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(stream)}
    binding = dict(schema='revisit-eligibility-learning-v1', script_sha256=core.digest(__file__),
                   replay_sha256=core.digest(old.__file__), previous_report_sha256=core.digest(a.previous / 'report.json'),
                   source_hashes={n: core.digest(p) for n, p in [('probe', a.probe), ('policy', a.policy), ('model', a.model), ('sentences', a.sentences)]},
                   frozen_suite_sha256=core.digest(a.suite / 'manifest.json'),
                   counts=dict(collections.Counter(c['fit_split'] for c in cases)), removed_fit_calibration_duplicates=before - len(cases),
                   grid=dict(ridges=RIDGES, gates=GATES, candidate_limit=[3, 8], following=[1, 2, 3], positive_future=[True, False], calibration_threshold_quantiles=41),
                   broad_collection_band=dict(rejected=MAX_REJECTED_BAND, accepted=MAX_ACCEPTED_BAND),
                   train='Ridge regression of exact F0.5 local utility change; per-episode total weight one. Separate pre-context ridge estimates recoverable utility for queue eligibility.',
                   runtime='Immediate native decision unchanged; max two queued unsure decisions; max one rewrite per boundary; prefix changes discard later snapshots',
                   limitations=['Additional Aalto records are selected correction events, not population prevalence', 'Mobile future is clean prompt simulation', 'Historically exposed development benchmark', 'No phone timing, cancellation or InputConnection integration'],
                   production_changed=False)
    core.write_json(a.output / 'protocol.json', binding)
    acceptance = old.Acceptance(a.policy)
    probe = old.Probe(a.probe, a.model, a.output)
    load_cache(probe, a.cache, binding['source_hashes'])
    try:
        records = []
        for i, c in enumerate(cases):
            records.append(episode(probe, acceptance, c, old.future_from_prompt(c, refs[c['group']])))
            if (i + 1) % 1000 == 0:
                print('Development', i + 1, flush=True)
        core.jsonl(a.output / 'development-episodes.jsonl', records)
        models, value, cuts, training = train(cases, records)
        old_records = {r['id']: r for r in core.rows(a.previous / 'development-episodes.jsonl')}
        incumbent_words = [old.final_word(old_records[c['id']], previous['incumbent_rule'], acceptance.threshold) for c in cases]
        selection = tune(cases, records, models, value, cuts, incumbent_words)
        core.write_json(a.output / 'selection.json', selection)
        winner = selection['winner']
        model, rule = models[winner['model_index']], winner['rule']
        core.write_json(a.output / 'frozen-policy.json', dict(model=model, value_model=value, rule=rule,
                        training=training, selected_family=selection['selected_family'], evaluation_used=False))
        print('Frozen selection', json.dumps(dict(winner=winner, selected_family=selection['selected_family'], parents=selection['parents'])), flush=True)
        # Evaluation labels enter final metrics/coverage only after policy freeze.
        frozen = core.rows(a.suite / 'cases.jsonl')
        trials = core.rows(a.suite / 'trials.jsonl')
        by_id = {c['id']: c for c in frozen}
        future = {identifier: [by_id[k]['typed'] for k in trial['words'][j + 1:j + 4]]
                  for trial in trials for j, identifier in enumerate(trial['words'])}
        evaluation = []
        for i, c in enumerate(frozen):
            following = old.future_from_prompt(c, refs[c['group'].removeprefix('aalto:')]) if c['panel'].startswith('mobile_') else future.get(c['id'], [])
            evaluation.append(episode(probe, acceptance, c, following))
            if (i + 1) % 1000 == 0:
                print('Evaluation', i + 1, flush=True)
        core.jsonl(a.output / 'evaluation-episodes.jsonl', evaluation)
        words = {r['id']: final_word(r, rule, model, value) for r in evaluation}
        fixed = old.overall(frozen, [words[c['id']] for c in frozen])
        revisions = []
        for trial in trials:
            cc = [by_id[k] for k in trial['words']]
            ww, changes = replay_trial(probe, acceptance, cc, rule, model, value)
            words.update(zip(trial['words'], ww))
            revisions.extend(changes)
        live = old.overall(frozen, [words[c['id']] for c in frozen])
        touch = [c for c in frozen if c['panel'] == 'real_touch_observed']
        live['touch_cer'] = core.stream_metrics(trials, touch, [words[c['id']] if words[c['id']] != c['typed'] else None for c in touch])
        old_predictions = json.loads((a.previous / 'predictions.json').read_text())
        changes = [dict(id=c['id'], panel=c['panel'], typed=c['typed'], target=c['target'], before=old_predictions['incumbent'][c['id']], after=words[c['id']])
                   for c in frozen if normalized(words[c['id']]) != normalized(old_predictions['incumbent'][c['id']])]
        core.jsonl(a.output / 'changes-vs-incumbent.jsonl', changes)
        core.jsonl(a.output / 'touch-revisions.jsonl', revisions)
        core.write_json(a.output / 'predictions.json', words)
        audit = coverage(frozen, evaluation, rule, model, value, acceptance)
        report = dict(binding=binding, selected_family=selection['selected_family'], rule=rule,
                      training=training, fixed_focal=fixed, observed_touch_closed_loop=live,
                      no_revisit_baseline=previous['results']['baseline']['score'], incumbent_score=previous['results']['incumbent']['score'],
                      delta_vs_no_revisit=live['score']-previous['results']['baseline']['score'],
                      delta_vs_incumbent=live['score']-previous['results']['incumbent']['score'],
                      coverage=audit, development_coverage=coverage(cases, records, rule, model, value, acceptance),
                      touch_revisions=len(revisions), accepted_revisited=sum(r['initially_accepted'] for r in revisions),
                      changed_vs_incumbent=len(changes), production_changed=False)
        core.write_json(a.output / 'report.json', report)
        print('Final', json.dumps(dict(score=live['score'], delta=report['delta_vs_incumbent'], coverage=audit)), flush=True)
    finally:
        probe.close()


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--previous', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--cache', type=Path, action='append', default=[])
    p.add_argument('--suite', type=Path, default=core.PROJECT / 'build/autocorrect-eval-v4/frozen')
    p.add_argument('--model', type=Path, default=core.PROJECT / 'app/src/main/assets/language_model/model.cng')
    p.add_argument('--sentences', type=Path, default=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv'))
    run(p.parse_args())
