#!/usr/bin/env python3
"""Development experiments for edit costs and recorded touch geometry.

Never reads protected LM tests. Touch participant splits are inherited unchanged.
Text scores come from the baseline content-bound cache, not another keyboard's
corrections. No fitted value is automatically installed into the app.
Requires NumPy.
"""
import argparse
import csv
import json
from pathlib import Path

import numpy as np

from align_mobile_targets import reference_target
from audit_candidate_pipeline import digest, penalty
from iterate_english_policy import split


def operations(observed, intended):
    """OSA traceback counts: substitution, extra tap, omitted tap, swap."""
    table = [[(i+j, (0, i, j, 0)) for j in range(len(intended)+1)]
             for i in range(len(observed)+1)]
    for i, a in enumerate(observed, 1):
        for j, b in enumerate(intended, 1):
            choices = []
            for pi, pj, index, changed in ((i-1, j-1, 0, a != b),
                                           (i-1, j, 1, True), (i, j-1, 2, True)):
                cost, counts = table[pi][pj]
                counts = list(counts)
                counts[index] += changed
                choices.append((cost+changed, tuple(counts)))
            if i > 1 and j > 1 and a == intended[j-2] and observed[i-2] == b:
                cost, counts = table[i-2][j-2]
                counts = list(counts)
                counts[3] += 1
                choices.append((cost+1, tuple(counts)))
            table[i][j] = min(choices)
    return table[-1][-1][1]


def aligned_cases(iteration, references):
    cases = [json.loads(line) for line in (iteration/'cases.jsonl').open()]
    cache = json.loads((iteration/'native-cache.json').read_text())
    if cache['binding']['probe'] != 'd7a596915e77fce8901fd125a8c3dfbefb566ef9feb224a1406264445182cd22':
        raise ValueError('This experiment requires the frozen pre-change probe cache')
    outputs = cache['outputs']
    if len(cases) != len(outputs):
        raise ValueError('Cached case/result count mismatch')
    for case, output in zip(cases, outputs):
        if case['category'].startswith('prefix_'):
            continue
        target = case['target'] if case['category'] == 'source_preservation' else reference_target(case, references[case['group']])
        if target is not None:
            yield dict(case, target=target), output


def fit_edits(iteration, references):
    cases = list(aligned_cases(iteration, references))
    groups, evidence, features, gold = [], [], [], []
    # Keep the existing candidate pool; report coverage separately from ranking.
    for case, output in cases:
        rows = output['candidates']
        start = len(evidence)
        for row in rows:
            word = row['word'].lower()
            old_cost = (1.5 if len(word) == len(case['typed'])+1 else 1.) if ' ' in word else penalty(case['typed'].lower(), word, row['edits'])
            if row['completedChars']:
                old_cost = .5*row['completedChars']
            evidence.append(row['score']+old_cost)
            features.append((0, 0, 0, 0) if row['completedChars'] else operations(case['typed'].lower(), word))
            gold.append(word == case['target'])
        groups.append((case, rows, start, len(evidence)))
    lm = np.array(evidence)
    x = np.array(features, dtype=float)
    gold = np.array(gold, dtype=float)
    train = [(a, b) for c, _, a, b in groups if split(c['group']) == 'train' and b > a and gold[a:b].sum()]
    indices = np.concatenate([np.arange(a, b) for a, b in train])
    starts = np.cumsum([0]+[b-a for a, b in train[:-1]])
    lengths = np.array([b-a for a, b in train])
    tx, tlm, ty = x[indices], lm[indices], gold[indices]

    def metrics(weights, partition, baseline=False):
        counts = dict(cases=0, pool_covered=0, top1=0, top8=0)
        scores = lm-x@weights
        for c, rows, a, b in groups:
            if split(c['group']) != partition:
                continue
            counts['cases'] += 1
            counts['pool_covered'] += bool(gold[a:b].sum())
            order = sorted(range(a, b), key=lambda k: (-(rows[k-a]['score']+.5*rows[k-a]['completedChars'] if baseline else scores[k]), rows[k-a]['word']))
            counts['top1'] += bool(order and gold[order[0]])
            counts['top8'] += bool(gold[order[:8]].sum())
        return counts

    trials = []
    for regularization in (0., .001, .01, .1):
        weights = np.zeros(4)
        for epoch in range(250):
            scores = tlm-tx@weights
            scores -= np.repeat(np.maximum.reduceat(scores, starts), lengths)
            p = np.exp(scores)
            p /= np.repeat(np.add.reduceat(p, starts), lengths)
            gradient = -(tx.T@(p-ty))/len(train)+regularization*weights
            weights = np.maximum(0., weights-.2*gradient/(1+epoch/125))
        trials.append(dict(regularization=regularization, costs=weights.tolist(), selection=metrics(weights, 'selection')))
    winner = max(trials, key=lambda t: (t['selection']['top8'], t['selection']['top1'], -sum(t['costs'])))
    return dict(features=['substitution', 'extra_tap', 'omitted_tap', 'adjacent_swap'],
                fitting_cases=len(train), trials=trials, selected=winner,
                baseline_evaluation=metrics(np.zeros(4), 'evaluation', True),
                fitted_evaluation=metrics(np.array(winner['costs']), 'evaluation'),
                deployed=False, limitation='Fixed generated candidate pool; costs invalidate existing acceptance calibration. No automatic-correction quality claim.')


