#!/usr/bin/env python3
"""Verify the compiled policy against its frozen development evaluation."""
import argparse
import csv
import json
import math
from pathlib import Path

from align_mobile_targets import reference_target
from audit_candidate_pipeline import digest, probe
from iterate_english_policy import metric, rerank


def decision(policy, case, output, ranked):
    if (output['known'] and policy['preserve_known_words']) or len(case['typed']) < policy['minimum_observed_length'] or not ranked:
        return None
    best = ranked[0]
    if best['completedChars'] or len(best['word']) < policy['minimum_candidate_length']:
        return None
    margin = min(best['score']-ranked[1]['score'], policy['margin_cap']) if len(ranked) > 1 else policy['margin_cap']
    literal = output.get('literalScore')
    if output['known'] and 'literal_score' in policy['features'] and literal is None:
        raise ValueError('Literal-aware policy requires scored original-word evidence')
    values = dict(winner_score=best['score'], runner_margin=margin, known_word=int(output['known']),
                  literal_score=literal or 0., score_over_literal=best['score']-literal if literal is not None else 0.,
                  observed_apostrophe=int("'" in case['typed'] or '’' in case['typed']))
    features = [values[name] for name in policy['features']]
    logit = policy['weights'][0]+sum(w*(x-m)/s for w, x, m, s in zip(policy['weights'][1:], features, policy['means'], policy['scales']))
    return best['word'] if 1/(1+math.exp(-max(-700, min(700, logit)))) >= policy['threshold'] else None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--iteration', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    project = Path(__file__).resolve().parents[1]
    model = project/'app/src/main/assets/language_model/model.cng'
    policy_path = project.parent/'QwenASR/crates/cantonese-ngram/src/english_acceptance.json'
    policy = json.loads(policy_path.read_text())
    apostrophe_path = policy_path.with_name('english_apostrophe_acceptance.json')
    apostrophe = json.loads(apostrophe_path.read_text())
    forms_path = policy_path.with_name('english_apostrophe_forms.json')
    accepted = json.loads((project/'config/english_iteration/2026-10-21/apostrophe-evaluation.json').read_text())
    assert digest(model) == apostrophe['model_sha256']
    assert digest(forms_path) == apostrophe['forms_sha256']
    assert accepted['binding']['apostrophe_policy'] == digest(apostrophe_path)
    assert accepted['binding']['policy'] == digest(policy_path)
    assert accepted['binding']['forms'] == digest(forms_path)
    assert digest(model) == policy['model_sha256'], 'Policy requires recalibration for this model'
    cases = [json.loads(l) for l in (args.iteration/'cases.jsonl').open() if not json.loads(l)['category'].startswith('prefix_')]
    old = json.loads((args.iteration/'native-cache.json').read_text())['outputs'][:len(cases)]
    references = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(open('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv'))}
    rows = []
    for c, o in zip(cases, old):
        target = c['target'] if c['category'] == 'source_preservation' else reference_target(c, references[c['group']])
        if target is not None:
            rows.append((dict(c, target=target), o))
    cases, old = map(list, zip(*rows))
    print(f'Checking actual native decisions for {len(cases)} aligned cases', flush=True)
    actual = probe(args.probe, model, [dict(left=c['left'], typed=c['typed'], spelling_limit=4096) for c in cases])
    rankings = [rerank(o['candidates'], 0.) for o in old]
    expected = [decision(policy, c, o, r) for c, o, r in zip(cases, actual, rankings)]
    for i, output in enumerate(actual):
        for evidence in output['apostropheEvidence']:
            logit = apostrophe['weights'][0] + sum(w*(x-m)/s for w,x,m,s in zip(
                apostrophe['weights'][1:], evidence['features'], apostrophe['means'], apostrophe['scales']))
            if 1/(1+math.exp(-max(-700,min(700,logit)))) >= apostrophe['threshold']:
                expected[i] = evidence['word']
    native = [o['correction'] for o in actual]
    mismatches = [c['id'] for c, a, b in zip(cases, expected, native) if a != b]
    report = dict(model_sha256=digest(model), policy_sha256=digest(policy_path), probe_sha256=digest(args.probe),
                  cases=len(cases), decision_mismatches=mismatches,
                  evaluation=metric(cases, actual, [o['candidates'] for o in actual], native, 'evaluation'),
                  baseline_evaluation=accepted['baseline_evaluation'],
                  apostrophe_policy_sha256=digest(apostrophe_path), forms_sha256=digest(forms_path),
                  candidate_order_changes=sum([r['word'] for r in a['candidates']] != [r['word'] for r in b] for a,b in zip(actual,rankings)))
    for field in ('cases', 'correct', 'wrong', 'typos', 'f05'):
        assert report['evaluation'][field] == accepted['evaluation'][field], field
    predictions = args.output.with_suffix('.predictions.json')
    predictions.write_text(json.dumps(dict(model_sha256=digest(model), policy_sha256=digest(policy_path),
                                           case_ids=[c['id'] for c in cases], outputs=actual)))
    report['predictions_sha256'] = digest(predictions)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))
    if mismatches:
        raise SystemExit('Compiled native policy differs from frozen policy')


if __name__ == '__main__':
    main()
