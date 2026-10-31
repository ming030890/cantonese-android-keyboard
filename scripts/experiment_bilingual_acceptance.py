#!/usr/bin/env python3
"""Mac-local 24-parameter gate experiment with a matched data-only control.

Freeze all existing NN parameters. Compare updating its 24 raw-score input
weights with adding 24 initially-zero English-log-mass weights. Fit only TRAIN
rows, retain the original cutoff, choose on supplemental development, and leave
confirmation unread. This is not a large-model retraining job.
"""
import argparse
import collections
import copy
import json
import math
import os
from pathlib import Path
import shutil
import subprocess

import numpy as np

import autocorrect_eval_v2 as core
from experiment_clean_acceptance import load_features,ENGINE
from experiment_conditional_english_scores import infer,metrics,eligible,quality

ROOT=Path(__file__).resolve().parents[1]


def build(output):
    source=output/'native-source'
    if source.exists():shutil.rmtree(source)
    shutil.copytree(ROOT/'build/conditional-english-scores-20261030/native-source',source)
    cnn=source/'src/cnn.rs';text=cnn.read_text()
    text=text.replace('    pub literal_score: Option<f32>,','    pub literal_score: Option<f32>,\n    pub english_log_mass: Option<f32>,')
    text=text.replace('literal_score:None,apostrophe_evidence:vec![]','literal_score:None,english_log_mass:None,apostrophe_evidence:vec![]')
    start=text.index('        let acceptance_offset=if conditional_weight>0. {')
    end=text.index('        let input_is_lower',start)
    text=text[:start]+'''        let english_mass=logp.iter().enumerate().filter(|(i,_)|self.weights.words.contains(i))
            .map(|(_,v)|(*v as f64).exp()).sum::<f64>();
        let english_log_mass=english_mass.max(1e-30).ln() as f32;
        let acceptance_offset=-english_log_mass*conditional_weight as f32;
'''+text[end:]
    old='literal_score.map(|v|v+offset))'
    assert text.count(old)==1;text=text.replace(old,'literal_score.map(|v|v+offset),english_log_mass)')
    text=text.replace('known,literal_score,apostrophe_evidence})','known,literal_score,english_log_mass:Some(english_log_mass),apostrophe_evidence})')
    cnn.write_text(text)
    path=source/'src/cnn_spelling.rs';text=path.read_text()
    text=text.replace('    literal: Option<f32>,','    literal: Option<f32>,\n    english_log_mass: f32,')
    text=text.replace('matches!(inputs, 20 | 22)','matches!(inputs, 20 | 22 | 23)')
    text=text.replace('policy.means.len() == 22','policy.means.len() >= 22')
    old='''    ];

    let mut hidden = policy.b1;'''
    assert text.count(old)==1;text=text.replace(old,'''        english_log_mass as f64,
    ];

    let mut hidden = policy.b1;''')
    path.write_text(text)
    path=source/'src/suggestions.rs';text=path.read_text()
    text=text.replace('literal_score:None,','literal_score:None,english_log_mass:None,')
    text=text.replace('literal_score: None,','literal_score: None,\n                english_log_mass: None,')
    path.write_text(text)
    env=dict(os.environ,CARGO_TARGET_DIR=str(output/'target'))
    subprocess.run(['cargo','build','--offline','--manifest-path',str(source/'Cargo.toml'),
        '--release','--features','android','--bin','ngram_probe','--bin','suggestion_probe','--lib'],check=True,env=env)
    return output/'target/release/ngram_probe'


def extract(cases,outputs,policy,features):
    dictionary=set((ENGINE/'english_valid_words.txt').read_text().splitlines())
    values=[];allowed=[];words=[];restored=[]
    for c,o in zip(cases,outputs,strict=True):
        x,ok,word=features.extract(c,o)
        typed=c['typed'].replace('’',"'").lower();lower=(word or '').lower()
        values.append(list(x)+[float(typed in dictionary),float(bool(lower) and all(w in dictionary for w in lower.split(' '))),o.get('englishLogMass') or 0.])
        restore=features.restored(o)
        if word and lower==typed and word!=typed:restore=restore or word
        allowed.append(ok);words.append(word);restored.append(restore)
    return np.array(values),np.array(allowed),words,restored


def predictions(policy,x,allowed,words,restorations,features):
    z=features.predict_logits(features.parameters(policy),(x[:,:len(policy['means'])]-policy['means'])/policy['scales'])
    cutoff=math.log(policy['threshold']/(1-policy['threshold']))
    return [rest or (word if ok and value>=cutoff else None) for value,ok,word,rest in zip(z,allowed,words,restorations)]


