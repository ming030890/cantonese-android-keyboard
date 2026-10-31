#!/usr/bin/env python3
"""Diagnostic-only decomposition of the released English/bilingual decoder.

No fitting, threshold selection, or production changes. Gold targets never enter
native requests. Synthetic mixed-context errors are frozen before inference and
must remain evaluation-only. Runtime timing is Mac native work, not Android IME.
"""
import argparse
import collections
import hashlib
import json
import re
import statistics
import struct
import subprocess
from pathlib import Path

import autocorrect_eval_v2 as core
from align_mobile_targets import osa

SEED = 'error-analysis-mixed-20261030-v1'
ROOT = Path(__file__).resolve().parents[1]


def rank(target, words):
    words = [core.norm(w) for w in words]
    return words.index(core.norm(target)) + 1 if core.norm(target) in words else None


def outcome(case, output):
    if case['target'] is None:
        return 'unlabelled'
    target, typed = core.norm(case['target']), core.norm(case['typed'])
    final = core.norm(output.get('correction') or case['typed'])
    if typed == target:
        return 'clean_preserved' if final == target else 'clean_damaged'
    if final == target:
        return 'corrected'
    position = rank(target, [c['word'] for c in output['candidates']])
    if position == 1:
        return 'target_top1_not_committed'
    if position is not None:
        return 'target_rank2_to8'
    return 'target_absent_top8'


def latency(values):
    return dict(n=len(values), median_ms=statistics.median(values),
                p95_ms=core.percentile(values, .95), max_ms=max(values),
                above_40ms=sum(v > 40 for v in values)) if values else {}


def native(binary, args, requests, output):
    """Sequential calls avoid host contention and preserve exact request order."""
    results = []
    with output.open('w') as saved, subprocess.Popen(
        [str(binary.resolve()), *map(str, args)], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, text=True, bufsize=1,
    ) as proc:
        try:
            for i, request in enumerate(requests):
                proc.stdin.write(json.dumps(request, ensure_ascii=False) + '\n')
                proc.stdin.flush()
                line = proc.stdout.readline()
                if not line:
                    raise RuntimeError(f'Native process stopped at {i}: {proc.poll()}')
                result = json.loads(line)
                results.append(result)
                saved.write(line)
                if (i + 1) % 128 == 0:
                    print(f'{output.stem}: {i+1}/{len(requests)}', flush=True)
            proc.stdin.close()
            if proc.wait(timeout=30):
                raise RuntimeError('Native process failed')
        except BaseException:
            proc.kill()
            proc.wait()
            raise
    return results


def summary(cases, outputs):
    return dict(metrics=core.metrics(cases, outputs),
                outcomes=dict(collections.Counter(outcome(c, o) for c, o in zip(cases, outputs, strict=True))))


