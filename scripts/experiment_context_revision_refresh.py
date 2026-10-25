#!/usr/bin/env python3
"""Expand development events, freeze revision rules, compare bounded right refresh.

No live keyboard change. Extra real events inherit the original prompt split;
evaluation prompts and exact evaluation inputs are excluded before inference.
"""
import argparse
import collections
import csv
import itertools
import json
from pathlib import Path

import autocorrect_eval_v2 as core
import audit_candidate_pipeline as intake
from align_mobile_targets import reference_target
from iterate_english_policy import split, hashed
import replay_context_revision as replay


def csv_rows(path):
    with path.open() as stream:
        yield from csv.DictReader(stream)


def prepare(source, old, frozen, refs, cap=24):
    existing = [c for c in old if c['source'] == 'real' and c['fit_split'] in ['fit', 'calibration', 'selection']]
    known = {core.input_key(c) for c in old}
    forbidden = {core.input_key(c) for c in frozen}
    eval_groups = {c['group'].removeprefix('aalto:') for c in frozen if c['panel'].startswith('mobile_')}
    original_count = len(existing)
    existing = [c for c in existing if core.input_key(c) not in forbidden and c['group'] not in eval_groups]
    removed_eval = original_count - len(existing)
    selection_inputs = {core.input_key(c) for c in existing if c['fit_split'] == 'selection'}
    before = len(existing)
    existing = [c for c in existing if c['fit_split'] == 'selection' or core.input_key(c) not in selection_inputs]
    removed_selection = before - len(existing)
    buckets = collections.defaultdict(dict)
    audit = collections.Counter()
    for r in csv_rows(source):
        audit['source_events'] += 1
        group = r['SENTENCE_ID']
        # Same original TRAIN/selection prompt assignment, including reserved prompts.
        if split(group) == 'evaluation' or group in eval_groups:
            continue
        typed, target = r['TYPED_WORD'].strip(), r['ORIGINAL_WORD'].strip().lower()
        left = intake.preceding_context(r['CURRENT_INPUT'], typed)
        if left is None or not intake.WORD.fullmatch(typed) or not intake.WORD.fullmatch(target):
            continue
        if not (2 <= len(typed) <= 32 and 2 <= len(target) <= 32):
            continue
        case = dict(typed=typed, target=target, left=left, group=group,
                    category='recorded_typo' if typed.lower() != target else 'recorded_preservation')
        aligned = reference_target(case, refs[group])
        if aligned is None:
            audit['excluded_unaligned'] += 1
            continue
        case['target'] = aligned
        key = core.input_key(case)
        if key in known or key in forbidden:
            continue
        case.update(id='extra:' + intake.key(case), source='real', partition=split(group))
        case['fit_split'] = ('calibration' if int(hashed('calibration:' + group)[:8], 16) % 5 == 0 else 'fit') if split(group) == 'train' else 'selection'
        bucket = buckets[group]
        # Deduplicate by observable input, never by label/outcome.
        bucket.setdefault(key, case)
    extra = []
    seen = set(known) | forbidden
    for group in sorted(buckets):
        for case in sorted(buckets[group].values(), key=lambda c: c['id'])[:cap]:
            key = core.input_key(case)
            if key not in seen:
                extra.append(case)
                seen.add(key)
    cases = existing + extra
    assert not ({core.input_key(c) for c in cases} & forbidden)
    assert not ({c['group'] for c in cases} & eval_groups)
    fit_inputs = {core.input_key(c) for c in cases if c['fit_split'] != 'selection'}
    assert not (fit_inputs & {core.input_key(c) for c in cases if c['fit_split'] == 'selection'})
    audit.update(existing=len(existing), excluded_existing_overlapping_inputs=removed_eval,
                 excluded_fit_inputs_matching_selection=removed_selection, added=len(extra), total=len(cases),
                 exact_evaluation_overlap=0, evaluation_prompt_overlap=0, exact_fit_selection_overlap=0)
    return cases, dict(audit)


def tune(cases, records, threshold, incumbent):
    ids = {fold: [i for i, c in enumerate(cases) if (c['fit_split'] in ['fit', 'calibration'] if fold == 'fit' else c['fit_split'] == fold)]
           for fold in ['fit', 'selection']}
    def assess(rule, fold):
        ii = ids[fold]
        words = [replay.final_word(records[i], rule, threshold) for i in ii]
        m = replay.measure([cases[i] for i in ii], words)
        m['revisions'] = sum(w.casefold() != records[i]['initial'].casefold() for i, w in zip(ii, words))
        return m
    trials = [dict(rule=r, fit=assess(r, 'fit'), selection=assess(r, 'selection')) for r in [None, incumbent]]
    for band, beta, lag in itertools.product(replay.GRID['band_width'], replay.GRID['future_weight'], replay.GRID['max_following']):
        options = []
        for margin in replay.GRID['revision_margin']:
            rule = dict(band_width=band, future_weight=beta, max_following=lag, revision_margin=margin)
            options.append(dict(rule=rule, fit=assess(rule, 'fit')))
        best = max(options, key=lambda t: (t['fit']['f05'], -t['fit']['revisions'], t['rule']['revision_margin']))
        best['selection'] = assess(best['rule'], 'selection')
        trials.append(best)
    def key(t):
        rule = t['rule'] or dict(max_following=0, band_width=0, future_weight=0)
        return t['selection']['f05'], -t['selection']['revisions'], -rule['max_following'], -rule['band_width'], -rule['future_weight']
    return dict(winner=max(trials, key=key), trials=trials, evaluation_used=False)


