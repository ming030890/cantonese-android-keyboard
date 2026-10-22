#!/usr/bin/env python3
"""Fit only the two remaining multi-edit surcharges on recorded TRAIN errors.

The five v66 base costs, acceptance model/cutoff, candidate generation and split
costs stay fixed. Evaluation is revealed only after a complete selection freeze.
"""
import argparse
import json
from pathlib import Path
import numpy as np

from audit_candidate_pipeline import digest
from analyze_single_edit_root_cause import metrics
from fit_edit_position_costs import prepare, predict, NAMES
from iterate_english_policy import split, hashed
from iterate_unambiguous_neighbors import load_real, unique_neighbor


def fit(x, lm, groups, baseline, family, regularization):
    training = []
    for g in groups:
        c = g['case']
        if split(c['group']) != 'train' or int(hashed('calibration:' + c['group'])[:8], 16) % 5 == 0:
            continue
        if c['typed'].lower() != c['target'] and any(r['word'].lower() == c['target'] for r in g['rows']):
            training.append(g)
    ids = np.concatenate([np.arange(g['a'], g['b']) for g in training])
    starts = np.cumsum([0] + [g['b'] - g['a'] for g in training[:-1]])
    lengths = np.array([g['b'] - g['a'] for g in training])
    gold = np.array([r['word'].lower() == g['case']['target'] for g in training for r in g['rows']], dtype=float)
    design = x[ids, 5:7] * baseline[5:7]
    if family == 'shared_multiplier':
        design = design.sum(axis=1, keepdims=True)
    parameters = np.ones(design.shape[1])
    evidence = lm[ids] - (x[ids] @ baseline - design @ parameters)
    for epoch in range(600):
        scores = evidence - design @ parameters
        scores -= np.repeat(np.maximum.reduceat(scores, starts), lengths)
        probabilities = np.exp(scores)
        probabilities /= np.repeat(np.add.reduceat(probabilities, starts), lengths)
        gradient = design.T @ (gold - probabilities) / len(training) + regularization * (parameters - 1.)
        parameters = np.maximum(0., parameters - .075 / (1 + epoch / 300) * gradient)
    result = baseline.copy()
    result[5:7] *= parameters[0] if family == 'shared_multiplier' else parameters
    support = dict(fit_cases=len(training),
                   cases_with_any_multi_edit_candidate=sum(bool(x[g['a']:g['b'], 5:7].any()) for g in training),
                   gold_cases_by_surcharge={NAMES[k]: sum(any(r['word'].lower() == g['case']['target'] and x[g['a']+j,k] > 0
                       for j,r in enumerate(g['rows'])) for g in training) for k in (5,6)})
    return result, support


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--evaluate', action='store_true')
    p.add_argument('--frozen-selection', type=Path)
    p.add_argument('--source', type=Path, default=Path('/Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt'))
    args = p.parse_args()
    if args.evaluate and not args.frozen_selection:
        p.error('freeze selection before evaluation')
    project = Path(__file__).resolve().parents[1]
    native = project.parent/'QwenASR/crates/cantonese-ngram/src'
    policy_file = native/'english_acceptance.json'
    apostrophe_file = native/'english_apostrophe_acceptance.json'
    policy = json.loads(policy_file.read_text())
    apostrophe = json.loads(apostrophe_file.read_text())
    previous = project/'config/english_iteration/2026-10-21/edit-position-costs/selection.json'
    baseline = np.array([json.loads(previous.read_text())['champion']['costs'][k] for k in NAMES])
    cases, outputs, cache_file, cases_file, refs_file = load_real(project)
    print('Preparing recorded candidate pools', flush=True)
    x, lm, groups = prepare(cases, outputs, policy, apostrophe)
    base = predict(x, lm, groups, baseline, policy)[0]
    accepted_file = project/'build/edit-position-costs-2026-10-21/native-verification.predictions.json'
    accepted = next(row['outputs'] for row in json.loads(accepted_file.read_text()) if row['name'] == 'real')
    assert base == [o['correction'] for o in accepted]
    gen_file = project/'build/acceptance-refit-2026-10-21/cases.jsonl'
    gen_cache = project/'build/acceptance-refit-2026-10-21/generated-native.json'
    generated = [json.loads(line) for line in gen_file.open() if json.loads(line)['source'] == 'generated']
    gen_outputs = json.loads(gen_cache.read_text())['outputs']
    lexicon = {line.split()[0] for line in args.source.open() if len(line.split()[0]) <= 32}
    pairs = [(c,o) for c,o in zip(generated, gen_outputs) if split(c['group']) in ('selection','evaluation')
             and (c['typed'] == c['target'] or unique_neighbor(c['typed'], lexicon) == c['target'])]
    gc, go = map(list, zip(*pairs))
    gx, gl, gg = prepare(gc, go, policy, apostrophe)
    gen_base = predict(gx, gl, gg, baseline, policy)[0]
    def summarize(rows, fixes, partition, short=False):
        ids = [i for i,c in enumerate(rows) if split(c['group']) == partition
               and (not short or c['typed'] != c['target'] and 4 <= len(c['typed']) <= 5)]
        return metrics([rows[i] for i in ids], [fixes[i] for i in ids])
    def panels(fixes, gen_fixes, partition):
        return dict(real=summarize(cases, fixes, partition),
                    unambiguous_neighbor=summarize(gc, gen_fixes, partition),
                    unambiguous_neighbor_4_5=summarize(gc, gen_fixes, partition, True))
    baseline_selection = panels(base, gen_base, 'selection')
    trials = []
    for family in ('shared_multiplier', 'separate_surcharges'):
        for regularization in (.01, .1):
            costs, support = fit(x, lm, groups, baseline, family, regularization)
            assert np.array_equal(costs[:5], baseline[:5]) and costs[7] == baseline[7]
            assert np.isfinite(costs).all() and (costs >= 0).all()
            fixes = predict(x, lm, groups, costs, policy)[0]
            gen_fixes = predict(gx, gl, gg, costs, policy)[0]
            selection = panels(fixes, gen_fixes, 'selection')
            trials.append(dict(family=family, regularization=regularization, costs=dict(zip(NAMES, costs.tolist())),
                               support=support, selection=selection, recalibrate_cutoff=False))
            print(f'{family} reg={regularization}: costs={costs[5:7]} F0.5={selection["real"]["f05"]:.8f}', flush=True)
    eligible = [t for t in trials if t['selection']['real']['f05'] > baseline_selection['real']['f05']
                and all(t['selection'][name]['f05'] >= baseline_selection[name]['f05']
                        for name in ('unambiguous_neighbor', 'unambiguous_neighbor_4_5'))]
    winner = max(eligible, key=lambda t:(t['selection']['real']['f05'],t['family']=='shared_multiplier',t['regularization'])) if eligible else None
    diagnostic_trial = max(trials, key=lambda t:t['selection']['real']['f05'])
    diagnostic_costs = np.array([diagnostic_trial['costs'][name] for name in NAMES])
    _, _, old_best, _, _ = predict(gx, gl, gg, baseline, policy)
    new_fix, _, new_best, _, _ = predict(gx, gl, gg, diagnostic_costs, policy)
    lost = [i for i,c in enumerate(gc) if split(c['group']) == 'selection' and c['typed'] != c['target']
            and gen_base[i] == c['target'] and new_fix[i] != c['target']]
    diagnostic = dict(family=diagnostic_trial['family'], regularization=diagnostic_trial['regularization'],
                      primary_correct_fixes_lost=len(lost),
                      lost_with_same_correct_winner=sum(old_best[i] == new_best[i] == gc[i]['target'] for i in lost),
                      lost_with_different_winner=sum(old_best[i] != new_best[i] for i in lost),
                      explanation='For these one-edit neighbour targets the score is unchanged. A preserved correct winner '
                                  'that stops correcting therefore loses acceptance through the runner margin.')
    files = [Path(__file__), Path(__file__).with_name('fit_edit_position_costs.py'), policy_file, apostrophe_file,
             native/'cnn_spelling.rs', previous, cache_file, cases_file, refs_file, accepted_file, gen_file, gen_cache, args.source]
    report = dict(scope='Only two additional-edit surcharges; v66 base costs, acceptance/cutoff and all generation/split rules fixed.',
                  protocol='Four TRAIN ranking fits; select better real F0.5 with no primary or short-primary F0.5 decline. '
                           'Keep baseline on ties. Existing development evaluation is historically exposed, not independent confirmation.',
                  baseline_costs=dict(zip(NAMES, baseline.tolist())), baseline_selection=baseline_selection,
                  trials=trials, champion=winner, selection_diagnostic=diagnostic, hashes={str(f):digest(f) for f in files})
    if args.evaluate:
        assert json.loads(args.frozen_selection.read_text()) == report
        report['baseline_evaluation'] = panels(base, gen_base, 'evaluation')
        if winner:
            costs = np.array([winner['costs'][name] for name in NAMES])
            report['evaluation'] = panels(predict(x,lm,groups,costs,policy)[0], predict(gx,gl,gg,costs,policy)[0], 'evaluation')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(dict(baseline=baseline_selection, champion=winner), indent=2))


if __name__ == '__main__':
    main()
