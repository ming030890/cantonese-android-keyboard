#!/usr/bin/env python3
"""Matched acceptance-NN ablations; no fitting on the frozen objective/user cases.

Train the same 24-hidden-unit network from scratch with identical data/seeds.
Zero and freeze disabled input columns, making score removal an actual ablation.
Calibration uses the original real/teacher cohort, not synthetic prevalence.
"""
import argparse
import collections
import copy
import json
import math
from pathlib import Path
import struct
import subprocess

import numpy as np

import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE, PROJECT, load_features, native
from experiment_noisy_acceptance import cutoff

MASKS = dict(control=[], no_absolute=[0, 1, 2], contextual_only=[0, 1, 2, 3, 4])
NAMES = ['candidate_raw_lm', 'literal_lm', 'candidate_minus_literal',
         'raw_runner_margin', 'penalized_runner_margin', 'known', 'typed_length',
         'candidate_length', 'length_difference', 'edits', 'edit_ratio',
         'adjacent_substitutions', 'other_substitutions', 'swaps', 'extra', 'missing',
         'first_letter_changed', 'split', 'apostrophe', 'completion_length',
         'typed_dictionary', 'candidate_dictionary', 'contextual_log_odds_gain']


def prepare(args):
    args.output.mkdir(parents=True, exist_ok=False)
    policy = json.loads(args.policy.read_text())
    features = load_features(policy, args.features)
    dictionary = set((ENGINE / 'english_valid_words.txt').read_text().splitlines())
    original = core.rows(args.cache / 'cases.jsonl')
    old_outputs = core.rows(args.cache / 'native-outputs.jsonl')
    added = core.rows(args.augmentation / 'augmentation.jsonl')
    added_outputs = core.rows(args.augmentation / 'augmentation-outputs.jsonl')
    assert len(original) == len(old_outputs) and len(added) == len(added_outputs)
    pairs = [(c, o) for c, o in zip(original + added, old_outputs + added_outputs)
             if c['typed'].casefold() not in {'tue', 'ir'} and c['target'].casefold() not in {'tue', 'ir'}]
    cases, outputs = map(list, zip(*pairs))
    assert all(c['fit_split'] in ['fit', 'calibration', 'selection'] for c in cases)
    with args.model.open('rb') as file:
        header = file.read(12)
        meta = json.loads(file.read(struct.unpack('<I', header[8:12])[0]))
    request = dict(left='', candidate_ids=list(range(len(meta['tokens']))), distribution=True)
    proc = subprocess.run([str(args.probe.resolve()), str(args.model.resolve())],
                          input=json.dumps(request) + '\n', text=True, capture_output=True,
                          check=True, timeout=60)
    distribution = json.loads(proc.stdout)['log_probs']
    baseline = {}
    for i in meta['english_word_ids']:
        word = meta['tokens'][i].replace('’', "'").lower()
        baseline[word] = max(baseline.get(word, -math.inf), distribution[i])
    core.write_json(args.output / 'unconditional-scores.json', baseline)
    raw, eligible, words, restorations = [], [], [], []
    for case, output in zip(cases, outputs):
        values, allowed, word = features.extract(case, output)
        typed, candidate = case['typed'].replace('’', "'").lower(), (word or '').lower()
        values = list(values) + [float(typed in dictionary),
                                float(bool(candidate) and all(w in dictionary for w in candidate.split(' ')))]
        gain = values[2] - (baseline[candidate] - baseline[typed]) if candidate in baseline and typed in baseline else 0.
        raw.append(values + [gain])
        eligible.append(allowed)
        words.append(word or '')
        restored = features.restored(output) or ''
        if word and word.lower() == case['typed'].lower() and word != case['typed'].lower():
            restored = restored or word
        restorations.append(restored)
    raw, eligible = np.array(raw), np.array(eligible)
    # Fresh native parity on a fixed input-only sample before using cached scores.
    sample = sorted(range(len(cases)), key=lambda i: core.hashlib.sha256(cases[i]['id'].encode()).hexdigest())[:1024]
    actual = native(args.probe, args.model, [cases[i] for i in sample])
    old_params = features.parameters(policy)
    logits = features.predict_logits(old_params, (raw[:, :22] - policy['means']) / policy['scales'])
    expected = features.decisions(logits, math.log(policy['threshold'] / (1 - policy['threshold'])), eligible, words, restorations)
    mismatches = [i for i, out in zip(sample, actual) if (expected[i] or None) != out['correction']]
    ranking_mismatches = [i for i, out in zip(sample, actual)
                          if any(out[k] != outputs[i][k] for k in ['known', 'literalScore', 'candidates', 'apostropheEvidence'])]
    core.write_json(args.output / 'parity.json', dict(cases=len(sample), decision_mismatches=mismatches,
                                                   ranking_mismatches=ranking_mismatches))
    if mismatches or ranking_mismatches:
        raise ValueError('Baseline cache/native parity differs')
    fit = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'fit' and eligible[i] and not restorations[i]])
    cal = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'calibration' and
                    (c['source'] == 'real' or c['source'].startswith('gboard_'))])
    subsets = dict(original=[i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] == 'real' and not c.get('noise')],
                   noisy=[i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] == 'real' and c.get('noise')],
                   short=[i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] in ['clean_short', 'generated_short']])
    labels = np.array([float(bool(w) and c['typed'].casefold() != c['target'].casefold() and w.casefold() == c['target'].casefold())
                       for c, w in zip(cases, words)])
    means, scales = raw[fit].mean(0), np.maximum(raw[fit].std(0), 1e-6)
    counts = collections.Counter(cases[i]['source'] for i in fit if not cases[i].get('noise'))
    mass = dict(real=1., generated=.25, blank=.10, gboard_word=.10, gboard_context=.16, gboard_touch=.19,
                clean_dictionary=.25, clean_dropout=.25, clean_short=.25, generated_short=.25)
    weights = np.array([.125 if cases[i].get('noise') else mass[cases[i]['source']] * counts['real'] / counts[cases[i]['source']] for i in fit])
    calweights = np.array([.125 if cases[i].get('noise') else 1. for i in cal])
    core.jsonl(args.output / 'cases.jsonl', cases)
    np.savez(args.output / 'features.npz', raw=raw, eligible=eligible, labels=labels, fit=fit, cal=cal,
             means=means, scales=scales, weights=weights, calweights=calweights)
    core.write_json(args.output / 'decisions.json', dict(words=words, restorations=restorations, subsets=subsets))
    core.write_json(args.output / 'parent-policy.json', policy)
    binding = dict(schema='frequency-ablation-v1', cases=len(cases), fit=len(fit), calibration_cases=len(cal),
                   feature_names=NAMES, masks=MASKS, seed=20261029, initialization='matched random 23-24-1 NN; disabled columns frozen at zero',
                   calibration='Original real/teacher cohort; original and noisy selection mean F0.5; no user/frozen scoring during fitting',
                   excluded_typed_or_target=['tue', 'ir'], model_sha256=core.digest(args.model), probe_sha256=core.digest(args.probe),
                   original_cases_sha256=core.digest(args.cache / 'cases.jsonl'),
                   augmentation_sha256=core.digest(args.augmentation / 'augmentation.jsonl'),
                   cases_sha256=core.digest(args.output / 'cases.jsonl'), script_sha256=core.digest(__file__),
                   features_sha256=core.digest(args.output / 'features.npz'))
    core.write_json(args.output / 'protocol.json', binding)
    print(json.dumps(binding), flush=True)