class Costs:
    """No experiment cache, count refresh predictions as extra work at same boundary."""
    def __init__(self, probe):
        self.probe = probe
        self.boundaries = []
        self.next_is_boundary = False

    def query(self, request):
        self.probe.cache.clear()
        out = self.probe.query(request)
        elapsed = out['elapsedMicros'] / 1000
        if self.next_is_boundary:
            self.boundaries.append(dict(spelling_ms=elapsed, extra_ms=0., sequence_calls=0, refresh_calls=0))
            self.next_is_boundary = False
        else:
            self.boundaries[-1]['extra_ms'] += elapsed
            self.boundaries[-1]['refresh_calls' if 'typed' in request else 'sequence_calls'] += 1
        return out

    def predict(self, left, typed):
        return self.query(dict(left=left[-512:], typed=typed, spelling_limit=8))


def bound_cases(costs, cases):
    for c in cases:
        costs.next_is_boundary = True
        yield c


def cost_summary(rows):
    checked = [b for b in rows if b['extra_ms'] > 0]
    values = [b['extra_ms'] for b in checked]
    return dict(boundaries=len(rows), checked=len(checked),
                sequence_calls=sum(b['sequence_calls'] for b in rows), refresh_calls=sum(b['refresh_calls'] for b in rows),
                median_extra_ms=core.percentile(values, .5) if values else 0.,
                p95_extra_ms=core.percentile(values, .95) if values else 0., max_extra_ms=max(values, default=0.),
                mean_extra_ms=sum(values) / len(rows), over_40ms=sum(v > 40 for v in values))


