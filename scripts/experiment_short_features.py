#!/usr/bin/env python3
"""Matched short-word data/feature experiments with the existing 24-unit NN."""
import argparse
import collections
import copy
import json
import math
from pathlib import Path
import struct

import numpy as np

import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE, PROJECT, load_features, native
from experiment_noisy_acceptance import cutoff

EXTRAS = ['literal_no_lowercase_token', 'literal_title_token', 'last_substitution',
          'mean_substitution_position', 'horizontal_key_distance', 'vertical_key_distance',
          'context_word_count', 'contextual_log_odds_gain']
VARIANTS = {
    'old_data_control': ([], []), 'data_control': ([], []),
    'drop_literal_score': ([], [1]), 'drop_candidate_score': ([], [0]),
    'drop_raw_runner': ([], [3]), 'lexical': ([0, 1], []),
    'position': ([2, 3], []), 'keyboard': ([4, 5], []),
    'context_length': ([6], []), 'lexical_position': ([0, 1, 2, 3], []),
    'context_gain': ([7], []), 'lexical_gain': ([0, 1, 7], []),
    'runner_penalty_gap': ([], []),
}
KEYS = {c: (i + [.0, .25, .75][r], r) for r, row in enumerate(['qwertyuiop', 'asdfghjkl', 'zxcvbnm']) for i, c in enumerate(row)}


def extra_features(typed, candidate, left, forms):
    typed = typed.replace('’', "'").lower()
    candidate = candidate.lower()
    tokens = forms.get(typed, [])
    no_lower = bool(tokens) and typed not in tokens
    title = no_lower and any(t.istitle() for t in tokens)
    last = position = horizontal = vertical = 0.
    if len(typed) == len(candidate) and ' ' not in candidate:
        edits = [i for i, (a, b) in enumerate(zip(typed, candidate)) if a != b]
        if edits:
            last = float(edits[-1] == len(typed) - 1)
            position = sum(edits) / len(edits) / max(1, len(typed) - 1)
            pairs = [(KEYS[typed[i]], KEYS[candidate[i]]) for i in edits if typed[i] in KEYS and candidate[i] in KEYS]
            if pairs:
                horizontal = sum(abs(a[0] - b[0]) for a, b in pairs) / len(pairs)
                vertical = sum(abs(a[1] - b[1]) for a, b in pairs) / len(pairs)
    return [float(no_lower), float(title), last, position, horizontal, vertical,
            float(min(32, len(left.split())))]


