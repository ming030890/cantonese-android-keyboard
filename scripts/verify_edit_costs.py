#!/usr/bin/env python3
"""Verify frozen edit-cost predictions through the production native scorer."""
import argparse
import json
from pathlib import Path

from audit_candidate_pipeline import digest, probe
from analyze_single_edit_root_cause import metrics
from fit_edit_position_costs import prepare, predict, NAMES
import numpy as np
from iterate_unambiguous_neighbors import load_real, unique_neighbor
from iterate_english_policy import split


def compare(cases, before, after, lexicon):
    primary = [i for i,c in enumerate(cases) if c['typed'].lower()!=c['target']
               and unique_neighbor(c['typed'].lower(),lexicon)==c['target']]
    controls = [i for i,c in enumerate(cases) if c['typed'].lower()==c['target']]
    def summary(ids):
        return {name:metrics([cases[i] for i in ids],[outputs[i]['correction'] for i in ids])
                for name,outputs in (('before',before),('after',after))}
    return dict(all=summary(list(range(len(cases)))), primary=summary(primary), controls=summary(controls),
                by_length={f'{lo}-{hi}':summary([i for i in primary if lo<=len(cases[i]['typed'])<=hi])
                           for lo,hi in ((4,5),(6,7),(8,32))})


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--source',type=Path,required=True)
    p.add_argument('--selection',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    project=Path(__file__).resolve().parents[1]
    native=project.parent/'QwenASR/crates/cantonese-ngram/src'
    model=project/'app/src/main/assets/language_model/model.cng'
    policy_file=native/'english_acceptance.json'
    apostrophe_file=native/'english_apostrophe_acceptance.json'
    policy=json.loads(policy_file.read_text()); apostrophe=json.loads(apostrophe_file.read_text())
    lexicon={line.split()[0] for line in args.source.open() if len(line.split()[0])<=32}
    real,real_outputs,real_file,cases_file,refs_file=load_real(project)
    original_file=project/'build/single-edit-final-2026-10-21/predictions.jsonl'
    original=[json.loads(line) for line in original_file.open()]
    original_native_file=project/'build/single-edit-root-cause-2026-10-21/synthetic-native.json'
    original_outputs=json.loads(original_native_file.read_text())['outputs']
    fresh_file=project/'build/neighbor-repair-2026-10-21/native-verification.predictions.json'
    fresh_cache=json.loads(fresh_file.read_text())
    fresh,fresh_outputs=fresh_cache['fresh_cases'],fresh_cache['before']
    refit=project/'build/acceptance-refit-2026-10-21'
    generated=[json.loads(line) for line in (refit/'cases.jsonl').open() if json.loads(line)['source']=='generated']
    generated_outputs=json.loads((refit/'generated-native.json').read_text())['outputs']
    pairs=[(c,o) for c,o in zip(generated,generated_outputs) if split(c['group'])=='evaluation']
    generated,generated_outputs=map(list,zip(*pairs))
    datasets=[('real',real,real_outputs),('original',original,original_outputs),
              ('exposed_new_words',fresh,fresh_outputs),('held_out_word_groups',generated,generated_outputs)]
    frozen=json.loads(args.selection.read_text())['champion']
    costs=np.array([frozen['costs'][name] for name in NAMES])
    assert not frozen['recalibrate_cutoff'] and costs[-1]==0
    accepted_file=refit/'native-verification.predictions.json'
    accepted={p['name']:p['outputs'] for p in json.loads(accepted_file.read_text())}
    report=dict(runtime_change='Five existing edit costs only; acceptance coefficients/cutoff unchanged; no position term',panels={})
    total=0; raw=[]
    for name,cases,before in datasets:
        print(f'Checking {name}: {len(cases)} native decisions',flush=True)
        actual=probe(args.probe,model,[dict(left=c['left'],typed=c['typed'],spelling_limit=4096) for c in cases])
        x,lm,groups=prepare(cases,before,policy,apostrophe)
        expected=predict(x,lm,groups,costs,policy)[0]
        mismatches=[c['typed'] for c,a,b in zip(cases,actual,expected) if a['correction']!=b]
        assert not mismatches, (name,mismatches[:10])
        assert all(sorted(r['word'] for r in a['candidates'])==sorted(r['word'] for r in b['candidates']) for a,b in zip(actual,before))
        previous=accepted[name]
        if name=='real':
            ev=[i for i,c in enumerate(cases) if split(c['group'])=='evaluation']
            report['real_evaluation']={key:metrics([cases[i] for i in ev],[rows[i]['correction'] for i in ev])
                for key,rows in [('before',previous),('after',actual)]}
        ids=([i for i,c in enumerate(cases) if c['operation'] in ('qwerty_substitution','clean')]
             if name=='original' else list(range(len(cases))))
        report['panels'][name]=compare([cases[i] for i in ids],[previous[i] for i in ids],[actual[i] for i in ids],lexicon)
        total+=len(cases)
        raw.append(dict(name=name,outputs=actual))
    report.update(native_decisions=total,decision_mismatches=0,candidate_pool_changes=0,
                  hashes={str(f):digest(f) for f in (Path(__file__),args.probe,args.source,model,policy_file,apostrophe_file,
                          real_file,cases_file,refs_file,original_file,original_native_file,fresh_file,args.selection,accepted_file,
                          native/"cnn_spelling.rs",
                          refit/'cases.jsonl',refit/'generated-native.json')})
    raw_file=args.output.with_suffix('.predictions.json')
    raw_file.write_text(json.dumps(raw))
    report['predictions_sha256']=digest(raw_file)
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(native_decisions=total,panels={n:r['by_length']['4-5'] for n,r in report['panels'].items()}),indent=2))


if __name__=='__main__':main()
