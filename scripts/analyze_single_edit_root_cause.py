#!/usr/bin/env python3
"""Trace current autocorrection and run fixed causal diagnostics, without fitting.

Counterfactual policies are deliberately NOT deployment candidates. They isolate
dependencies while holding fitted coefficients/cutoff fixed.
"""
import argparse
from collections import Counter
import csv
import json
import math
from pathlib import Path

from align_mobile_targets import reference_target
from analyze_autocorrect_errors import score_parts
from audit_candidate_pipeline import digest, probe
from iterate_english_policy import hashed, split, f05, rerank

VARIANTS = ('baseline', 'winner_lm_in_acceptance', 'remove_length_surcharge',
            'exclude_completions_from_correction_pool', 'final_letter_as_edit',
            'without_learned_gate')


def logistic(value):
    return 1/(1+math.exp(-max(-700, min(700, value))))


def trace(policy, case, output, ranked, winner_lm=False):
    if not ranked:
        return dict(guard='no_candidates', decision=None)
    best = ranked[0]
    literal = output['literalScore']
    score = score_parts(case['typed'], best)['lm'] if winner_lm else best['score']
    values = [score,
              min(best['score']-ranked[1]['score'], policy['margin_cap']) if len(ranked)>1 else policy['margin_cap'],
              int(output['known']), literal or 0., score-literal if literal is not None else 0.,
              int("'" in case['typed'] or '’' in case['typed'])]
    terms = [w*(x-m)/s for w, x, m, s in zip(policy['weights'][1:], values, policy['means'], policy['scales'])]
    value = policy['weights'][0]+sum(terms)
    probability = logistic(value)
    if output['known'] and (policy['preserve_known_words'] or literal is None):
        guard = 'known_without_literal'
    elif best['completedChars']:
        guard = 'completion'
    elif len(case['typed']) < policy['minimum_observed_length']:
        guard = 'short_input'
    elif len(best['word']) < policy['minimum_candidate_length']:
        guard = 'short_candidate'
    else:
        guard = 'learned_gate' if probability < policy['threshold'] else 'accepted'
    return dict(winner=best['word'], runner=ranked[1]['word'] if len(ranked)>1 else None,
                score_parts=score_parts(case['typed'], best),
                features=dict(zip(policy['features'], values)),
                contributions=dict(zip(policy['features'], terms)),
                logit=value, decision_score=probability, cutoff=policy['threshold'], guard=guard,
                decision=best['word'] if guard == 'accepted' else None)


def restoration(policy, output):
    restored = None
    for evidence in output.get('apostropheEvidence', []):
        z = policy['weights'][0]+sum(w*(x-m)/s for w, x, m, s in zip(
            policy['weights'][1:], evidence['features'], policy['means'], policy['scales']))
        if logistic(z) >= policy['threshold']:
            restored = evidence['word']
    return restored


def decide(policy, apostrophe, case, output, variant):
    ranked = [dict(r) for r in output['candidates']]
    # Surface restorations are inserted after the word decision. Remove them to
    # reproduce the pre-restoration word pool before applying the same override.
    surfaces = {e['word'] for e in output.get('apostropheEvidence', [])}
    ranked = [r for r in ranked if r['word'] not in surfaces]
    ranked.sort(key=lambda r: (-r['score'], r['word']))
    if variant == 'exclude_completions_from_correction_pool':
        ranked = [r for r in ranked if not r['completedChars']]
    elif variant in ('remove_length_surcharge', 'final_letter_as_edit'):
        for r in ranked:
            if variant == 'remove_length_surcharge' and not r['completedChars'] and ' ' not in r['word']:
                r['score'] += 3*abs(len(case['typed'])-len(r['word']))
            elif variant == 'final_letter_as_edit' and r['completedChars'] == 1:
                r.update(completedChars=0, edits=1, score=r['score']-4.5)
        ranked.sort(key=lambda r: (-r['score'], r['word']))
    detail = trace(policy, case, output, ranked, variant == 'winner_lm_in_acceptance')
    fix = detail['decision']
    if variant == 'without_learned_gate' and detail['guard'] == 'learned_gate':
        fix = detail['winner']
    return restoration(apostrophe, output) or fix, detail