def touch_examples(case):
    # Substitution-only alignment avoids inventing reference-to-tap alignment.
    target, observed = case['target'], case['typed']
    if len(target) != len(observed) or len(case['touch']) != len(target) or not target.isascii() or not target.isalpha():
        return []
    if any(operations(observed.lower(), target.lower())[1:]):
        return []
    centers = np.array(case['centers'], dtype=float)
    # Normalize horizontal and vertical axes by the recorded row/key pitches.
    horizontal = float(np.linalg.norm(centers[ord('w')-97]-centers[ord('q')-97]))
    vertical = abs(float(centers[ord('a')-97, 1]-centers[ord('q')-97, 1]))
    if horizontal <= 0 or vertical <= 0 or not np.isfinite(centers).all():
        return []
    scale = np.array([horizontal, vertical])
    result = []
    for letter, tap in zip(target.lower(), case['touch']):
        tap = np.array(tap, dtype=float)
        if not np.isfinite(tap).all():
            continue
        index = ord(letter)-97
        result.append((index, (tap-centers[index])/scale, (tap-centers)/scale))
    return result


def fit_touch(path):
    samples = {name: [] for name in ('train', 'calibration', 'selection')}
    people = {name: set() for name in samples}
    for line in path.open():
        case = json.loads(line)
        samples[case['split']].extend(touch_examples(case))
        people[case['split']].add(case['group'])
    assert not (people['train'] & people['selection'] or people['train'] & people['calibration'] or people['calibration'] & people['selection'])
    labels = np.array([r[0] for r in samples['train']])
    residuals = np.array([r[1] for r in samples['train']])
    global_mean = residuals.mean(axis=0)
    global_second = (residuals**2).mean(axis=0)

    def estimate(strength):
        means, variances = [], []
        for key in range(26):
            rows = residuals[labels == key]
            mean = (rows.sum(axis=0)+strength*global_mean)/(len(rows)+strength)
            second = ((rows**2).sum(axis=0)+strength*global_second)/(len(rows)+strength)
            means.append(mean)
            variances.append(np.maximum(second-mean**2, np.finfo(float).eps))
        return np.array(means), np.array(variances)

    def evaluate(rows, means=None, variances=None):
        nll = hits = 0
        for label, _, offsets in rows:
            if means is None:
                score = -(offsets**2).sum(axis=1)
            else:
                score = -.5*(((offsets-means)**2/variances).sum(axis=1)+np.log(variances).sum(axis=1))
            nll += -score[label]+np.log(np.exp(score-score.max()).sum())+score.max()
            hits += int(score.argmax() == label)
        return dict(taps=len(rows), correct=hits, accuracy=hits/len(rows), mean_key_nll=nll/len(rows))

    trials = []
    for strength in (1., 4., 16., 64., 256., 1024.):
        means, variances = estimate(strength)
        trials.append(dict(shrinkage_taps=strength, calibration=evaluate(samples['calibration'], means, variances)))
    winner = min(trials, key=lambda t: t['calibration']['mean_key_nll'])
    means, variances = estimate(winner['shrinkage_taps'])
    return dict(schema='recorded-touch-gaussian-v1', coordinate_units='horizontal and vertical key pitch from supplied key centres',
                participants={k: sorted(v) for k, v in people.items()},
                fit_taps=len(residuals), selection_by='calibration key negative log likelihood', trials=trials,
                shrinkage_taps=winner['shrinkage_taps'], means=means.tolist(), variances=variances.tolist(),
                selection_nearest_center=evaluate(samples['selection']),
                selection_fitted=evaluate(samples['selection'], means, variances), deployed=False,
                limitations=['Retained alphabetic spans with substitution-only OSA alignment; not all taps or deletion errors.',
                             'Participant-disjoint copy typing; prompts can overlap.',
                             'Key classification, not word correction or on-device latency validation.',
                             'Historical development exposure; no fresh-test claim.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--iteration', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reference_path = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    touch_path = Path('/Volumes/SSD/cantonese_ngram/experiments/human-touch-v28/cases.jsonl')
    references = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(reference_path.open())}
    print('Fitting edit operation costs on TRAIN prompt groups', flush=True)
    edits = fit_edits(args.iteration, references)
    print('Fitting touch offsets on TRAIN participants; selecting on calibration participants', flush=True)
    touch = fit_touch(touch_path)
    sources = [Path(__file__), reference_path, touch_path, touch_path.with_name('manifest.json'),
               args.iteration/'cases.jsonl', args.iteration/'native-cache.json']
    report = dict(source_sha256={str(p): digest(p) for p in sources}, edit_costs=edits, touch=touch)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(dict(edits={k: v for k, v in edits.items() if k != 'trials'},
                          touch={k: v for k, v in touch.items() if k not in ('means', 'variances', 'trials')}), indent=2))


if __name__ == '__main__':
    main()
