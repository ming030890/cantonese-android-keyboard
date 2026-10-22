#!/usr/bin/env python3
"""Verify experimental native parity, mapping invariance and no-touch behavior.

Uses selection participants only, and the existing frozen Aalto regression data.
Does not open TSI evaluation participants for scoring or tune any parameters.
"""
import argparse
import json
import statistics
import subprocess
from pathlib import Path

import numpy as np
from fit_tsi_touch import (PROJECT, ENGINE, read_data, cases, digest, replay, metrics,
                           touch_delta)
from iterate_unambiguous_neighbors import load_real
from iterate_english_policy import split


def probe(binary, requests, touch=None):
    args = [str(binary), str(PROJECT/'app/src/main/assets/language_model/model.cng')]
    if touch:
        args += ['--touch-model', str(touch)]
    proc = subprocess.run(args, input=''.join(json.dumps(r)+'\n' for r in requests),
                          text=True, capture_output=True, check=True, timeout=600)
    rows = [json.loads(line) for line in proc.stdout.splitlines()]
    assert len(rows) == len(requests)
    return rows


def without_time(rows):
    return [{k:v for k,v in r.items() if k != 'elapsedMicros'} for r in rows]


def latency(rows):
    times = [r['elapsedMicros']/1000 for r in rows]
    return dict(calls=len(times), median_ms=statistics.median(times), p95_ms=float(np.percentile(times,95)))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--raw',type=Path,default=PROJECT/'build/tsi-touch/raw')
    p.add_argument('--report-dir',type=Path,default=PROJECT/'config/english_iteration/2026-10-22/tsi-touch')
    a=p.parse_args()
    model_path=a.report_dir/'touch-model.json'
    model=json.loads(model_path.read_text())
    frozen=json.loads((a.report_dir/'selection.json').read_text())
    assert frozen['touch_model_sha256']==digest(model_path)
    assert frozen['probe_sha256']==digest(a.baseline)
    layout,rows=read_data(a.raw)
    words,_=cases(layout,rows,a.raw)
    words=[c for c in words if c['split']=='selection']
    requests=[dict(left=c['left'],typed=c['typed'],spelling_limit=4096,
                   touch_geometry=c['geometry'],touch_points=c['points']) for c in words]
    before=probe(a.baseline,requests)
    actual=probe(a.probe,requests,model_path)
    policy=json.loads((ENGINE/'english_acceptance.json').read_text())
    expected=replay(model,policy,words,before,model['weight'])
    assert expected==[r['correction'] for r in actual], 'Native decision mismatch'
    errors=[]
    for c,b,n in zip(words,before,actual):
        reference={r['word']:r for r in b['candidates']}
        assert set(reference)=={r['word'] for r in n['candidates']}, 'Candidate pool changed'
        for r in n['candidates']:
            old=reference[r['word']]
            delta=0. if b['apostropheEvidence'] else touch_delta(model,c['typed'],r['word'],c['points'],c['geometry'],r['edits'],r['completedChars'])
            score=float(np.float32(old['score'])+np.float32(model['weight'])*np.float32(delta))
            errors.append(abs(score-r['score']))
    assert max(errors)<1e-5, max(errors)
    transformed=[]
    for request in requests:
        g=request['touch_geometry'];points=request['touch_points']
        transformed.append(dict(request,
            touch_geometry=[v for i in range(0,len(g),4) for v in [g[i]*2+500,g[i+1]*3-200,g[i+2]*2,g[i+3]*3]],
            touch_points=[v for i in range(0,len(points),2) for v in [points[i]*2+500,points[i+1]*3-200]]))
    mapped=probe(a.probe,transformed,model_path)
    assert without_time(mapped)==without_time(actual), 'Coordinate mapping changed results'
    missing=[{k:v for k,v in r.items() if k not in ('touch_geometry','touch_points')} for r in requests]
    assert without_time(probe(a.probe,missing,model_path))==without_time(before), 'Missing-touch fallback changed'
    # Full frozen English cohort, including controls and missing candidates.
    real,*_=load_real(PROJECT)
    real=[c for c in real if split(c['group'])=='evaluation']
    assert len(real)==2042
    real_requests=[dict(left=c['left'],typed=c['typed']) for c in real]
    old=probe(a.baseline,real_requests)
    new=probe(a.probe,real_requests,model_path)
    assert without_time(old)==without_time(new), 'Aalto no-touch regression'
    # Warm release-host engine timing. Reverse order on the second run.
    timing_requests=[dict(r,spelling_limit=8,timing=True) for r in requests[:256]]
    text_times=[];touch_times=[]
    for order in [False,True]:
        for enabled in ([True,False] if order else [False,True]):
            out=probe(a.probe,timing_requests,model_path if enabled else None)
            (touch_times if enabled else text_times).extend(out[16:])
    report=dict(native_decision_mismatches=0,selection_cases=len(words),candidate_pool_mismatches=0,
                max_score_error=max(errors),mapping_invariance_cases=len(words),missing_touch_parity_cases=len(words),
                aalto_no_touch_parity_cases=len(real),aalto_metrics=metrics(real,[r['correction'] for r in new]),
                selection_baseline=metrics(words,[r['correction'] for r in before]),
                selection_touch=metrics(words,[r['correction'] for r in actual]),
                latency=dict(boundary='Warm release-host engine calls; excludes phone scheduling/rendering',
                             no_touch=latency(text_times),touch=latency(touch_times)),
                hashes={str(f):digest(f) for f in [a.baseline,a.probe,model_path,Path(__file__)]})
    (a.report_dir/'native-verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