def train(args):
    args.output.mkdir(parents=True, exist_ok=False)
    data = np.load(args.data / 'features.npz')
    raw, fit, cal = data['raw'], data['fit'], data['cal']
    x = (raw - data['means']) / data['scales']
    cases = core.rows(args.data / 'cases.jsonl')
    decoded = json.loads((args.data / 'decisions.json').read_text())
    words, restorations, subsets = (decoded[k] for k in ['words', 'restorations', 'subsets'])
    template = json.loads((args.data / 'parent-policy.json').read_text())
    features = load_features(template, args.features)
    mask = MASKS[args.variant]
    x[:, mask] = 0.
    labels, weights, eligible = data['labels'], data['weights'], data['eligible']
    def evaluate(logits, threshold):
        fixes = features.decisions(logits, threshold, eligible, words, restorations)
        result = {name: features.metrics([cases[i] for i in ids], [fixes[i] for i in ids]) for name, ids in subsets.items()}
        result['selection_f05'] = (result['original']['f05'] + result['noisy']['f05']) / 2
        return result
    trials, policies = [], {}
    for lr in [.001, .003]:
        rng = np.random.default_rng(20261029)
        params = dict(w1=rng.normal(0, 1 / math.sqrt(23), (24, 23)), b1=np.zeros(24),
                      w2=rng.normal(0, 1 / math.sqrt(24), 24), b2=np.array(0.))
        params['w1'][:, mask] = 0.
        m, v = ({k: np.zeros_like(q) for k, q in params.items()} for _ in range(2))
        step = 0
        for epoch in range(1, 101):
            order = rng.permutation(len(fit))
            for at in range(0, len(order), 512):
                oi = order[at:at + 512]
                idx = fit[oi]
                xx, yy, ww = x[idx], labels[idx], weights[oi]
                hpre = xx @ params['w1'].T + params['b1']
                h = np.maximum(0, hpre)
                logits = h @ params['w2'] + params['b2']
                g = (1 / (1 + np.exp(-np.clip(logits, -50, 50))) - yy) * ww / ww.sum()
                dh = g[:, None] * params['w2'][None, :] * (hpre > 0)
                grads = dict(w1=dh.T @ xx, b1=dh.sum(0), w2=h.T @ g, b2=g.sum())
                grads['w1'][:, mask] = 0.
                norm = math.sqrt(sum(float(np.sum(q * q)) for q in grads.values()))
                step += 1
                for key, grad in grads.items():
                    grad *= min(1., 1 / max(norm, 1e-30))
                    m[key] = .9 * m[key] + .1 * grad
                    v[key] = .999 * v[key] + .001 * grad * grad
                    params[key] -= lr * (m[key] / (1 - .9 ** step)) / (np.sqrt(v[key] / (1 - .999 ** step)) + 1e-8)
            if epoch not in [30, 100]:
                continue
            assert np.all(params['w1'][:, mask] == 0)
            logits = features.predict_logits(params, x)
            threshold = cutoff([cases[i] for i in cal], logits[cal], eligible[cal],
                               [words[i] for i in cal], [restorations[i] for i in cal], data['calweights'])
            ident = f'lr{lr}-epoch{epoch}'
            trial = dict(id=ident, metrics=evaluate(logits, threshold), threshold_logit=threshold)
            payload = copy.deepcopy(template)
            payload.update({k: q.tolist() for k, q in params.items()})
            payload.update(means=data['means'].tolist(), scales=data['scales'].tolist(),
                           threshold=1 / (1 + math.exp(-threshold)), threshold_logit=threshold,
                           additional_features=['contextual_log_odds_gain'], disabled_input_indices=mask,
                           provenance=dict(method='Matched NN frequency-feature ablation', variant=args.variant,
                                           protocol_sha256=core.digest(args.data / 'protocol.json'), trial_id=ident,
                                           evaluation_used_for_training_or_selection=False))
            policies[ident] = payload
            trials.append(trial)
            core.write_json(args.output / (ident + '-policy.json'), payload)
            print(json.dumps(trial), flush=True)
    winner = max(trials, key=lambda t: (t['metrics']['selection_f05'], -t['metrics']['short']['false_changes']))
    core.write_json(args.output / 'selection.json', dict(variant=args.variant, disabled_features=[NAMES[i] for i in mask], trials=trials, winner=winner))
    core.write_json(args.output / 'selected-policy.json', policies[winner['id']])
    print('Selected ' + winner['id'], flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('prepare')
    p.add_argument('--cache', type=Path, required=True)
    p.add_argument('--augmentation', type=Path, required=True)
    p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    p.add_argument('--output', type=Path, required=True)
    t = sub.add_parser('train')
    t.add_argument('--data', type=Path, required=True)
    t.add_argument('--variant', choices=MASKS, required=True)
    t.add_argument('--output', type=Path, required=True)
    for child in [p, t]:
        child.add_argument('--features', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/features.py')
    args = parser.parse_args()
    (prepare if args.command == 'prepare' else train)(args)
