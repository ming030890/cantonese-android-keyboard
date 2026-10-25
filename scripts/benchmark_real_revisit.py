#!/usr/bin/env python3
"""Fresh no-query-cache replay of recorded touch evaluation, native CPU cost."""
import argparse
import json
from pathlib import Path

import autocorrect_eval_v2 as core
import experiment_real_revisit as real
import experiment_revisit_policy as feature
import replay_context_revision as old
from experiment_context_revision_refresh import Costs, cost_summary


def run(a):
    a.output.mkdir(parents=True,exist_ok=False)
    source=json.loads((a.run/'report.json').read_text())
    previous=json.loads((a.previous/'report.json').read_text())
    for name,p in [('probe',a.probe),('policy',a.policy),('model',a.model)]:
        assert core.digest(p)==source['binding']['source_hashes'][name]
    trials=core.rows(a.suite/'trials.jsonl')
    cases={c['id']:c for c in core.rows(a.suite/'cases.jsonl')}
    report=dict(source_report_sha256=core.digest(a.run/'report.json'),script_sha256=core.digest(__file__),
                scope='Mac native CPU work, fresh process per mode, no query cache; no phone or editor deadline claim',modes={})
    for mode in ['no_revisit','incumbent','simple']:
        directory=a.output/mode;directory.mkdir()
        native=old.Probe(a.probe,a.model,directory)
        probe=Costs(native)
        acceptance=old.Acceptance(a.policy)
        cc_all,ww_all,revisions=[],[],[]
        try:
            for trial in trials:
                cc=[cases[k] for k in trial['words']]
                class Marked(list):
                    def __iter__(self):
                        for c in list.__iter__(self):
                            probe.next_is_boundary=True
                            yield c
                if mode=='incumbent':
                    ww,changes=old.replay_trial(probe,acceptance,Marked(cc),previous['incumbent_rule'])
                else:
                    ww,changes=real.replay(probe,acceptance,Marked(cc),source['rule'] if mode=='simple' else None)
                cc_all.extend(cc);ww_all.extend(ww);revisions.extend(changes)
        finally:
            native.close()
        metrics=feature.metrics(cc_all,ww_all)
        expected=source['results'][mode]['touch_metrics']
        for key in ['tp','fp','clean_changes','clean','typos','unlabelled']:
            assert metrics[key]==expected[key],(mode,key)
        report['modes'][mode]=dict(metrics=metrics,cost=cost_summary(probe.boundaries),revisions=len(revisions))
        core.jsonl(directory/'boundary-costs.jsonl',probe.boundaries)
        print(mode,json.dumps(report['modes'][mode]),flush=True)
    core.write_json(a.output/'report.json',report)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True)
    p.add_argument('--previous',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--policy',type=Path,required=True)
    p.add_argument('--model',type=Path,default=core.PROJECT/'app/src/main/assets/language_model/model.cng')
    p.add_argument('--suite',type=Path,default=core.PROJECT/'build/autocorrect-eval-v4/frozen')
    run(p.parse_args())
