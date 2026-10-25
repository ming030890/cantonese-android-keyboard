#!/usr/bin/env python3
"""Fresh native cost for predeclared distance ablations; never picks distance."""
import argparse
import json
from pathlib import Path

import autocorrect_eval_v2 as core
import experiment_revisit_distance as distance
import experiment_revisit_policy as feature
import replay_context_revision as old
from experiment_context_revision_refresh import Costs, cost_summary


def run(a):
    a.output.mkdir(parents=True,exist_ok=False)
    source=json.loads((a.run/'report.json').read_text())
    for key,p in [('probe',a.probe),('model',a.model),('policy',a.policy)]:
        assert core.digest(p)==source['protocol']['source_hashes'][key]
    cases={c['id']:c for c in core.rows(a.suite/'cases.jsonl')}
    trials=core.rows(a.suite/'trials.jsonl')
    report=dict(source_report_sha256=core.digest(a.run/'report.json'),script_sha256=core.digest(__file__),
                scope='Fresh native process per distance; no Python query cache; Mac CPU work, not phone key latency',results=[])
    for expected in source['evaluation']:
        value=expected['distance']
        name=str(value) if value is not None else 'unbounded'
        directory=a.output/name;directory.mkdir()
        native=old.Probe(a.probe,a.model,directory)
        probe=Costs(native)
        acceptance=old.Acceptance(a.policy)
        cc_all,ww_all=[],[]
        try:
            for trial in trials:
                cc=[cases[k] for k in trial['words']]
                class Marked(list):
                    def __iter__(self):
                        for c in list.__iter__(self):
                            probe.next_is_boundary=True
                            yield c
                words,_=distance.replay(probe,acceptance,Marked(cc),source['protocol']['fixed_policy'],value)
                cc_all.extend(cc);ww_all.extend(words)
        finally:
            native.close()
        metrics=feature.metrics(cc_all,ww_all)
        assert metrics==expected['metrics']
        costs=cost_summary(probe.boundaries)
        assert costs['sequence_calls']==expected['requests']['revisit']
        result=dict(distance=value,metrics=metrics,cost=costs)
        report['results'].append(result)
        core.jsonl(directory/'boundary-costs.jsonl',probe.boundaries)
        print(json.dumps(result),flush=True)
    core.write_json(a.output/'report.json',report)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--policy',type=Path,required=True)
    p.add_argument('--model',type=Path,default=core.PROJECT/'app/src/main/assets/language_model/model.cng')
    p.add_argument('--suite',type=Path,default=core.PROJECT/'build/autocorrect-eval-v4/frozen')
    run(p.parse_args())
