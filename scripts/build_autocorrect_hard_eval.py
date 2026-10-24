#!/usr/bin/env python3
"""Version the authored stress cohort by input properties, never model outcomes."""
import argparse
import collections
import json
import shutil
from pathlib import Path

from align_mobile_targets import osa
from autocorrect_eval_v2 import digest, jsonl, norm, overlap, rows, scoring_hashes, write_json

RULE = dict(schema='authored-hard-v1',
            retain_errors='OSA distance >= 2 OR domain in apostrophes, word_boundaries, real_word_intent',
            retain_clean='All original authored clean controls',
            remove_errors='Other single-edit authored errors, irrespective of model correctness',
            natural_panels='Retain every original natural observation and source-copy control',
            exposure='User-requested redesign after v2 results were exposed; not independent confirmation')


def retain(case):
    if case['panel'] != 'authored_challenge':
        return True
    if norm(case['typed']) == norm(case['target']):
        return True
    return (osa(norm(case['typed']), norm(case['target'])) >= 2 or
            case['domain'] in ['apostrophes', 'word_boundaries', 'real_word_intent'])


def build(parent, training, output):
    if output.exists():
        raise ValueError('Refusing to replace a frozen suite')
    manifest = json.loads((parent / 'manifest.json').read_text())
    for name in ['cases', 'trials', 'overlap']:
        suffix = '.json' if name == 'overlap' else '.jsonl'
        if digest(parent / (name + suffix)) != manifest[name + '_sha256']:
            raise ValueError('Parent frozen suite changed')
    if manifest['scoring_code_sha256'] != scoring_hashes():
        raise ValueError('Parent scoring code changed')
    if digest(training) != manifest['source_hashes']['acceptance_replay']:
        raise ValueError('Acceptance replay differs from parent overlap audit')
    source = rows(parent / 'cases.jsonl')
    selected = [dict(c, panel='authored_hard' if c['panel'] == 'authored_challenge' else c['panel'])
                for c in source if retain(c)]
    removed = [dict(c, removal_reason='Ordinary single-edit error') for c in source if not retain(c)]
    replay = rows(training)
    audit = {panel: overlap([c for c in selected if c['panel'] == panel], replay)
             for panel in sorted({c['panel'] for c in selected})}
    output.mkdir(parents=True)
    jsonl(output / 'cases.jsonl', selected)
    jsonl(output / 'removed-authored.jsonl', removed)
    shutil.copyfile(parent / 'trials.jsonl', output / 'trials.jsonl')
    write_json(output / 'overlap.json', audit)
    new = dict(manifest, schema='autocorrect-eval-v3-hard',
               cases_sha256=digest(output / 'cases.jsonl'),
               trials_sha256=digest(output / 'trials.jsonl'),
               overlap_sha256=digest(output / 'overlap.json'),
               parent_manifest_sha256=digest(parent / 'manifest.json'),
               builder_sha256=digest(__file__), cohort_rule=RULE,
               removed_authored=len(removed), removed_authored_sha256=digest(output / 'removed-authored.jsonl'),
               panels=dict(collections.Counter(c['panel'] for c in selected)),
               limits=manifest['limits'] + [RULE['exposure'],
                   'Hard authored error cohort is small and no longer covers ordinary short-word single-edit errors; historical v2 remains available'])
    write_json(output / 'manifest.json', new)
    jsonl(output / 'authored-hard.jsonl', [c for c in selected if c['panel'] == 'authored_hard'])
    print(json.dumps(dict(panels=new['panels'], removed_authored=len(removed), rule=RULE), indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, required=True)
    p.add_argument('--training', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    build(a.parent, a.training, a.output)
