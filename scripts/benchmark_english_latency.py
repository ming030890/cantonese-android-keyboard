#!/usr/bin/env python3
"""Paired release-host spelling latency; excludes startup and Android UI."""
import argparse
import json
import statistics
from pathlib import Path

from audit_candidate_pipeline import digest, probe


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--after', type=Path, required=True)
    p.add_argument('--cases', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--count', type=int, default=256)
    p.add_argument('--rounds', type=int, default=4)
    p.add_argument('--allow-candidate-changes', action='store_true')
    args = p.parse_args()
    model = Path(__file__).resolve().parents[1]/'app/src/main/assets/language_model/model.cng'
    cases = sorted((json.loads(l) for l in args.cases.open() if not json.loads(l)['category'].startswith('prefix_')),key=lambda c:c['id'])[:args.count]
    requests = [dict(left=c['left'], typed=c['typed'], spelling_limit=8, timing=True) for c in cases]
    times = dict(before=[],after=[])
    rounds = []
    comparisons = 0
    changed = 0
    for run in range(args.rounds):
        row = {}
        predictions = {}
        for name in (('before','after') if run%2==0 else ('after','before')):
            outputs = probe(getattr(args,name),model,requests[:16]+requests)
            values = [r['elapsedMicros'] for r in outputs[16:]]
            predictions[name] = [[r['word'] for r in out['candidates']] for out in outputs[16:]]
            times[name].extend(values)
            row[name] = dict(median_us=statistics.median(values),p95_us=sorted(values)[int(.95*len(values))])
        rounds.append(row)
        changes = sum(a != b for a,b in zip(predictions['before'],predictions['after']))
        if not args.allow_candidate_changes:
            assert not changes, 'Candidate generation/ranking changed'
        changed += changes
        comparisons += len(cases)
        print(f'Round {run+1}: {row}',flush=True)
    result = dict(method='Release host engine calls, 16 warmup requests per process, alternating process order; not Android input-to-display',
                  count=len(cases), candidate_lists_identical=comparisons-changed, candidate_lists_changed=changed, rounds=rounds, case_ids=[c['id'] for c in cases],
                  source_sha256={str(f):digest(f) for f in (Path(__file__),args.before,args.after,args.cases,model)},
                  summary={name:dict(samples=len(v),median_us=statistics.median(v),p95_us=sorted(v)[int(.95*len(v))]) for name,v in times.items()})
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result['summary'],indent=2))


if __name__=='__main__':
    main()