def fit_column(policy,x,y,weights,anchor_mask,column,learning_rate):
    normalized=(x[:,:len(policy['means'])]-policy['means'])/policy['scales']
    w1=np.array(policy['w1']);w2=np.array(policy['w2'])
    hidden=normalized@w1.T+policy['b1']
    before=np.maximum(hidden,0)@w2+policy['b2']
    scalar=normalized[:,column]
    delta=np.zeros(24);m=np.zeros(24);v=np.zeros(24)
    anchor=weights*anchor_mask;anchor/=max(anchor.sum(),1e-12)
    weights=weights/weights.sum()
    checkpoints={}
    for epoch in range(1,101):
        activation=hidden+scalar[:,None]*delta
        logits=np.maximum(activation,0)@w2+policy['b2']
        prob=1/(1+np.exp(-np.clip(logits,-50,50)))
        dz=weights*(prob-y)+.4*anchor*(logits-before)
        gradient=(dz[:,None]*(activation>0)*w2*scalar[:,None]).sum(0)+.002*delta
        m=.9*m+.1*gradient;v=.999*v+.001*gradient*gradient
        delta-=learning_rate*(m/(1-.9**epoch))/(np.sqrt(v/(1-.999**epoch))+1e-8)
        if epoch in (30,100):
            result=copy.deepcopy(policy)
            for i in range(24):result['w1'][i][column]+=float(delta[i])
            checkpoints[epoch]=result
    return checkpoints


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite',type=Path,required=True)
    p.add_argument('--training',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--resume-failed-build',action='store_true')
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=args.resume_failed_build);args.output=args.output.resolve()
    assert not (args.output/'train-outputs.jsonl').exists() and not (args.output/'development-report.json').exists()
    shutil.copyfile(__file__,args.output/'executed-script.py')
    parent=json.loads((ENGINE/'english_acceptance.json').read_text())
    protocol=dict(schema='bilingual-acceptance-column-study-v1',script_sha256=core.digest(__file__),
        training_protocol_sha256=core.digest(args.training/'protocol.json'),
        suite_protocol_sha256=core.digest(args.suite/'protocol.json'),parent_policy_sha256=core.digest(ENGINE/'english_acceptance.json'),
        models=dict(data_only='24 existing raw-LM input weights updated',english_mass='24 new English-log-mass input weights; all existing weights frozen'),
        learning_rates=[.003,.01],epochs=[30,100],anchor=.2,l2=.001,cutoff_retained=parent['threshold'],
        selection='Same dev panel Pareto guard as normalization; max mean typo recall; parent eligible; ties unchanged parent then fewer epochs',
        confirmation_used=False,hardware='Local Mac CPU NumPy; no remote training')
    core.write_json(args.output/'protocol.json',protocol)
    binary=build(args.output);model=ROOT/'app/src/main/assets/language_model/model.cng'
    train=core.rows(args.training/'cases.jsonl');dev=core.rows(args.suite/'development.jsonl')
    outputs={}
    for name,cases in [('train',train),('development',dev)]:
        outputs[name]=infer(binary,model,cases,'accepted',0.)
        core.jsonl(args.output/(name+'-outputs.jsonl'),outputs[name])
    f=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
    tx,ta,tw,tr=extract(train,outputs['train'],parent,f)
    dx,da,dw,dr=extract(dev,outputs['development'],parent,f)
    for cases,x,allowed,words,rest,outs in [(train,tx,ta,tw,tr,outputs['train']),(dev,dx,da,dw,dr,outputs['development'])]:
        actual=predictions(parent,x,allowed,words,rest,f)
        mismatch=sum((a or None)!=(o.get('correction') or None) for a,o in zip(actual,outs,strict=True))
        assert mismatch==0,mismatch
    labels=np.array([float(c['target'] is not None and c['typed'].casefold()!=c['target'].casefold() and (w or '').casefold()==c['target'].casefold()) for c,w in zip(train,tw)])
    kinds=collections.Counter(c['source_kind'] for c in train)
    weights=np.array([1/kinds[c['source_kind']] for c in train])
    weights*=ta & np.array([not bool(r) for r in tr])
    anchor=np.array([c['source_kind']=='legacy_anchor' for c in train],dtype=float)
    baseline=metrics(dev,outputs['development'])
    report=dict(protocol=protocol,training_rows=len(train),development_rows=len(dev),native_feature_parity_mismatches=0,models={})
    report['models']['parent']=dict(metrics=baseline,eligible=True,quality=quality(baseline),epochs=0)
    for family in ('data_only','english_mass'):
        initial=copy.deepcopy(parent)
        column=0
        if family=='english_mass':
            initial['means'].append(0.);initial['scales'].append(max(float(tx[:,22].std()),1.))
            for row in initial['w1']:row.append(0.)
            column=22
        for lr in (.003,.01):
            for epochs,policy in fit_column(initial,tx,labels,weights,anchor,column,lr).items():
                name=f'{family}-lr{lr:g}-e{epochs}'
                fixes=predictions(policy,dx,da,dw,dr,f)
                out=[dict(o,correction=fix) for o,fix in zip(outputs['development'],fixes)]
                values=metrics(dev,out)
                result=dict(metrics=values,eligible=eligible(values,baseline),quality=quality(values),epochs=epochs,family=family,lr=lr)
                report['models'][name]=result
                core.write_json(args.output/(name+'-policy.json'),policy)
                core.jsonl(args.output/(name+'-outputs.jsonl'),out)
                print(name,'eligible',result['eligible'],'quality',round(result['quality'],6),[(p,m['tp'],m['fp']) for p,m in values.items()],flush=True)
    choices=[(name,v) for name,v in report['models'].items() if v['eligible']]
    winner=max(choices,key=lambda item:(item[1]['quality'],item[0]=='parent',-item[1]['epochs']))[0]
    report['winner']=winner
    core.write_json(args.output/'development-report.json',report)
    print('DEVELOPMENT WINNER:',winner,flush=True)


if __name__=='__main__':main()
