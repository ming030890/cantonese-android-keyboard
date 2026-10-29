#!/usr/bin/env python3
"""Select trained checkpoints that pass the existing preservation regressions.

No fitting, threshold changes or user-reported evaluation examples are used.
"""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import struct

import numpy as np

import autocorrect_eval_v2 as core
from experiment_clean_acceptance import PROJECT, load_features, native
from experiment_noisy_acceptance import PRESERVATION
from experiment_short_features import EXTRAS, extra_features


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--policy', type=Path, required=True)
    parser.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--baseline-scores', type=Path, default=PROJECT / 'build/frequency-ablation-20261029/data/unconditional-scores.json')
    parser.add_argument('--features', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/features.py')
    parser.add_argument('--fixed-parent-threshold', action='store_true')
    parser.add_argument('--data', type=Path)
    parser.add_argument('--exclude-observations', type=Path)
    args = parser.parse_args()
    parent = json.loads(args.policy.read_text())
    helper = load_features(parent, args.features)
    regressions = PRESERVATION + [('Let me know if yyou ', 'heve'),
                                  ('let me know if ', 'hou'), ('', 'gwme'), ('', 'mwrge')]
    cases = [dict(left=left, typed=typed) for left, typed in regressions]
    outputs = native(args.probe, args.model, cases)
    with args.model.open('rb') as file:
        header = file.read(12)
        meta = json.loads(file.read(struct.unpack('<I', header[8:12])[0]))
    forms = defaultdict(list)
    for i in meta['english_word_ids']:
        forms[meta['tokens'][i].lower()].append(meta['tokens'][i])
    dictionary = set((PROJECT.parent / 'QwenASR/crates/cantonese-ngram/src/english_valid_words.txt').read_text().splitlines())
    baseline = json.loads(args.baseline_scores.read_text())
    if args.fixed_parent_threshold:
        if args.data is None:
            raise ValueError('--data is required for new selection metrics')
        data = np.load(args.data / 'features.npz')
        all_cases = core.rows(args.data / 'cases.jsonl')
        decoded = json.loads((args.data / 'decisions.json').read_text())
        subsets = dict(original=[i for i, c in enumerate(all_cases) if c['fit_split']=='selection' and c['source']=='real' and not c.get('noise')],
                       noisy=[i for i, c in enumerate(all_cases) if c['fit_split']=='selection' and c['source']=='real' and c.get('noise')],
                       natural_short=[i for i, c in enumerate(all_cases) if c['fit_split']=='selection' and i >= int(data['old_count'])])
        if args.exclude_observations:
            excluded = {(c['left'],c['typed'].casefold(),(c['target'] or '').casefold()) for c in core.rows(args.exclude_observations)}
            subsets = {name:[i for i in ids if (all_cases[i]['left'],all_cases[i]['typed'].casefold(),all_cases[i]['target'].casefold()) not in excluded]
                       for name,ids in subsets.items()}
    results = []
    for selection in sorted(args.root.glob('*/selection.json')):
        trained = json.loads(selection.read_text())
        checks = []
        for trial in trained['trials']:
            path = selection.parent / (trial['id'] + '-policy.json')
            policy = json.loads(path.read_text())
            if args.fixed_parent_threshold:
                policy['threshold'] = parent['threshold']
                policy['threshold_logit'] = math.log(parent['threshold'] / (1 - parent['threshold']))
            decisions = []
            for case, output in zip(cases, outputs):
                values, allowed, word = helper.extract(case, output)
                typed, candidate = case['typed'].replace('’', "'").lower(), (word or '').lower()
                values = list(values) + [float(typed in dictionary), float(bool(candidate) and all(w in dictionary for w in candidate.split(' ')))]
                if policy.get('runner_penalty_gap'):
                    values[3] -= values[4]
                extra = dict(zip(EXTRAS, extra_features(typed, candidate, case['left'], forms)))
                extra['contextual_log_odds_gain'] = values[2] - (baseline[candidate] - baseline[typed]) if typed in baseline and candidate in baseline else 0.
                values += [extra[name] for name in policy.get('additional_features', [])]
                z = float(helper.predict_logits(helper.parameters(policy), ((np.array(values) - policy['means']) / policy['scales'])[None])[0])
                fixed = helper.restored(output) or (word if candidate == typed and word != typed else '')
                fix = fixed or (word if allowed and z >= policy['threshold_logit'] else None)
                decisions.append(fix or None)
            passed = decisions == [o['correction'] for o in outputs]
            metrics = trial['metrics']
            if passed and args.fixed_parent_threshold:
                columns = list(range(22)) + [22 + EXTRAS.index(name) for name in policy.get('additional_features', [])]
                x = data['raw'][:, columns].copy()
                if policy.get('runner_penalty_gap'):
                    x[:, 3] -= x[:, 4]
                logits = helper.predict_logits(helper.parameters(policy), (x - policy['means']) / policy['scales'])
                fixes = helper.decisions(logits, policy['threshold_logit'], data['eligible'], decoded['words'], decoded['restorations'])
                metrics = {name: helper.metrics([all_cases[i] for i in ids], [fixes[i] for i in ids]) for name, ids in subsets.items()}
                metrics['selection_f05'] = (metrics['original']['f05'] + metrics['noisy']['f05']) / 2
            checks.append(dict(id=trial['id'], passes=passed, decisions=decisions, metrics=metrics))
        valid = [c for c in checks if c['passes']]
        winner = max(valid, key=lambda c: (c['metrics']['selection_f05'], c['metrics']['natural_short']['f05'])) if valid else None
        report = dict(variant=selection.parent.name, checks=checks, winner=winner,
                      guards=[dict(**c, expected=o['correction']) for c, o in zip(cases, outputs)],
                      evaluation_used_for_fitting=False, new_weights_fitted=False,
                      source_selection_sha256=core.digest(selection))
        prefix = 'fixed-threshold-' if args.fixed_parent_threshold else ''
        report['fixed_parent_threshold'] = args.fixed_parent_threshold
        report['excluded_observations_sha256'] = core.digest(args.exclude_observations) if args.exclude_observations else None
        core.write_json(selection.parent / (prefix + 'qualified-selection.json'), report)
        if winner:
            payload = json.loads((selection.parent / (winner['id'] + '-policy.json')).read_text())
            if args.fixed_parent_threshold:
                payload.update(threshold=parent['threshold'], threshold_logit=math.log(parent['threshold'] / (1-parent['threshold'])))
                payload['provenance']['calibration'] = 'Accepted parent threshold retained; no new cutoff fitted'
            payload['provenance'].pop('evaluation_used_for_training_or_selection', None)
            clean_inputs = bool(args.data and json.loads((args.data / 'protocol.json').read_text()).get('evaluation_inputs_excluded'))
            payload['provenance'].update(frozen_inputs_excluded_from_updates=clean_inputs,
                                        checkpoint_ranking='TRAIN selection metrics after existing regression qualification',
                                        development_objective_used_to_compare_families=True)
            core.write_json(selection.parent / (prefix + 'qualified-policy.json'), payload)
        results.append(dict(variant=selection.parent.name, passes=len(valid), winner=winner['id'] if winner else None,
                            selection_f05=winner['metrics']['selection_f05'] if winner else None))
    core.write_json(args.root / ('fixed-threshold-qualification.json' if args.fixed_parent_threshold else 'qualification.json'), results)
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
