#!/usr/bin/env python3
"""Prospective stress check of a frozen ranker; no coefficient fitting.

Includes recorded English mistakes, preservation controls, English in Chinese
context, completion prefixes, and Chinese codes. All sources are SSD development
data. This does not establish independent production accuracy.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path

from audit_candidate_pipeline import digest, dictionary, probe
from audit_prediction_context import decode_context
from iterate_english_policy import split

SEED = 'mixed-generalization-20261021-v1'


def key(value):
    return hashlib.sha256((SEED + value).encode()).hexdigest()


def han(value):
    return any('\u3400' <= c <= '\u9fff' or ord(c) >= 0x20000 for c in value)


def metric(rows, orders):
    ranks = [o.index(r['target'])+1 if r['target'] in o else math.inf for r,o in zip(rows,orders)]
    return dict(n=len(rows), top1=sum(r==1 for r in ranks), top8=sum(r<=8 for r in ranks),
                generated=sum(math.isfinite(r) for r in ranks), mrr=sum(1/r for r in ranks)/max(1,len(rows)))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--prefix-only', action='store_true', help='Separate short-prefix cohort; preserves the frozen main cohort')
    p.add_argument('--phrase-only', action='store_true', help='Separate contextual Chinese span cohort; no training phrase labels')
    a = p.parse_args()
    if a.prefix_only and a.phrase_only:p.error('Choose one separate cohort')
    root = Path(__file__).resolve().parents[1]
    assets = root/'app/src/main/assets'
    ssd = Path('/Volumes/SSD/cantonese_ngram')
    model = assets/'language_model/model.cng'
    cases_path = root/'build/english-iteration-2026-10-20/cases.jsonl'
    from align_mobile_targets import reference_target
    import csv
    refs = {r['SENTENCE_ID']:r['SENTENCE'] for r in csv.DictReader((ssd/'raw/aalto-ite-v88/sentences.csv').open())}
    buckets = collections.defaultdict(list)
    for c in map(json.loads,cases_path.open()):
        if c['category'].startswith('prefix_') or split(c['group']) != 'evaluation':
            continue
        target = c['target'] if c['category']=='source_preservation' else reference_target(c,refs[c['group']])
        if target is None or not c['typed'].isascii() or not c['typed'].isalpha() or not 3<=len(c['typed'])<=12:
            continue
        panel = 'recorded_typo' if c['typed'].lower()!=target else 'preservation'
        buckets[panel].append(dict(id=c['id'], group=c['group'], target=target, observed=c['typed'].lower(),
            left=c['left'],right='',version=3 if int(key(c['id'])[:8],16)%2 else 5,panel=panel))
    paths = [ssd/'experiments/keyboard-eval-v13-final/development.jsonl',
             ssd/'data/shared-multilingual-v1-stage/eval/finalist_suite.jsonl']
    codes={}
    if a.phrase_only:
        for v in (3,5):
            options={}
            for i,chars in enumerate(dictionary(assets/f'quick/quick{v}.bin')[26:]):
                code=chr(97+i//26)+chr(97+i%26)
                for rank,c in enumerate(chars):
                    if c not in options or (rank,code)<options[c]:options[c]=(rank,code)
            codes[v]={c:t[1] for c,t in options.items()}
    for r in map(json.loads,paths[0].open()):
        t=r['target'].lower()
        if a.phrase_only:
            # Contiguous real corpus spans, with only preceding observable text.
            # Not claimed to be segmented words. Do not feed the span as context.
            v=3 if int(key(r['id'])[:8],16)%2 else 5
            size=2+int(key(r['id']+'length')[:8],16)%2
            span=t+r['right'][:size-1]
            if len(t)!=1 or len(span)!=size or not all('\u3400'<=c<='\u9fff' and c in codes[v] for c in span):continue
            buckets['chinese_context_phrase'].append(dict(id=r['id'],group=r['document_group'],target=span,
                observed=''.join(codes[v][c] for c in span),left=r['left'],right='',version=v,panel='chinese_context_phrase'))
            continue
        english=t.isascii() and t.isalpha() and 3<=len(t)<=12 and han(r['left'])
        chinese=bool(r['quick_codes']) and han(t)
        if not (english or chinese):continue
        panel='english_in_chinese_context' if english else 'chinese_context_code'
        v=3 if int(key(r['id'])[:8],16)%2 else 5
        if chinese and str(v) not in r['quick_codes']:continue
        buckets[panel].append(dict(id=r['id'],group=r['document_group'],target=t,
            observed=t if english else r['quick_codes'][str(v)],left=r['left'],right=r['right'],version=v,panel=panel))
    for r in map(json.loads,paths[1].open()):
        if r['partition']!='selection' or r['task']!='typed_prefix_completion':continue
        if not r['prefix'].isascii() or not r['prefix'].isalpha() or not (1 if a.prefix_only else 3)<=len(r['prefix'])<=12:continue
        buckets['prefix'].append(dict(id=r['id'],group='_'.join(r['id'].split('_')[:3]),
            target=r['target_visible_word'].lower(),observed=r['prefix'].lower(),left=decode_context(r['context_tokens']),
            right='',version=3 if int(key(r['id'])[:8],16)%2 else 5,panel='prefix'))
    rows=[]
    for panel, candidates in sorted(buckets.items()):
        if a.prefix_only and panel!='prefix':continue
        if a.phrase_only and panel!='chinese_context_phrase':continue
        seen=set()
        for r in sorted(candidates,key=lambda r:key(r['id'])):
            if r['group'] in seen:continue
            # Regression words are never calibration targets. They have separate tests.
            if r['target'] in ('continue','兒歌','自歌'):continue
            seen.add(r['group']);rows.append(r)
            if len(seen)==(128 if panel in ('recorded_typo','preservation') else 64):break
    suffix='prefix' if a.prefix_only else 'phrase' if a.phrase_only else 'generalization'
    cohort=root/f'build/mixed-{suffix}-cohort.json'
    serialized=json.dumps(dict(seed=SEED,rows=rows),ensure_ascii=False,sort_keys=True)
    if cohort.exists():assert cohort.read_text()==serialized,'Frozen cohort changed'
    else:cohort.write_text(serialized)
    policy=root.parent/'QwenASR/crates/cantonese-ngram/src/mixed_ranking.json'
    binding=dict(model=digest(model),probe=digest(a.probe),before=digest(a.before),policy=digest(policy),cohort=digest(cohort))
    cache_path=root/f'build/mixed-{suffix}-output.json'
    cached=json.loads(cache_path.read_text()) if cache_path.exists() else dict(binding=binding,chunks=[])
    assert cached['binding']==binding,'Inputs changed after freeze'
    all_before=[];all_after=[];durations=[];coverage_changes=0
    extra=('-',assets/'handwriting/labels.txt',assets/'quick/quick3.bin',assets/'quick/quick5.bin')
    for start in range(0,len(rows),32):
        batch=rows[start:start+32];chunk=start//32
        if chunk<len(cached['chunks']):result=cached['chunks'][chunk]
        else:
            qs=[dict(code=r['observed'],left=r['left'],right=r['right'],version=r['version']) for r in batch]
            old=probe(a.before,model,qs,extra=extra);new=probe(a.probe,model,qs,extra=extra)
            english=probe(a.probe,model,[dict(typed=r['observed'],left=r['left'],spelling_limit=8) for r in batch])
            before=[];after=[];times=[];changes=0
            for r,b,n,e in zip(batch,old,new,english):
                en=list(dict.fromkeys([r['observed']]+[c['word'] for c in e['candidates'] if c['word']!=r['observed']]))[:9]
                bcs=b['candidates'];ncs=n['candidates'];changes+=set(bcs)!=set(ncs)
                if len(r['observed'])==2:
                    before.append(list(dict.fromkeys(bcs+en)));after.append(list(dict.fromkeys(ncs+en)));continue
                old_pool=list(dict.fromkeys(bcs[:8]+en))
                new_pool=list(dict.fromkeys((ncs[:8] if len(r['observed'])==1 else ncs)+en))
                union=list(dict.fromkeys(old_pool+new_pool))
                scored=probe(a.probe,model,[dict(mixed=union,observed=r['observed'],left=r['left'],right=r['right'],timing=True)])[0]
                evidence=dict(zip(union,scored['evidence']));scores=dict(zip(union,scored['scores']))
                bo=sorted(old_pool,key=lambda c:(not han(c),-evidence[c]['unitLogProbability']))
                no=sorted(new_pool,key=lambda c:-scores[c])
                before.append(bo+[c for c in bcs if c not in bo]);after.append(no+[c for c in ncs if c not in no])
                times.append(scored['elapsedMicros'])
            result=dict(before=before,after=after,durations=times,coverage_changes=changes)
            cached['chunks'].append(result);cache_path.write_text(json.dumps(cached))
        all_before+=result['before'];all_after+=result['after'];durations+=result['durations'];coverage_changes+=result['coverage_changes']
        print(f'{min(start+32,len(rows))}/{len(rows)} checked',flush=True)
    report=dict(binding=binding,seed=SEED,method='Frozen weights, fixed rows, no refitting. One case per prompt/document per panel. Development data with historical exposure; not an independent test.',
        sources={str(path):digest(path) for path in [cases_path,*paths]},overall=dict(before=metric(rows,all_before),after=metric(rows,all_after)),
        panels={p:dict(before=metric([r for r in rows if r['panel']==p],[o for r,o in zip(rows,all_before) if r['panel']==p]),
                      after=metric([r for r in rows if r['panel']==p],[o for r,o in zip(rows,all_after) if r['panel']==p])) for p in sorted({r['panel'] for r in rows})},
        quick_candidate_sets_changed=coverage_changes)
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
