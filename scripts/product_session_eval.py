#!/usr/bin/env python3
"""Validate session provenance and report exact product outcomes by slice.

Authored diagnostics never become a population score. Recorded chat requires
capture metadata, event times and participant/conversation isolation. Labels
describe intent; model output or absence of an undo is not ground truth.
"""
import argparse
import collections
import json
from pathlib import Path

import autocorrect_eval_v2 as core

ORIGINS={'authored_diagnostic','synthetic','recorded_copy_typing','recorded_chat'}
KINDS={'type','space','enter','submit','delete','choose','cursor'}


def validate(sessions):
    seen=set();people={};conversations={};groups={}
    for row in sessions:
        identifier=row['id']
        if identifier in seen:raise ValueError('Duplicate session ID: '+identifier)
        seen.add(identifier)
        if row.get('schema')!='keyboard-product-session-v1':raise ValueError('Unknown schema')
        if row['origin'] not in ORIGINS:raise ValueError('Unknown origin')
        if row['layout'] not in ('english','quick3','quick5'):raise ValueError('Unknown layout')
        if not isinstance(row['initial_text'],str):raise ValueError('Missing actual initial context')
        if row['intent_status'] not in ('author_specified','human_confirmed','task_reference','unreviewed'):raise ValueError('Unknown label status')
        if row['intent_status']=='task_reference' and row['origin']!='recorded_copy_typing':raise ValueError('Task reference requires a recorded copy task')
        if row['intent_status']=='unreviewed':
            if row.get('expected_text') is not None:raise ValueError('Unreviewed output cannot supply gold text')
        elif not isinstance(row.get('expected_text'),str):raise ValueError('Missing intended final text')
        previous=-1
        for event in row['events']:
            if event['kind'] not in KINDS:raise ValueError('Unsupported event')
            if event['kind'] in ('type','choose') and not isinstance(event['text'],str):raise ValueError('Missing observed key/selection text')
            if row['origin']=='recorded_chat':
                timestamp=event.get('at_ms')
                if not isinstance(timestamp,(int,float)) or timestamp<previous:raise ValueError('Missing/unsorted recorded event time')
                previous=timestamp
        if row['origin']=='recorded_chat':
            if row['intent_status']=='author_specified':raise ValueError('Recorded chat cannot use invented intent labels')
            for field in ('participant_id','conversation_id','device_family','capture_app_version','split'):
                if not row.get(field):raise ValueError('Missing recorded provenance: '+field)
            if row['split'] not in ('development','confirmation'):raise ValueError('Unknown session split')
            for mapping,value in [(people,row['participant_id']),(conversations,row['conversation_id']),(groups,row['group'])]:
                if value in mapping and mapping[value]!=row['split']:raise ValueError('Participant/conversation/group crosses splits')
                mapping[value]=row['split']
    return dict(sessions=len(sessions),groups=len({r['group'] for r in sessions}),
        origins=dict(collections.Counter(r['origin'] for r in sessions)),
        unreviewed=sum(r['intent_status']=='unreviewed' for r in sessions),
        recorded_chat_confirmation_sessions=sum(r['origin']=='recorded_chat' and r['split']=='confirmation' for r in sessions))


def summarize(sessions,results):
    by_id={r['id']:r for r in results}
    if len(by_id)!=len(results) or set(by_id)!={r['id'] for r in sessions}:raise ValueError('Replay membership differs')
    def metrics(rows):
        labelled=[r for r in rows if r['intent_status']!='unreviewed']
        exact=lambda r: by_id[r['id']]['actual']==r['expected_text'] and by_id[r['id']]['composing']==r['expected_composing']
        folded=lambda r: by_id[r['id']]['actual'].casefold()==r['expected_text'].casefold() and by_id[r['id']]['composing']==r['expected_composing']
        clean=[r for r in labelled if r['intent']=='preservation']
        return dict(sessions=len(rows),labelled=len(labelled),groups=len({r['group'] for r in rows}),
            exact_success=sum(exact(r) for r in labelled),casefold_success=sum(folded(r) for r in labelled),
            capitalization_only_failures=sum(folded(r) and not exact(r) for r in labelled),
            clean_sessions=len(clean),clean_sessions_damaged=sum(not exact(r) for r in clean),
            success_fraction=sum(exact(r) for r in labelled)/len(labelled) if labelled else None)
    result=dict(overall_diagnostic_counts=metrics(sessions))
    for field in ('origin','layout','language','intent'):
        result[field]={v:metrics([r for r in sessions if r[field]==v]) for v in sorted({r[field] for r in sessions})}
    result['tags']={v:metrics([r for r in sessions if v in r['tags']]) for v in sorted({t for r in sessions for t in r['tags']})}
    result['failures']=[dict(id=r['id'],group=r['group'],layout=r['layout'],tags=r['tags'],
        expected=r['expected_text'],actual=by_id[r['id']]['actual'],composing=by_id[r['id']]['composing'])
        for r in sessions if r['intent_status']!='unreviewed' and
        (by_id[r['id']]['actual']!=r['expected_text'] or by_id[r['id']]['composing']!=r['expected_composing'])]
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sessions',type=Path,required=True)
    p.add_argument('--baseline',type=Path)
    p.add_argument('--candidate',type=Path)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    sessions=core.rows(args.sessions)
    report=dict(provenance=validate(sessions),sessions_sha256=core.digest(args.sessions),
        limitations=['Authored/synthetic sessions are diagnostic, never representative quality or independent confirmation.',
            'Layout variants share a scenario group; do not count them as independent observations.',
            'Virtual scheduling/fake editor do not measure physical phone timing or real InputConnection behavior.',
            'Intended labels stay outside controller inputs; absence of an undo does not establish correctness.'])
    for name,path in [('baseline',args.baseline),('candidate',args.candidate)]:
        if path:
            raw=json.loads(path.read_text())
            if raw['sessions_sha256']!=report['sessions_sha256']:raise ValueError('Session hash changed')
            report[name]=summarize(sessions,raw['results'])
            report[name+'_native_library_sha256']=raw['native_library_sha256']
    if args.baseline and args.candidate:
        report['diagnostic_exact_success_delta']=report['candidate']['overall_diagnostic_counts']['exact_success']-report['baseline']['overall_diagnostic_counts']['exact_success']
    args.output.parent.mkdir(parents=True,exist_ok=True)
    core.write_json(args.output,report)
    print(json.dumps({k:v for k,v in report.items() if k not in ('baseline','candidate')},indent=2))


if __name__=='__main__':main()
