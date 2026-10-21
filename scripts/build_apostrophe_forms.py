#!/usr/bin/env python3
"""Export TRAIN-attested apostrophe forms missing from the shared word vocabulary."""
import argparse
import collections
import csv
import hashlib
import json
import re
import struct
from pathlib import Path
from iterate_english_policy import split
from audit_candidate_pipeline import digest


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--iteration', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--report', type=Path, required=True)
    args = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    model = root/'app/src/main/assets/language_model/model.cng'
    raw = model.read_bytes()
    meta = json.loads(raw[12:12+struct.unpack('<I', raw[8:12])[0]])
    vocabulary = {meta['tokens'][i].lower() for i in meta['english_word_ids']}
    cases = args.iteration/'cases.jsonl'
    groups = {c['group'] for c in map(json.loads, cases.open())
              if not c['category'].startswith('prefix_') and split(c['group']) == 'train'}
    source = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    counts = collections.Counter()
    bare = collections.Counter()
    for row in csv.DictReader(source.open()):
        if row['SENTENCE_ID'] not in groups:
            continue
        for word in re.findall(r"[A-Za-z]+(?:['’][A-Za-z]+)*", row['SENTENCE']):
            word = word.lower().replace('’', "'")
            bare[word] += 1
            if "'" in word and word.replace("'", '') in vocabulary and word not in vocabulary:
                counts[word] += 1
    forms = [dict(word=w, count=counts[w], bare_count=bare[w.replace("'", '')]) for w in sorted(counts)]
    args.output.write_text(json.dumps(forms, indent=2)+'\n')
    report = dict(forms=counts, groups=len(groups), partition='train only; unique prompts',
                  groups_sha256=hashlib.sha256(('\n'.join(sorted(groups))+'\n').encode()).hexdigest(),
                  model_sha256=digest(model), cases_sha256=digest(cases), source_sha256=digest(source),
                  output_sha256=digest(args.output))
    args.report.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
