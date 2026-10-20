#!/usr/bin/env python3
"""Fit literal-versus-replacement evidence using fixed prompt-group partitions."""
import argparse
import collections
import csv
import hashlib
import json
import struct
from pathlib import Path

import numpy as np

from align_mobile_targets import reference_target
from audit_candidate_pipeline import digest, probe
from iterate_english_policy import split, hashed, rerank, metric, f05
from verify_english_acceptance import decision

FEATURES = ['winner_score', 'runner_margin', 'known_word', 'literal_score', 'score_over_literal', 'observed_apostrophe']


def fit(rows, labels, regularization):
    means = rows.mean(axis=0)
    scales = np.maximum(.1, rows.std(axis=0))
    x = np.column_stack([np.ones(len(rows)), (rows-means)/scales])
    weights = np.zeros(x.shape[1])
    for epoch in range(600):
        probabilities = 1/(1+np.exp(-np.clip(x@weights, -40, 40)))
        penalty = regularization*weights
        penalty[0] = 0
        weights -= .25/(1+epoch/300)*(x.T@(probabilities-labels)/len(rows)+penalty)
    return dict(means=means.tolist(), scales=scales.tolist(), weights=weights.tolist())


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--iteration', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--evaluate', action='store_true', help='Reveal evaluation only after selection is frozen')
    p.add_argument('--baseline-policy', type=Path)
    p.add_argument('--frozen-selection', type=Path)
    args = p.parse_args()
    if args.evaluate and not args.frozen_selection:
        p.error('--evaluate requires --frozen-selection')
    project = Path(__file__).resolve().parents[1]
    model_path = project/'app/src/main/assets/language_model/model.cng'
    policy_path = args.baseline_policy or project.parent/'QwenASR/crates/cantonese-ngram/src/english_acceptance.json'
    policy = json.loads(policy_path.read_text())
    case_file, cache_file = args.iteration/'cases.jsonl', args.iteration/'native-cache.json'
    binding = json.loads((project/'config/english_iteration/2026-10-20/f05-linear-acceptance.json').read_text())['input_sha256']
    assert digest(case_file) == binding['build/english-iteration-2026-10-20/cases.jsonl']
    assert digest(cache_file) == binding['build/english-iteration-2026-10-20/native-cache.json']
    refs_path = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    refs = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(refs_path.open())}
    cases, outputs = [], []
    for c, out in zip(map(json.loads, case_file.open()), json.loads(cache_file.read_text())['outputs']):
        if c['category'].startswith('prefix_'):
            continue
        target = c['target'] if c['category']=='source_preservation' else reference_target(c, refs[c['group']])
        if target is not None:
            cases.append(dict(c, target=target)); outputs.append(out)
    raw = model_path.read_bytes()
    meta = json.loads(raw[12:12+struct.unpack('<I', raw[8:12])[0]])
    spellings = collections.defaultdict(list)
    for i in meta['english_word_ids']:
        spellings[meta['tokens'][i].replace('’', "'").lower()].append(i)
    contexts = collections.defaultdict(set)
    for c, o in zip(cases, outputs):
        if o['known']:
            contexts[c['left']].update(spellings[c['typed'].lower()])
    requests = [dict(left=left, candidate_ids=sorted(ids), distribution=True) for left, ids in sorted(contexts.items())]
    cache_binding = dict(model=digest(model_path), probe=digest(args.probe),
                         requests=hashlib.sha256(json.dumps(requests, sort_keys=True).encode()).hexdigest())
    cache = args.iteration/'literal-scores.json'
    if cache.exists() and json.loads(cache.read_text())['binding']==cache_binding:
        scores = json.loads(cache.read_text())['outputs']
    else:
        print(f'Scoring literals in {len(requests)} observed contexts; no labels sent to inference', flush=True)
        scores = probe(args.probe, model_path, requests)
        cache.write_text(json.dumps(dict(binding=cache_binding, outputs=scores)))
    literal = {r['left']: dict(zip(r['candidate_ids'], s['log_probs'])) for r, s in zip(requests, scores)}
    ranked = [rerank(o['candidates'], 0.) for o in outputs]
    features, eligible = [], []
    for c, o, ranking in zip(cases, outputs, ranked):
        b = ranking[0] if ranking else None
        allowed = bool(b and len(c['typed'])>=3 and len(b['word'])>=3 and not b['completedChars'])
        eligible.append(allowed)
        if not b:
            features.append([0.]*6); continue
        observed = max(literal[c['left']][i] for i in spellings[c['typed'].lower()]) if o['known'] else 0.
        o['literalScore'] = observed if o['known'] else None
        margin = min(b['score']-ranking[1]['score'], 20.) if len(ranking)>1 else 20.
        features.append([b['score'], margin, int(o['known']), observed,
                         b['score']-observed if o['known'] else 0., int("'" in c['typed'] or '’' in c['typed'])])
    x = np.array(features)
    labels = np.array([int(bool(r) and r[0]['word'].lower()==c['target'] and c['typed'].lower()!=c['target']) for c, r in zip(cases, ranked)])
    training = np.array([i for i, c in enumerate(cases) if eligible[i] and split(c['group'])=='train' and int(hashed('calibration:'+c['group'])[:8],16)%5!=0])
    calibration = [i for i, c in enumerate(cases) if split(c['group'])=='train' and int(hashed('calibration:'+c['group'])[:8],16)%5==0]
    typo_count = sum(cases[i]['typed'].lower()!=cases[i]['target'] for i in calibration)
    baseline = [decision(policy, c, o, r) for c, o, r in zip(cases, outputs, ranked)]
    trials = []
    # Fixed feature families, selected without accessing evaluation metrics.
    for columns in ([0,1,5], [0,1,2,4], [0,1,2,3,4,5]):
        for reg in (.001,.01,.1):
            fit_indices = np.array([i for i in training if columns!=[0,1,5] or not outputs[i]['known']])
            fit_model = fit(x[fit_indices][:, columns], labels[fit_indices], reg)
            normalized = (x[:, columns]-fit_model['means'])/fit_model['scales']
            probs = 1/(1+np.exp(-np.clip(fit_model['weights'][0]+normalized@np.array(fit_model['weights'][1:]), -40,40)))
            # The apostrophe-only family retains known-word preservation.
            mask = [ok and (not outputs[i]['known'] if columns==[0,1,5] else True) for i, ok in enumerate(eligible)]
            order = sorted((i for i in calibration if mask[i]),key=lambda i:-probs[i])
            tp = fp = 0; cutoffs = []
            for pos, i in enumerate(order):
                tp += int(labels[i]); fp += int(not labels[i])
                if pos+1<len(order) and probs[order[pos+1]]==probs[i]:continue
                cutoffs.append((f05(tp,fp,typo_count-tp),float(probs[i])))
            calibration_f05, threshold = max(cutoffs)
            decisions = [r[0]['word'] if mask[i] and probs[i]>=threshold else None for i,r in enumerate(ranked)]
            m = dict(fit_model, features=[FEATURES[i] for i in columns], threshold=threshold,
                     regularization=reg, preserve_known_words=columns==[0,1,5], calibration_f05=calibration_f05, fit_rows=len(fit_indices))
            trial = dict(model=m, selection=metric(cases,outputs,ranked,decisions,'selection'))
            trials.append(trial)
            print(f'{m["features"]} L2={reg} selection F0.5={trial["selection"]["f05"]:.6f}',flush=True)
    winner = max(trials, key=lambda t:(t['selection']['f05'],-len(t['model']['features'])))
    report = dict(primary_metric='f0.5', fit_rows=len(training), trials=trials, champion=winner,
                  baseline_selection=metric(cases,outputs,ranked,baseline,'selection'),
                  scope='Fixed developmental prompt groups; TRAIN fit/calibration, selection only; historical exposure',
                  source_sha256={str(p):digest(p) for p in (Path(__file__),case_file,cache_file,cache,refs_path,model_path,policy_path)})
    if args.evaluate:
        frozen = json.loads(args.frozen_selection.read_text())
        assert winner == frozen['champion'], 'Selection changed after freezing'
        report['frozen_selection_sha256'] = digest(args.frozen_selection)
        m = winner['model']; columns = [FEATURES.index(f) for f in m['features']]
        probs = 1/(1+np.exp(-np.clip(m['weights'][0]+((x[:,columns]-m['means'])/m['scales'])@np.array(m['weights'][1:]),-40,40)))
        fixes = [r[0]['word'] if eligible[i] and (not outputs[i]['known'] or not m['preserve_known_words']) and probs[i]>=m['threshold'] else None for i,r in enumerate(ranked)]
        report.update(evaluation=metric(cases,outputs,ranked,fixes,'evaluation'), baseline_evaluation=metric(cases,outputs,ranked,baseline,'evaluation'))
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('trials','source_sha256')},indent=2))


if __name__=='__main__':
    main()
