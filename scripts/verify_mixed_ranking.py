#!/usr/bin/env python3
"""Check compiled ranking and actual retrieval on the frozen SSD cohort.

Uses every frozen row; never refits weights. Timings are host engine calls,
excluding Android scheduling and rendering. Quick search and mixed scoring are
reported separately because the first selectable short-code preview is earlier.
"""
import argparse
import json
import statistics
from pathlib import Path

from audit_candidate_pipeline import digest, probe
from fit_mixed_ranking import metrics


def summary(values):
    return dict(n=len(values), median_us=statistics.median(values),
                p95_us=sorted(values)[int(len(values) * .95)])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    assets = root / 'app/src/main/assets'
    model = assets / 'language_model/model.cng'
    frozen = root / 'build/mixed-ranking-features.json'
    cache = json.loads(frozen.read_text())
    rows = cache['rows']
    policy = json.loads((root.parent / 'QwenASR/crates/cantonese-ngram/src/mixed_ranking.json').read_text())
    assert policy['cohort_sha256'] == digest(frozen)
    assert policy['model_sha256'] == digest(model)
    requests = [dict(mixed=r['candidates'], observed=r['observed'], left=r['left'], timing=True) for r in rows]
    actual = probe(a.probe, model, requests)
    for r, features, out in zip(rows, cache['features'], actual):
        expected = [sum(w * f for w, f in zip(policy['weights'], fs)) for fs in features]
        assert max(abs(x-y) for x,y in zip(expected, out['scores'])) < 1e-5, r['group']
        assert sorted(range(len(expected)), key=lambda i: (-expected[i], i)) == out['order'], r['group']
    print('Compiled scores/order match frozen features on all rows', flush=True)
    extra = ('-', assets/'handwriting/labels.txt', assets/'quick/quick3.bin', assets/'quick/quick5.bin')
    quick_requests = [dict(code=r['observed'], version=r['version'], left=r['left'], timing=True) for r in rows]
    before = probe(a.before, model, quick_requests, extra=extra)
    after = probe(a.probe, model, quick_requests, extra=extra)
    print('Actual Quick candidate generation complete', flush=True)
    pools = []
    for r, out in zip(rows, after):
        # Same English alternatives as frozen, unchanged spelling pipeline.
        english = [c for c in r['candidates'] if c.isascii()]
        pools.append(list(dict.fromkeys(out['candidates'] + english)))
    ranked = probe(a.probe, model, [dict(mixed=cs, observed=r['observed'], left=r['left'], timing=True)
                                  for r, cs in zip(rows, pools)])
    orders = [[cs[i] for i in out['order']] for cs,out in zip(pools,ranked)]
    shortlists=[]
    for r in rows:
        cs=r['candidates'];n=next((i for i,c in enumerate(cs) if c.isascii()),len(cs))
        shortlists.append(dict(mixed=cs[:8]+cs[n:],observed=r['observed'],left=r['left'],timing=True))
    shortlist_scored=probe(a.probe,model,shortlists)
    report = dict(model_sha256=digest(model), probe_sha256=digest(a.probe), before_sha256=digest(a.before),
        cohort_sha256=digest(frozen), compiled_parity_rows=len(rows),
        quick_candidate_sets_changed=sum(set(b['candidates']) != set(c['candidates']) for b,c in zip(before,after)),
        actual_retrieval={s:metrics(rows,orders,s) for s in ('train','selection','evaluation')},
        evaluation_by_language={lang:metrics([r for r in rows if r['language']==lang],
            [o for r,o in zip(rows,orders) if r['language']==lang], 'evaluation') for lang in ('chinese','english')},
        latency=dict(method='One sequential process per engine over 256 fixed SSD rows; engine calls, not phone latency. Cold first calls included.',
            quick_before=summary([o['elapsedMicros'] for o in before]),
            quick_after=summary([o['elapsedMicros'] for o in after]),
            mixed_full_pool=summary([o['elapsedMicros'] for o in ranked]),
            same_engine_old_shortlist=summary([o['elapsedMicros'] for o in shortlist_scored]),
            shortlist_caveat='Same current engine with old top-eight Chinese pool; isolates pool size, not a paired old-binary benchmark. Measurements from separate passes.'))
    a.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
