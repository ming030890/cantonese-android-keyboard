#!/usr/bin/env python3
"""Paired first-call and warm-call latency for the lexical fallback path."""
import argparse
import json
from pathlib import Path
import statistics

from audit_candidate_pipeline import digest, probe


def stats(values):
    ordered = sorted(values)
    return dict(samples=len(values), median_us=statistics.median(values), p95_us=ordered[int(.95*len(ordered))])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--after', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    model = Path(__file__).resolve().parents[1]/'app/src/main/assets/language_model/model.cng'
    request = dict(left='', typed='gwme', spelling_limit=8, timing=True)
    cold, warm, rounds = {n: [] for n in ('before', 'after')}, {n: [] for n in ('before', 'after')}, []
    for run in range(4):
        row = {}
        for name in (('before','after') if run%2==0 else ('after','before')):
            outputs = probe(getattr(args,name), model, [request]*65)
            cold[name].append(outputs[0]['elapsedMicros'])
            warm[name].extend(o['elapsedMicros'] for o in outputs[1:])
            row[name] = dict(first_call_us=cold[name][-1], warm=stats([o['elapsedMicros'] for o in outputs[1:]]))
            assert outputs[0]['correction'] == ('game' if name=='after' else None)
        rounds.append(row)
    report = dict(method='Four alternating release-host process pairs; first engine call includes lazy lexical '
                  'index initialization but excludes process start/model load. 64 repeated warm calls per process. '
                  'Not Android input-to-display latency. Run without other verification workloads.',
                  request=request, first_call={n:stats(v) for n,v in cold.items()},
                  warm={n:stats(v) for n,v in warm.items()}, rounds=rounds,
                  hashes={str(f):digest(f) for f in (Path(__file__),args.before,args.after,model)})
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('first_call','warm')},indent=2))


if __name__ == '__main__':
    main()
