#!/usr/bin/env python3
"""Freeze and score separate autocorrection panels, without training or selection.

Only left/typed go to native inference. Source-copy, observed, and authored data
are never pooled into a purported population score. Standard library only.
"""
import argparse
import collections
import csv
import hashlib
import json
import random
import statistics
import subprocess
from pathlib import Path

from align_mobile_targets import osa
from iterate_english_policy import split

PROJECT = Path(__file__).resolve().parents[1]
SPEC = PROJECT / 'config/autocorrect_eval_v2'
EVAL_IDS = '2a6361cda8ee35c00bfed060b409561d4abe6f863597df8eb029edea18271e5c'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def rows(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line]


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def jsonl(path, values):
    Path(path).write_text(''.join(json.dumps(v, ensure_ascii=False, sort_keys=True) + '\n' for v in values))


def norm(text):
    return text.casefold()


def input_key(row):
    return (' '.join(norm(row['left']).split()), norm(row['typed']))


def native_requests(cases):
    return ''.join(json.dumps(dict(left=c['left'], typed=c['typed'], spelling_limit=8, timing=True),
                              ensure_ascii=False) + '\n' for c in cases)


def stream_metrics(trials, cases, predictions):
    """Original observed spans, including ambiguous labels; Levenshtein CER.

    Same replay boundary as public_touch_eval.stream_metrics, without its NumPy
    training-tool imports. Earlier corrections never enter later word contexts.
    """
    by_id = {c['id']: (c, p) for c, p in zip(cases, predictions, strict=True)}
    def distance(a, b):
        previous = list(range(len(b) + 1))
        for i, x in enumerate(a, 1):
            current = [i]
            for j, y in enumerate(b, 1):
                current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (x != y)))
            previous = current
        return previous[-1]
    total = collections.Counter()
    people = collections.defaultdict(collections.Counter)
    for trial in trials:
        text = trial['observed']
        for identifier in reversed(trial['words']):
            case, prediction = by_id[identifier]
            if prediction is not None:
                text = text[:case['start']] + prediction + text[case['end']:]
        values = dict(character_errors=distance(text, trial['reference']),
                      reference_characters=len(trial['reference']), exact_trials=int(text == trial['reference']), trials=1)
        total.update(values)
        people[trial['participant']].update(values)
    def result(c):
        return dict(c, cer=c['character_errors'] / max(1, c['reference_characters']))
    return dict(overall=result(total), participants={p: result(c) for p, c in people.items()})


def scoring_hashes():
    return {name: digest(Path(__file__).with_name(name)) for name in
            ['autocorrect_eval_v2.py', 'align_mobile_targets.py', 'iterate_english_policy.py']}


def authored(spec=SPEC):
    result = []
    for filename in ['scenarios.tsv', 'preservation.tsv']:
        with (spec / filename).open() as stream:
            for i, row in enumerate(csv.DictReader(stream, delimiter='\t')):
                group = f'authored:{filename}:{i:03d}'
                variants = [row['target']] + row.get('errors', '').split(';')
                for j, typed in enumerate(v for v in variants if v):
                    if typed != typed.strip() or not typed or not row['target'].strip():
                        raise ValueError(f'Invalid authored token in {group}')
                    result.append(dict(id=f'{group}:{j}', group=group,
                                       panel='authored_challenge', domain=row['domain'],
                                       category='authored_clean' if j == 0 else 'authored_error',
                                       left=row['left'], typed=typed, target=row['target'],
                                       label_basis='Author-specified intent; not a recorded user error',
                                       ambiguity='intent_only' if row['domain'] == 'real_word_intent' else 'not_adjudicated'))
    return result


def metrics(cases, outputs):
    counts = collections.Counter(cases=len(cases))
    for case, output in zip(cases, outputs, strict=True):
        if case['target'] is None:
            counts['unlabelled'] += 1
            continue
        typo = norm(case['typed']) != norm(case['target'])
        fix = output.get('correction')
        changed = bool(fix) and norm(fix) != norm(case['typed'])
        correct = bool(changed and typo and norm(fix) == norm(case['target']))
        counts['typos' if typo else 'clean'] += 1
        counts['tp'] += correct
        counts['fp'] += changed and not correct
        counts['clean_changes'] += changed and not typo
        if typo:
            candidates = [norm(c['word']) for c in output.get('candidates', [])]
            available = norm(case['target']) in candidates
            counts['target_in_top8'] += available
            counts['target_top1'] += bool(candidates and candidates[0] == norm(case['target']))
            if not correct:
                counts['miss_absent_from_top8' if not available else 'miss_ranking_or_acceptance'] += 1
    counts['fn'] = counts['typos'] - counts['tp']
    # Materialize zero counters so every slice has the same schema.
    for key in ['unlabelled', 'typos', 'clean', 'tp', 'fp', 'clean_changes',
                'target_in_top8', 'target_top1', 'miss_absent_from_top8', 'miss_ranking_or_acceptance']:
        counts[key] += 0
    return dict(counts, **rates(counts))


