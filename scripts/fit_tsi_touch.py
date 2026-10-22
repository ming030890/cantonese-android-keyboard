#!/usr/bin/env python3
"""Coordinate-only TSI experiment. No reference text is passed to the decoder.

Fit user01-10; calibrate mixture weight user11-12; qualify user13-14;
evaluate user15-16 only after the selection report has been saved.
Requires numpy. Raw files stay in ignored build/tsi-touch/raw.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

import numpy as np

PROJECT = Path(__file__).resolve().parents[1]
ENGINE = PROJECT.parent / 'QwenASR/crates/cantonese-ngram/src'
LETTERS = 'abcdefghijklmnopqrstuvwxyz'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def partition(user):
    n = int(user[-2:])
    return 'fit' if n <= 10 else 'calibration' if n <= 12 else 'selection' if n <= 14 else 'evaluation'


def read_data(raw):
    layout = json.loads((raw / 'keyboard_data.json').read_text())['keys_info']
    with (raw / 'touch_data.csv').open(newline='') as f:
        rows = list(csv.DictReader(f))
    return layout, rows


def fit(layout, rows):
    offsets = collections.defaultdict(list)
    for r in rows:
        key = r['ref_char']
        if partition(r['participant_id']) != 'fit' or key not in LETTERS:
            continue
        k = layout[key]
        offsets[key].append([(float(r['first_frame_touch_x']) - k['key_center_x']) / k['key_width'],
                             (float(r['first_frame_touch_y']) - k['key_center_y']) / k['key_height']])
    # Winsorization bounds the influence of alignment errors. Student-t scoring
    # retains heavy tails; no key-frequency prior is learned from prompted text.
    pooled = np.clip(np.array([v for values in offsets.values() for v in values]), -2, 2)
    global_mean = pooled.mean(axis=0)
    global_cov = np.cov(pooled.T, bias=True)
    keys = []
    for key in LETTERS:
        a = np.clip(np.array(offsets[key]), -2, 2)
        strength = len(a) / (len(a) + 100.)
        mean = strength * a.mean(axis=0) + (1-strength) * global_mean
        cov = strength * np.cov(a.T, bias=True) + (1-strength) * global_cov + np.eye(2) * .0025
        inv = np.linalg.inv(cov)
        keys.append(dict(mean=mean.tolist(), inverse=[inv[0, 0], inv[0, 1], inv[1, 1]],
                         log_det=float(np.log(np.linalg.det(cov))), samples=len(a)))
    return dict(schema='tsi-coordinate-v1', weight=0., degrees_of_freedom=4.,
                max_log_ratio=8., keys=keys,
                provenance=dict(source='https://github.com/google-research-datasets/tap-typing-with-touch-sensing-images',
                                license='CC-BY-4.0', fit_participants=[f'user{i:02}' for i in range(1, 11)],
                                features=['dx/key_width', 'dy/key_height'],
                                scope='same-length letter substitutions; original costs retained'))


def log_density(model, key, dx, dy):
    p = model['keys'][LETTERS.index(key)]
    x, y = dx-p['mean'][0], dy-p['mean'][1]
    a, b, c = p['inverse']
    q = max(0., a*x*x + 2*b*x*y + c*y*y)
    return -.5*p['log_det'] - 3.*math.log1p(q/4.)


def touch_delta(model, typed, candidate, points, geometry, edits, completed):
    candidate = candidate.lower()
    if completed or len(typed) != len(candidate) or not set(typed+candidate) <= set(LETTERS):
        return 0.
    # A swap/length change needs explicit alignment, and is outside this pilot.
    if sum(a != b for a, b in zip(typed, candidate)) != edits:
        return 0.
    delta = 0.
    for i, (a, b) in enumerate(zip(typed, candidate)):
        if a == b:
            continue
        x, y = points[2*i:2*i+2]
        def score(k):
            cx, cy, w, h = geometry[4*LETTERS.index(k):4*LETTERS.index(k)+4]
            return log_density(model, k, (x-cx)/w, (y-cy)/h)
        delta += score(b)-score(a)
    return max(-model['max_log_ratio'], min(model['max_log_ratio'], delta))


def nearest(layout, x, y):
    # Reconstruct a visual-rectangle hit test. These are replay observations,
    # not the unavailable original Gboard key outputs/hit-target expansion.
    key = min(layout, key=lambda c: ((max(abs(x-layout[c]['key_center_x'])-layout[c]['key_width']/2, 0)/135)**2 +
                                    (max(abs(y-layout[c]['key_center_y'])-layout[c]['key_height']/2, 0)/206)**2,
                                    ((x-layout[c]['key_center_x'])/135)**2 + ((y-layout[c]['key_center_y'])/206)**2))
    return ' ' if key == 'SPACE' else key


def cases(layout, rows, raw, include_evaluation=False):
    with (raw/'prompt_data.csv').open(newline='') as f:
        prompts = {tuple(r[k] for k in ('participant_id', 'task_id', 'trial_id')): r for r in csv.DictReader(f)}
    groups = collections.defaultdict(list)
    for r in rows:
        if partition(r['participant_id']) == 'evaluation' and not include_evaluation:
            continue
        if r['was_deleted'] == 'False':
            groups[tuple(r[k] for k in ('participant_id', 'task_id', 'trial_id'))].append(r)
    geom = [v for c in LETTERS for v in (layout[c]['key_center_x'], layout[c]['key_center_y'], layout[c]['key_width'], layout[c]['key_height'])]
    output, audit = [], collections.Counter()
    for group, taps in sorted(groups.items()):
        prompt = prompts[group]
        if prompt['prompt_type'] != 'phrase':
            continue
        taps.sort(key=lambda r: int(r['timestamp_ms']))
        observed = ''.join(nearest(layout, float(r['first_frame_touch_x']), float(r['first_frame_touch_y'])) for r in taps)
        indices = [int(r['ref_char_index_in_prompt']) for r in taps]
        for match in re.finditer(r'[A-Za-z]+', prompt['prompt']):
            audit['reference_words'] += 1
            positions = [i for i, ref in enumerate(indices) if match.start() <= ref < match.end()]
            if not positions or [indices[i] for i in positions] != list(range(match.start(), match.end())) or positions != list(range(positions[0], positions[-1]+1)):
                audit['unaligned_or_length_error'] += 1
                continue
            start, end = positions[0], positions[-1]+1
            typed = observed[start:end]
            if not typed.isascii() or not typed.isalpha() or (start and observed[start-1].isalpha()) or (end < len(observed) and observed[end].isalpha()):
                audit['boundary_error'] += 1
                continue
            if not 3 <= len(typed) <= 32:
                audit['outside_word_length_support'] += 1
                continue
            output.append(dict(id=':'.join(group)+f':{match.start()}', participant=group[0], split=partition(group[0]),
                               typed=typed, target=match.group().lower(), left=observed[:start],
                               points=[float(r[k]) for r in taps[start:end] for k in ('first_frame_touch_x', 'first_frame_touch_y')],
                               geometry=geom))
    audit['scored_words'] = len(output)
    return output, dict(audit)


def accept(policy, typed, result, ranked):
    if not ranked:
        return None
    best = ranked[0]
    literal = result['literalScore']
    known = result['known']
    if (known and (policy['preserve_known_words'] or literal is None)) or best['completedChars'] or len(typed)<policy['minimum_observed_length'] or len(best['word'])<policy['minimum_candidate_length']:
        return None
    margin = min(best['score']-ranked[1]['score'] if len(ranked)>1 else policy['margin_cap'], policy['margin_cap'])
    features = [best['score'], margin, float(known), literal or 0., best['score']-literal if literal is not None else 0., float("'" in typed)]
    z = policy['weights'][0] + sum(w*(v-m)/s for w, v, m, s in zip(policy['weights'][1:], features, policy['means'], policy['scales']))
    return best['word'] if z >= math.log(policy['threshold']/(1-policy['threshold'])) else None


def replay(model, policy, words, results, weight):
    predictions = []
    for case, result in zip(words, results):
        if result.get('apostropheEvidence'):
            predictions.append(result['correction'])
            continue
        ranked = []
        for r in result['candidates']:
            delta = touch_delta(model, case['typed'], r['word'], case['points'], case['geometry'], r['edits'], r['completedChars'])
            row = dict(r)
            row['score'] = float(np.float32(r['score']) + np.float32(weight)*np.float32(delta))
            ranked.append(row)
        ranked.sort(key=lambda r: (-r['score'], r['word']))
        # No apostrophes in this dataset's intended alphabet. Preserve native
        # restoration decisions rather than reinterpreting its separate policy.
        correction = accept(policy, case['typed'], result, ranked)
        predictions.append(correction)
    return predictions


def metrics(words, predictions):
    observed = [c['typed'].lower() for c in words]
    targets = [c['target'].lower() for c in words]
    predictions = [p.lower() if p is not None else None for p in predictions]
    typo = sum(a != b for a, b in zip(observed, targets))
    tp = sum(p == b and a != b for a, b, p in zip(observed, targets, predictions))
    fp = sum(p is not None and p != b for b, p in zip(targets, predictions))
    clean = sum(p is not None and a == b and p != b for a, b, p in zip(observed, targets, predictions))
    return dict(cases=len(words), typos=typo, controls=len(words)-typo, correct=tp, wrong=fp, false_changes=clean,
                f05=1.25*tp/(1.25*tp+fp+.25*(typo-tp)) if typo+fp else 0.,
                precision=tp/(tp+fp) if tp+fp else 0., recall=tp/typo if typo else 0.)


def run_probe(binary, words):
    requests = [dict(left=c['left'], typed=c['typed'], spelling_limit=4096) for c in words]
    proc = subprocess.run([str(binary), str(PROJECT/'app/src/main/assets/language_model/model.cng')],
                          input=''.join(json.dumps(c)+'\n' for c in requests), text=True, capture_output=True, check=True, timeout=600)
    results = [json.loads(line) for line in proc.stdout.splitlines()]
    assert len(results) == len(words)
    return results


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--raw', type=Path, default=PROJECT/'build/tsi-touch/raw')
    p.add_argument('--output', type=Path, default=PROJECT/'config/english_iteration/2026-10-22/tsi-touch')
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--evaluate', action='store_true')
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    layout, rows = read_data(args.raw)
    selection_file = args.output/'selection.json'
    if args.evaluate:
        frozen = json.loads(selection_file.read_text())
        if not frozen['qualifies']:
            raise ValueError('Candidate failed selection; keep evaluation participants unopened')
        model = json.loads((args.output/'touch-model.json').read_text())
        assert frozen['touch_model_sha256'] == digest(args.output/'touch-model.json')
    else:
        model = fit(layout, rows)
    words, audit = cases(layout, rows, args.raw, args.evaluate)
    splits = ['evaluation'] if args.evaluate else ['calibration', 'selection']
    policy = json.loads((ENGINE/'english_acceptance.json').read_text())
    data = {}
    for split in splits:
        w = [c for c in words if c['split'] == split]
        cache = args.raw.parent/f'{split}-baseline.json'
        identity = hashlib.sha256(json.dumps(w, sort_keys=True).encode()).hexdigest()
        if cache.exists():
            saved = json.loads(cache.read_text())
            assert saved['cases_sha256'] == identity and saved['probe_sha256'] == digest(args.probe)
            r = saved['results']
        else:
            r = run_probe(args.probe, w)
            cache.write_text(json.dumps(dict(cases_sha256=identity, probe_sha256=digest(args.probe), results=r)))
        assert replay(model, policy, w, r, 0.) == [x['correction'] for x in r], 'Baseline acceptance replay mismatch'
        data[split] = (w, r)
    if not args.evaluate:
        w, r = data['calibration']
        grid = {str(a): metrics(w, replay(model, policy, w, r, a)) for a in [0., .125, .25, .5, 1., 2.]}
        weight = max(grid, key=lambda a: (grid[a]['f05'], -float(a)))
        model['weight'] = float(weight)
        (args.output/'touch-model.json').write_text(json.dumps(model, indent=2)+'\n')
    report = dict(protocol='participant-disjoint TSI coordinate pilot v1', audit=audit,
                  raw_sha256={f.name: digest(f) for f in args.raw.iterdir() if f.suffix in ('.json', '.csv')},
                  probe_sha256=digest(args.probe), model_sha256=digest(PROJECT/'app/src/main/assets/language_model/model.cng'),
                  acceptance_sha256=digest(ENGINE/'english_acceptance.json'), script_sha256=digest(Path(__file__)),
                  touch_model_sha256=digest(args.output/'touch-model.json'), weight=model['weight'], panels={})
    for split, (w, r) in data.items():
        report['panels'][split] = dict(participants=sorted({c['participant'] for c in w}),
                                     baseline=metrics(w, [x['correction'] for x in r]),
                                     touch=metrics(w, replay(model, policy, w, r, model['weight'])))
    if not args.evaluate:
        report['calibration_grid'] = grid
        panel = report['panels']['selection']
        report['qualifies'] = model['weight'] > 0 and panel['touch']['f05'] > panel['baseline']['f05']
    name = 'evaluation.json' if args.evaluate else 'selection.json'
    (args.output/name).write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