def prepare(args):
    args.output.mkdir(parents=True, exist_ok=False)
    policy = json.loads(args.policy.read_text())
    features = load_features(policy, args.features)
    dictionary = set((ENGINE / 'english_valid_words.txt').read_text().splitlines())
    old = core.rows(args.old_data / 'cases.jsonl')
    old_data = np.load(args.old_data / 'features.npz')
    decoded = json.loads((args.old_data / 'decisions.json').read_text())
    words, restorations = decoded['words'], decoded['restorations']
    raw = old_data['raw'][:, :22].tolist()
    eligible = old_data['eligible'].tolist()
    labels = old_data['labels'].tolist()
    new_cases = core.rows(args.new_data / 'cases.jsonl')
    new_outputs = core.rows(args.new_data / 'native-outputs.jsonl')
    assert len(new_cases) == len(new_outputs)
    seen = {(c['left'], c['typed'].casefold(), c['target'].casefold()) for c in old}
    cases = list(old)
    added = []
    for case, out in zip(new_cases, new_outputs, strict=True):
        key = (case['left'], case['typed'].casefold(), case['target'].casefold())
        if key in seen:
            continue
        seen.add(key)
        x, allowed, word = features.extract(case, out)
        lower = (word or '').lower()
        raw.append(list(x) + [float(case['typed'].lower() in dictionary), float(bool(lower) and all(w in dictionary for w in lower.split(' ')))])
        eligible.append(allowed)
        labels.append(float(bool(word) and case['typed'].casefold() != case['target'].casefold() and word.casefold() == case['target'].casefold()))
        restored = features.restored(out) or ''
        if word and word.lower() == case['typed'].lower() and word != case['typed'].lower():
            restored = restored or word
        restorations.append(restored)
        words.append(word or '')
        cases.append(case)
        added.append(case)
    assert all(c['typed'].casefold() not in {'tue', 'ir'} and c['target'].casefold() not in {'tue', 'ir'} for c in cases)
    with args.model.open('rb') as file:
        header = file.read(12)
        meta = json.loads(file.read(struct.unpack('<I', header[8:12])[0]))
    forms = collections.defaultdict(list)
    for i in meta['english_word_ids']:
        forms[meta['tokens'][i].lower()].append(meta['tokens'][i])
    for c, x, word in zip(cases, raw, words):
        x.extend(extra_features(c['typed'], word, c['left'], forms))
    baseline = json.loads(args.baseline_scores.read_text())
    for c, x, word in zip(cases, raw, words):
        typed, candidate = c['typed'].replace('’', "'").lower(), word.lower()
        x.append(x[2] - (baseline[candidate] - baseline[typed]) if typed in baseline and candidate in baseline else 0.)
    raw, eligible = np.array(raw), np.array(eligible)
    sample = sorted(range(len(cases)), key=lambda i: core.hashlib.sha256(cases[i]['id'].encode()).hexdigest())[:512]
    actual = native(args.probe, args.model, [cases[i] for i in sample])
    logits = features.predict_logits(features.parameters(policy), (raw[:, :22] - policy['means']) / policy['scales'])
    expected = features.decisions(logits, math.log(policy['threshold'] / (1 - policy['threshold'])), eligible, words, restorations)
    mismatch = [i for i, out in zip(sample, actual) if (expected[i] or None) != out['correction']]
    if mismatch:
        raise ValueError('Native baseline feature parity mismatch')
    core.jsonl(args.output / 'cases.jsonl', cases)
    core.jsonl(args.output / 'new-cases.jsonl', added)
    core.write_json(args.output / 'parent-policy.json', policy)
    core.write_json(args.output / 'decisions.json', dict(words=words, restorations=restorations))
    np.savez(args.output / 'features.npz', raw=raw, labels=np.array(labels), eligible=eligible, old_count=len(old))
    protocol = dict(schema='short-feature-study-v2', cases=len(cases), new_cases=len(added), extra_features=EXTRAS,
                    variants=VARIANTS, warm_start='Existing NN, function-preserving input recentering, zero initial extra weights',
                    seed=2026102902, grid=dict(lr=[.0001, .0003], epochs=[30, 100]), replay_anchor=.2,
                    calibration='Original real/teacher cohort; not synthetic prevalence',
                    selection='Mean original/noisy real selection F0.5; natural short-word results as tie-break',
                    holdout_surfaces=['tue', 'ir'], baseline_parity_cases=len(sample), baseline_parity_mismatches=mismatch,
                    source_sha256=core.digest(args.old_data / 'features.npz'), new_data_sha256=core.digest(args.new_data / 'cases.jsonl'),
                    feature_sha256=core.digest(args.output / 'features.npz'), model_sha256=core.digest(args.model),
                    unconditional_scores_sha256=core.digest(args.baseline_scores),
                    script_sha256=core.digest(__file__))
    core.write_json(args.output / 'protocol.json', protocol)
    print(json.dumps(protocol), flush=True)


