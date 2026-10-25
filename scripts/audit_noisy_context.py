#!/usr/bin/env python3
"""Paired diagnostic: does a prior typo disrupt the current word's correction?

Changes only the nearest eligible ASCII word in left context. Selection uses
input and a fixed seed, never targets or model outputs. This is synthetic stress
testing on exposed development data, not a new acceptance score or training set.
"""
import argparse
import collections
import hashlib
import json
import re
import statistics
import subprocess
from pathlib import Path

import autocorrect_eval_v2 as core

SEED = 'noisy-context-20261025-v1'
OPERATIONS = ('delete', 'substitute', 'duplicate', 'transpose')


def corrupt(left, operation):
    if operation not in OPERATIONS:
        raise ValueError(operation)
    # Do not edit ASCII fragments inside names with digits, accents, or Han.
    words = list(re.finditer(r'(?<![\w\'’])[A-Za-z]{3,}(?![\w\'’])', left))
    if not words:
        return None
    match = words[-1]
    word = match.group()
    positions = list(range(len(word)))
    if operation == 'transpose':
        positions = [i for i in range(len(word) - 1) if word[i] != word[i + 1]]
    if not positions:
        return None
    value = int(hashlib.sha256((SEED + operation + left).encode()).hexdigest(), 16)
    i = positions[value % len(positions)]
    if operation == 'delete':
        edited = word[:i] + word[i + 1:]
    elif operation == 'substitute':
        # Generic character noise, not a fitted touch-error distribution.
        alphabet = 'abcdefghijklmnopqrstuvwxyz'.replace(word[i].lower(), '')
        letter = alphabet[(value // len(positions)) % len(alphabet)]
        edited = word[:i] + (letter.upper() if word[i].isupper() else letter) + word[i + 1:]
    elif operation == 'duplicate':
        edited = word[:i] + word[i] + word[i:]
    else:
        edited = word[:i] + word[i + 1] + word[i] + word[i + 2:]
    return left[:match.start()] + edited + left[match.end():]


def pairs(cases):
    result = []
    for case in cases:
        if case['target'] is None:
            continue
        for operation in OPERATIONS:
            left = corrupt(case['left'], operation)
            if left is not None:
                result.append(dict(id=case['id'] + ':' + operation,
                                   source_id=case['id'], panel=case['panel'],
                                   operation=operation, original_left=case['left'],
                                   left=left, typed=case['typed'], target=case['target']))
    return result


def paired_metrics(cases, original, noisy):
    before = core.metrics(cases, original)
    after = core.metrics(cases, noisy)
    def committed(case, output):
        return core.norm(output.get('correction') or case['typed'])
    changes = collections.Counter(regressions=0, recoveries=0, decisions_changed=0)
    for case, old, new in zip(cases, original, noisy, strict=True):
        a, b = committed(case, old), committed(case, new)
        target = core.norm(case['target'])
        changes['decisions_changed'] += a != b
        changes['regressions'] += a == target and b != target
        changes['recoveries'] += a != target and b == target
    return dict(original=before, noisy=after, f05_delta=after['f05'] - before['f05'], **changes)


def run(args):
    manifest = json.loads((args.suite / 'manifest.json').read_text())
    source_path = args.suite / 'cases.jsonl'
    if core.digest(source_path) != manifest['cases_sha256']:
        raise ValueError('Frozen source cases changed')
    source = core.rows(source_path)
    cases = pairs(source)
    if not cases:
        raise ValueError('No eligible left contexts')
    if len({c['id'] for c in cases}) != len(cases):
        raise ValueError('Duplicate paired IDs')
    args.output.mkdir(parents=True, exist_ok=False)
    core.jsonl(args.output / 'pairs.jsonl', cases)
    binding = dict(schema='noisy-context-audit-v1', seed=SEED,
                   source_manifest_sha256=core.digest(args.suite / 'manifest.json'),
                   source_cases_sha256=core.digest(source_path),
                   pairs_sha256=core.digest(args.output / 'pairs.jsonl'),
                   script_sha256=core.digest(__file__), metrics_code_sha256=core.digest(core.__file__),
                   model_sha256=core.digest(args.model), probe_sha256=core.digest(args.probe),
                   source_cases=len(source), paired_cases=len(cases),
                   eligible_source_cases=len({c['source_id'] for c in cases}),
                   parameters_selected_in_this_run=False,
                   inference_fields=['left', 'typed', 'spelling_limit', 'timing'])
    # Freeze provenance and every perturbation before any inference.
    core.write_json(args.output / 'frozen-run.json', binding)
    requests = []
    for case in cases:
        requests.extend([dict(case, left=case['original_left']), case])
    proc = subprocess.run([str(args.probe.resolve()), str(args.model.resolve())],
                          input=core.native_requests(requests), text=True,
                          capture_output=True, check=True, timeout=1800)
    outputs = [json.loads(line) for line in proc.stdout.splitlines() if line]
    if len(outputs) != 2 * len(cases):
        raise ValueError('Probe output count differs from request count')
    for name, path in [('probe', args.probe), ('model', args.model)]:
        if core.digest(path) != binding[name + '_sha256']:
            raise ValueError(name + ' changed during inference')
    core.jsonl(args.output / 'outputs.jsonl', outputs)
    original, noisy = outputs[::2], outputs[1::2]
    report = dict(binding=binding, operations={})
    for operation in OPERATIONS:
        ids = [i for i, c in enumerate(cases) if c['operation'] == operation]
        def summarize(indices):
            return paired_metrics([cases[i] for i in indices], [original[i] for i in indices],
                                  [noisy[i] for i in indices])
        report['operations'][operation] = dict(
            overall=summarize(ids),
            panels={panel: summarize([i for i in ids if cases[i]['panel'] == panel])
                    for panel in sorted({cases[i]['panel'] for i in ids})})
    timings = [o['elapsedMicros'] / 1000 for o in outputs if 'elapsedMicros' in o]
    report['latency'] = dict(calls=len(timings), median_ms=statistics.median(timings) if timings else None,
                             p95_ms=core.percentile(timings, .95) if timings else None,
                             scope='Interleaved original/noisy release-host calls; repeated contexts and cache reuse; not phone latency or a speed comparison')
    report['limits'] = [
        'Synthetic single-character context noise; not measured user error frequencies',
        'The source left context may already contain errors; original does not mean clean',
        'Targets describe the current word only and stay unchanged; preceding intent is not adjudicated',
        'Nearest eligible ASCII word only; no Cantonese, touch, next-word, or closed-loop typing qualification',
        'Repeated exposed development cases; operation scores are paired diagnostics, not independent evidence',
        'No training, model selection, objective weights, acceptance threshold, or runtime changes',
    ]
    core.write_json(args.output / 'report.json', report)
    print(json.dumps({op: report['operations'][op]['overall'] for op in OPERATIONS}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path, required=True)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--model', type=Path, default=core.PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args())


if __name__ == '__main__':
    main()
