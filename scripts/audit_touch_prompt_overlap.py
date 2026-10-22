#!/usr/bin/env python3
"""Audit text overlap after scoring; never exclude cases or tune the model."""
import argparse
import csv
import json
import re
from pathlib import Path

from fit_tsi_touch import PROJECT, digest
from public_touch_eval import dump


def normalize(text):
    return ' '.join(re.findall('[a-z]+',text.lower()))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sentences',type=Path,default=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv'))
    p.add_argument('--synthetic',type=Path,default=PROJECT/'build/touch-eval-public/synthetic/cases.jsonl')
    p.add_argument('--trials',type=Path,default=PROJECT/'build/touch-eval-public/prepared/trials.jsonl')
    p.add_argument('--output',type=Path,default=PROJECT/'config/english_iteration/2026-10-22/public-touch/overlap-audit.json')
    a=p.parse_args()
    groups={json.loads(line)['group'] for line in a.synthetic.open()}
    with a.sentences.open() as f:
        refs={r['SENTENCE_ID']:normalize(r['SENTENCE']) for r in csv.DictReader(f) if r['SENTENCE_ID'] in groups}
    trials=[json.loads(line) for line in a.trials.open()]
    shared=[t for t in trials if normalize(t['reference']) in set(refs.values())]
    report=dict(synthetic_prompt_groups=len(groups),resolved_prompt_groups=len(refs),real_trials=len(trials),
                shared_trials=len(shared),shared_unique_reference_texts=len({normalize(t['reference']) for t in shared}),
                real_unique_reference_texts=len({normalize(t['reference']) for t in trials}),
                interpretation='Real touch events were never training inputs. Text overlap limits prompt-independent generalization. Do not remove evaluation cases or refit using this audit.',
                hashes={str(f):digest(f) for f in [a.sentences,a.synthetic,a.trials,Path(__file__)]})
    dump(a.output,report)
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
