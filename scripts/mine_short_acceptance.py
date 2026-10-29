#!/usr/bin/env python3
"""Mine intentional short forms and generated typos with known source targets.

Dictionary forms are weak clean labels, including initials/abbreviations. Never
infer a gold label from the autocorrector's answer. Split by intended word before
generating variants. Exclude tue and exact frozen evaluation triples from fitting.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import subprocess

import autocorrect_eval_v2 as core

PROJECT = Path(__file__).resolve().parents[1]
SEED = 'short-acceptance-20261029-v1'
ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']
POSITIONS = {c: (r, i) for r, row in enumerate(ROWS) for i, c in enumerate(row)}


def hashed(text):
    return hashlib.sha256((SEED + text).encode()).hexdigest()


def variants(word):
    result = set()
    for i, c in enumerate(word):
        result.add(word[:i] + word[i + 1:])
        result.add(word[:i] + c + word[i:])
        for letter, pos in POSITIONS.items():
            if letter != c and all(abs(a - b) <= 1 for a, b in zip(POSITIONS[c], pos)):
                result.add(word[:i] + letter + word[i + 1:])
        if i + 1 < len(word):
            result.add(word[:i] + word[i + 1] + c + word[i + 2:])
    return sorted(result - {'', word}, key=hashed)[:8]


def generate(dictionary, source, evaluation):
    forbidden = {(r['left'], r['typed'].casefold(), (r['target'] or '').casefold()) for r in evaluation}
    words = sorted(w for w in dictionary if w.isascii() and w.isalpha() and len(w) <= 3)
    # Include single-letter words explicitly: the dictionary's label may differ
    # from whether the current model exposes them as English word tokens.
    authentic = collections.defaultdict(set)
    for row in source:
        if row['fit_split'] == 'fit' and row['typed'].casefold() == row['target'].casefold() and row['left'].strip():
            authentic[row['target'].casefold()].add(row['left'])
    result = []
    for word in words:
        if word == 'tue':
            continue
        bucket = int(hashed(word)[:8], 16) % 10
        split = 'fit' if bucket < 8 else 'calibration' if bucket == 8 else 'selection'
        # Literal-reporting contexts make an intentional abbreviation coherent.
        # Natural contexts additionally exercise actual sentence evidence.
        contexts = ['', 'I typed ', 'the abbreviation is ', 'I wrote ', 'I meant ',
                    'the word is ', 'the code is ', 'it says ', 'the label is ',
                    'the initials are ', 'it is called ']
        contexts += sorted(authentic[word], key=hashed)[:2]
        for left in contexts:
            corruptions = variants(word) if left in ['', 'I typed ', 'the abbreviation is '] or left in authentic[word] else []
            for typed in [word] + corruptions:
                if typed == 'tue' or (left, typed, word) in forbidden:
                    continue
                clean = typed == word
                # Without sentence evidence, another valid word is ambiguous.
                # Keep real-word typo supervision in authentic source contexts.
                if not clean and typed in dictionary and left not in authentic[word]:
                    continue
                ident = hashed(json.dumps([left, typed, word]))
                result.append(dict(id='short:' + ident, left=left, typed=typed, target=word,
                                   fit_split=split, source='clean_short' if clean else 'generated_short',
                                   noise=False, category='short_preservation' if clean else 'short_generated_typo',
                                   target_length=len(word), typed_in_dictionary=typed in dictionary,
                                   context_kind='blank' if not left else 'authentic' if left in authentic[word] else 'literal_report'))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--source', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/cases.jsonl')
    parser.add_argument('--evaluation', type=Path, default=PROJECT / 'build/autocorrect-eval-v4/frozen/cases.jsonl')
    args = parser.parse_args()
    dictionary_path = PROJECT.parent / 'QwenASR/crates/cantonese-ngram/src/english_valid_words.txt'
    dictionary = set(dictionary_path.read_text().splitlines())
    cases = generate(dictionary, core.rows(args.source), core.rows(args.evaluation))
    args.output.mkdir(parents=True, exist_ok=False)
    core.jsonl(args.output / 'cases.jsonl', cases)
    proc = subprocess.run([str(args.probe.resolve()), str(args.model.resolve())],
                          input=core.native_requests(cases), text=True,
                          capture_output=True, check=True, timeout=600)
    outputs = [json.loads(line) for line in proc.stdout.splitlines()]
    if len(outputs) != len(cases):
        raise ValueError('Native output count mismatch')
    core.jsonl(args.output / 'native-outputs.jsonl', outputs)
    clean_changes, useful = [], []
    counts = collections.Counter()
    for c, o in zip(cases, outputs, strict=True):
        clean = c['typed'] == c['target']
        changed = bool(o['correction'] and o['correction'].casefold() != c['typed'])
        hit = changed and o['correction'].casefold() == c['target']
        counts[f"{c['fit_split']}:{c['target_length']}:{c['context_kind']}:{'clean' if clean else 'typo'}"] += 1
        if clean and changed:
            clean_changes.append(dict(c, correction=o['correction']))
        if not clean and hit:
            useful.append(dict(c, correction=o['correction']))
    core.jsonl(args.output / 'clean-false-corrections.jsonl', clean_changes)
    core.jsonl(args.output / 'useful-corrections.jsonl', useful)
    report = dict(schema='short-acceptance-mining-v1', seed=SEED, cases=len(cases),
                  clean_false_corrections=len(clean_changes), useful_generated_corrections=len(useful),
                  counts=dict(counts), dictionary_sha256=core.digest(dictionary_path),
                  source_sha256=core.digest(args.source), evaluation_sha256=core.digest(args.evaluation),
                  probe_sha256=core.digest(args.probe), model_sha256=core.digest(args.model),
                  cases_sha256=core.digest(args.output / 'cases.jsonl'),
                  labels='Intentional dictionary forms and deterministic corruption source targets; model answers are observations, never labels',
                  limitations='Dictionary controls include uncommon abbreviations; synthetic errors do not estimate natural typo frequencies')
    core.write_json(args.output / 'report.json', report)
    print(json.dumps({k: report[k] for k in ['cases', 'clean_false_corrections', 'useful_generated_corrections']}))


if __name__ == '__main__':
    main()
