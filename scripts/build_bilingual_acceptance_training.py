#!/usr/bin/env python3
"""TRAIN-only mixed-context gate examples, separated from all new eval text."""
import argparse
import collections
import json
from pathlib import Path
import sqlite3

import autocorrect_eval_v2 as core
from build_independent_autocorrect_eval import WORD,HAN,stable,perturb

ROOT=Path(__file__).resolve().parents[1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--documents',type=int,default=4000)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    protocol=json.loads((args.suite/'protocol.json').read_text())
    patterns=collections.Counter({(r['operation'],r['original'],r['typed']):r['count'] for r in protocol['channel_patterns']})
    excluded=set();groups=set()
    for split in ('development','confirmation'):
        for r in core.rows(args.suite/(split+'.jsonl')):
            excluded.add((r['left'],r['typed'].casefold()));groups.add(r['group'])
    for r in core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'):
        excluded.add((r['left'],r['typed'].casefold()))
    dictionary=set((ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_valid_words.txt').read_text().splitlines())
    stage=Path('/Volumes/SSD/cantonese_ngram/data/shared-multilingual-v1-stage/staging.sqlite')
    train=Path('/Volumes/SSD/cantonese_ngram/data/shared-multilingual-v2-repair/training.sqlite')
    db=sqlite3.connect(f'file:{stage}?mode=ro&immutable=1',uri=True)
    training=sqlite3.connect(f'file:{train}?mode=ro&immutable=1',uri=True)
    fps={r[0] for r in training.execute('select fp from docs')};training.close()
    query="""select o.fp,o.grp,o.source,p.text from origins o join payloads p on o.fp=p.fp
        where o.partition='train' and p.language='mixed' and
        o.source in ('cantonese_web_no_quotes','wiki_yue_body')"""
    candidates=[];seen=set()
    for fp,group,source,text in db.execute(query):
        if fp not in fps or fp in seen or 'mixed:'+group in groups:continue
        choices=[m for m in WORD.finditer(text) if HAN.search(text[max(0,m.start()-512):m.start()]) and 2<=len(m.group())<=16]
        if not choices:continue
        m=min(choices,key=lambda m:stable(fp+str(m.start())))
        if m.group().casefold() in ('tue','ir'):continue
        seen.add(fp)
        candidates.append(dict(fp=fp,group='mixed:'+group,source=source,left=text[:m.start()][-512:],target=m.group()))
    db.close()
    rows=[];seen_groups=set();doc_count=0
    for c in sorted(candidates,key=lambda c:stable(c['fp'])):
        if c['group'] in seen_groups:continue
        family=[dict(c,id='mixed-train:'+c['fp'],typed=c['target'],source_kind='mixed_clean')]
        if c['target'].casefold() in dictionary:
            for operation in ('substitute','delete','insert','transpose'):
                typed=perturb(c['target'].lower(),operation,patterns,c['fp'])
                if typed and c['target'].isupper():typed=typed.upper()
                elif typed and c['target'][:1].isupper():typed=typed[:1].upper()+typed[1:]
                if typed and typed.casefold() not in ('tue','ir'):
                    family.append(dict(c,id='mixed-train:'+c['fp']+':'+operation,typed=typed,source_kind='mixed_typo'))
        if any((r['left'],r['typed'].casefold()) in excluded for r in family):continue
        seen_groups.add(c['group']);doc_count+=1;rows.extend(family)
        if doc_count>=args.documents:break
    # Keep a fixed input-only stratified sample of the latest quarantined TRAIN
    # replay as a preservation/behavior anchor; no calibration/selection rows.
    legacy=ROOT/'build/short-nn-v2-20261029/input-clean-data/cases.jsonl'
    buckets=collections.defaultdict(list)
    for c in core.rows(legacy):
        if c['fit_split']=='fit' and (c['left'],c['typed'].casefold()) not in excluded:
            buckets[c['source']].append(c)
    for source,values in sorted(buckets.items()):
        for c in sorted(values,key=lambda c:stable(c['id']))[:256]:
            rows.append(dict(c,source_kind='legacy_anchor',legacy_source=source))
    assert not {(r['left'],r['typed'].casefold()) for r in rows}&excluded
    assert not {r['group'] for r in rows if r['source_kind'].startswith('mixed_')}&groups
    core.jsonl(args.output/'cases.jsonl',rows)
    report=dict(script_sha256=core.digest(__file__),cases_sha256=core.digest(args.output/'cases.jsonl'),
        eval_protocol_sha256=core.digest(args.suite/'protocol.json'),documents=doc_count,
        rows=dict(collections.Counter(r['source_kind'] for r in rows)),
        mixed_sources=dict(collections.Counter(r['source'] for r in rows if r['source_kind']=='mixed_clean')),
        case_handling='Preserve original title/all-uppercase pattern in both clean and synthetic typo families',
        exact_eval_input_overlap=0,mixed_eval_group_overlap=0,
        sampling='Fixed hash sample of admitted current-LM TRAIN document fingerprints; one word per source group; legacy TRAIN-only source strata',
        holdouts='All new development/confirmation inputs and groups excluded mechanically; no confirmation outputs read; tue/ir excluded from new mixed targets and inputs',
        limitations='New mixed typos are synthetic; legacy anchor is historically exposed; no new participant/chat data')
    core.write_json(args.output/'protocol.json',report);print(json.dumps(report,indent=2))


if __name__=='__main__':main()
