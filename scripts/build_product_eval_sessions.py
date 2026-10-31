#!/usr/bin/env python3
"""Freeze authored product diagnostics; these are NOT recorded user sessions."""
import json
from pathlib import Path

import autocorrect_eval_v2 as core

ROOT=Path(__file__).resolve().parents[1]


def main():
    output=ROOT/'config/product_eval_v1'
    output.mkdir(parents=True,exist_ok=True)
    destination=output/'sessions.jsonl'
    if destination.exists():raise ValueError('Refusing to replace frozen session labels')
    rows=[]
    def add(name,left,typed,expected,tags,language='english',intent='preservation',events=None,caps=False):
        operations=events or [dict(kind='type',text=typed),dict(kind='space')]
        for layout in ('english','quick3','quick5'):
            rows.append(dict(schema='keyboard-product-session-v1',id=name+':'+layout,group=name,
                origin='authored_diagnostic',intent_status='author_specified',language=language,
                layout=layout,initial_text=left,events=operations,expected_text=expected,
                expected_composing='',tags=tags,intent=intent,auto_capitalization=caps))
    for word,left in [('pls','can you send it '),('asap','need this '),('thu','free on '),('rn','busy '),
                      ('Ishan','message '),('WaiMan','ask '),('Siu','thanks '),
                      ('qwen_v3','the branch is '),('authToken','the variable is ')]:
        add('keep-'+word,left,word,left+word+' ',['names' if word in ['Ishan','WaiMan','Siu'] else 'informal_or_identifier'])
    for word,left in [('MTR','我搭 '),('MPF','今個月未交 '),('Zoom','陣間用 '),('book','幫我 '),('Siu','我個friend叫 ')]:
        add('mixed-keep-'+word,left,word,left+word+' ',['mixed_preservation'],'mixed_cantonese_english')
    for typed,target,left in [('tommorow','tomorrow','我哋約 '),('reciept','receipt','send返張 '),('meting','meeting','聽朝個 ')]:
        add('mixed-fix-'+typed,left,typed,left+target+' ',['mixed_correction'],'mixed_cantonese_english','correction')
    add('two-edit-short','Are ','toi','Are you ',['multiple_edits'],'english','correction')
    add('missing-space','thanks ','alot','thanks a lot ',['missing_space'],'english','correction')
    add('real-word','the message is ','form','the message is from ',['real_word_intent'],'english','correction')
    def words(text):
        return [e for w in text.split() for e in [dict(kind='type',text=w),dict(kind='space')]]
    add('english-stream','','','the plan is good ',['closed_loop'],'english','correction',words('teh plan is godo'))
    add('mixed-stream','我諗 ','','我諗 the meeting is tomorrow ',['closed_loop','language_switch'],
        'mixed_cantonese_english','correction',words('teh meeting is tommorow'))
    add('undo-keeps-literal','Did ','','Did toy ',['undo','literal_override'],'english','interaction',
        [dict(kind='type',text='toy'),dict(kind='space'),dict(kind='delete'),dict(kind='space')])
    add('capitalization','','','Hello there ',['capitalization'],'english','interaction',words('hello there'),True)
    core.jsonl(destination,rows)
    manifest=dict(schema='keyboard-product-eval-v1',sessions=len(rows),independent_authored_groups=len(rows)//3,
        cases_sha256=core.digest(destination),builder_sha256=core.digest(__file__),frozen_before_inference=True,
        origin='Authored diagnostic cases, not natural typing, independent confirmation, or usage-weighted data',
        labels='Author-specified exact final editor text and composition; ambiguous real-word intent is explicitly authored',
        inference='Events, layout, initial context and settings only; expected output stays outside the controller',
        train_use='Evaluation only; do not use these sessions, labels or variants for fitting',
        grouping='All English/Quick3/Quick5 variants share a scenario group; never count them as independent samples',
        release_status='Diagnostic dashboard only; no population score or new release threshold')
    core.write_json(output/'manifest.json',manifest)
    print(json.dumps(manifest,indent=2))


if __name__=='__main__':main()
