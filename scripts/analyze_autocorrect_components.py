#!/usr/bin/env python3
"""Fixed diagnostic interventions on saved native outputs; never fit a model.

Restoring clean-context features uses unavailable oracle evidence. Counts explain
the existing gate; they are not deployable gains or threshold recommendations.
"""
import argparse
import collections
import json
import math
from pathlib import Path
import re
import statistics
import struct
import subprocess

import numpy as np

import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE, PROJECT, load_features


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tokenization-probe', type=Path)
    args = parser.parse_args()
    source = PROJECT / 'build/error-analysis-20261030'
    audit = PROJECT / 'build/noisy-context-release74-check'
    report = json.loads((source / 'report.json').read_text())
    policy_path = ENGINE / 'english_acceptance.json'
    model_path = PROJECT / 'app/src/main/assets/language_model/model.cng'
    assert core.digest(model_path) == report['binding']['model_sha256']
    assert core.digest(policy_path) == report['binding']['acceptance_sha256']
    suite = PROJECT / 'build/autocorrect-eval-v4/frozen/cases.jsonl'
    assert core.digest(suite) == report['binding']['cases_sha256']
    assert core.digest(audit / 'outputs.jsonl') == report['derivation']['noise_outputs_sha256']
    assert core.digest(source / 'frozen-outputs.jsonl') == report['derivation']['output_hashes']['frozen-outputs.jsonl']
    policy = json.loads(policy_path.read_text())
    features_path = PROJECT.parent / 'gboard-probe/improve-v3/features.py'
    features = load_features(policy, features_path)
    dictionary = set((ENGINE / 'english_valid_words.txt').read_text().splitlines())
    params = features.parameters(policy)
    cutoff = math.log(policy['threshold'] / (1 - policy['threshold']))

    def extract(case, out):
        x, allowed, word = features.extract(case, out)
        typed = case['typed'].replace('’', "'").lower()
        lower = (word or '').lower()
        x = np.array(list(x) + [float(typed in dictionary),
                     float(bool(lower) and all(w in dictionary for w in lower.split(' ')))])
        return x, allowed, word

    def logit(x):
        return float(features.predict_logits(params, (x - policy['means']) / policy['scales']))

    def decide(case, out):
        x, allowed, word = extract(case, out)
        restored = features.restored(out)
        if word and word.lower() == case['typed'].lower() and word != case['typed'].lower():
            restored = restored or word
        return restored or (word if allowed and logit(x) >= cutoff else None)

    cases = core.rows(suite)
    outputs = core.rows(source / 'frozen-outputs.jsonl')
    pairs = core.rows(audit / 'pairs.jsonl')
    noisy = core.rows(audit / 'outputs.jsonl')
    parity = sum(decide(c, o) != o['correction'] for c, o in zip(cases, outputs, strict=True))
    noise_parity = sum(decide(c, o) != o['correction'] for c, a, b in
                       zip(pairs, noisy[::2], noisy[1::2], strict=True) for o in (a, b))
    assert parity == noise_parity == 0, (parity, noise_parity)

    def final(c, o):
        return core.norm(o.get('correction') or c['typed'])

    def words(out):
        return [core.norm(r['word']) for r in out['candidates']]

    # Unconditionally committing top-1 measures the gate's protective role too.
    always_top = [dict(o, correction=o['candidates'][0]['word'] if o['candidates'] else None)
                  for o in outputs]
    result = dict(binding=dict(model_sha256=core.digest(model_path),
        policy_sha256=core.digest(policy_path), script_sha256=core.digest(__file__),
        feature_adapter_sha256=core.digest(features_path), source_report_sha256=core.digest(source/'report.json'),
        frozen_python_native_parity_mismatches=parity, noise_python_native_parity_mismatches=noise_parity,
        frozen_parity_cases=len(cases), noise_parity_outputs=len(noisy), parameters_fitted=False),
        baseline=core.metrics(cases, outputs),
        unconditionally_commit_top1_diagnostic=core.metrics(cases, always_top))

    # For ranking errors, undo only the implemented edit cost. Apostrophe byte
    # scores have different units, so exclude those targets from this comparison.
    expanded = iter(core.rows(source / 'expanded-outputs.jsonl'))
    ranking = collections.Counter()
    for c, o in zip(cases, outputs, strict=True):
        if c['target'] is None or core.norm(c['typed']) == core.norm(c['target']):
            continue
        target = core.norm(c['target'])
        candidates = o['candidates']
        if target not in words(o):
            candidates = next(expanded)['candidates']
        if final(c, o) == target or not candidates or core.norm(candidates[0]['word']) == target:
            continue
        row = next((r for r in candidates if core.norm(r['word']) == target), None)
        if row is None:
            continue
        ranking['generated_but_not_top1'] += 1
        surfaces = {e['word'] for e in o.get('apostropheEvidence', [])}
        best = candidates[0]
        if row['word'] in surfaces or best['word'] in surfaces:
            ranking['separate_apostrophe_score_path'] += 1
            continue
        def raw(r):
            cost = np.float32(0) if r['completedChars'] else features.penalty(
                c['typed'].lower(), r['word'].lower(), r['edits'])
            return float(np.float32(np.float32(r['score']) + cost))
        ranking['gold_raw_lm_beats_winner_but_edit_cost_reverses' if raw(row) > raw(best)
                else 'gold_loses_to_winner_on_raw_lm_too'] += 1
    assert next(expanded, None) is None
    result['ranking_diagnosis'] = dict(ranking)

    data = model_path.read_bytes()
    meta = json.loads(data[12:12+struct.unpack('<I', data[8:12])[0]])
    ids = {t: i for i, t in enumerate(meta['tokens'])}
    en = re.compile(r"[A-Za-z0-9]+(?:['’\-][A-Za-z0-9]+)*")

    def tokenize(text, force_byte_span=None):
        tokens, fallback, i = [], 0, 0
        while i < len(text):
            if text[i].isspace():
                i += 1
                continue
            match = en.match(text, i)
            end = match.end() if match else i + 1
            unit = text[i:end]
            if unit in ids and (i, end) != force_byte_span:
                tokens.append(ids[unit])
            else:
                byte_ids = [ids[f'[byte:{b:02x}]'] for b in unit.encode()]
                fallback += len(byte_ids)
                tokens.extend(byte_ids)
            i = end
        return tokens, fallback

    result['noise_feature_interventions'] = {}
    result['tokenization'] = {}
    examples = []
    for operation in ('delete', 'substitute', 'duplicate', 'transpose'):
        counts = collections.Counter()
        deltas = collections.defaultdict(list)
        token_counts = collections.Counter()
        for c, a, b in zip(pairs, noisy[::2], noisy[1::2], strict=True):
            if c['operation'] != operation or core.norm(c['typed']) == core.norm(c['target']):
                continue
            target = core.norm(c['target'])
            ta, fa = tokenize(c['original_left'])
            tb, fb = tokenize(c['left'])
            token_counts['typo_pairs'] += 1
            token_counts['byte_fallback_increases'] += fb > fa
            token_counts['context_token_count_increases'] += len(tb) > len(ta)
            token_counts['crosses_32_token_limit'] += len(ta) <= 32 < len(tb)
            lost = final(c, a) == target and final(c, b) != target
            if lost:
                token_counts['lost_fixes'] += 1
                token_counts['lost_fixes_with_increased_byte_fallback'] += fb > fa
                token_counts['lost_fixes_without_any_32_token_truncation'] += len(tb) <= 32 and len(ta) <= 32
            if not a['candidates'] or not b['candidates'] or words(a)[:2] != words(b)[:2]:
                continue
            # Exact same winner AND runner is necessary for feature swaps to
            # describe the same decision. Spelling/known features must agree.
            xa, allowed_a, wa = extract(c, a)
            xb, allowed_b, wb = extract(c, b)
            if wa != wb or not np.array_equal(xa[5:], xb[5:]):
                counts['same_casefolded_top_two_but_changed_surface_features'] += 1
                continue
            counts['same_top_two_typo_pairs'] += 1
            if final(c, a) == target and final(c, b) == target:
                deltas['retained_candidate_raw_delta'].append(float(xb[0]-xa[0]))
            if not lost or core.norm(wb or '') != target or not allowed_b:
                continue
            counts['lost_fix_same_top_two_correct_candidate_eligible'] += 1
            deltas['lost_candidate_raw_delta'].append(float(xb[0]-xa[0]))
            deltas['lost_penalized_margin_delta'].append(float(xb[4]-xa[4]))
            for name, cols in [('candidate_raw',[0]), ('literal_advantage',[1,2]),
                               ('runner_margins',[3,4]), ('all_lm_features',list(range(5)))]:
                x = xb.copy()
                x[cols] = xa[cols]
                counts['recovered_restoring_' + name] += logit(x) >= cutoff
            if operation == 'substitute' and len(examples) < 6:
                examples.append(dict(c, candidate=wb, clean_raw=float(xa[0]), noisy_raw=float(xb[0]),
                    clean_pen_margin=float(xa[4]), noisy_pen_margin=float(xb[4]),
                    clean_gate_logit=logit(xa), noisy_gate_logit=logit(xb),
                    clean_context_tokens=len(ta), noisy_context_tokens=len(tb)))
        result['noise_feature_interventions'][operation] = dict(counts, medians={
            k:statistics.median(v) for k,v in deltas.items() if v})
        result['tokenization'][operation] = dict(token_counts)
    result['noise_feature_examples'] = examples
    if args.tokenization_probe:
        assert core.digest(args.tokenization_probe) == report['binding']['probe_sha256']
        target_ids = collections.defaultdict(list)
        for i in meta['english_word_ids']:
            target_ids[core.norm(meta['tokens'][i])].append(i)
        requests, selected = [], []
        for c in pairs:
            if c['operation'] != 'substitute' or core.norm(c['typed']) == core.norm(c['target']):
                continue
            matches = list(re.finditer(r"(?<![\w'’])[A-Za-z]{3,}(?![\w'’])", c['original_left']))
            span = matches[-1]
            # Fixed eligibility, without selecting by baseline success or score.
            if span.group() not in ids or not target_ids[core.norm(c['target'])]:
                continue
            forced = tokenize(c['original_left'], (span.start(), span.end()))[0][-32:]
            forced = [0] * (32-len(forced)) + forced
            selected.append(c)
            common = dict(candidate_ids=target_ids[core.norm(c['target'])] +
                          [ids[f'[byte:{b:02x}]'] for b in range(256)], distribution=True)
            requests.extend([dict(common, left=c['original_left']),
                             dict(common, left=c['left']),
                             dict(common, context_ids=forced)])
        request_path = source/'tokenization-ablation-v2-requests.jsonl'
        output_path = source/'tokenization-ablation-v2-outputs.jsonl'
        if output_path.exists():
            assert core.rows(request_path) == requests
            predictions = core.rows(output_path)
        else:
            core.jsonl(request_path, requests)
            proc = subprocess.run([str(args.tokenization_probe), str(model_path)],
                input=''.join(json.dumps(r)+'\n' for r in requests), text=True,
                capture_output=True, check=True, timeout=120)
            predictions = [json.loads(line) for line in proc.stdout.splitlines()]
            core.jsonl(output_path, predictions)
        assert len(predictions) == len(requests)
        clean_byte_delta, noise_delta = [], []
        byte_mass = collections.defaultdict(list)
        conditional_word_delta = []
        for c, a, b, f in zip(selected, predictions[::3], predictions[1::3], predictions[2::3], strict=True):
            clean_ids = tokenize(c['original_left'])[0][-32:]
            clean_ids = [0]*(32-len(clean_ids)) + clean_ids
            assert clean_ids == a['context_ids']
            noisy_ids = tokenize(c['left'])[0][-32:]
            noisy_ids = [0]*(32-len(noisy_ids)) + noisy_ids
            assert noisy_ids == b['context_ids']
            size = len(target_ids[core.norm(c['target'])])
            clean_byte_delta.append(max(f['log_probs'][:size])-max(a['log_probs'][:size]))
            noise_delta.append(max(b['log_probs'][:size])-max(a['log_probs'][:size]))
            for name, out in [('original', a), ('substitution', b), ('forced_clean_bytes', f)]:
                byte_mass[name].append(sum(math.exp(v) for v in out['log_probs'][size:]))
            # Conditional on not continuing with a byte, does the word score
            # still collapse? This isolates probability mass allocation only;
            # non-byte tokens include Han and special symbols, not just English.
            conditional_word_delta.append(noise_delta[-1] -
                math.log1p(-min(byte_mass['substitution'][-1], 1-1e-12)) +
                math.log1p(-min(byte_mass['original'][-1], 1-1e-12)))
        result['tokenization_encoding_ablation'] = dict(
            cases=len(selected), native_context_encoding_parity_mismatches=0,
            median_target_logp_change_clean_word_forced_to_bytes=statistics.median(clean_byte_delta),
            median_target_logp_change_actual_substitution=statistics.median(noise_delta),
            clean_word_forced_to_bytes_target_drop_above2=sum(d < -2 for d in clean_byte_delta),
            actual_substitution_target_drop_above2=sum(d < -2 for d in noise_delta),
            median_byte_token_probability_mass={k:statistics.median(v) for k,v in byte_mass.items()},
            median_target_logp_change_substitution_conditional_on_nonbyte=statistics.median(conditional_word_delta),
            requests_sha256=core.digest(request_path), outputs_sha256=core.digest(output_path))
    result['limitations'] = [
        'Oracle clean-feature swaps are diagnostic and may be off the model input distribution; not deployable gains.',
        'Restoration counts overlap and are not additive or unique causal attributions.',
        'Tokenization effects are measured associations; a different tokenizer was not trained or evaluated.',
        'Forced byte encoding is an artificial representation intervention with unchanged text; its scores do not establish correction gains.',
        'Raw ranking comparison is pairwise against the current winner, not a complete reranking experiment.',
        'Exposed frozen development data and synthetic stresses do not estimate production error prevalence.',
    ]
    destination = source / 'component-diagnostics.json'
    core.write_json(destination, result)
    print(json.dumps({k:v for k,v in result.items() if k != 'noise_feature_examples'}, indent=2))


if __name__ == '__main__':
    main()
