#!/usr/bin/env python3
"""Warm-start acceptance on TRAIN-only prior-context noise; no evaluation fitting.

Requires NumPy and the existing gboard-probe/improve-v3 feature implementation.
Its feature arithmetic is checked against every current native decision before
fitting. Outputs are experimental until rebuilt native objective/QA checks pass.
"""
import argparse
import collections
import copy
import importlib.util
import json
import math
from pathlib import Path
import re
import subprocess

import numpy as np
import audit_noisy_context as noise
import autocorrect_eval_v2 as core

PROJECT = Path(__file__).resolve().parents[1]
NATIVE = PROJECT.parent / 'QwenASR/crates/cantonese-ngram/src'
GRID = dict(learning_rates=[0.0001, 0.0003], noise_weights=[0.25, 0.5, 1.0], epochs=[20, 60])
# Existing JNI literal/uncertainty characterizations, not fitting examples.
PRESERVATION = [('', word) for word in ['butger', 'wastg', 'bokeh', 'Alstom', 'Xentara', 'thses', 'inthe']]
PRESERVATION += [('I ', "don't"), ('I said ', 'hel')]


def cutoff(cases, logits, eligible, words, restorations, weights):
    typos = sum(w * (c['typed'].casefold() != c['target'].casefold()) for c, w in zip(cases, weights))
    tp = fp = 0.
    for c, fix, w in zip(cases, restorations, weights):
        changed = bool(fix and fix.casefold() != c['typed'].casefold())
        hit = bool(changed and fix.casefold() == c['target'].casefold())
        tp += w * hit
        fp += w * (changed and not hit)
    score = lambda: 1.25 * tp / (tp + fp + .25 * typos) if tp + fp + typos else 0.
    best = (score(), float(max(logits) + 1))
    active = [i for i, c in enumerate(cases) if eligible[i] and not restorations[i]
              and words[i] and words[i].casefold() != c['typed'].casefold()]
    active.sort(key=lambda i: -logits[i])
    for at, i in enumerate(active):
        hit = words[i].casefold() == cases[i]['target'].casefold()
        tp += weights[i] * hit
        fp += weights[i] * (not hit)
        if at + 1 < len(active) and logits[active[at + 1]] == logits[i]:
            continue
        if score() > best[0] + 1e-15:
            best = score(), float(logits[i])
    return best[1]


