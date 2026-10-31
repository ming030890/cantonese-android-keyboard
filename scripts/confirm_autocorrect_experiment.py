#!/usr/bin/env python3
"""One confirmation pass for the choice frozen after development + legacy gate."""
import argparse
import json
import os
from pathlib import Path

import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import infer,metrics,eligible
from check_autocorrect_regressions import check

ROOT=Path(__file__).resolve().parents[1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite',type=Path,required=True)
    p.add_argument('--experiment',type=Path,required=True)
    p.add_argument('--baseline-probe',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    development=json.loads((args.experiment/'development-report.json').read_text())
    choice='candidate-w0.25'
    assert development['profiles'][choice]['eligible']
    legacy=json.loads((args.experiment/'legacy-next-eligible/score.json').read_text())
    assert legacy['delta']>0
    # The two gate-fitting studies independently selected the unchanged parent.
    studies=[ROOT/'build/bilingual-acceptance-20261030'/name/'development-report.json'
             for name in ('column-study','calibrated-study','full-study')]
    assert all(json.loads(p.read_text())['winner']=='parent' for p in studies)
    binary=args.experiment/'target/release/ngram_probe'
    model=ROOT/'app/src/main/assets/language_model/model.cng'
    preflight=check(binary.resolve(),model,dict(os.environ,KEYBOARD_EVAL_RADIUS='candidate',KEYBOARD_EVAL_ENGLISH_WEIGHT='.25'))
    core.write_json(args.output/'preflight.json',preflight)
    if not preflight['passed']:
        raise SystemExit('Known regression preflight failed; confirmation was not opened')
    protocol=dict(schema='autocorrect-confirmation-v1',script_sha256=core.digest(__file__),
        choice=choice,radius='candidate',weight=.25,
        selection='Highest supplemental-dev Pareto-eligible profile that passes unchanged legacy objective; all fitted gate variants rejected',
        development_report_sha256=core.digest(args.experiment/'development-report.json'),
        rejected_study_hashes={str(p):core.digest(p) for p in studies},legacy_gate=legacy,
        probe_sha256=core.digest(binary),baseline_probe_sha256=core.digest(args.baseline_probe),
        suite_protocol_sha256=core.digest(args.suite/'protocol.json'),
        decision='Every confirmation panel must retain baseline TP, FP and clean-damage budgets, with at least one additional correct fix; report uncertainty separately',
        native_preflight_passed=True,confirmation_previously_scored=False)
    core.write_json(args.output/'frozen-choice.json',protocol)
    suite=json.loads((args.suite/'protocol.json').read_text())
    path=args.suite/'confirmation.jsonl'
    assert core.digest(path)==suite['split_hashes']['confirmation']
    cases=core.rows(path);model=ROOT/'app/src/main/assets/language_model/model.cng'
    before=infer(args.baseline_probe.resolve(),model,cases,'accepted',0.)
    after=infer(binary.resolve(),model,cases,'candidate',.25)
    core.jsonl(args.output/'baseline-outputs.jsonl',before)
    core.jsonl(args.output/'candidate-outputs.jsonl',after)
    baseline,candidate=metrics(cases,before),metrics(cases,after)
    uncertainty={}
    changes=[]
    for panel in baseline:
        ids=[i for i,c in enumerate(cases) if c['panel']==panel]
        uncertainty[panel]=core.uncertainty([cases[i] for i in ids],[after[i] for i in ids],[before[i] for i in ids])
    for c,b,a in zip(cases,before,after):
        old,new=b['correction'] or c['typed'],a['correction'] or c['typed']
        if old!=new:changes.append(dict(id=c['id'],panel=c['panel'],typed=c['typed'],target=c['target'],before=old,after=new))
    report=dict(protocol=protocol,baseline=baseline,candidate=candidate,uncertainty=uncertainty,
        guard_passed=eligible(candidate,baseline),
        additional_correct=sum(v['tp']-baseline[k]['tp'] for k,v in candidate.items()),
        changed_cases=changes,now_exposed=True,
        limitation='Source-disjoint supplemental confirmation, not spontaneous bilingual phone typing or an audit of all inherited LM exposure')
    report['candidate_qualified']=report['guard_passed'] and report['additional_correct']>0
    core.write_json(args.output/'report.json',report)
    print(json.dumps({k:v for k,v in report.items() if k not in ('protocol','changed_cases','uncertainty')},indent=2))


if __name__=='__main__':main()
