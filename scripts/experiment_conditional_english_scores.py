#!/usr/bin/env python3
"""Development-only scoring experiment in an isolated native source copy.

Condition English acceptance evidence on total English-token probability mass.
Vocabulary/ranking/weights remain fixed. New confirmation is never read here.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

import autocorrect_eval_v2 as core

ROOT=Path(__file__).resolve().parents[1]
NATIVE=ROOT.parent/'QwenASR/crates/cantonese-ngram'
WEIGHTS=[0.,.125,.25,.5,1.]


def with_radius_candidate(text):
    """Recreate the withdrawn candidate only inside the experiment checkout."""
    if 'fn spelling_radius(length: usize)' in text:return text
    marker='''pub struct EnglishSuggestion {
    pub word: String, pub score: f32, pub edits: u8, pub completed_chars: usize,
}'''
    helpers='''
fn spelling_radius(length: usize) -> usize {
    match length { 0..=2 => 1, 3 => 2, 4..=5 => 3, 6..=12 => 4, _ => 3 }
}
fn acceptance_radius(length: usize) -> usize {
    if length >= 6 { 3 } else if length >= 4 { 2 } else { 1 }
}
fn acceptance_runner(length: usize, ranked: &[EnglishSuggestion]) -> Option<&EnglishSuggestion> {
    let best=ranked.first()?;
    let mut original=ranked.iter().filter(|r|r.edits as usize<=acceptance_radius(length) || r.completed_chars>0 || r.word.contains(' '));
    if original.next().is_some_and(|r|r.word==best.word && r.score==best.score) {original.next()} else {ranked.get(1)}
}
'''
    assert text.count(marker)==1
    text=text.replace(marker,marker+helpers)
    original='let radius=if input.len()>=6 {3}else if input.len()>=4 {2}else{1};'
    assert text.count(original)==1;text=text.replace(original,'let radius=spelling_radius(input.len());')
    old='best.score, best.edits, ranked.get(1).map(|r|(r.score, r.edits, r.completed_chars, r.word.as_str())), literal_score))'
    new='best.score, best.edits, acceptance_runner(input.len(), &ranked).map(|r|(r.score, r.edits, r.completed_chars, r.word.as_str())), literal_score))'
    assert text.count(old)==1
    return text.replace(old,new)


def build(output):
    source=output/'native-source';source.mkdir()
    shutil.copytree(NATIVE/'src',source/'src')
    for name in ('Cargo.toml','Cargo.lock'):shutil.copyfile(NATIVE/name,source/name)
    path=source/'src/cnn.rs';text=with_radius_candidate(path.read_text())
    old='fn spelling_radius(length: usize) -> usize {\n'
    assert text.count(old)==1
    text=text.replace(old,old+'''    if std::env::var("KEYBOARD_EVAL_RADIUS").as_deref()==Ok("accepted") { return acceptance_radius(length); }
''')
    old='        let literal_score=literal_ids.into_iter().map(|id|logp[id]).max_by(f32::total_cmp);'
    assert text.count(old)==1
    text=text.replace(old,old+'''
        let conditional_weight=std::env::var("KEYBOARD_EVAL_ENGLISH_WEIGHT").ok()
            .and_then(|v|v.parse::<f64>().ok()).unwrap_or(0.);
        assert!((0. ..=1.).contains(&conditional_weight));
        let acceptance_offset=if conditional_weight>0. {
            let mass=logp.iter().enumerate().filter(|(i,_)|self.weights.words.contains(i))
                .map(|(_,v)|(*v as f64).exp()).sum::<f64>();
            (-mass.max(1e-30).ln()*conditional_weight) as f32
        } else {0.};
''')
    old='''        let correction=ranked.first().filter(|best|
            crate::cnn_spelling::accept(&input, known, &best.word, best.completed_chars,
                best.score, best.edits, acceptance_runner(input.len(), &ranked).map(|r|(r.score, r.edits, r.completed_chars, r.word.as_str())), literal_score))
            .map(|best|best.word.clone());'''
    new='''        let correction=ranked.first().filter(|best| {
            let runner=acceptance_runner(input.len(),&ranked);
            // Word-token probabilities have comparable units. Keep byte,
            // split and completion decisions on their original calibration.
            let ordinary=best.edits>0 && best.completed_chars==0 && best.word.bytes().all(|c|c.is_ascii_alphabetic()) &&
                runner.is_none_or(|r|r.completed_chars==0 && r.word.bytes().all(|c|c.is_ascii_alphabetic()));
            let offset=if ordinary {acceptance_offset} else {0.};
            crate::cnn_spelling::accept(&input,known,&best.word,best.completed_chars,
                best.score+offset,best.edits,runner.map(|r|(r.score+offset,r.edits,r.completed_chars,r.word.as_str())),
                literal_score.map(|v|v+offset))
        }).map(|best|best.word.clone());'''
    assert text.count(old)==1;path.write_text(text.replace(old,new))
    env=dict(os.environ,CARGO_TARGET_DIR=str(output/'target'))
    subprocess.run(['cargo','build','--offline','--manifest-path',str(source/'Cargo.toml'),
        '--release','--features','android','--bin','ngram_probe','--bin','suggestion_probe','--lib'],env=env,check=True)
    return output/'target/release/ngram_probe'


def infer(binary,model,cases,radius,weight):
    env=dict(os.environ,KEYBOARD_EVAL_RADIUS=radius,KEYBOARD_EVAL_ENGLISH_WEIGHT=str(weight))
    proc=subprocess.run([str(binary),str(model)],input=core.native_requests(cases),text=True,
        capture_output=True,env=env,check=True,timeout=300)
    out=[json.loads(line) for line in proc.stdout.splitlines()]
    assert len(out)==len(cases)
    return out


def metrics(cases,outputs):
    return {panel:core.metrics([c for c in cases if c['panel']==panel],
        [o for c,o in zip(cases,outputs) if c['panel']==panel]) for panel in sorted({c['panel'] for c in cases})}


def eligible(current,baseline):
    for panel,base in baseline.items():
        value=current[panel]
        if value['clean_changes']>base['clean_changes'] or value['fp']>base['fp'] or value['tp']<base['tp']:
            return False
    return True


def quality(value):
    return sum(value[p]['recall'] for p in ('github_real_typo','mixed_synthetic_typo'))/2


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--baseline-probe',type=Path,required=True)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=False);args.output=args.output.resolve()
    suite=json.loads((args.suite/'protocol.json').read_text())
    assert core.digest(args.suite/'development.jsonl')==suite['split_hashes']['development']
    cases=core.rows(args.suite/'development.jsonl')
    protocol=dict(schema='conditional-english-score-experiment-v1',weights=WEIGHTS,radii=['accepted','candidate'],
        mechanism='Acceptance scores shifted by -lambda log sum(P(English token | left)); ordinary single-word repair only',
        selection='Every dev panel TP >= baseline, FP <= baseline, clean damage <= baseline; maximize mean two typo-panel recall; ties smaller weight then accepted radius',
        confirmation_used=False,parameters_fitted=False,script_sha256=core.digest(__file__),
        native_source_sha256=core.digest(NATIVE/'src/cnn.rs'),suite_protocol_sha256=core.digest(args.suite/'protocol.json'),
        model_sha256=core.digest(ROOT/'app/src/main/assets/language_model/model.cng'))
    core.write_json(args.output/'protocol.json',protocol)
    binary=build(args.output);model=ROOT/'app/src/main/assets/language_model/model.cng'
    baseline=infer(binary,model,cases,'accepted',0.)
    original=infer(args.baseline_probe.resolve(),model,cases,'accepted',0.)
    clean=lambda row:{k:v for k,v in row.items() if k!='elapsedMicros'}
    assert all(clean(a)==clean(b) for a,b in zip(baseline,original,strict=True))
    report=dict(protocol=protocol,baseline_parity_mismatches=0,profiles={})
    parent=metrics(cases,baseline)
    for radius in ('accepted','candidate'):
        for weight in WEIGHTS:
            name=f'{radius}-w{weight:g}'
            outputs=baseline if radius=='accepted' and weight==0 else infer(binary,model,cases,radius,weight)
            core.jsonl(args.output/(name+'-outputs.jsonl'),outputs)
            values=metrics(cases,outputs)
            result=dict(metrics=values,eligible=eligible(values,parent),quality=quality(values),radius=radius,weight=weight)
            report['profiles'][name]=result
            print(name,'eligible',result['eligible'],'recall',round(result['quality'],5),
                [(p,m['tp'],m['fp'],m['clean_changes']) for p,m in values.items()],flush=True)
    candidates=[(name,v) for name,v in report['profiles'].items() if v['eligible']]
    winner=max(candidates,key=lambda item:(item[1]['quality'],-item[1]['weight'],item[1]['radius']=='accepted'))[0]
    report.update(winner=winner,probe_sha256=core.digest(binary))
    core.write_json(args.output/'development-report.json',report)
    print('DEVELOPMENT WINNER:',winner,flush=True)


if __name__=='__main__':main()
