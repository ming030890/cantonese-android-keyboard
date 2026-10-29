#!/usr/bin/env python3
"""Short-word supervision from authentic TRAIN sentence prefixes.

Known source targets label generated edits; native answers never supply labels.
Title-only vocabulary prefixes supply synthetic abbreviation controls only when
both the short and full forms lack lowercase tokens. No named abbreviation list.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import struct
import subprocess

import autocorrect_eval_v2 as core
from mine_short_acceptance import variants

PROJECT = Path(__file__).resolve().parents[1]
HOLDOUT = {'tue', 'ir'}
SEED = 'short-context-supervision-20261029-v2'


def generate(source, forms, dictionary, frozen):
    forbidden = {(r['left'], r['typed'].casefold(), (r['target'] or '').casefold()) for r in frozen}
    short_titles = {w for w, tokens in forms.items() if 2 <= len(w) <= 3 and w.isalpha()
                    and w.isascii() and w in dictionary and w not in tokens
                    and any(t.istitle() for t in tokens) and w not in HOLDOUT}
    rows = {}
    priority = dict(fit=0, calibration=1, selection=2)

    def add(parent, left, typed, target, category):
        if typed in HOLDOUT or target in HOLDOUT or (left, typed, target) in forbidden:
            return
        key = (left, typed, target)
        row = dict(id=hashlib.sha256((SEED + json.dumps(key)).encode()).hexdigest(),
                   left=left, typed=typed, target=target, source=category,
                   fit_split=parent['fit_split'], group=parent['group'], parent_id=parent['id'],
                   noise=False, context_kind='empty' if not left else 'authentic_or_truncated',
                   intended_length=len(target), real_word_error=typed != target and typed in dictionary)
        # Identical generated observations never occur across new split files.
        if key not in rows or priority[row['fit_split']] > priority[rows[key]['fit_split']]:
            rows[key] = row

    for parent in source:
        if parent['source'] != 'real' or parent['fit_split'] not in priority:
            continue
        target = parent['target'].casefold()
        if not target.isascii() or not target.isalpha() or target in HOLDOUT:
            continue
        left = parent['left']
        contexts = list(dict.fromkeys([left, '', ' '.join(left.split()[-1:]) + (' ' if left.split() else '')]))
        if 2 <= len(target) <= 3:
            for context in contexts:
                add(parent, context, target, target, 'authentic_short_clean')
                for typed in variants(target):
                    if len(typed) < 2:
                        continue
                    # A valid word without sentence evidence has unresolved intent.
                    if not context and typed in dictionary:
                        continue
                    add(parent, context, typed, target, 'authentic_short_typo')
        elif target in forms and target not in forms[target] and any(t.istitle() for t in forms[target]):
            for abbreviation in sorted(short_titles):
                if target.startswith(abbreviation):
                    for context in contexts:
                        add(parent, context, abbreviation, abbreviation, 'title_abbreviation_clean')
    return sorted(rows.values(), key=lambda r: r['id'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=PROJECT.parent / 'gboard-probe/improve-v3/cases.jsonl')
    parser.add_argument('--model', type=Path, default=PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--frozen', type=Path, default=PROJECT / 'build/autocorrect-eval-v4/frozen/cases.jsonl')
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with args.model.open('rb') as file:
        header = file.read(12)
        meta = json.loads(file.read(struct.unpack('<I', header[8:12])[0]))
    forms = collections.defaultdict(list)
    for i in meta['english_word_ids']:
        forms[meta['tokens'][i].casefold()].append(meta['tokens'][i])
    dictionary_path = PROJECT.parent / 'QwenASR/crates/cantonese-ngram/src/english_valid_words.txt'
    dictionary = set(dictionary_path.read_text().splitlines())
    rows = generate(core.rows(args.source), forms, dictionary, core.rows(args.frozen))
    args.output.mkdir(parents=True, exist_ok=False)
    core.jsonl(args.output / 'cases.jsonl', rows)
    proc = subprocess.run([str(args.probe.resolve()), str(args.model.resolve())], input=core.native_requests(rows),
                          text=True, capture_output=True, check=True, timeout=600)
    outputs = [json.loads(line) for line in proc.stdout.splitlines()]
    assert len(outputs) == len(rows)
    core.jsonl(args.output / 'native-outputs.jsonl', outputs)
    report = dict(schema='short-context-training-v2', seed=SEED, cases=len(rows),
                  counts=dict(collections.Counter(r['fit_split'] + ':' + r['source'] for r in rows)),
                  real_word_errors=sum(r['real_word_error'] for r in rows),
                  source_sha256=core.digest(args.source), model_sha256=core.digest(args.model),
                  dictionary_sha256=core.digest(dictionary_path), probe_sha256=core.digest(args.probe),
                  cases_sha256=core.digest(args.output / 'cases.jsonl'), script_sha256=core.digest(__file__),
                  holdout_surfaces=sorted(HOLDOUT),
                  limits='Synthetic corruptions and abbreviation controls retain known intent; they do not estimate natural typo frequency')
    core.write_json(args.output / 'report.json', report)
    print(json.dumps(report))


if __name__ == '__main__':
    main()
