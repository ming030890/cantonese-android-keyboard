#!/usr/bin/env python3
"""Compare native apostrophe restoration on frozen groups, selection before eval."""
import argparse
import csv
import json
from pathlib import Path
from align_mobile_targets import reference_target
from audit_candidate_pipeline import digest, probe
from iterate_english_policy import metric, split


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--evaluate', action='store_true')
    p.add_argument('--frozen-selection', type=Path)
    a = p.parse_args()
    if a.evaluate and not a.frozen_selection:
        p.error('--evaluate requires --frozen-selection')
    root = Path(__file__).resolve().parents[1]
    iteration = root/'build/english-iteration-2026-10-20'
    model = root/'app/src/main/assets/language_model/model.cng'
    source = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    refs = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(source.open())}
    cases = []
    for c in map(json.loads, (iteration/'cases.jsonl').open()):
        if c['category'].startswith('prefix_'): continue
        target = c['target'] if c['category']=='source_preservation' else reference_target(c, refs[c['group']])
        if target is not None: cases.append(dict(c, target=target))
    baseline = json.loads((iteration/'literal-native-verification.predictions.json').read_text())
    assert [c['id'] for c in cases] == baseline['case_ids']
    binding = dict(probe=digest(a.probe),model=digest(model),cases=digest(iteration/'cases.jsonl'),
                   policy=digest(root.parent/'QwenASR/crates/cantonese-ngram/src/english_acceptance.json'),
                   forms=digest(root.parent/'QwenASR/crates/cantonese-ngram/src/english_apostrophe_forms.json'),
                   apostrophe_policy=digest(root.parent/'QwenASR/crates/cantonese-ngram/src/english_apostrophe_acceptance.json'))
    cache_path = root/'build/apostrophe-native-cache.json'
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}
    if cache.get('binding') != binding:
        print(f'Scoring {len(cases)} aligned cases', flush=True)
        outputs = probe(a.probe, model, [dict(left=c['left'],typed=c['typed'],spelling_limit=4096) for c in cases])
        cache = dict(binding=binding,outputs=outputs)
        cache_path.write_text(json.dumps(cache))
    outputs = cache['outputs']
    old = baseline['outputs']
    def metrics(values, part):
        return metric(cases,values,[o['candidates'] for o in values],[o['correction'] for o in values],part)
    report = dict(binding=binding,train=metrics(outputs,'train'),selection=metrics(outputs,'selection'),
                  baseline_selection=metrics(old,'selection'),
                  selection_changes=[dict(id=c['id'],typed=c['typed'],target=c['target'],left=c['left'],
                                          before=b['correction'],after=n['correction'])
                                     for c,b,n in zip(cases,old,outputs)
                                     if split(c['group'])=='selection' and b['correction']!=n['correction']])
    if a.evaluate:
        frozen=json.loads(a.frozen_selection.read_text())
        assert frozen==report, 'Candidate changed after selection freeze'
        report.update(evaluation=metrics(outputs,'evaluation'),baseline_evaluation=metrics(old,'evaluation'),
                      frozen_selection_sha256=digest(a.frozen_selection))
    a.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__': main()
