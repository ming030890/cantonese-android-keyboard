#!/usr/bin/env python3
"""Warm-start the existing acceptance NN on clean words, without runtime rules.

TRAIN dictionary words and real clean text with dropped context supply new
negative examples. User-reported tue cases and frozen evaluation word surfaces
are excluded from augmentation. Select on training selection splits, then run
the unchanged native objective and supplemental user audit separately.
"""
import argparse
import collections
import copy
import importlib.util
import json
import math
from pathlib import Path
import re
import struct
import subprocess

import numpy as np

import autocorrect_eval_v2 as core
from experiment_noisy_acceptance import PRESERVATION, cutoff

PROJECT = Path(__file__).resolve().parents[1]
ENGINE = PROJECT.parent / 'QwenASR/crates/cantonese-ngram/src'
SEED = 'clean-acceptance-20261028-v1'


def load_features(policy, path):
    spec = importlib.util.spec_from_file_location('acceptance_features', path)
    features = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(features)
    features.POLICY = policy
    features.APOSTROPHE = json.loads((ENGINE / 'english_apostrophe_acceptance.json').read_text())
    text = (ENGINE / 'cnn_spelling.rs').read_text()
    features.COST = {k: np.float32(float(re.search(r'const ' + k + r': f32 = ([0-9.]+);', text)[1]))
                     for k in ['NEIGHBOR', 'OTHER', 'SWAP', 'EXTRA', 'MISSING']}
    previous = features.adjacent
    features.adjacent = lambda a, b: (b in 'cvbnm' if a == ' ' else a in 'cvbnm' if b == ' '
                                     else previous(a, b))
    return features


def native(probe, model, cases):
    proc = subprocess.run([str(probe.resolve()), str(model.resolve())],
                          input=core.native_requests(cases), text=True,
                          capture_output=True, check=True, timeout=600)
    outputs = [json.loads(line) for line in proc.stdout.splitlines()]
    if len(outputs) != len(cases):
        raise ValueError('Native output count mismatch')
    return outputs


def recenter_dictionary_input(policy):
    """Preserve the NN function while giving dictionary=False a zero input."""
    mean, scale = policy['means'][20], policy['scales'][20]
    for i, row in enumerate(policy['w1']):
        row[20] /= scale
        policy['b1'][i] -= row[20] * mean
    policy['means'][20], policy['scales'][20] = 0., 1.


def augment(args, original, dictionary):
    # Exclude exposed test surfaces before constructing any training examples.
    excluded = {'tue'}
    for row in core.rows(args.evaluation / 'cases.jsonl'):
        excluded.add(row['typed'].casefold())
        if row['target']:
            excluded.add(row['target'].casefold())
    for row in original:
        if row['fit_split'] != 'fit':
            excluded.update([row['typed'].casefold(), row['target'].casefold()])
    with args.model.open('rb') as file:
        header = file.read(12)
        meta = json.loads(file.read(struct.unpack('<I', header[8:12])[0]))
    vocabulary = {meta['tokens'][i].lower() for i in meta['english_word_ids']}
    rows = []
    for word in sorted(vocabulary & dictionary - excluded):
        if not word.isascii() or not word.isalpha() or len(word) < 2:
            continue
        ident = core.hashlib.sha256((SEED + word).encode()).hexdigest()
        bucket = int(ident[:8], 16) % 10
        split = 'fit' if bucket < 8 else 'calibration' if bucket == 8 else 'selection'
        rows.append(dict(id='dictionary:' + ident, left='', typed=word, target=word,
                         fit_split=split, source='clean_dictionary', noise=False))
    # Context dropout preserves the observed target; it invents no sentences.
    seen = set()
    for row in original:
        word = row['typed'].casefold()
        if (row.get('noise') or row['source'] != 'real' or
                word != row['target'].casefold() or word in excluded or not row['left'].strip()):
            continue
        if (word, row['fit_split']) in seen:
            continue
        seen.add((word, row['fit_split']))
        rows.append(dict(row, id='dropout:' + row['id'], left='',
                         source='clean_dropout', noise=False))
    return rows, excluded


