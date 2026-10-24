#!/usr/bin/env python3
"""One fixed development objective across three autocorrection evaluations."""
import argparse
import collections
import contextlib
import hashlib
import io
import json
import shutil
from pathlib import Path

import autocorrect_eval_v2 as core

SEED = 'autocorrect-clean-balance-20261024-v1'
OBJECTIVE = dict(schema='autocorrect-objective-v1', metric='equal_weight_mean_f05',
                 weights=dict(historical_full_mobile=1 / 3, real_touch_observed=1 / 3, authored_hard=1 / 3),
                 direction='maximize', clean_controls=46, hard_errors=46,
                 clean_selection='Seeded domain round robin without model predictions',
                 status='Exposed development objective for hill climbing; not population accuracy or independent confirmation')


def stable_key(text):
    return hashlib.sha256((SEED + text).encode()).hexdigest()


def balanced_clean(cases, count=46):
    buckets = collections.defaultdict(list)
    for c in cases:
        if core.norm(c['typed']) == core.norm(c['target']):
            buckets[c['domain']].append(c)
    for bucket in buckets.values():
        bucket.sort(key=lambda c: stable_key(c['id']))
    domains = sorted(buckets, key=stable_key)
    selected = []
    while len(selected) < count:
        added = False
        for domain in domains:
            if buckets[domain] and len(selected) < count:
                selected.append(buckets[domain].pop(0))
                added = True
        if not added:
            raise ValueError('Not enough clean controls')
    return selected


def objective(report):
    values = dict(historical_full_mobile=report['historical_full_mobile']['f05'],
                  real_touch_observed=report['panels']['real_touch_observed']['candidate']['f05'],
                  authored_hard=report['panels']['authored_hard']['candidate']['f05'])
    return sum(OBJECTIVE['weights'][name] * value for name, value in values.items()), values


def freeze(args):
    if args.output.exists():
        raise ValueError('Refusing to replace a frozen objective suite')
    parent = json.loads((args.parent / 'manifest.json').read_text())
    if parent['schema'] != 'autocorrect-eval-v3-hard':
        raise ValueError('Expected the preserved v3 hard suite')
    for name in ['cases', 'trials', 'overlap']:
        suffix = '.json' if name == 'overlap' else '.jsonl'
        if core.digest(args.parent / (name + suffix)) != parent[name + '_sha256']:
            raise ValueError('Parent suite changed')
    if core.digest(args.training) != parent['source_hashes']['acceptance_replay']:
        raise ValueError('Acceptance replay changed')
    source = core.rows(args.parent / 'cases.jsonl')
    hard = [c for c in source if c['panel'] == 'authored_hard']
    chosen = {c['id'] for c in balanced_clean(hard)}
    def keep(c):
        return c['panel'] != 'authored_hard' or core.norm(c['typed']) != core.norm(c['target']) or c['id'] in chosen
    selected = [c for c in source if keep(c)]
    errors = [c for c in hard if core.norm(c['typed']) != core.norm(c['target'])]
    if len(errors) != 46:
        raise ValueError('Hard error cohort changed')
    replay = core.rows(args.training)
    audit = {panel: core.overlap([c for c in selected if c['panel'] == panel], replay)
             for panel in sorted({c['panel'] for c in selected})}
    args.output.mkdir(parents=True)
    core.jsonl(args.output / 'cases.jsonl', selected)
    core.jsonl(args.output / 'authored-hard.jsonl', [c for c in selected if c['panel'] == 'authored_hard'])
    core.jsonl(args.output / 'removed-clean.jsonl', [c for c in source if not keep(c)])
    shutil.copyfile(args.parent / 'trials.jsonl', args.output / 'trials.jsonl')
    core.write_json(args.output / 'overlap.json', audit)
    manifest = dict(parent, schema='autocorrect-eval-v4-objective', objective=OBJECTIVE,
                    parent_manifest_sha256=core.digest(args.parent / 'manifest.json'),
                    objective_code_sha256=core.digest(__file__),
                    cases_sha256=core.digest(args.output / 'cases.jsonl'),
                    overlap_sha256=core.digest(args.output / 'overlap.json'),
                    scoring_code_sha256=core.scoring_hashes(),
                    panels=dict(collections.Counter(c['panel'] for c in selected)),
                    clean_selection_seed=SEED, removed_clean=len(source) - len(selected),
                    removed_clean_sha256=core.digest(args.output / 'removed-clean.jsonl'),
                    limits=parent['limits'] + [OBJECTIVE['status'], 'Equal benchmark weights are an optimization convention, not measured user-population weights'])
    core.write_json(args.output / 'manifest.json', manifest)
    print(json.dumps(dict(panels=manifest['panels'], objective=OBJECTIVE), indent=2))


def score(args):
    manifest = json.loads((args.suite / 'manifest.json').read_text())
    if manifest.get('objective') != OBJECTIVE or manifest.get('objective_code_sha256') != core.digest(__file__):
        raise ValueError('Objective or code differs from frozen definition')
    with contextlib.redirect_stdout(io.StringIO()):
        core.score(args)
    report_path = args.output / 'report.json'
    report = json.loads(report_path.read_text())
    value, components = objective(report)
    result = dict(score=value, objective=OBJECTIVE, components=components,
                  manifest_sha256=core.digest(args.suite / 'manifest.json'),
                  detailed_report_sha256=core.digest(report_path))
    if args.baseline_probe:
        old = dict(historical_full_mobile=report['panels']['mobile_observed']['baseline'])
        # Reconstruct original combined mobile counts; do not average its subpanels.
        observed = report['panels']['mobile_observed']['baseline']
        copied = report['panels']['mobile_source_copy']['baseline']
        combined = collections.Counter({k: observed[k] + copied[k] for k in ['tp', 'fp', 'typos', 'clean', 'clean_changes']})
        old['historical_full_mobile'] = core.rates(combined)
        old['panels'] = {name: dict(candidate=report['panels'][name]['baseline']) for name in ['real_touch_observed', 'authored_hard']}
        before, _ = objective(old)
        result.update(baseline_score=before, delta=value - before)
    core.write_json(args.output / 'score.json', result)
    print(f'{value:.6f}')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='action', required=True)
    f = sub.add_parser('freeze')
    f.add_argument('--parent', type=Path, required=True)
    f.add_argument('--training', type=Path, required=True)
    f.add_argument('--output', type=Path, required=True)
    s = sub.add_parser('score')
    s.add_argument('--suite', type=Path, required=True)
    s.add_argument('--probe', type=Path, required=True)
    s.add_argument('--baseline-probe', type=Path)
    s.add_argument('--model', type=Path, default=core.PROJECT / 'app/src/main/assets/language_model/model.cng')
    s.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    (freeze if args.action == 'freeze' else score)(args)


if __name__ == '__main__':
    main()
