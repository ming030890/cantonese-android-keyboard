#!/usr/bin/env python3
"""Compare native decisions with the exported NN, including added evidence."""
import argparse
import json
import math
from pathlib import Path
import struct
from collections import defaultdict

import numpy as np

import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE, PROJECT, load_features


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--outputs', type=Path, required=True)
    parser.add_argument('--policy', type=Path, required=True)
    parser.add_argument('--baseline-scores', type=Path)
    parser.add_argument('--features', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/features.py')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    args = parser.parse_args()
    cases, outputs = core.rows(args.cases), core.rows(args.outputs)
    if len(cases) != len(outputs):
        raise ValueError('Case/output count differs')
    policy = json.loads(args.policy.read_text())
    features = load_features(policy, args.features)
    dictionary = set((ENGINE / 'english_valid_words.txt').read_text().splitlines())
    baseline = json.loads(args.baseline_scores.read_text()) if args.baseline_scores else {}
    from experiment_short_features import extra_features, EXTRAS
    forms = defaultdict(list)
    if any(name in EXTRAS for name in policy.get('additional_features', [])):
        with args.model.open('rb') as file:
            header = file.read(12)
            meta = json.loads(file.read(struct.unpack('<I', header[8:12])[0]))
        for i in meta['english_word_ids']:
            forms[meta['tokens'][i].lower()].append(meta['tokens'][i])
    raw, eligible, words, restorations = [], [], [], []
    for case, output in zip(cases, outputs, strict=True):
        x, allowed, word = features.extract(case, output)
        typed, candidate = case['typed'].replace('’', "'").lower(), (word or '').lower()
        x = list(x) + [float(typed in dictionary), float(bool(candidate) and all(w in dictionary for w in candidate.split(' ')))]
        if policy.get('runner_penalty_gap'):
            x[3] -= x[4]
        for added in policy.get('additional_features', []):
            if added == 'contextual_log_odds_gain':
                if not baseline:
                    raise ValueError('Unconditional baseline scores required')
                x.append(x[2] - (baseline[candidate] - baseline[typed]) if candidate in baseline and typed in baseline else 0.)
            elif added == 'context_word_count':
                x.append(float(min(32, len(case['left'].split()))))
            elif added in EXTRAS:
                x.append(extra_features(typed, candidate, case['left'], forms)[EXTRAS.index(added)])
            else:
                raise ValueError('Unknown additional feature: ' + added)
        raw.append(x)
        eligible.append(allowed)
        words.append(word or '')
        restored = features.restored(output) or ''
        if word and word.lower() == case['typed'].lower() and word != case['typed'].lower():
            restored = restored or word
        restorations.append(restored)
    normalized = (np.array(raw) - policy['means']) / policy['scales']
    logits = features.predict_logits(features.parameters(policy), normalized)
    cutoff = math.log(policy['threshold'] / (1 - policy['threshold']))
    decisions = features.decisions(logits, cutoff, eligible, words, restorations)
    mismatch = [dict(id=case.get('id', i), expected=fix or None, actual=output['correction'])
                for i, (case, output, fix) in enumerate(zip(cases, outputs, decisions, strict=True))
                if (fix or None) != output['correction']]
    report = dict(cases=len(cases), mismatches=mismatch,
                  cases_sha256=core.digest(args.cases), outputs_sha256=core.digest(args.outputs),
                  policy_sha256=core.digest(args.policy), script_sha256=core.digest(__file__))
    core.write_json(args.report, report)
    print(json.dumps(dict(cases=len(cases), mismatches=len(mismatch))))
    if mismatch:
        raise SystemExit('Native NN decision mismatch')


if __name__ == '__main__':
    main()