def mutate(word, operation, identifier):
    value = int(hashlib.sha256((SEED + identifier + operation).encode()).hexdigest(), 16)
    positions = list(range(len(word)))
    if operation == 'transpose':
        positions = [i for i in range(len(word)-1) if word[i] != word[i+1]]
    if not positions:
        return None
    i = positions[value % len(positions)]
    if operation == 'delete':
        return word[:i] + word[i+1:]
    if operation == 'duplicate':
        return word[:i] + word[i] + word[i:]
    if operation == 'transpose':
        return word[:i] + word[i+1] + word[i] + word[i+2:]
    # Horizontal QWERTY neighbor: a controlled stressor, not an empirical noise fit.
    row = next(r for r in ('qwertyuiop', 'asdfghjkl', 'zxcvbnm') if word[i] in r)
    at = row.index(word[i])
    choices = row[max(0, at-1):at] + row[at+1:at+2]
    return word[:i] + choices[(value // len(positions)) % len(choices)] + word[i+1:]


def mixed_rows():
    rows = []
    for name in ('generalization', 'phrase', 'prefix'):
        rows += json.loads((ROOT / f'build/mixed-{name}-cohort.json').read_text())['rows']
    result = []
    for row in rows:
        # Frozen natural context; remove right context to simulate live typing.
        base = dict(row, right='', typed=row['observed'], operation='original')
        if row['panel'] != 'english_in_chinese_context':
            result.append(base)
            continue
        for version in (3, 5):
            result.append(dict(base, version=version, id=f"{row['id']}:v{version}"))
            for operation in ('delete', 'substitute', 'duplicate', 'transpose'):
                typed = mutate(row['observed'], operation, row['id'])
                if typed:
                    result.append(dict(base, id=f"{row['id']}:v{version}:{operation}",
                                       panel='synthetic_english_typo_in_chinese_context',
                                       version=version, typed=typed, observed=typed, operation=operation))
    return result


def mixed_summary(rows, outputs):
    result = {}
    for panel in sorted({r['panel'] for r in rows}):
        for version in (3, 5):
            ids = [i for i, r in enumerate(rows) if r['panel'] == panel and r['version'] == version]
            if not ids:
                continue
            cases, predictions = [rows[i] for i in ids], [outputs[i] for i in ids]
            ranks = [rank(c['target'], o['display']) for c, o in zip(cases, predictions)]
            english = [o['english'] for o in predictions]
            values = dict(n=len(ids), display_top1=sum(r == 1 for r in ranks),
                          display_top8=sum(r is not None and r <= 8 for r in ranks),
                          display_available=sum(r is not None for r in ranks),
                          native_latency=latency([o['elapsed_ms'] for o in predictions]))
            if panel.startswith('chinese_'):
                values['chinese_generated'] = sum(rank(c['target'], o['chinese']) is not None
                                                 for c, o in zip(cases, predictions))
                values['english_correction_proposed'] = sum(bool(o.get('correction')) and
                    core.norm(o['correction']) != core.norm(c['typed']) for c, o in zip(cases, english))
            elif panel != 'prefix':
                values['english'] = summary(cases, english)
            result[f'{panel}/quick{version}'] = values
    return result


def summarize_existing(output):
    """Derive paired stage transitions without repeating native inference."""
    report = json.loads((output/'report.json').read_text())
    source = ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'
    assert core.digest(source) == report['binding']['cases_sha256']
    cases, predictions = core.rows(source), core.rows(output/'frozen-outputs.jsonl')
    panels = ({'mobile_observed','mobile_source_copy'}, {'real_touch_observed'}, {'authored_hard'})
    scores = [core.metrics([c for c in cases if c['panel'] in panel],
        [o for c,o in zip(cases,predictions,strict=True) if c['panel'] in panel])['f05'] for panel in panels]
    baseline = json.loads((ROOT/'config/english_iteration/2026-10-29/short-nn-v2/accepted-score.json').read_text())['score']
    report['unchanged_official_score'] = dict(score=sum(scores)/3, baseline=baseline, delta=sum(scores)/3-baseline)
    audit = ROOT/'build/noisy-context-release74-check'
    audit_report = json.loads((audit/'report.json').read_text())
    assert audit_report['binding']['probe_sha256'] == report['binding']['probe_sha256']
    assert core.digest(audit/'pairs.jsonl') == audit_report['binding']['pairs_sha256']
    pairs, noise = core.rows(audit/'pairs.jsonl'), core.rows(audit/'outputs.jsonl')
    report['prior_context_lost_corrections_by_stage'] = {}
    for operation in ('delete','substitute','duplicate','transpose'):
        counts = collections.Counter()
        for c,a,b in zip(pairs,noise[::2],noise[1::2],strict=True):
            if c['operation'] == operation and core.norm(c['typed']) != core.norm(c['target']) and \
                core.norm(a['correction'] or c['typed']) == core.norm(c['target']) and \
                core.norm(b['correction'] or c['typed']) != core.norm(c['target']):
                counts[outcome(c,b)] += 1
        report['prior_context_lost_corrections_by_stage'][operation] = dict(counts)
    mixed = core.rows(output/'mixed-cases.jsonl')
    assert core.digest(output/'mixed-cases.jsonl') == report['binding']['mixed_cases_sha256']
    full, preview = core.rows(output/'mixed-full-outputs.jsonl'), core.rows(output/'mixed-preview-outputs.jsonl')
    report['mixed_demotions'] = {}
    for version in (3,5):
        counts = collections.Counter()
        for c,f,p in zip(mixed,full,preview,strict=True):
            if c['panel'] != 'synthetic_english_typo_in_chinese_context' or c['version'] != version:
                continue
            rf, rp = rank(c['target'],f['display']), rank(c['target'],p['display'])
            counts['n'] += 1
            counts['preview_top8_to_full_below8'] += bool(rp and rp<=8 and (rf is None or rf>8))
            counts['preview_below8_to_full_top8'] += bool((rp is None or rp>8) and rf and rf<=8)
        report['mixed_demotions'][str(version)] = dict(counts)
    report['derivation'] = dict(script_sha256=core.digest(__file__),
        output_hashes={p.name:core.digest(p) for p in sorted(output.glob('*outputs.jsonl'))},
        noise_outputs_sha256=core.digest(audit/'outputs.jsonl'))
    report['limitations'] = [
        'Diagnostic-only; no parameters fitted or selected.',
        'The frozen English suite and older bilingual cohorts have development exposure.',
        'Synthetic mixed-context variants are 255 perturbations from 64 documents; Quick3/5 are paired, not independent.',
        'Prefix completion and explicit Chinese selection are distinct from automatic English correction.',
        'Native Mac timings exclude Android scheduling/rendering and do not establish phone deadline failures.',
    ]
    core.write_json(output/'report.json',report)
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe', type=Path)
    p.add_argument('--joined-probe', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--summarize-existing', action='store_true')
    a = p.parse_args()
    if a.summarize_existing:
        print(json.dumps(summarize_existing(a.output),ensure_ascii=False,indent=2))
        return
    if not a.probe or not a.joined_probe:
        p.error('--probe and --joined-probe are required for inference')
    a.output.mkdir(parents=True, exist_ok=False)
    assets = ROOT / 'app/src/main/assets'
    model = assets / 'language_model/model.cng'
    suite = ROOT / 'build/autocorrect-eval-v4/frozen'
    manifest = json.loads((suite / 'manifest.json').read_text())
    assert core.digest(suite / 'cases.jsonl') == manifest['cases_sha256']
    cases = core.rows(suite / 'cases.jsonl')
    mixed = mixed_rows()
    core.jsonl(a.output / 'mixed-cases.jsonl', mixed)
    binding = dict(seed=SEED, model_sha256=core.digest(model), probe_sha256=core.digest(a.probe),
        joined_probe_sha256=core.digest(a.joined_probe), script_sha256=core.digest(__file__),
        cases_sha256=core.digest(suite / 'cases.jsonl'), mixed_cases_sha256=core.digest(a.output/'mixed-cases.jsonl'),
        acceptance_sha256=core.digest(ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_acceptance.json'),
        source_cohorts={name: core.digest(ROOT/f'build/mixed-{name}-cohort.json')
                        for name in ('generalization', 'phrase', 'prefix')},
        inference_fields=['left', 'typed/code', 'version', 'preview', 'spelling_limit', 'timing'],
        parameters_selected=False, right_context_used=False)
    core.write_json(a.output / 'binding.json', binding)
    outputs = native(a.probe, [model], [dict(left=c['left'], typed=c['typed'], spelling_limit=8, timing=True)
        for c in cases], a.output / 'frozen-outputs.jsonl')
    report = dict(binding=binding, frozen=summary(cases, outputs))
    report['frozen']['panels'] = {panel: summary([c for c in cases if c['panel'] == panel],
        [o for c, o in zip(cases, outputs) if c['panel'] == panel]) for panel in sorted({c['panel'] for c in cases})}
    report['frozen']['lengths'] = {name: summary([c for c in cases if group(c['typed']) == name],
        [o for c, o in zip(cases, outputs) if group(c['typed']) == name]) for name in ('1','2','3','4-5','6+')}
    report['frozen']['known_input'] = {str(known): summary([c for c,o in zip(cases,outputs) if o['known']==known],
        [o for o in outputs if o['known']==known]) for known in (True,False)}
    report['frozen']['native_latency'] = latency([o['elapsedMicros']/1000 for o in outputs])
    missing = [i for i, (c, o) in enumerate(zip(cases, outputs)) if outcome(c, o) == 'target_absent_top8']
    expanded = native(a.probe, [model], [dict(left=cases[i]['left'],typed=cases[i]['typed'],
        spelling_limit=4096) for i in missing], a.output / 'expanded-outputs.jsonl')
    data = model.read_bytes()
    meta = json.loads(data[12:12+struct.unpack('<I', data[8:12])[0]])
    vocabulary = {meta['tokens'][i].lower() for i in meta['english_word_ids']
                  if re.fullmatch("[a-z']{1,32}", meta['tokens'][i].lower())}
    coverage, details = collections.Counter(), []
    for i, o in zip(missing, expanded):
        c = cases[i]; target, typed = c['target'].lower(), c['typed'].lower()
        position = rank(target, [x['word'] for x in o['candidates']])
        if position is not None:
            reason = 'generated_but_rank_below8'
        elif len(o['candidates']) >= 4096:
            reason = 'diagnostic_cap_unresolved'
        elif not re.fullmatch("[a-z']{1,32}", typed):
            reason = 'input_rejected_by_canonicalizer'
        elif target not in vocabulary:
            reason = 'outside_ordinary_word_vocabulary_or_multitoken'
        elif osa(typed, target) > (3 if len(typed)>=6 else 2 if len(typed)>=4 else 1):
            reason = 'outside_edit_radius'
        else:
            reason = 'other_generation_exclusion'
        coverage[reason] += 1
        details.append(dict(c, reason=reason, full_rank=position, top8=outputs[i]['candidates'],
                            correction=outputs[i].get('correction')))
    report['frozen']['absent_top8_decomposition'] = dict(coverage)
    core.jsonl(a.output / 'missing-targets.jsonl', details)
    errors = [dict(c, outcome=outcome(c,o), prediction=o) for c,o in zip(cases,outputs)
              if outcome(c,o) not in ('clean_preserved','corrected','unlabelled')]
    core.jsonl(a.output/'errors.jsonl',errors)
    original = core.rows(ROOT/'build/autocorrect-eval-v4/release74-check/candidate-outputs.jsonl')
    report['release74_parity_mismatches'] = sum(
        {k:v for k,v in x.items() if k!='elapsedMicros'} != {k:v for k,v in y.items() if k!='elapsedMicros'}
        for x,y in zip(original,outputs,strict=True))
    audit = json.loads((ROOT/'build/noisy-context-release74-check/report.json').read_text())
    assert audit['binding']['model_sha256'] == binding['model_sha256']
    assert audit['binding']['probe_sha256'] == binding['probe_sha256']
    report['prior_context_noise'] = {k:v['overall'] for k,v in audit['operations'].items()}
    core.write_json(a.output/'report.json',report)
    requests = [dict(code=c['typed'], left=c['left'], right='', version=c['version']) for c in mixed]
    joined = native(a.joined_probe, [assets], requests, a.output/'mixed-full-outputs.jsonl')
    report['mixed_full'] = mixed_summary(mixed, joined)
    mixed_english = [(c,o) for c,o in zip(mixed,joined) if c['panel'] in
                     ('english_in_chinese_context','synthetic_english_typo_in_chinese_context')]
    no_context = native(a.probe,[model],[dict(typed=c['typed'],left='',spelling_limit=8)
        for c,o in mixed_english],a.output/'mixed-without-context-outputs.jsonl')
    report['mixed_context_ablation'] = {}
    for panel in ('english_in_chinese_context','synthetic_english_typo_in_chinese_context'):
        ids=[i for i,(c,o) in enumerate(mixed_english) if c['panel']==panel and c['version']==3]
        cc=[mixed_english[i][0] for i in ids]
        report['mixed_context_ablation'][panel]=dict(with_context=summary(cc,[mixed_english[i][1]['english'] for i in ids]),
            without_context=summary(cc,[no_context[i] for i in ids]))
    preview = native(a.joined_probe,[assets],[dict(r,preview=True) for r in requests],a.output/'mixed-preview-outputs.jsonl')
    report['mixed_preview'] = mixed_summary(mixed,preview)
    report['mixed_preview_full_english_mismatches'] = sum(x['english'] != y['english'] for x,y in zip(joined,preview))
    core.jsonl(a.output/'mixed-errors.jsonl', [dict(c, full=o, preview=p) for c,o,p in zip(mixed,joined,preview)
        if rank(c['target'],o['display']) != 1 or (c['panel'].startswith('synthetic_') and
            core.norm(o['english'].get('correction') or c['typed']) != core.norm(c['target']))])
    for key,path in [('model',model),('probe',a.probe),('joined_probe',a.joined_probe)]:
        assert core.digest(path)==binding[key+'_sha256'], f'{key} changed during run'
    core.write_json(a.output/'report.json',report)
    print(json.dumps(summarize_existing(a.output),ensure_ascii=False,indent=2),flush=True)


def group(word):
    return str(len(word)) if len(word)<=3 else '4-5' if len(word)<=5 else '6+'


if __name__ == '__main__':
    main()