def run(args):
    args.output.mkdir(parents=True, exist_ok=False)
    policy = json.loads(args.policy.read_text())
    assert len(policy['means']) == 22 and not policy['preserve_known_words']
    if args.dictionary_only:
        if args.context_length:
            raise ValueError('Context length cannot preserve non-dictionary outputs')
        # Equivalent reparameterization: a missing dictionary flag contributes
        # exactly zero. Updating this column then leaves all non-dictionary
        # inputs unchanged, without introducing an inference-time branch.
        recenter_dictionary_input(policy)
    features = load_features(policy, args.features)
    dictionary = set((ENGINE / 'english_valid_words.txt').read_text().splitlines())
    cases = core.rows(args.cache / 'cases.jsonl')
    outputs = core.rows(args.cache / 'native-outputs.jsonl')
    assert len(cases) == len(outputs)
    assert all(c['fit_split'] in ['fit', 'calibration', 'selection'] for c in cases)
    # Keep the user example out of original fitting rows too.
    pairs = [(c, o) for c, o in zip(cases, outputs) if c['typed'].casefold() != 'tue'
             and c['target'].casefold() != 'tue']
    ambiguous_removed = 0
    if args.drop_ambiguous_synthetic:
        retained = []
        for case, output in pairs:
            ambiguous = (case['source'] != 'real' and not case['left'].strip() and
                         case['typed'].casefold() != case['target'].casefold() and
                         case['typed'].casefold() in dictionary)
            if ambiguous and case['fit_split'] == 'fit':
                ambiguous_removed += 1
            else:
                retained.append((case, output))
        pairs = retained
    cases, outputs = map(list, zip(*pairs))
    added, excluded = augment(args, cases, dictionary)
    added_outputs = None
    if args.clean_cache:
        if core.rows(args.clean_cache / 'augmentation.jsonl') != added:
            raise ValueError('Clean augmentation cache differs')
        binding = json.loads((args.clean_cache / 'protocol.json').read_text())
        if binding['probe_sha256'] != core.digest(args.probe) or binding['model_sha256'] != core.digest(args.model):
            raise ValueError('Clean augmentation cache identity differs')
        added_outputs = core.rows(args.clean_cache / 'augmentation-outputs.jsonl')
    if args.short_data:
        short_cases = core.rows(args.short_data / 'cases.jsonl')
        short_outputs = core.rows(args.short_data / 'native-outputs.jsonl')
        if args.focus_short:
            pairs = []
            for case, out in zip(short_cases, short_outputs, strict=True):
                surfaces = {e['word'] for e in out.get('apostropheEvidence', [])}
                ranked = sorted((r for r in out['candidates'] if r['word'] not in surfaces), key=lambda r: (-r['score'], r['word']))
                # Generated errors whose target cannot win are ranking problems;
                # this experiment trains acceptance, with known-target positives.
                if case['source'] == 'clean_short' or (ranked and ranked[0]['word'].casefold() == case['target']):
                    pairs.append((case, out))
            short_cases, short_outputs = map(list, zip(*pairs))
        added += short_cases
        if added_outputs is not None:
            binding = json.loads((args.short_data / 'report.json').read_text())
            if binding['probe_sha256'] != core.digest(args.probe) or binding['model_sha256'] != core.digest(args.model):
                raise ValueError('Short augmentation cache identity differs')
            added_outputs += short_outputs
    core.jsonl(args.output / 'augmentation.jsonl', added)
    if added_outputs is None:
        added_outputs = native(args.probe, args.model, added)
    if len(added_outputs) != len(added):
        raise ValueError('Augmentation output count differs')
    core.jsonl(args.output / 'augmentation-outputs.jsonl', added_outputs)
    old_count = len(cases)
    cases += added
    outputs += added_outputs
    guard_start = len(cases)
    guard_outputs = []
    if args.preservation_guard:
        regressions = PRESERVATION + [('Let me know if yyou ', 'heve'),
                                      ('let me know if ', 'hou'), ('', 'gwme')]
        guards = [dict(id=f'guard:{i}', left=left, typed=typed, target=typed,
                       fit_split='guard', source='regression', noise=False)
                  for i, (left, typed) in enumerate(regressions)]
        guard_outputs = native(args.probe, args.model, guards)
        for case, output in zip(guards, guard_outputs, strict=True):
            case['target'] = output['correction'] or case['typed']
        cases += guards
        outputs += guard_outputs
        core.jsonl(args.output / 'guard-cases.jsonl', guards)
        core.jsonl(args.output / 'guard-outputs.jsonl', guard_outputs)
    raw, eligible, words, restorations = [], [], [], []
    for c, o in zip(cases, outputs):
        x, allowed, word = features.extract(c, o)
        lower = (word or '').lower()
        raw.append(list(x) + [float(c['typed'].replace('’', "'").lower() in dictionary),
                             float(bool(lower) and all(w in dictionary for w in lower.split(' ')))])
        eligible.append(allowed)
        words.append(word or '')
        restored = features.restored(o) or ''
        if word and word.lower() == c['typed'].lower() and word != c['typed'].lower():
            restored = restored or word
        restorations.append(restored)
    if args.context_gain:
        with args.model.open('rb') as file:
            header = file.read(12)
            meta = json.loads(file.read(struct.unpack('<I', header[8:12])[0]))
        request = dict(left='', candidate_ids=list(range(len(meta['tokens']))), distribution=True)
        proc = subprocess.run([str(args.probe.resolve()), str(args.model.resolve())],
                              input=json.dumps(request) + '\n', text=True,
                              capture_output=True, check=True, timeout=60)
        distribution = json.loads(proc.stdout)['log_probs']
        baseline = {}
        for i in meta['english_word_ids']:
            word = meta['tokens'][i].replace('’', "'").lower()
            baseline[word] = max(baseline.get(word, -math.inf), distribution[i])
        for case, values, word in zip(cases, raw, words):
            typed = case['typed'].replace('’', "'").lower()
            candidate = word.lower()
            # Undefined for non-vocabulary literals and multiword splits;
            # existing known/split features distinguish those cases.
            gain = values[2] - (baseline[candidate] - baseline[typed]) if typed in baseline and candidate in baseline else 0.
            values.append(gain)
        fit_rows = [i for i, c in enumerate(cases) if c['fit_split'] == 'fit']
        extra = np.array([raw[i][-1] for i in fit_rows])
        policy['means'].append(0. if args.dictionary_only else float(extra.mean()))
        policy['scales'].append(float(max(extra.std(), 1e-6)))
        for row in policy['w1']:
            row.append(0.)
        policy['additional_features'] = ['contextual_log_odds_gain']
        core.write_json(args.output / 'unconditional-scores.json', baseline)
    elif args.context_length:
        for case, values in zip(cases, raw):
            values.append(float(min(32, len(case['left'].split()))))
        extra = np.array([raw[i][-1] for i, c in enumerate(cases) if c['fit_split'] == 'fit'])
        policy['means'].append(float(extra.mean()))
        policy['scales'].append(float(max(extra.std(), 1e-6)))
        for row in policy['w1']:
            row.append(0.)
        policy['additional_features'] = ['context_word_count']
    x = (np.array(raw) - policy['means']) / policy['scales']
    eligible = np.array(eligible)
    labels = np.array([float(bool(w) and c['typed'].casefold() != c['target'].casefold()
                             and w.casefold() == c['target'].casefold()) for c, w in zip(cases, words)])
    parent = features.parameters(policy)
    parent_logits = features.predict_logits(parent, x)
    parent_threshold = math.log(policy['threshold'] / (1 - policy['threshold']))
    # Check feature arithmetic against fresh native outputs, not cached decisions.
    sample = sorted(range(old_count), key=lambda i: core.hashlib.sha256(cases[i]['id'].encode()).hexdigest())[:256]
    actual = native(args.probe, args.model, [cases[i] for i in sample]) + added_outputs + guard_outputs
    indices = sample + list(range(old_count, len(cases)))
    expected = features.decisions(parent_logits, parent_threshold, eligible, words, restorations)
    mismatch = [i for i, o in zip(indices, actual) if (expected[i] or None) != o['correction']]
    core.write_json(args.output / 'feature-parity.json', dict(cases=len(indices), mismatches=mismatch[:20]))
    if mismatch:
        raise ValueError('Python/native feature mismatch')
    fit = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'fit' and eligible[i] and not restorations[i]])
    cal = np.array([i for i, c in enumerate(cases) if c['fit_split'] == 'calibration' and
                    (c['source'] == 'real' or c['source'].startswith(('gboard_', 'clean_')))])
    if args.real_calibration:
        cal = np.array([i for i in cal if i < old_count])
    selection = {
        'original': [i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] == 'real' and not c.get('noise')],
        'noisy': [i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] == 'real' and c.get('noise')],
        'clean': [i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'].startswith('clean_')],
    }
    if args.short_data:
        selection['short'] = [i for i, c in enumerate(cases) if c['fit_split'] == 'selection' and c['source'] in ['clean_short', 'generated_short']]
    counts = collections.Counter(cases[i]['source'] for i in fit if not cases[i].get('noise'))
    masses = dict(real=1., generated=.25, blank=.10, gboard_word=.10, gboard_context=.16, gboard_touch=.19)
    masses['generated_short'] = 1.
    binding = dict(schema='clean-acceptance-v1', seed=SEED, model_sha256=core.digest(args.model),
                   parent_policy_sha256=core.digest(args.policy), probe_sha256=core.digest(args.probe),
                   script_sha256=core.digest(__file__), source_cases_sha256=core.digest(args.cache / 'cases.jsonl'),
                   source_outputs_sha256=core.digest(args.cache / 'native-outputs.jsonl'),
                   augmentation_sha256=core.digest(args.output / 'augmentation.jsonl'),
                   architecture=f'{len(policy["means"])}-24-1', excluded_surfaces=len(excluded),
                   added_feature='contextual_log_odds_gain' if args.context_gain else 'context_word_count' if args.context_length else None,
                   short_acceptance_focus=args.focus_short,
                   hard_negative_mining=args.hard_negatives,
                   replay_anchor=args.anchor,
                   trainable_parameters=('48 dictionary/context input weights' if args.context_gain else '24 existing typed-dictionary input weights') if args.dictionary_only else 'all NN parameters',
                   fixed_parent_threshold=args.fixed_threshold,
                   calibration='Original real/teacher cohort only; synthetic prevalence not used' if args.real_calibration else 'Original plus synthetic augmentation',
                   ambiguous_synthetic_fit_rows_removed=ambiguous_removed,
                   evaluation_used_for_training_or_selection=False,
                   selection='Mean original/noisy/short selection F0.5 when short data supplied, otherwise original/noisy; then fewer clean false changes, then smaller weight change; existing preservation regressions must pass',
                   preservation_guard=regressions if args.preservation_guard else [],
                   augmentation_counts=dict(collections.Counter(c['fit_split'] + ':' + c['source'] for c in added)))
    if args.short_data:
        binding['short_data_sha256'] = core.digest(args.short_data / 'cases.jsonl')
    core.write_json(args.output / 'protocol.json', binding)

    def metrics(z, threshold):
        fixes = features.decisions(z, threshold, eligible, words, restorations)
        result = {key: features.metrics([cases[i] for i in ids], [fixes[i] for i in ids]) for key, ids in selection.items()}
        result['mean_f05'] = (result['original']['f05'] + result['noisy']['f05']) / 2
        if args.short_data:
            result['mean_f05'] = (result['original']['f05'] + result['noisy']['f05'] + result['short']['f05']) / 3
        result['preservation_pass'] = fixes[guard_start:] == [o['correction'] for o in guard_outputs]
        return result

    trials = [dict(id='parent', metrics=metrics(parent_logits, parent_threshold), change=0.)]
    payloads = {'parent': policy}
    for lr in [.0001, .0003]:
        for clean_mass in [.25, 1.]:
            params = {k: v.copy() for k, v in parent.items()}
            m = {k: np.zeros_like(v) for k, v in params.items()}
            v = {k: np.zeros_like(v) for k, v in params.items()}
            weights = np.array([.125 if cases[i].get('noise') else
                                (clean_mass * (4. if args.focus_short and cases[i]['source'] == 'clean_short' else 1.)
                                 if cases[i]['source'].startswith('clean_') else masses[cases[i]['source']])
                                * counts['real'] / counts[cases[i]['source']] for i in fit])
            if args.hard_negatives:
                weights *= np.array([8. if cases[i]['source'] == 'clean_short' and
                                     outputs[i]['correction'] and outputs[i]['correction'].casefold() != cases[i]['typed'].casefold()
                                     else 1. for i in fit])
            calweights = np.array([.125 if cases[i].get('noise') else
                                   clean_mass * (4. if args.focus_short and cases[i]['source'] == 'clean_short' else 1.)
                                   if cases[i]['source'].startswith('clean_') else 1. for i in cal])
            rng = np.random.default_rng(20261028)
            step = 0
            for epoch in range(1, 31):
                order = rng.permutation(len(fit))
                for at in range(0, len(order), 512):
                    oi = order[at:at + 512]
                    idx = fit[oi]
                    xx, yy, ww = x[idx], labels[idx], weights[oi]
                    hpre = xx @ params['w1'].T + params['b1']
                    h = np.maximum(0, hpre)
                    z = h @ params['w2'] + params['b2']
                    g = (1 / (1 + np.exp(-np.clip(z, -50, 50))) - yy) * ww / ww.sum()
                    # Replay anchor applies to existing rows, not new negatives.
                    g += args.anchor * (z - parent_logits[idx]) * (idx < old_count) / len(idx)
                    dh = g[:, None] * params['w2'][None, :] * (hpre > 0)
                    grads = dict(w1=dh.T @ xx, b1=dh.sum(0), w2=h.T @ g, b2=g.sum())
                    if args.dictionary_only:
                        column = grads['w1'][:, 20].copy()
                        context_column = grads['w1'][:, 22].copy() if args.context_gain else None
                        grads = {key: np.zeros_like(value) for key, value in grads.items()}
                        grads['w1'][:, 20] = column
                        if context_column is not None:
                            grads['w1'][:, 22] = context_column
                    norm = math.sqrt(sum(float(np.sum(q * q)) for q in grads.values()))
                    step += 1
                    for key, grad in grads.items():
                        grad *= min(1., 1 / max(norm, 1e-30))
                        m[key] = .9 * m[key] + .1 * grad
                        v[key] = .999 * v[key] + .001 * grad * grad
                        params[key] -= lr * (m[key] / (1 - .9 ** step)) / (np.sqrt(v[key] / (1 - .999 ** step)) + 1e-8)
                if epoch not in [10, 30]:
                    continue
                z = features.predict_logits(params, x)
                threshold = parent_threshold if args.fixed_threshold else cutoff(
                    [cases[i] for i in cal], z[cal], eligible[cal],
                    [words[i] for i in cal], [restorations[i] for i in cal], calweights)
                key = f'lr{lr}-clean{clean_mass}-e{epoch}'
                change = sum(float(np.sum((params[k] - parent[k]) ** 2)) for k in params)
                trial = dict(id=key, metrics=metrics(z, threshold), change=change)
                trials.append(trial)
                payload = copy.deepcopy(policy)
                payload.update({k: value.tolist() for k, value in params.items()})
                payload.update(threshold=1 / (1 + math.exp(-threshold)), threshold_logit=threshold,
                               provenance=dict(method='Clean TRAIN dictionary controls and real clean context dropout; unchanged acceptance features',
                                               protocol_sha256=core.digest(args.output / 'protocol.json'), trial_id=key,
                                               evaluation_used_for_selection=False))
                payloads[key] = payload
                core.write_json(args.output / (key + '-policy.json'), payload)
                print(json.dumps(trial), flush=True)
    winner = max([t for t in trials if t['metrics']['preservation_pass']],
                 key=lambda t: (t['metrics']['mean_f05'], -t['metrics']['clean']['false_changes'], -t['change']))
    core.write_json(args.output / 'selection.json', dict(trials=trials, winner=winner))
    core.write_json(args.output / 'selected-policy.json', payloads[winner['id']])
    print('Selected ' + winner['id'], flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--policy', type=Path, required=True)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--features', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/features.py')
    parser.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--evaluation', type=Path, default=PROJECT / 'build/autocorrect-eval-v4/frozen')
    parser.add_argument('--output', type=Path, required=True)
    context = parser.add_mutually_exclusive_group()
    context.add_argument('--context-gain', action='store_true')
    context.add_argument('--context-length', action='store_true')
    parser.add_argument('--short-data', type=Path)
    parser.add_argument('--clean-cache', type=Path)
    parser.add_argument('--focus-short', action='store_true')
    parser.add_argument('--hard-negatives', action='store_true')
    parser.add_argument('--anchor', type=float, default=.2)
    parser.add_argument('--drop-ambiguous-synthetic', action='store_true')
    parser.add_argument('--preservation-guard', action='store_true')
    parser.add_argument('--real-calibration', action='store_true')
    parser.add_argument('--dictionary-only', action='store_true')
    parser.add_argument('--fixed-threshold', action='store_true')
    run(parser.parse_args())