def metrics(cases, fixes):
    s = Counter(cases=len(cases))
    for c, fix in zip(cases, fixes):
        typo = c['typed'].lower() != c['target'].lower()
        applied = bool(fix and fix.lower() != c['typed'].lower())
        correct = bool(applied and typo and fix.lower() == c['target'].lower())
        s['typos'] += typo
        s['controls'] += not typo
        s['correct'] += correct
        s['wrong'] += applied and not correct
        s['false_changes'] += applied and not typo
    return dict(s, recall=s['correct']/s['typos'] if s['typos'] else 0.,
                precision=s['correct']/(s['correct']+s['wrong']) if s['correct']+s['wrong'] else 0.,
                f05=f05(s['correct'], s['wrong'], s['typos']-s['correct']))


def analyze(cases, outputs, policy, apostrophe):
    traces, decisions = {}, {}
    for variant in VARIANTS:
        fixes, details = zip(*(decide(policy, apostrophe, c, o, variant) for c, o in zip(cases, outputs)))
        traces[variant], decisions[variant] = details, fixes
    assert list(decisions['baseline']) == [o['correction'] for o in outputs], 'Native parity failed'
    result = dict(cases=len(cases), native_parity=True, variants={})
    for variant in VARIANTS:
        fixes = decisions[variant]
        result['variants'][variant] = dict(all=metrics(cases, fixes))
        for partition in sorted({c['partition'] for c in cases}):
            indices = [i for i, c in enumerate(cases) if c['partition'] == partition]
            result['variants'][variant][partition] = metrics([cases[i] for i in indices], [fixes[i] for i in indices])
    missed = Counter()
    for c, o, t in zip(cases, outputs, traces['baseline']):
        if c['typed'].lower() == c['target'].lower() or o['correction'] == c['target']:
            continue
        rank = next((i for i, r in enumerate(o['candidates']) if r['word'].lower()==c['target'].lower()), None)
        missed['not_returned' if rank is None else 'ranking' if rank else t['guard']] += 1
    result['misses'] = dict(missed)
    return result, traces['baseline']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    project = Path(__file__).resolve().parents[1]
    native = project.parent/'QwenASR/crates/cantonese-ngram/src'
    policy_file, apostrophe_file = native/'english_acceptance.json', native/'english_apostrophe_acceptance.json'
    policy, apostrophe = [json.loads(f.read_text()) for f in (policy_file, apostrophe_file)]
    model = project/'app/src/main/assets/language_model/model.cng'
    synthetic_file = project/'build/single-edit-final-2026-10-21/predictions.jsonl'
    synthetic_report = project/'config/english_iteration/2026-10-21/single-edit/report.json'
    assert digest(model) == policy['model_sha256']
    assert digest(synthetic_file) in json.loads(synthetic_report.read_text())['hashes'].values()
    cases = [dict(json.loads(line), partition='synthetic') for line in synthetic_file.open()]
    requests = [dict(left=c['left'], typed=c['typed'], spelling_limit=4096) for c in cases]
    args.output.mkdir(parents=True, exist_ok=True)
    cache_file = args.output/'synthetic-native.json'
    binding = dict(probe=digest(args.probe), model=digest(model), cases=digest(synthetic_file))
    if cache_file.exists() and json.loads(cache_file.read_text())['binding'] == binding:
        outputs = json.loads(cache_file.read_text())['outputs']
    else:
        print(f'Reading full native evidence for {len(cases)} synthetic cases', flush=True)
        outputs = probe(args.probe, model, requests)
        cache_file.write_text(json.dumps(dict(binding=binding, outputs=outputs)))
    assert [o['correction'] for o in outputs] == [c['correction'] for c in cases]
    synthetic, details = analyze(cases, outputs, policy, apostrophe)
    examples = []
    for typed in ('plizza', 'piza', 'butger', 'gruop', 'lunc', 'provied'):
        i = next(i for i, c in enumerate(cases) if c['typed'] == typed)
        examples.append(dict(typed=typed, target=cases[i]['target'], trace=details[i],
                             counterfactuals={v: decide(policy, apostrophe, cases[i], outputs[i], v)[0] for v in VARIANTS}))
    iteration = project/'build/english-iteration-2026-10-20'
    case_file = iteration/'cases.jsonl'
    refs_file = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    reference = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(refs_file.open())}
    real_cases, fit_outputs = [], []
    frozen_file = iteration/'native-cache.json'
    frozen = json.loads(frozen_file.read_text())
    fit_binding_file = project/'config/english_iteration/2026-10-20/f05-linear-acceptance.json'
    fit_binding = json.loads(fit_binding_file.read_text())['input_sha256']
    for name in ('cases.jsonl', 'native-cache.json'):
        assert digest(iteration/name) == fit_binding[f'build/english-iteration-2026-10-20/{name}']
    for c, old in zip(map(json.loads, case_file.open()), frozen['outputs']):
        if c['category'].startswith('prefix_'):
            continue
        target = c['target'] if c['category']=='source_preservation' else reference_target(c, reference[c['group']])
        if target is not None:
            real_cases.append(dict(c, target=target, partition=split(c['group'])))
            fit_outputs.append(dict(old, candidates=rerank(old['candidates'], 0.)))
    real_file = project/'build/apostrophe-native-cache.json'
    real_cache = json.loads(real_file.read_text())
    for key, path in dict(policy=policy_file, apostrophe_policy=apostrophe_file, model=model, cases=case_file).items():
        assert real_cache['binding'][key] == digest(path), key
    real, _ = analyze(real_cases, real_cache['outputs'], policy, apostrophe)
    # Paired TRAIN-only diagnostic: same observations, with/without their actual
    # recorded left context. Hash-order selection does not use targets or scores.
    panel = sorted([(c, o) for c, o in zip(real_cases, real_cache['outputs']) if c['partition']=='train'],
                   key=lambda pair: hashed(pair[0]['id']))[:2048]
    blank_file = args.output/'empty-context-native.json'
    blank_requests = [dict(left='', typed=c['typed'], spelling_limit=4096) for c, o in panel]
    blank_binding = dict(binding, panel=[c['id'] for c, o in panel])
    if blank_file.exists() and json.loads(blank_file.read_text())['binding'] == blank_binding:
        blank = json.loads(blank_file.read_text())['outputs']
    else:
        print(f'Paired context ablation for {len(panel)} TRAIN cases', flush=True)
        blank = probe(args.probe, model, blank_requests)
        blank_file.write_text(json.dumps(dict(binding=blank_binding, outputs=blank)))
    context = dict(partition='TRAIN only; deterministic hash-order sample, no fitting',
                   recorded_context=metrics([c for c, o in panel], [o['correction'] for c, o in panel]),
                   empty_context=metrics([c for c, o in panel], [o['correction'] for o in blank]))
    training = [(c, o) for c, o in zip(real_cases, fit_outputs)
                if c['partition']=='train' and int(hashed('calibration:'+c['group'])[:8],16)%5 != 0]
    eligible = [(c, o) for c, o in training if o['candidates'] and len(c['typed'])>=3
                and len(o['candidates'][0]['word'])>=3 and not o['candidates'][0]['completedChars']]
    exposure = dict(eligible_fit_rows=len(eligible), empty_context_rows=sum(not c['left'].strip() for c, o in eligible),
                    unknown_input_rows=sum(not o['known'] for c, o in eligible),
                    unknown_empty_context_rows=sum(not o['known'] and not c['left'].strip() for c, o in eligible))
    ranking = {}
    for operation in sorted({c['operation'] for c in cases if c['operation'] != 'clean'}):
        counts = Counter()
        for c, o in zip(cases, outputs):
            if c['operation'] != operation:
                continue
            target = next((r for r in o['candidates'] if r['word']==c['target']), None)
            if target is None:
                continue
            counts['target_is_completion'] += bool(target['completedChars'])
            best = o['candidates'][0]
            if best['word'] == c['target']:
                continue
            counts['ranking_failure'] += 1
            counts['ranking_completion_winner'] += bool(best['completedChars'])
            counts['better_target_lm_overridden_by_cost'] += score_parts(c['typed'], target)['lm'] > score_parts(c['typed'], best)['lm']
        ranking[operation] = dict(counts)
    report = dict(method='Fixed diagnostic ablations, no fitting or cutoff search; all counterfactuals uncalibrated and undeployed.',
                  synthetic=synthetic, real_typing=real, paired_context=context, fit_exposure=exposure, examples=examples,
                  synthetic_ranking_causes=ranking,
                  hashes={str(f): digest(f) for f in (Path(__file__), args.probe, model, policy_file, apostrophe_file,
                          synthetic_file, synthetic_report, cache_file, case_file, refs_file, real_file, blank_file,
                          frozen_file, fit_binding_file)})
    (args.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({k: report[k] for k in ('synthetic', 'real_typing', 'paired_context', 'fit_exposure')}, indent=2))


if __name__ == '__main__':
    main()
