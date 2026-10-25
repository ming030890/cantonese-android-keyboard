#!/usr/bin/env python3
"""Development-only adjacent-typo stress replay; never a natural-population score.

Recorded uncertain focal events are paired with one deterministic synthetic typo
in the preceding prompt word. Clean counterparts retain the original prefix.
Only development rows enter this diagnostic; parameters remain frozen.
"""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path
import re

import autocorrect_eval_v2 as core
import audit_noisy_context as noise
import replay_context_revision as replay


def build_pairs(cases, records, refs, limit=1000):
    by_id = {c['id']: c for c in cases}
    chosen = []
    groups = collections.Counter()
    for record in sorted(records, key=lambda r: hashlib.sha256(('adjacent-refresh-v1:' + r['id']).encode()).hexdigest()):
        if not record['seed']:
            continue
        c = by_id[record['id']]
        if groups[c['group']] >= 2:
            continue
        # Every prefix word must be an exactly aligned clean prompt token.
        if not refs[c['group']].casefold().startswith(c['left'].casefold()):
            continue
        tokens = c['left'].split()
        if not tokens or len(tokens[-1]) < 3 or not all(replay.WORD.fullmatch(w) for w in tokens):
            continue
        operation = noise.OPERATIONS[int(hashlib.sha256(c['id'].encode()).hexdigest()[:8], 16) % len(noise.OPERATIONS)]
        mutated = noise.corrupt(tokens[-1] + ' ', operation)
        if mutated is None:
            continue
        future = replay.future_from_prompt(c, refs[c['group']])
        clean = [dict(id=c['id'] + ':prefix:' + str(i), typed=w, target=w) for i, w in enumerate(tokens)]
        clean.append(dict(id=c['id'], typed=c['typed'], target=c['target']))
        clean.extend(dict(id=c['id'] + ':future:' + str(i), typed=w, target=w) for i, w in enumerate(future))
        noisy = [dict(r) for r in clean]
        noisy[len(tokens) - 1]['typed'] = mutated.strip()
        chosen.append(dict(id=c['id'], group=c['group'], operation=operation, clean=clean, noisy=noisy))
        groups[c['group']] += 1
        if len(chosen) == limit:
            break
    return chosen


def run(a):
    a.output.mkdir(parents=True, exist_ok=False)
    parent = json.loads((a.run / 'report.json').read_text())
    for name, p in [('probe', a.probe), ('policy', a.policy), ('model', a.model)]:
        assert core.digest(p) == parent['protocol']['source_hashes'][name]
    with a.sentences.open() as stream:
        refs = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(stream)}
    pairs = build_pairs(core.rows(a.run / 'development.jsonl'), core.rows(a.run / 'development-episodes.jsonl'), refs)
    core.jsonl(a.output / 'pairs.jsonl', pairs)
    protocol = dict(schema='adjacent-revision-stress-v1', parent_report_sha256=core.digest(a.run / 'report.json'),
                    pairs_sha256=core.digest(a.output / 'pairs.jsonl'), script_sha256=core.digest(__file__),
                    replay_sha256=core.digest(replay.__file__), cases=len(pairs), selection='Development focal seed exists, input alignment and ASCII prefix, deterministic hash order, max two per prompt; no outcome selection',
                    limitation='Enriched uncertain-focal cohort with synthetic prior typo and clean prompt future. Not recorded whole trajectories or population prevalence. No parameter tuning.',
                    rules=dict(incumbent=parent['incumbent_rule'], retuned=parent['selected_rule']))
    core.write_json(a.output / 'protocol.json', protocol)
    probe = replay.Probe(a.probe, a.model, a.output)
    acceptance = replay.Acceptance(a.policy)
    results = {}
    predictions = {}
    try:
        for rule_name, rule in protocol['rules'].items():
            for condition in ['clean', 'noisy']:
                all_cases = [c for pair in pairs for c in pair[condition]]
                for mode in ['drop', 'strict', 'ordinary']:
                    words, changes = [], []
                    before_calls = probe.calls.copy()
                    for pair in pairs:
                        ww, cc = replay.replay_trial(probe, acceptance, pair[condition], rule, mode)
                        words.extend(ww)
                        changes.extend(cc)
                    key = ':'.join([rule_name, condition, mode])
                    result = replay.measure(all_cases, words)
                    # Candidate coverage is not populated by this final-word scorer.
                    for k in ['target_in_top8', 'target_top1', 'miss_absent_from_top8', 'miss_ranking_or_acceptance']:
                        result.pop(k, None)
                    result.update(revisions=len(changes), refreshed_revisions=sum(c.get('refreshed', False) for c in changes), uncached_calls=dict(probe.calls - before_calls))
                    results[key] = result
                    predictions[key] = words
                    core.jsonl(a.output / (key.replace(':', '-') + '-revisions.jsonl'), changes)
                    print(key, json.dumps(result), flush=True)
        for rule_name in protocol['rules']:
            for condition in ['clean', 'noisy']:
                key = ':'.join([rule_name, condition])
                base = predictions[key + ':drop']
                cc = [c for pair in pairs for c in pair[condition]]
                for mode in ['strict', 'ordinary']:
                    new = predictions[key + ':' + mode]
                    changes = [dict(c, before=b, after=n) for c, b, n in zip(cc, base, new) if b.casefold() != n.casefold()]
                    results[key + ':' + mode]['changed_vs_drop'] = changes
        core.write_json(a.output / 'report.json', dict(protocol=protocol, results=results, production_changed=False))
    finally:
        probe.close()


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--model', type=Path, default=core.PROJECT / 'app/src/main/assets/language_model/model.cng')
    p.add_argument('--sentences', type=Path, default=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv'))
    run(p.parse_args())
