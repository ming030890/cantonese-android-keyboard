#!/usr/bin/env python3
"""Attribute errors on the frozen cohort without tuning the production policy."""
import argparse
import collections
import csv
import hashlib
import json
import struct
from pathlib import Path

from align_mobile_targets import osa, reference_target
from audit_candidate_pipeline import digest, penalty
from iterate_english_policy import metric, rerank, split
from verify_english_acceptance import decision


def score_parts(typed, row):
    word = row['word'].lower()
    if row['completedChars']:
        cost = 0.
    elif ' ' in word:
        cost = 1.5 if len(word) == len(typed)+1 else 1.
    else:
        cost = penalty(typed.lower(), word, row['edits'])
    # Split LM evidence is logp(first) + 0.5 logp(second), not a causal phrase score.
    return dict(lm=row['score']+cost, cost=cost, score=row['score'])


def missing_reason(typed, target, vocabulary):
    if any(part not in vocabulary for part in target.split()):
        return 'target_word_absent_from_generation_vocabulary'
    if ' ' in target:
        return 'multiword_split_proposal_restriction'
    if target.startswith(typed):
        return 'unexpected_missing_prefix'
    if abs(len(target)-len(typed)) > 1:
        return 'length_difference_greater_than_one'
    radius = 3 if len(typed) >= 6 else 2 if len(typed) >= 4 else 1
    if osa(typed, target) > radius:
        return 'outside_length_dependent_edit_radius'
    return 'unexpected_missing_in_supported_neighborhood'


