#!/usr/bin/env python3
"""Reproducible synthetic spelling diagnostic; never fits correction policy.

Select common words from a frequency list, before seeing engine outputs. One
hash-selected corruption per word and operation, plus unchanged controls.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import statistics
import string

from audit_candidate_pipeline import digest, probe

SEED = 'single-edit-common-20261021-v1'
POSITIONS = {c: (r, i) for r, row in enumerate(('qwertyuiop', 'asdfghjkl', 'zxcvbnm'))
             for i, c in enumerate(row)}


def corruptions(word):
    variants = defaultdict(set)
    for i, char in enumerate(word):
        variants['deletion'].add(word[:i] + word[i+1:])
        for replacement in string.ascii_lowercase:
            if replacement == char:
                continue
            typed = word[:i] + replacement + word[i+1:]
            variants['substitution'].add(typed)
            if all(abs(a-b) <= 1 for a, b in zip(POSITIONS[char], POSITIONS[replacement])):
                variants['qwerty_substitution'].add(typed)
        if i+1 < len(word) and char != word[i+1]:
            variants['transposition'].add(word[:i] + word[i+1] + char + word[i+2:])
    for i in range(len(word)+1):
        for char in string.ascii_lowercase:
            variants['insertion'].add(word[:i] + char + word[i:])
    return {kind: min(values, key=lambda typed: hashlib.sha256(
        f'{SEED}:{word}:{kind}:{typed}'.encode()).digest())
        for kind, values in sorted(variants.items())}


def summarize(rows):
    counts = Counter()
    for row in rows:
        counts['cases'] += 1
        typo = row['operation'] != 'clean'
        counts['typos' if typo else 'controls'] += 1
        fix = row['correction']
        applied = bool(fix and fix.lower() != row['typed'])
        correct = bool(applied and typo and fix.lower() == row['target'])
        counts['correct'] += correct
        counts['wrong'] += applied and not correct
        counts['unchanged'] += not applied
        counts['false_changes'] += applied and not typo
        for key in ('top1', 'top8', 'target_returned'):
            counts[key] += row[key]
        if typo and not correct:
            counts['miss_' + row['failure_stage']] += 1
    tp, fp, fn = counts['correct'], counts['wrong'], counts['typos']-counts['correct']
    latency = sorted(r['elapsedMicros'] for r in rows)
    return dict(counts, precision=tp/(tp+fp) if tp+fp else 0,
                recall=tp/counts['typos'] if counts['typos'] else 0,
                f05=1.25*tp/(1.25*tp+fp+.25*fn) if tp+fp+fn else 0,
                median_us=statistics.median(latency), p95_us=latency[int(.95*len(latency))])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--words', type=Path, required=True, help='word count lines; use TRAIN counts')
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--count', type=int, default=1000)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.count < 1:
        parser.error('--count must be positive')
    project = Path(__file__).resolve().parents[1]
    model = project/'app/src/main/assets/language_model/model.cng'
    words = {}
    for line in args.words.read_text().splitlines():
        word, count = line.split()
        if word.isascii() and word.isalpha() and word.islower() and 1 <= len(word) <= 20:
            words[word] = int(count)
    selected = sorted(words, key=lambda word: (-words[word], word))[:args.count]
    if not selected:
        parser.error('no eligible words')
    cases = []
    for word in selected:
        for operation, typed in dict(clean=word, **corruptions(word)).items():
            cases.append(dict(target=word, typed=typed, operation=operation, left=''))
    requests = [dict(left=c['left'], typed=c['typed'], spelling_limit=4096, timing=True) for c in cases]
    print(f'Scoring {len(cases)} cases from {len(selected)} common words', flush=True)
    outputs = probe(args.probe, model, requests[:16]+requests)[16:]
    rows = []
    for case, out in zip(cases, outputs):
        candidates = [r['word'].lower() for r in out['candidates']]
        rank = candidates.index(case['target'])+1 if case['target'] in candidates else None
        stage = 'not_returned' if rank is None else 'ranking' if rank > 1 else 'acceptance'
        rows.append(dict(case, known=out['known'], correction=out['correction'],
                         target_rank=rank, target_returned=rank is not None,
                         top1=rank == 1, top8=rank is not None and rank <= 8,
                         failure_stage=stage, candidates=out['candidates'], elapsedMicros=out['elapsedMicros']))
    args.output.mkdir(parents=True, exist_ok=True)
    predictions = args.output/'predictions.jsonl'
    predictions.write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in rows))
    word_file = args.output/'words.tsv'
    word_file.write_text(''.join(f'{word}\t{words[word]}\n' for word in selected))
    typos = [r for r in rows if r['operation'] != 'clean']
    report = dict(seed=SEED, method='Top TRAIN-frequency dictionary words, ASCII letters, lengths 1–20; '
                  'one deterministic corruption per operation per word, empty left context; 16 warmup calls. '
                  'Transposition is one adjacent swap, not one Levenshtein edit. QWERTY substitutions '
                  'are a separate panel and can overlap arbitrary substitutions. No policy fitting.',
                  limitations='Synthetic intended targets can be ambiguous, especially valid-word corruptions. '
                  'Frequency source is domain-specific; not an independent general-English word list. '
                  '4096 candidate diagnostic timings include full returned suggestions; not Android UI latency. '
                  'Not-returned does not distinguish vocabulary absence from bounded candidate generation.',
                  hashes={str(p): digest(p) for p in (Path(__file__), args.words, args.probe, model, predictions, word_file)},
                  word_count=len(selected), all=summarize(rows), typos=summarize(typos),
                  by_operation={op: summarize([r for r in rows if r['operation'] == op])
                                for op in sorted({r['operation'] for r in rows})},
                  by_known={str(known): summarize([r for r in typos if r['known'] == known])
                            for known in (False, True) if any(r['known'] == known for r in typos)},
                  examples=[{k: r[k] for k in ('target', 'typed', 'operation', 'known', 'correction', 'target_rank', 'failure_stage')}
                            for r in typos if not r['known'] and r['correction'] != r['target']][:30])
    (args.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({k: report[k] for k in ('word_count', 'typos', 'by_operation', 'by_known')}, indent=2))


if __name__ == '__main__':
    main()
