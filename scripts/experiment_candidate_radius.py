#!/usr/bin/env python3
"""Bounded radius pilot in an isolated native source copy; no production edits.

Declare four profiles before inference and select using existing development
selection folds only. The unchanged parent is eligible. Exposed frozen results
are evaluated subsequently, never used to fit an acceptance policy here.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

import autocorrect_eval_v2 as core
from analyze_closed_loop_autocorrect import prefix, surface

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT.parent/'QwenASR/crates/cantonese-ngram'
PROFILES = ['parent','short','medium','long','combined']
OLD = 'let radius=if input.len()>=6 {3}else if input.len()>=4 {2}else{1};'
NEW = '''let original_radius=if input.len()>=6 {3}else if input.len()>=4 {2}else{1};
        // Host-only isolated diagnostic; this source copy is never packaged.
        let radius=match std::env::var("KEYBOARD_RADIUS_PILOT").as_deref() {
            Ok("short") if input.len()==3 => 2,
            Ok("medium") if (4..=5).contains(&input.len()) => 3,
            Ok("long") if (6..=12).contains(&input.len()) => 4,
            Ok("combined") if (3..=12).contains(&input.len()) => original_radius+1,
            _ => original_radius,
        };'''


def run_requests(binary,model,requests,profile):
    env = dict(os.environ,KEYBOARD_RADIUS_PILOT=profile)
    proc = subprocess.run([str(binary),str(model)],input=''.join(json.dumps(r)+'\n' for r in requests),
        text=True,capture_output=True,check=True,env=env,timeout=300)
    outputs = [json.loads(line) for line in proc.stdout.splitlines()]
    assert len(outputs)==len(requests)
    return outputs


def closed_loop(binary,model,cases,trials,profile):
    by_id = {c['id']:c for c in cases}
    env = dict(os.environ,KEYBOARD_RADIUS_PILOT=profile)
    outputs = {}
    with subprocess.Popen([str(binary),str(model)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE,text=True,env=env) as proc:
        for trial in trials:
            committed = []
            for identifier in trial['words']:
                c = by_id[identifier]
                request = dict(left=prefix(committed),typed=c['typed'],spelling_limit=8,timing=True)
                proc.stdin.write(json.dumps(request)+'\n');proc.stdin.flush()
                out = json.loads(proc.stdout.readline())
                outputs[identifier] = out
                committed.append(surface(out.get('correction'),c['typed']))
        proc.stdin.close()
        assert proc.wait(timeout=30)==0
    return [outputs[c['id']] for c in cases]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--baseline-probe',type=Path,required=True)
    args=p.parse_args()
    args.output.mkdir(parents=True,exist_ok=False)
    args.output=args.output.resolve()
    model=ROOT/'app/src/main/assets/language_model/model.cng'
    source=args.output/'native-source'
    source.mkdir()
    shutil.copytree(NATIVE/'src',source/'src')
    for name in ('Cargo.toml','Cargo.lock'):
        shutil.copyfile(NATIVE/name,source/name)
    spelling=source/'src/cnn.rs'
    text=spelling.read_text()
    assert text.count(OLD)==1
    spelling.write_text(text.replace(OLD,NEW))
    replay=ROOT.parent/'gboard-probe/improve-v3/cases.jsonl'
    selection=[c for c in core.rows(replay) if c['fit_split']=='selection' and c['source']=='real']
    data=ROOT/'build/real-revisit-20261025/data3'
    recorded=[c for c in core.rows(data/'cases.jsonl') if c['split']=='selection']
    trials=[t for t in core.rows(data/'trials.jsonl') if t['split']=='selection']
    core.jsonl(args.output/'snapshot-selection.jsonl',selection)
    core.jsonl(args.output/'recorded-selection.jsonl',recorded)
    protocol=dict(profiles=PROFILES,
        expansion=dict(short='length3 radius2',medium='length4-5 radius3',long='length6-12 radius4',combined='length3-12 existing radius plus1'),
        unchanged=['LM','acceptance','edit costs','vocabulary','mixed-language ranker'],
        selection='Mean snapshot-selection F0.5 and recorded closed-loop selection F0.5; parent eligible; ties parent then narrower length scope',
        hashes=dict(script=core.digest(__file__),native_source=core.digest(NATIVE/'src/cnn.rs'),
            model=core.digest(model),policy=core.digest(NATIVE/'src/english_acceptance.json'),
            baseline_probe=core.digest(args.baseline_probe),source_replay=core.digest(replay),
            snapshot_selection=core.digest(args.output/'snapshot-selection.jsonl'),
            recorded_selection=core.digest(args.output/'recorded-selection.jsonl')),
        frozen_labels_used_for_selection=False,production_changed=False)
    core.write_json(args.output/'protocol.json',protocol)
    build_env=dict(os.environ,CARGO_TARGET_DIR=str(args.output/'target'))
    subprocess.run(['cargo','build','--offline','--manifest-path',str(source/'Cargo.toml'),
        '--release','--features','android','--bin','ngram_probe','--bin','suggestion_probe'],env=build_env,check=True)
    binary=args.output/'target/release/ngram_probe'
    requests=[dict(left=c['left'],typed=c['typed'],spelling_limit=8,timing=True) for c in selection]
    parent_outputs=run_requests(args.baseline_probe,model,requests,'parent')
    report=dict(protocol=protocol,profiles={})
    for profile in PROFILES:
        outputs=run_requests(binary,model,requests,profile)
        if profile=='parent':
            clean=lambda out:{k:v for k,v in out.items() if k!='elapsedMicros'}
            assert all(clean(a)==clean(b) for a,b in zip(parent_outputs,outputs,strict=True))
            report['isolated_parent_native_parity_mismatches']=0
        live=closed_loop(binary,model,recorded,trials,profile)
        core.jsonl(args.output/(profile+'-snapshot-outputs.jsonl'),outputs)
        core.jsonl(args.output/(profile+'-closed-loop-outputs.jsonl'),live)
        metrics=dict(snapshot=core.metrics(selection,outputs),closed_loop=core.metrics(recorded,live))
        metrics['selection_score']=(metrics['snapshot']['f05']+metrics['closed_loop']['f05'])/2
        metrics['native_p95_ms']=core.percentile([o['elapsedMicros']/1000 for o in outputs+live],.95)
        report['profiles'][profile]=metrics
        print(profile,json.dumps(metrics),flush=True)
    winner=max(PROFILES,key=lambda profile:report['profiles'][profile]['selection_score'])
    report.update(winner=winner,probe_sha256=core.digest(binary))
    core.write_json(args.output/'selection-report.json',report)
    print('SELECTION WINNER:',winner,flush=True)


if __name__=='__main__':
    main()