def veto(policy, case, out, best):
    if out['known']:
        return 'known_word_preservation'
    if len(case['typed']) < policy['minimum_observed_length']:
        return 'observed_word_too_short'
    if best['completedChars']:
        return 'automatic_completion_disabled'
    if len(best['word']) < policy['minimum_candidate_length']:
        return 'candidate_word_too_short'
    return 'learned_acceptance_threshold'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--iteration', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    model = project/'app/src/main/assets/language_model/model.cng'
    policy_path = project/'config/english_iteration/2026-10-20/acceptance-policy-v1.json'
    reference_path = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    cases_path, cache_path = args.iteration/'cases.jsonl', args.iteration/'native-cache.json'
    binding = json.loads((project/'config/english_iteration/2026-10-20/f05-linear-acceptance.json').read_text())['input_sha256']
    assert digest(cases_path) == binding['build/english-iteration-2026-10-20/cases.jsonl']
    assert digest(cache_path) == binding['build/english-iteration-2026-10-20/native-cache.json']
    policy = json.loads(policy_path.read_text())
    assert digest(model) == policy['model_sha256']
    raw = model.read_bytes()
    n = struct.unpack('<I', raw[8:12])[0]
    metadata = json.loads(raw[12:12+n])
    vocabulary = {metadata['tokens'][i].replace('’', "'").lower() for i in metadata['english_word_ids']}
    references = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(reference_path.open())}
    cases = [json.loads(l) for l in cases_path.open()]
    cached = json.loads(cache_path.read_text())['outputs']
    assert len(cases) == len(cached)
    selected = []
    for c, out in zip(cases, cached):
        if c['category'].startswith('prefix_') or split(c['group']) != 'evaluation':
            continue
        target = c['target'] if c['category'] == 'source_preservation' else reference_target(c, references[c['group']])
        if target is not None:
            selected.append((dict(c, target=target), out))
    identities = '\n'.join(sorted(c['id'] for c, _ in selected))+'\n'
    assert hashlib.sha256(identities.encode()).hexdigest() == '2a6361cda8ee35c00bfed060b409561d4abe6f863597df8eb029edea18271e5c'
    counts = collections.Counter()
    missing = collections.Counter()
    rejection = collections.Counter()
    ranking_causes = collections.Counter()
    wrong_causes = collections.Counter()
    breakdown = collections.defaultdict(collections.Counter)
    errors, rankings, fixes = [], [], []
    ablations = {name: collections.Counter() for name in ('current', 'lm_only', 'uniform_two_per_edit', 'cost_only')}
    acceptance_ablations = {name: [] for name in ('without_known_word_veto', 'without_learned_threshold')}
    for c, out in selected:
        typed, target = c['typed'].lower(), c['target']
        ranked = rerank(out['candidates'], 0.)
        fix = decision(policy, c, out, ranked)
        acceptance_ablations['without_known_word_veto'].append(decision(policy, c, dict(out, known=False), ranked))
        eligible = ranked and veto(policy, c, out, ranked[0]) == 'learned_acceptance_threshold'
        acceptance_ablations['without_learned_threshold'].append(ranked[0]['word'] if eligible else None)
        rankings.append(ranked)
        fixes.append(fix)
        typo = typed != target
        gold_rank = next((i+1 for i, r in enumerate(ranked) if r['word'].lower() == target), None)
        counts['cases'] += 1
        counts['typos' if typo else 'controls'] += 1
        counts['known_typo' if typo and out['known'] else 'known_control' if out['known'] else 'unknown_input'] += 1
        if not typo:
            if fix:
                counts['wrong_on_control'] += 1
                wrong_causes['clean_control_changed'] += 1
                errors.append(dict(c, kind='wrong_on_control', fix=fix, best=ranked[0],
                                   target_rank=gold_rank, known=out['known']))
            continue
        counts['target_in_pool'] += gold_rank is not None
        counts['target_in_top8'] += gold_rank is not None and gold_rank <= 8
        counts['target_top1'] += gold_rank == 1
        if gold_rank is not None:
            gold = ranked[gold_rank-1]
            for name, order in ablations.items():
                if name == 'current':
                    best = ranked[0]
                else:
                    def key(row):
                        p = score_parts(typed, row)
                        score = p['lm'] if name == 'lm_only' else -p['cost'] if name == 'cost_only' else p['lm']-(0 if row['completedChars'] else 2*row['edits'])
                        return (-score, row['word'])
                    best = min(ranked, key=key)
                order['top1_on_covered_typos'] += best['word'].lower() == target
                if ' ' not in target and all(' ' not in r['word'] for r in (ranked[0], gold)):
                    order['top1_on_singleword_gold_and_current_winner'] += best['word'].lower() == target
        if fix and fix.lower() == target:
            counts['correct'] += 1
            continue
        counts['missed'] += 1
        if fix:
            counts['wrong_on_typo'] += 1
            wrong_causes['target_absent' if gold_rank is None else 'target_present_ranked_below_wrong_winner'] += 1
        if gold_rank is None:
            stage = 'generation'
            detail = missing_reason(typed, target, vocabulary)
            missing[detail] += 1
        elif gold_rank > 1:
            stage = 'ranking'
            a, b = score_parts(typed, ranked[0]), score_parts(typed, ranked[gold_rank-1])
            if b['lm'] > a['lm']:
                detail = 'cost_overrides_better_lm_evidence'
            elif b['cost'] < a['cost']:
                detail = 'lm_overrides_better_edit_cost'
            else:
                detail = 'lm_and_cost_both_favor_wrong_winner_or_tie'
            ranking_causes[detail] += 1
        else:
            stage = 'acceptance'
            detail = veto(policy, c, out, ranked[0])
            rejection[detail] += 1
        counts[stage+'_misses'] += 1
        breakdown[stage]['known_word_veto_also_applies'] += bool(out['known'])
        breakdown[stage]['wrong_replacement_applied'] += bool(fix)
        errors.append(dict(c, kind=stage, detail=detail, fix=fix, target_rank=gold_rank,
                           known=out['known'], best=ranked[0] if ranked else None,
                           best_parts=score_parts(typed, ranked[0]) if ranked else None,
                           gold=ranked[gold_rank-1] if gold_rank else None,
                           gold_parts=score_parts(typed, ranked[gold_rank-1]) if gold_rank else None))
    assert counts['missed'] == counts['generation_misses']+counts['ranking_misses']+counts['acceptance_misses']
    cases, outputs = map(list, zip(*selected))
    current = metric(cases, outputs, rankings, fixes, 'evaluation')
    expected = json.loads((project/'config/english_iteration/2026-10-20/f05-native-policy-verification.json').read_text())['evaluation']
    for field in ('cases', 'correct', 'wrong', 'typos', 'f05'):
        assert current[field] == expected[field], (field, current[field], expected[field])
    paths = (Path(__file__), cases_path, cache_path, reference_path, policy_path, model,
             Path(__file__).with_name('audit_candidate_pipeline.py'),
             Path(__file__).with_name('align_mobile_targets.py'),
             Path(__file__).with_name('iterate_english_policy.py'),
             Path(__file__).with_name('verify_english_acceptance.py'))
    report = dict(scope='Frozen development evaluation; diagnostic attribution only, no policy fitting or promotion',
                  source_sha256={str(p): digest(p) for p in paths}, evaluation=current,
                  counts=dict(counts), generation_reasons=dict(missing), ranking_reasons=dict(ranking_causes),
                  acceptance_reasons=dict(rejection), wrong_replacement_reasons=dict(wrong_causes),
                  overlapping_blockers={k: dict(v) for k, v in breakdown.items()},
                  ranking_ablations={k: dict(v) for k, v in ablations.items()}, errors=errors,
                  acceptance_ablations={name: metric(cases, outputs, rankings, decisions, 'evaluation')
                                       for name, decisions in acceptance_ablations.items()},
                  max_cached_candidates=max(len(o['candidates']) for _, o in selected),
                  extra_diagnostics=dict(
                      ranking_winner_is_completion=sum(e['kind']=='ranking' and e['best']['completedChars']>0 for e in errors),
                      missing_contraction_targets=sum(e.get('detail')=='target_word_absent_from_generation_vocabulary' and "'" in e['target'] for e in errors),
                      clean_controls_losing_apostrophe=sum(e['kind']=='wrong_on_control' and "'" in e['typed'] and "'" not in e['fix'] for e in errors)),
                  limits=['Stage attribution is earliest failure, not proof of a single causal defect.',
                          'Ranking ablations retain the same pool and do not refit acceptance; not deployable F0.5 comparisons.',
                          'Split LM evidence uses the production heuristic, not a normalized phrase probability.',
                          'No recorded tap positions or phone latency in this Aalto cohort.'])
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('errors', 'source_sha256')}, indent=2))


if __name__ == '__main__':
    main()