def rates(c):
    tp, fp, typos, clean = (c[k] for k in ['tp', 'fp', 'typos', 'clean'])
    return dict(f05=1.25 * tp / (tp + fp + .25 * typos) if tp + fp + typos else 0.,
                precision=tp / (tp + fp) if tp + fp else None,
                recall=tp / typos if typos else None,
                clean_changes_per_1000=1000 * c['clean_changes'] / clean if clean else None)


def percentile(values, p):
    values = sorted(values)
    index = (len(values) - 1) * p
    lo = int(index)
    return values[lo] + (values[min(lo + 1, len(values) - 1)] - values[lo]) * (index - lo)


def uncertainty(cases, outputs, baseline=None, repeats=1000):
    grouped = collections.defaultdict(list)
    for i, c in enumerate(cases):
        grouped[c['group']].append(i)
    counts = []
    for ids in grouped.values():
        sample = [cases[i] for i in ids]
        current = metrics(sample, [outputs[i] for i in ids])
        before = metrics(sample, [baseline[i] for i in ids]) if baseline is not None else None
        counts.append((current, before))
    if len(counts) < 2:
        return dict(groups=len(counts), replicates=0, f05_95_interval=None)
    rng = random.Random(2026102405)
    scores, deltas = [], []
    for _ in range(repeats):
        a, b = collections.Counter(), collections.Counter()
        for _ in counts:
            current, before = counts[rng.randrange(len(counts))]
            for key in ['tp', 'fp', 'typos', 'clean', 'clean_changes']:
                a[key] += current[key]
                if before is not None:
                    b[key] += before[key]
        score = rates(a)['f05']
        scores.append(score)
        if baseline is not None:
            deltas.append(score - rates(b)['f05'])
    result = dict(groups=len(counts), replicates=repeats, seed=2026102405,
                  method='Percentile cluster bootstrap: participant for touch, prompt for Aalto, scenario for authored',
                  f05_95_interval=[percentile(scores, p) for p in [.025, .975]],
                  limitation='Sampling uncertainty only; does not address selection bias, exposure, or population mismatch')
    if deltas:
        result['paired_delta_f05_95_interval'] = [percentile(deltas, p) for p in [.025, .975]]
    return result


def prevalence_sensitivity(m):
    if not m['typos'] or not m['clean']:
        return None
    tp_rate = m['tp'] / m['typos']
    wrong_typo_rate = (m['fp'] - m['clean_changes']) / m['typos']
    clean_rate = m['clean_changes'] / m['clean']
    result = []
    for prevalence in [.01, .05, .10, .20]:
        tp = prevalence * tp_rate
        fp = prevalence * wrong_typo_rate + (1 - prevalence) * clean_rate
        result.append(dict(assumed_typo_prevalence=prevalence,
                           precision=tp / (tp + fp) if tp + fp else None,
                           f05=1.25 * tp / (tp + fp + .25 * prevalence) if tp + fp else 0.,
                           wrong_corrections_per_1000=1000 * fp))
    return dict(scope='Hypothetical mixture, NOT estimated product prevalence or deployment performance', scenarios=result)


def slices(cases):
    groups = collections.defaultdict(list)
    for i, c in enumerate(cases):
        n = len(c['typed'])
        length = str(n) if n <= 3 else '4_5' if n <= 5 else '6_plus'
        names = ['length_' + length, 'domain_' + c['domain']]
        if c['target'] is None:
            names.append('ambiguous_unlabelled')
        elif norm(c['typed']) == norm(c['target']):
            names.append('preservation')
        else:
            distance = osa(norm(c['typed']), norm(c['target']))
            names.append('distance_1' if distance == 1 else 'distance_2_plus')
            if len(c['typed']) <= 3:
                names.append('short_typo_1_3')
        for name in names:
            groups[name].append(i)
    return groups