def train(args):
    protocol = json.loads((args.data / 'protocol.json').read_text())
    if not protocol.get('evaluation_inputs_excluded') and not args.allow_inherited_cache:
        raise ValueError('Run clean_short_study_data.py first; inherited caches can contain evaluation-prefix collisions')
    args.output.mkdir(parents=True, exist_ok=False)
    data = np.load(args.data / 'features.npz')
    raw, eligible, labels, old_count = data['raw'], data['eligible'], data['labels'], int(data['old_count'])
    cases = core.rows(args.data / 'cases.jsonl')
    decoded = json.loads((args.data / 'decisions.json').read_text())
    words, restorations = decoded['words'], decoded['restorations']
    policy = json.loads((args.data / 'parent-policy.json').read_text())
    helper = load_features(policy, args.features)
    extra, mask = VARIANTS[args.variant]
    columns = list(range(22)) + [22 + i for i in extra]
    excluded = set()
    if args.exclude_observations:
        excluded = {(c['left'], c['typed'].casefold(), (c['target'] or '').casefold())
                    for c in core.rows(args.exclude_observations)}
    blocked = {i for i, c in enumerate(cases) if (c['left'], c['typed'].casefold(), c['target'].casefold()) in excluded}
    fit = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'fit' and eligible[i] and not restorations[i]
                    and i not in blocked and (args.variant != 'old_data_control' or i < old_count)])
    cal = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'calibration' and
                    i not in blocked and (c['source'] == 'real' or c['source'].startswith('gboard_'))])
    subsets = dict(original=[i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] == 'real' and not c.get('noise')],
                   noisy=[i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] == 'real' and c.get('noise')],
                   natural_short=[i for i, c in enumerate(cases) if i >= old_count and c['fit_split'] == 'selection'],
                   synthetic_short=[i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] in ['clean_short', 'generated_short']])
    subsets = {name: [i for i in ids if i not in blocked] for name, ids in subsets.items()}
    selected = raw[:, columns].copy()
    penalty_gap = args.variant == 'runner_penalty_gap'
    if penalty_gap:
        selected[:, 3] -= selected[:, 4]
    means, scales = selected[fit].mean(0), np.maximum(selected[fit].std(0), 1e-6)
    x = (selected - means) / scales
    x[:, mask] = 0.
    reference = helper.predict_logits(helper.parameters(policy), (raw[:, :22] - policy['means']) / policy['scales'])
    initial = helper.parameters(policy)
    base_w = initial['w1'].copy()
    coefficient = base_w / policy['scales']
    constant = initial['b1'] - coefficient @ np.array(policy['means'])
    if penalty_gap:
        coefficient[:, 4] += coefficient[:, 3]
    initial['w1'] = np.column_stack([coefficient * scales[:22], np.zeros((24, len(extra)))])
    initial['b1'] = constant + coefficient @ means[:22]
    initial['w1'][:, mask] = 0.
    counts = collections.Counter(cases[i]['source'] for i in fit if not cases[i].get('noise'))
    masses = dict(real=1., generated=.25, blank=.10, gboard_word=.10, gboard_context=.16, gboard_touch=.19,
                  clean_dictionary=.25, clean_dropout=.25, clean_short=.10, generated_short=.25,
                  authentic_short_clean=.5, authentic_short_typo=.5, title_abbreviation_clean=.25)
    weights = np.array([.125 if cases[i].get('noise') else masses[cases[i]['source']] * counts['real'] / counts[cases[i]['source']] for i in fit])
    calweights = np.array([.125 if cases[i].get('noise') else 1. for i in cal])
    core.write_json(args.output / 'train-protocol.json', dict(variant=args.variant,
                    extras=[EXTRAS[i] for i in extra], disabled_input_indices=mask,
                    script_sha256=core.digest(__file__), data_protocol_sha256=core.digest(args.data / 'protocol.json'),
                    replay_anchor=.2, seed=2026102902, fit_rows=len(fit), source_weights=masses,
                    excluded_observations_sha256=core.digest(args.exclude_observations) if args.exclude_observations else None,
                    excluded_rows=len(blocked),
                    blocked_by_split=dict(collections.Counter(cases[i]['fit_split'] for i in blocked))))
    if not mask:
        np.testing.assert_allclose(helper.predict_logits(initial, x), reference, atol=1e-11, rtol=1e-11)

    def evaluate(logits, threshold):
        fixes = helper.decisions(logits, threshold, eligible, words, restorations)
        result = {name: helper.metrics([cases[i] for i in ids], [fixes[i] for i in ids]) for name, ids in subsets.items()}
        result['selection_f05'] = (result['original']['f05'] + result['noisy']['f05']) / 2
        return result

    trials, policies = [], {}
    for lr in [.0001, .0003]:
        params = {k: v.copy() for k, v in initial.items()}
        m, v = ({k: np.zeros_like(q) for k, q in params.items()} for _ in range(2))
        rng = np.random.default_rng(2026102902)
        step = 0
        for epoch in range(1, 101):
            order = rng.permutation(len(fit))
            for at in range(0, len(order), 512):
                oi = order[at:at + 512]
                idx = fit[oi]
                xx, yy, ww = x[idx], labels[idx], weights[oi]
                hpre = xx @ params['w1'].T + params['b1']
                h = np.maximum(0, hpre)
                z = h @ params['w2'] + params['b2']
                g = (1 / (1 + np.exp(-np.clip(z, -50, 50))) - yy) * ww / ww.sum()
                g += .2 * (z - reference[idx]) * (idx < old_count) / len(idx)
                dh = g[:, None] * params['w2'][None, :] * (hpre > 0)
                gradients = dict(w1=dh.T @ xx, b1=dh.sum(0), w2=h.T @ g, b2=g.sum())
                gradients['w1'][:, mask] = 0.
                norm = math.sqrt(sum(float(np.sum(q * q)) for q in gradients.values()))
                step += 1
                for k, grad in gradients.items():
                    grad *= min(1., 1 / max(norm, 1e-30))
                    m[k] = .9 * m[k] + .1 * grad
                    v[k] = .999 * v[k] + .001 * grad * grad
                    params[k] -= lr * (m[k] / (1 - .9 ** step)) / (np.sqrt(v[k] / (1 - .999 ** step)) + 1e-8)
            if epoch not in [30, 100]:
                continue
            assert np.all(params['w1'][:, mask] == 0.)
            logits = helper.predict_logits(params, x)
            threshold = cutoff([cases[i] for i in cal], logits[cal], eligible[cal],
                               [words[i] for i in cal], [restorations[i] for i in cal], calweights)
            ident = f'lr{lr}-e{epoch}'
            trial = dict(id=ident, metrics=evaluate(logits, threshold), threshold_logit=threshold)
            payload = copy.deepcopy(policy)
            payload.update({k: q.tolist() for k, q in params.items()})
            payload.update(means=means.tolist(), scales=scales.tolist(), threshold=1 / (1 + math.exp(-threshold)),
                           threshold_logit=threshold, additional_features=[EXTRAS[i] for i in extra], disabled_input_indices=mask,
                           runner_penalty_gap=penalty_gap,
                           provenance=dict(method='Short-context supervision and small-feature ablation', variant=args.variant,
                                           data_protocol_sha256=core.digest(args.data / 'protocol.json'), trial=ident,
                                           frozen_inputs_excluded_from_updates=bool(protocol.get('evaluation_inputs_excluded')),
                                           checkpoint_selection='TRAIN selection metrics; development objective evaluated separately'))
            trials.append(trial)
            policies[ident] = payload
            core.write_json(args.output / (ident + '-policy.json'), payload)
            print(json.dumps(trial), flush=True)
    winner = max(trials, key=lambda t: (t['metrics']['selection_f05'], t['metrics']['natural_short']['f05']))
    core.write_json(args.output / 'selection.json', dict(variant=args.variant, trials=trials, winner=winner))
    core.write_json(args.output / 'selected-policy.json', policies[winner['id']])
    print('Selected ' + winner['id'], flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('prepare')
    p.add_argument('--old-data', type=Path, required=True)
    p.add_argument('--new-data', type=Path, required=True)
    p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--baseline-scores', type=Path, default=PROJECT / 'build/frequency-ablation-20261029/data/unconditional-scores.json')
    t = sub.add_parser('train')
    t.add_argument('--data', type=Path, required=True)
    t.add_argument('--variant', choices=VARIANTS, required=True)
    t.add_argument('--output', type=Path, required=True)
    t.add_argument('--exclude-observations', type=Path)
    t.add_argument('--allow-inherited-cache', action='store_true', help='Exploratory legacy replay only; not eligible for promotion')
    for child in [p, t]:
        child.add_argument('--features', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/features.py')
    args = parser.parse_args()
    (prepare if args.command == 'prepare' else train)(args)