def run(a):
    a.output.mkdir(parents=True, exist_ok=False)
    prior = json.loads((a.previous / 'report.json').read_text())
    for k, path in [('probe', a.probe), ('model', a.model), ('policy', a.policy)]:
        assert core.digest(path) == prior['binding'][k + '_sha256']
    frozen_manifest = json.loads((a.suite / 'manifest.json').read_text())
    for name in ['cases', 'trials']:
        assert core.digest(a.suite / (name + '.jsonl')) == frozen_manifest[name + '_sha256']
    with a.sentences.open() as stream:
        refs = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(stream)}
    frozen = core.rows(a.suite / 'cases.jsonl')
    development, audit = prepare(a.events, core.rows(a.development), frozen, refs)
    core.jsonl(a.output / 'development.jsonl', development)
    protocol = dict(schema='context-revision-refresh-v1', grid=replay.GRID, cap_extra_events_per_prompt=24,
                    fit='Original real fit/calibration plus new aligned raw TRAIN events',
                    selection='Original plus new real selection-prompt events; parent and incumbent eligible',
                    refresh_modes=['drop', 'strict', 'ordinary'], max_pending=2, max_rewrites_per_boundary=2,
                    refresh='After left revision, requery only remaining queued unsure words once. Strict requires positive following evidence and delayed margin; ordinary also allows fresh native immediate acceptance. Never rescore already corrected or confident right words.',
                    mode_selection='All predeclared modes reported diagnostically; no selection using evaluation',
                    source_hashes={k: core.digest(p) for k, p in [('events', a.events), ('sentences', a.sentences), ('old_development', a.development), ('probe', a.probe), ('policy', a.policy), ('model', a.model)]},
                    code_hashes={p.name: core.digest(p) for p in [Path(__file__), Path(replay.__file__)]},
                    audit=audit, counts=dict(collections.Counter(c['fit_split'] for c in development)),
                    warm_cache_sha256=core.digest(a.warm_cache / 'native-cache.jsonl') if a.warm_cache else None,
                    limitations=['Additional events are selected autocorrect events, not natural error prevalence', 'Mobile future is simulated clean prompt continuation', 'Previously exposed development/evaluation, no fresh test claim', 'Native host CPU work only, no phone or editor deadlines'],
                    frozen_suite_sha256=core.digest(a.suite / 'manifest.json'), production_changed=False)
    core.write_json(a.output / 'protocol.json', protocol)
    probe = replay.Probe(a.probe, a.model, a.output)
    # Warm only exact queries from hash-bound preceding experiment.
    for row in core.rows(a.previous / 'native-cache.jsonl'):
        probe.cache[json.dumps(row['request'], sort_keys=True)] = row['output']
    if a.warm_cache:
        cached = json.loads((a.warm_cache / 'protocol.json').read_text())
        assert cached['source_hashes'] == protocol['source_hashes']
        for row in core.rows(a.warm_cache / 'native-cache.jsonl'):
            probe.cache[json.dumps(row['request'], sort_keys=True)] = row['output']
    acceptance = replay.Acceptance(a.policy)
    try:
        records = []
        for i, c in enumerate(development):
            records.append(replay.episode(probe, acceptance, c, replay.future_from_prompt(c, refs[c['group']])))
            if (i + 1) % 1000 == 0:
                print('Expanded development', i + 1, flush=True)
        core.jsonl(a.output / 'development-episodes.jsonl', records)
        selection = tune(development, records, acceptance.threshold, prior['selected_rule'])
        core.write_json(a.output / 'selection.json', selection)
        rule = selection['winner']['rule']
        print('Frozen rule', json.dumps(selection['winner']), flush=True)
        # Freeze rule before any new evaluation decisions.
        trials = core.rows(a.suite / 'trials.jsonl')
        by_id = {c['id']: c for c in frozen}
        old_records = core.rows(a.previous / 'evaluation-episodes.jsonl')
        results = {}
        for name, chosen, mode in [('baseline', None, 'drop'), ('incumbent', prior['selected_rule'], 'drop'),
                                   ('retuned', rule, 'drop'), ('retuned_strict_refresh', rule, 'strict'),
                                   ('retuned_ordinary_refresh', rule, 'ordinary'),
                                   ('incumbent_strict_refresh', prior['selected_rule'], 'strict'),
                                   ('incumbent_ordinary_refresh', prior['selected_rule'], 'ordinary')]:
            words = {r['id']: replay.final_word(r, chosen, acceptance.threshold) for r in old_records}
            changed = []
            # Fresh no-query-cache touch passes for every mode: cache never supplies timing.
            cost = Costs(probe)
            for trial in trials:
                cc = [by_id[k] for k in trial['words']]
                # Preserve list indexing inside replay while marking only observed predictions.
                class Marked(list):
                    def __iter__(self):
                        return bound_cases(cost, list.__iter__(self))
                committed, rev = replay.replay_trial(cost, acceptance, Marked(cc), chosen, mode)
                words.update(zip(trial['words'], committed))
                changed.extend(rev)
            metrics = replay.overall(frozen, [words[c['id']] for c in frozen])
            touch = [c for c in frozen if c['panel'] == 'real_touch_observed']
            metrics['touch_cer'] = core.stream_metrics(trials, touch, [words[c['id']] if words[c['id']] != c['typed'] else None for c in touch])
            metrics['cost'] = cost_summary(cost.boundaries)
            metrics['revisions'] = len(changed)
            metrics['refreshed_revisions'] = sum(c.get('refreshed', False) for c in changed)
            metrics['changed_vs_incumbent'] = []
            if 'incumbent' in results:
                old_words = results['incumbent']['words']
                metrics['changed_vs_incumbent'] = [dict(id=c['id'], typed=c['typed'], target=c['target'], before=old_words[c['id']], after=words[c['id']])
                                                   for c in frozen if old_words[c['id']].casefold() != words[c['id']].casefold()]
            results[name] = dict(metrics=metrics, words=words)
            core.jsonl(a.output / (name + '-revisions.jsonl'), changed)
            core.jsonl(a.output / (name + '-costs.jsonl'), cost.boundaries)
            print(name, json.dumps(dict(score=metrics['score'], cost=metrics['cost'], revisions=len(changed), refreshed=metrics['refreshed_revisions'])), flush=True)
        report = dict(protocol=protocol, selected_rule=rule, incumbent_rule=prior['selected_rule'],
                      results={k: v['metrics'] for k, v in results.items()}, production_changed=False)
        core.write_json(a.output / 'report.json', report)
        core.write_json(a.output / 'predictions.json', {k: v['words'] for k, v in results.items()})
    finally:
        probe.close()


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--previous', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--warm-cache', type=Path)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--suite', type=Path, default=core.PROJECT / 'build/autocorrect-eval-v4/frozen')
    p.add_argument('--model', type=Path, default=core.PROJECT / 'app/src/main/assets/language_model/model.cng')
    p.add_argument('--development', type=Path, default=core.PROJECT.parent / 'gboard-probe/improve-v3/cases.jsonl')
    p.add_argument('--events', type=Path, default=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/ac_words_en.csv'))
    p.add_argument('--sentences', type=Path, default=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv'))
    run(p.parse_args())