def overlap(cases, training):
    result = {}
    for name, predicate in [
        ('acceptance_fit_and_calibration', lambda r: r.get('fit_split') in ['fit', 'calibration']),
        ('selection_and_development_exposure', lambda r: r.get('fit_split') in ['selection', 'development'])]:
        selected = [r for r in training if predicate(r)]
        keys = {input_key(r) for r in selected}
        words = {norm(r['target']) for r in selected if r.get('target') is not None}
        contexts = {' '.join(norm(r['left']).split()) for r in selected if r['left'].strip()}
        result[name] = dict(rows=len(selected), exact_input_overlap=sum(input_key(c) in keys for c in cases),
                            target_type_overlap=sum(c['target'] is not None and norm(c['target']) in words for c in cases),
                            nonempty_context_overlap=sum(bool(c['left'].strip()) and ' '.join(norm(c['left']).split()) in contexts for c in cases))
    return dict(scope='Known acceptance replay only. No claim of disjointness from shared LM pretraining or every historical experiment.',
                normalization='Unicode casefold; context whitespace collapsed; typed spelling unchanged',
                overlaps=result, action='Annotate every case; retain original cohorts, never remove failures based on overlap')


def freeze(args):
    if args.output.exists():
        raise ValueError('Freeze directory exists; preserve the existing suite')
    old = [c for c in rows(args.mobile) if c.get('source') == 'real' and split(c['group']) == 'evaluation']
    identities = '\n'.join(sorted(c['id'] for c in old)) + '\n'
    if len(old) != 2042 or hashlib.sha256(identities.encode()).hexdigest() != EVAL_IDS:
        raise ValueError('Frozen mobile cohort differs')
    aligned = sorted((c['id'], c['target']) for c in old)
    if hashlib.sha256(json.dumps(aligned, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest() != 'ab9432f658fd7be9647f6e782d820ed75ac781266a5dba090152c746bb6ac1a3':
        raise ValueError('Frozen mobile targets differ')
    cases = []
    for c in old:
        cases.append(dict(c, id='aalto:' + c['id'], group='aalto:' + c['group'],
                          panel='mobile_source_copy' if c['category'] == 'source_preservation' else 'mobile_observed',
                          domain='mobile_copy_typing'))
    touch_manifest = json.loads((args.touch / 'manifest.json').read_text())
    for name in ['cases', 'trials']:
        if digest(args.touch / (name + '.jsonl')) != touch_manifest[name + '_sha256']:
            raise ValueError('Real-touch prepared source hash mismatch')
    for c in rows(args.touch / 'cases.jsonl'):
        # Retain ambiguous observations for whole-trial CER, not word F0.5.
        cases.append(dict(c, group='touch:' + c['participant'], panel='real_touch_observed',
                          domain='mobile_email_copy_typing', category='recorded_touch'))
    cases += authored()
    if len({c['id'] for c in cases}) != len(cases):
        raise ValueError('Duplicate IDs')
    training = rows(args.training)
    audit = {panel: overlap([c for c in cases if c['panel'] == panel], training)
             for panel in sorted({c['panel'] for c in cases})}
    args.output.mkdir(parents=True)
    jsonl(args.output / 'cases.jsonl', cases)
    jsonl(args.output / 'trials.jsonl', rows(args.touch / 'trials.jsonl'))
    write_json(args.output / 'overlap.json', audit)
    manifest = dict(schema='autocorrect-eval-v2', frozen_before_inference=True,
                    cases_sha256=digest(args.output / 'cases.jsonl'),
                    trials_sha256=digest(args.output / 'trials.jsonl'),
                    overlap_sha256=digest(args.output / 'overlap.json'),
                    source_hashes=dict(mobile=digest(args.mobile), acceptance_replay=digest(args.training),
                                       touch_manifest=digest(args.touch / 'manifest.json'),
                                       authored_scenarios=digest(SPEC / 'scenarios.tsv'),
                                       authored_preservation=digest(SPEC / 'preservation.tsv')),
                    scoring_code_sha256=scoring_hashes(),
                    panels=dict(collections.Counter(c['panel'] for c in cases)),
                    scoring='Case-insensitive exact intended replacement; wrong fix contributes FP and FN; missing targets stay in denominator',
                    inference_fields=['left', 'typed'],
                    no_product_population_weights=True,
                    limits=['All natural panels have historical exposure; no fresh confirmation claim',
                            'Aalto AC events are selected events, not a natural stream; source copies are separate',
                            'Authored challenge cases specify intent, including intrinsically ambiguous real-word errors; no natural frequency claim',
                            'Current model is text-only. Touch coordinates are retained but not supplied to this scorer',
                            'Touch replay is open loop, not Android controller or input-to-display testing',
                            'Overlap audit covers available acceptance replay, not complete LM training history'])
    write_json(args.output / 'manifest.json', manifest)
    print(json.dumps(manifest, indent=2))


def score(args):
    manifest = json.loads((args.suite / 'manifest.json').read_text())
    for name in ['cases', 'trials', 'overlap']:
        suffix = '.json' if name == 'overlap' else '.jsonl'
        if digest(args.suite / (name + suffix)) != manifest[name + '_sha256']:
            raise ValueError('Frozen suite changed: ' + name)
    if scoring_hashes() != manifest['scoring_code_sha256']:
        raise ValueError('Scorer differs from frozen protocol')
    if args.output.exists():
        raise ValueError('Report directory exists; never overwrite an exposed evaluation')
    cases = rows(args.suite / 'cases.jsonl')
    binding = dict(manifest_sha256=digest(args.suite / 'manifest.json'),
                   model_sha256=digest(args.model), probe_sha256=digest(args.probe),
                   baseline_probe_sha256=digest(args.baseline_probe) if args.baseline_probe else None,
                   scorer_sha256=digest(__file__), parameters_selected_in_this_run=False)
    # Candidate identity and scoring protocol are saved before any inference.
    args.output.mkdir(parents=True)
    write_json(args.output / 'frozen-run.json', binding)
    requests = native_requests(cases)
    def predict(binary, name):
        proc = subprocess.run([str(binary.resolve()), str(args.model.resolve())], input=requests,
                              text=True, capture_output=True, check=True, timeout=600)
        outputs = [json.loads(line) for line in proc.stdout.splitlines()]
        if len(outputs) != len(cases):
            raise ValueError('Native output count mismatch')
        expected_hash = binding['probe_sha256' if name == 'candidate' else 'baseline_probe_sha256']
        if digest(binary) != expected_hash or digest(args.model) != binding['model_sha256']:
            raise ValueError('Model or executable changed during inference')
        jsonl(args.output / (name + '-outputs.jsonl'), outputs)
        return outputs
    outputs = predict(args.probe, 'candidate')
    baseline = predict(args.baseline_probe, 'baseline') if args.baseline_probe else None
    panels = {}
    for name in manifest['panels']:
        ids = [i for i, c in enumerate(cases) if c['panel'] == name]
        cc, oo = [cases[i] for i in ids], [outputs[i] for i in ids]
        bb = [baseline[i] for i in ids] if baseline is not None else None
        metric = metrics(cc, oo)
        panels[name] = dict(candidate=metric, uncertainty=uncertainty(cc, oo, bb),
                            unique_targets=len({norm(c['target']) for c in cc if c['target'] is not None}),
                            prevalence_sensitivity=prevalence_sensitivity(metric),
                            slices={key: metrics([cc[i] for i in subset], [oo[i] for i in subset])
                                    for key, subset in slices(cc).items()})
        if bb is not None:
            panels[name]['baseline'] = metrics(cc, bb)
    # Reproduce the entire original cohort as an explicitly historical score.
    ids = [i for i, c in enumerate(cases) if c['panel'].startswith('mobile_')]
    historical = metrics([cases[i] for i in ids], [outputs[i] for i in ids])
    ids = [i for i, c in enumerate(cases) if c['panel'] == 'real_touch_observed']
    touch_cases = [cases[i] for i in ids]
    trials = rows(args.suite / 'trials.jsonl')
    streams = dict(candidate=stream_metrics(trials, touch_cases, [outputs[i]['correction'] for i in ids]),
                   literal=stream_metrics(trials, touch_cases, [None] * len(ids)))
    if baseline is not None:
        streams['baseline'] = stream_metrics(trials, touch_cases, [baseline[i]['correction'] for i in ids])
    timings = [o['elapsedMicros'] / 1000 for o in outputs if 'elapsedMicros' in o]
    report = dict(binding=binding, panels=panels, historical_full_mobile=historical,
                  whole_trial_touch=streams, overlap=json.loads((args.suite / 'overlap.json').read_text()),
                  latency=dict(scope='Single release-host pass including first call, native inference only; not phone input-to-display',
                               calls=len(timings), median_ms=statistics.median(timings) if timings else None,
                               p95_ms=percentile(timings, .95) if timings else None),
                  limits=manifest['limits'], production_qualification=False,
                  pooled_representative_score=None)
    write_json(args.output / 'report.json', report)
    print(json.dumps({name: p['candidate'] for name, p in panels.items()}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    p = actions.add_parser('freeze')
    p.add_argument('--mobile', type=Path, required=True)
    p.add_argument('--training', type=Path, required=True)
    p.add_argument('--touch', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p = actions.add_parser('score')
    p.add_argument('--suite', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--baseline-probe', type=Path)
    p.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    (freeze if args.action == 'freeze' else score)(args)


if __name__ == '__main__':
    main()