def run(args):
    args.output.mkdir(parents=True, exist_ok=False)
    policy = json.loads(args.policy.read_text())
    spec = importlib.util.spec_from_file_location('native_acceptance_features', args.features)
    features = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(features)
    # Use the current frozen policy, punctuation policy and operation costs,
    # rather than the older feature module's default source snapshot.
    features.POLICY = policy
    features.APOSTROPHE = json.loads((NATIVE / 'english_apostrophe_acceptance.json').read_text())
    text = (NATIVE / 'cnn_spelling.rs').read_text()
    features.COST = {k: np.float32(float(re.search(r'const ' + k + r': f32 = ([0-9.]+);', text)[1]))
                     for k in ['NEIGHBOR', 'OTHER', 'SWAP', 'EXTRA', 'MISSING']}
    previous_adjacent = features.adjacent
    features.adjacent = lambda a, b: (b in 'cvbnm' if a == ' ' else a in 'cvbnm' if b == ' '
                                     else previous_adjacent(a, b))
    dictionary = set((NATIVE / 'english_valid_words.txt').read_text().splitlines())
    # Exclude evaluation before generating features, labels or corruptions.
    rows = [r for r in core.rows(args.cases) if r['fit_split'] in ['fit', 'calibration', 'selection']]
    original = [dict(r, noise=False) for r in rows]
    augmented = []
    for row in rows:
        if row['source'] != 'real':
            continue
        for operation in noise.OPERATIONS:
            left = noise.corrupt(row['left'], operation)
            if left is not None:
                augmented.append(dict(row, left=left, noise=True, operation=operation,
                                      original_id=row['id'], id=row['id'] + ':' + operation))
    cases = original + augmented
    core.jsonl(args.output / 'cases.jsonl', cases)
    binding = dict(schema='noisy-acceptance-experiment-v1', grid=GRID,
                   seed=2026102501, noise_seed=noise.SEED,
                   policy_sha256=core.digest(args.policy), probe_sha256=core.digest(args.probe),
                   model_sha256=core.digest(args.model), source_cases_sha256=core.digest(args.cases),
                   cases_sha256=core.digest(args.output / 'cases.jsonl'), script_sha256=core.digest(__file__),
                   features_sha256=core.digest(args.features), spelling_source_sha256=core.digest(NATIVE / 'cnn_spelling.rs'),
                   selection=('Original real selection F0.5, then noisy selection F0.5, then smaller weight change; parent included' if args.original_first else
                              'Mean original/noisy real selection F0.5, then original selection F0.5, then smaller weight change; parent included'),
                   replay_anchor=args.anchor,
                   preservation_guard=PRESERVATION if args.preservation_guard else [],
                   fit='Original fit rows plus noisy real fit rows; BCE with replay-logit anchoring',
                   calibration='Original real/new calibration plus noisy real calibration; one global cutoff',
                   evaluation_used=False, counts=dict(collections.Counter((r['fit_split'] + ':noisy' if r['noise'] else r['fit_split'] + ':original') for r in cases)))
    core.write_json(args.output / 'protocol.json', binding)
    if args.native_cache:
        cached = json.loads((args.native_cache / 'protocol.json').read_text())
        for key in ['cases_sha256', 'probe_sha256', 'model_sha256']:
            if cached[key] != binding[key]:
                raise ValueError('Cached native request binding differs: ' + key)
        if core.digest(args.native_cache / 'cases.jsonl') != binding['cases_sha256']:
            raise ValueError('Cached request cases changed')
        outputs = core.rows(args.native_cache / 'native-outputs.jsonl')
    else:
        proc = subprocess.run([str(args.probe.resolve()), str(args.model.resolve())],
                              input=core.native_requests(cases), text=True, capture_output=True,
                              check=True, timeout=1800)
        outputs = [json.loads(line) for line in proc.stdout.splitlines() if line]
    if len(outputs) != len(cases) or core.digest(args.probe) != binding['probe_sha256']:
        raise ValueError('Native output count or executable identity changed')
    core.jsonl(args.output / 'native-outputs.jsonl', outputs)
    guard_start = len(cases)
    guard_outputs = []
    if args.preservation_guard:
        guard_cases = [dict(id=f'guard:{i}', left=left, typed=typed, target=typed,
                            source='regression', fit_split='guard', noise=False)
                       for i, (left, typed) in enumerate(PRESERVATION)]
        guard_proc = subprocess.run([str(args.probe.resolve()), str(args.model.resolve())],
                                    input=core.native_requests(guard_cases), text=True, capture_output=True,
                                    check=True, timeout=60)
        guard_outputs = [json.loads(line) for line in guard_proc.stdout.splitlines() if line]
        if len(guard_outputs) != len(guard_cases):
            raise ValueError('Guard output count differs')
        core.jsonl(args.output / 'guard-cases.jsonl', guard_cases)
        core.jsonl(args.output / 'guard-outputs.jsonl', guard_outputs)
        cases += guard_cases
        outputs += guard_outputs
    raw, eligible, words, restorations = [], [], [], []
    for c, o in zip(cases, outputs):
        x, e, word = features.extract(c, o)
        lower = (word or '').lower()
        x = list(x) + [float(c['typed'].replace('’', "'").lower() in dictionary),
                       float(bool(lower) and all(p in dictionary for p in lower.split(' ')))]
        raw.append(x)
        eligible.append(e)
        words.append(word or '')
        surface = features.restored(o) or ''
        if word and word.lower() == c['typed'].lower() and word != c['typed'].lower():
            surface = surface or word
        restorations.append(surface)
    x = (np.array(raw) - policy['means']) / policy['scales']
    eligible = np.array(eligible)
    labels = np.array([float(bool(w) and c['typed'].casefold() != c['target'].casefold()
                             and w.casefold() == c['target'].casefold()) for c, w in zip(cases, words)])
    parent = features.parameters(policy)
    parent_logits = features.predict_logits(parent, x)
    parent_threshold = math.log(policy['threshold'] / (1 - policy['threshold']))
    def decisions(z, t):
        return features.decisions(z, t, eligible, words, restorations)
    expected = decisions(parent_logits, parent_threshold)
    mismatches = [i for i, (a, b) in enumerate(zip(expected, outputs)) if (a or None) != b['correction']]
    core.write_json(args.output / 'feature-parity.json', dict(cases=len(cases), mismatches=len(mismatches), first_indices=mismatches[:20]))
    if mismatches:
        raise ValueError('Feature reproduction differs from native decisions; do not train')
    fit = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'fit' and eligible[i] and not restorations[i]])
    cal = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'calibration' and (c['source'] == 'real' or c['source'].startswith('gboard_'))])
    sel = {kind: [i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] == 'real' and c['noise'] == dirty]
           for kind, dirty in [('original', False), ('noisy', True)]}
    def evaluate(z, threshold):
        fixes = decisions(z, threshold)
        result = {kind: features.metrics([cases[i] for i in ids], [fixes[i] for i in ids]) for kind, ids in sel.items()}
        result['selection_mean_f05'] = sum(r['f05'] for r in result.values()) / 2
        result['preservation_pass'] = fixes[guard_start:] == [o['correction'] for o in guard_outputs]
        return result
    trials = [dict(id='parent', metrics=evaluate(parent_logits, parent_threshold), squared_weight_change=0.)]
    policies = {'parent': policy}
    counts = collections.Counter(cases[i]['source'] for i in fit if not cases[i]['noise'])
    masses = {'real': 1., 'generated': .25, 'blank': .10, 'gboard_word': .10, 'gboard_context': .16, 'gboard_touch': .19}
    for lr in GRID['learning_rates']:
        for nw in GRID['noise_weights']:
            params = {k: v.copy() for k, v in parent.items()}
            m = {k: np.zeros_like(v) for k, v in params.items()}
            v = {k: np.zeros_like(v) for k, v in params.items()}
            weights = np.array([nw / 4 if cases[i]['noise'] else masses[cases[i]['source']] * counts['real'] / counts[cases[i]['source']] for i in fit])
            calweights = np.array([nw / 4 if cases[i]['noise'] else 1. for i in cal])
            rng = np.random.default_rng(binding['seed'])
            step = 0
            for epoch in range(1, max(GRID['epochs']) + 1):
                order = rng.permutation(len(fit))
                for at in range(0, len(order), 512):
                    oi = order[at:at + 512]
                    idx = fit[oi]
                    xx, yy, ww = x[idx], labels[idx], weights[oi]
                    hpre = xx @ params['w1'].T + params['b1']
                    h = np.maximum(hpre, 0)
                    z = h @ params['w2'] + params['b2']
                    g = (1 / (1 + np.exp(-np.clip(z, -50, 50))) - yy) * ww / ww.sum()
                    anchor = np.array([not cases[i]['noise'] for i in idx], dtype=float)
                    g += args.anchor * (z - parent_logits[idx]) * anchor / len(idx)
                    dh = g[:, None] * params['w2'][None, :] * (hpre > 0)
                    gradients = dict(w1=dh.T @ xx, b1=dh.sum(0), w2=h.T @ g, b2=g.sum())
                    norm = math.sqrt(sum(float(np.sum(q * q)) for q in gradients.values()))
                    step += 1
                    for k, grad in gradients.items():
                        grad = grad * min(1., 1 / max(norm, 1e-30))
                        m[k] = .9 * m[k] + .1 * grad
                        v[k] = .999 * v[k] + .001 * grad * grad
                        params[k] -= lr * (m[k] / (1 - .9 ** step)) / (np.sqrt(v[k] / (1 - .999 ** step)) + 1e-8)
                if epoch not in GRID['epochs']:
                    continue
                z = features.predict_logits(params, x)
                threshold = cutoff([cases[i] for i in cal], z[cal], eligible[cal],
                                   [words[i] for i in cal], [restorations[i] for i in cal], calweights)
                key = f'lr{lr}-noise{nw}-e{epoch}'
                change = sum(float(np.sum((params[k] - parent[k]) ** 2)) for k in params)
                trial = dict(id=key, metrics=evaluate(z, threshold), squared_weight_change=change)
                trials.append(trial)
                payload = copy.deepcopy(policy)
                payload.update({k: value.tolist() for k, value in params.items()})
                payload.update(threshold=1 / (1 + math.exp(-threshold)), threshold_logit=threshold,
                               provenance=dict(method='TRAIN-only noisy-context acceptance warm start with clean replay and logit anchoring',
                                               protocol_sha256=core.digest(args.output / 'protocol.json'), trial_id=key,
                                               evaluation_used_for_selection=False))
                policies[key] = payload
                print(json.dumps(trial), flush=True)
    winner = max([r for r in trials if r['metrics']['preservation_pass']], key=lambda r: ((r['metrics']['original']['f05'], r['metrics']['noisy']['f05']) if args.original_first else
                                      (r['metrics']['selection_mean_f05'], r['metrics']['original']['f05'])) + (-r['squared_weight_change'],))
    for key, payload in policies.items():
        core.write_json(args.output / (key + '-policy.json'), payload)
    core.write_json(args.output / 'selection.json', dict(trials=trials, winner=winner, evaluation_used=False))
    core.write_json(args.output / 'selected-policy.json', policies[winner['id']])
    print('Selected ' + winner['id'], flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/cases.jsonl')
    parser.add_argument('--features', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/features.py')
    parser.add_argument('--policy', type=Path, required=True)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--native-cache', type=Path)
    parser.add_argument('--anchor', type=float, default=.02)
    parser.add_argument('--original-first', action='store_true')
    parser.add_argument('--preservation-guard', action='store_true')
    run(parser.parse_args())
