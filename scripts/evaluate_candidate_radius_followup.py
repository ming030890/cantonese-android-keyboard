#!/usr/bin/env python3
"""Evaluate the declared stable-evidence radius follow-up on selection only."""
import argparse
import json
from pathlib import Path

import autocorrect_eval_v2 as core
from experiment_candidate_radius import ROOT, closed_loop, run_requests


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    assert not (args.output/'selection-report.json').exists()
    pilot=ROOT/'build/candidate-radius-20261030'
    cases=core.rows(pilot/'snapshot-selection.jsonl')
    recorded=core.rows(pilot/'recorded-selection.jsonl')
    trials=[t for t in core.rows(ROOT/'build/real-revisit-20261025/data3/trials.jsonl') if t['split']=='selection']
    assert len({c['id'] for c in recorded})==len(recorded)
    assert len({t['id'] for t in trials})==len(trials)
    model=ROOT/'app/src/main/assets/language_model/model.cng'
    report=dict(probe_sha256=core.digest(args.probe),script_sha256=core.digest(__file__),
                pilot_selection_sha256=core.digest(pilot/'selection-report.json'),profiles={})
    for profile in ('parent','combined_stable'):
        requests=[dict(left=c['left'],typed=c['typed'],spelling_limit=8,timing=True) for c in cases]
        outputs=run_requests(args.probe.resolve(),model,requests,profile)
        live=closed_loop(args.probe.resolve(),model,recorded,trials,profile)
        core.jsonl(args.output/(profile+'-snapshot-outputs.jsonl'),outputs)
        core.jsonl(args.output/(profile+'-closed-loop-outputs.jsonl'),live)
        if profile=='parent':
            for name,aa in [('snapshot',outputs),('closed-loop',live)]:
                bb=core.rows(pilot/('parent-'+name+'-outputs.jsonl'))
                norm=lambda o:{k:v for k,v in o.items() if k!='elapsedMicros'}
                assert all(norm(a)==norm(b) for a,b in zip(aa,bb,strict=True))
            report['isolated_parent_parity_mismatches']=0
        values=dict(snapshot=core.metrics(cases,outputs),closed_loop=core.metrics(recorded,live))
        values['selection_score']=(values['snapshot']['f05']+values['closed_loop']['f05'])/2
        report['profiles'][profile]=values
        print(profile,json.dumps(values),flush=True)
    report['winner']=max(report['profiles'],key=lambda v:report['profiles'][v]['selection_score'])
    core.write_json(args.output/'selection-report.json',report)
    print('SELECTION WINNER:',report['winner'],flush=True)


if __name__=='__main__':
    main()
