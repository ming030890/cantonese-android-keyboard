#!/usr/bin/env python3
"""Freeze repository/document-disjoint supplemental evals before model calls.

GitHub edits are real corrections with automatically inferred typo labels, not
phone keystrokes. Bilingual contexts are source text; injected errors are a
separate, explicitly synthetic stress panel. Neither estimates chat prevalence.
"""
import argparse
import collections
import difflib
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import struct

import autocorrect_eval_v2 as core
from align_mobile_targets import osa

ROOT=Path(__file__).resolve().parents[1]
SEED='independent-autocorrect-eval-20261030-v1'
WORD=re.compile(r"(?<![A-Za-z0-9_])[A-Za-z]{2,24}(?![A-Za-z0-9_])")
HAN=re.compile(r'[\u3400-\u9fff\U00020000-\U000323af]')
EXPECTED_GTC_SHA='98219d275a49ad28fca832891876c255581b890d893b6d9c974ceb454ba2909d'


def stable(value):return hashlib.sha256((SEED+value).encode()).hexdigest()
def fold(group):return 'development' if int(stable(group)[:8],16)%2==0 else 'confirmation'


def github_rows(path,dictionary,known_words):
    counts=collections.Counter();eligible=[]
    with gzip.open(path,'rt') as stream:
        for line in stream:
            commit=json.loads(line);counts['commits']+=1
            for index,edit in enumerate(commit['edits']):
                counts['edits']+=1
                a,b=edit['src'],edit['tgt']
                if a.get('lang')!='eng' or b.get('lang')!='eng' or edit.get('prob_typo',0)<.95:continue
                if Path(a['path']).suffix.lower() not in ('.md','.rst','.txt','.markdown'):continue
                src,tgt=a['text'],b['text']
                if any(ch in src+tgt for ch in '{}<>`') or 'http' in src+tgt:continue
                aa,bb=list(WORD.finditer(src)),list(WORD.finditer(tgt))
                if len(aa)!=len(bb) or len(aa)<4:continue
                changed=[i for i,(x,y) in enumerate(zip(aa,bb)) if x.group().casefold()!=y.group().casefold()]
                if len(changed)!=1:continue
                x,y=aa[changed[0]],bb[changed[0]]
                typed,target=x.group(),y.group()
                if src[:x.start()]!=tgt[:y.start()] or src[x.end():]!=tgt[y.end():]:continue
                if typed.casefold() in known_words or target.casefold() not in dictionary:continue
                if not 1<=osa(typed.casefold(),target.casefold())<=3:continue
                # Bound paragraph-fragment context without supplying future text.
                left=src[:x.start()][-512:]
                if not left.strip():continue
                ident=stable(commit['repo']+commit['commit']+str(index))
                eligible.append(dict(id='gtc:'+ident,group=commit['repo'],left=left,typed=typed,target=target,
                    panel='github_real_typo',origin='real_repository_edit_auto_label',
                    repo=commit['repo'],commit=commit['commit'],source_path=a['path'],
                    label_basis='Single-word author edit; upstream automatic typo probability >=0.95; not manually confirmed',
                    paragraph_hash=stable(tgt.casefold()),split=fold(commit['repo'])))
    counts['eligible']=len(eligible)
    return eligible,dict(counts)


def channel(cases):
    """Recorded TRAIN-fold letter errors only; no eval errors supply a pattern."""
    patterns=collections.Counter()
    for c in cases:
        if c.get('split')!='fit' or c.get('target') is None:continue
        target,typed=c['target'].lower(),c['typed'].lower()
        if not target.isascii() or not typed.isascii() or not target.isalpha() or not typed.isalpha() or target==typed:continue
        swaps=[i for i in range(len(target)-1) if target[:i]+target[i+1]+target[i]+target[i+2:]==typed and target[i]!=target[i+1]]
        if swaps:
            i=swaps[0];patterns[('transpose',target[i:i+2],typed[i:i+2])]+=1;continue
        operations=[(tag,target[i:j],typed[k:l]) for tag,i,j,k,l in difflib.SequenceMatcher(None,target,typed,autojunk=False).get_opcodes() if tag!='equal']
        if len(operations)==1:
            tag,a,b=operations[0]
            if tag=='replace' and len(a)==len(b)==1:patterns[('substitute',a,b)]+=1
            elif tag=='delete' and len(a)==1:patterns[('delete',a,'')]+=1
            elif tag=='insert' and len(b)==1:patterns[('insert','',b)]+=1
    return patterns


def perturb(word,operation,patterns,identifier):
    candidates=[]
    for (op,a,b),count in sorted(patterns.items()):
        if op!=operation:continue
        positions=range(len(word)+1) if op=='insert' else [i for i in range(len(word)) if word.startswith(a,i)]
        for i in positions:
            changed=word[:i]+b+word[i+len(a):]
            if changed!=word:candidates.append((changed,count))
    if not candidates:return None
    choice=int(stable(identifier+operation),16)%sum(n for _,n in candidates)
    for value,weight in candidates:
        if choice<weight:return value
        choice-=weight
    raise AssertionError('Weighted choice')


def mixed_rows(stage,training,patterns,dictionary):
    db=sqlite3.connect(f'file:{stage}?mode=ro&immutable=1',uri=True)
    train=sqlite3.connect(f'file:{training}?mode=ro&immutable=1',uri=True)
    # Only LM selection text, never protected/reserved partitions. Direct FP
    # checks cover the current repair train; inherited exposure remains unknown.
    training_fps={r[0] for r in train.execute('select fp from docs')}
    query="""select o.fp,o.grp,o.source,p.text from origins o join payloads p on o.fp=p.fp
        where o.partition='selection' and p.language='mixed'"""
    result=[];seen=set();counts=collections.Counter()
    for fp,group,source,text in db.execute(query):
        counts['selection_documents']+=1
        if source not in ('cantonese_web_no_quotes','wiki_yue_body','wiki_yue_title','legacy_inmedia_article_dedup.csv'):
            counts['non_original_written_source_excluded']+=1;continue
        if fp in training_fps:counts['direct_training_fp_excluded']+=1;continue
        if fp in seen:continue
        seen.add(fp)
        choices=[m for m in WORD.finditer(text) if HAN.search(text[:m.start()]) and 2<=len(m.group())<=16]
        if not choices:continue
        m=min(choices,key=lambda m:stable(fp+str(m.start())))
        target=m.group()
        common=dict(group='mixed:'+group,source=source,source_fp=fp,split=fold('mixed:'+group),
            left=text[:m.start()][-512:],target=target,label_basis='Preserve original source span; synthetic typo intent derived from this span',
            origin='lm_selection_text_with_synthetic_typo',paragraph_hash=stable(text.casefold()))
        if not HAN.search(common['left']):continue
        result.append(dict(common,id='mixed:'+stable(fp),typed=target,panel='mixed_preservation'))
        # Romanization and unknown proper names are retention controls, not
        # automatically labelled English spelling-correction targets.
        if target.casefold() not in dictionary:continue
        for operation in ('substitute','delete','insert','transpose'):
            typo=perturb(target.lower(),operation,patterns,fp)
            if not typo:continue
            if target[:1].isupper():typo=typo[:1].upper()+typo[1:]
            result.append(dict(common,id='mixed:'+stable(fp)+':'+operation,typed=typo,
                panel='mixed_synthetic_typo',operation=operation))
    train.close();db.close()
    counts['eligible_documents']=sum(r['panel']=='mixed_preservation' for r in result)
    return result,dict(counts)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--github',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--github-per-fold',type=int,default=512)
    p.add_argument('--mixed-documents-per-fold',type=int,default=128)
    p.add_argument('--exclude-suite',type=Path,action='append',default=[],
        help='Exclude every group, paragraph and exact input from an exposed prior suite')
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    assert core.digest(args.github)==EXPECTED_GTC_SHA
    dictionary=set((ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_valid_words.txt').read_text().splitlines())
    model=ROOT/'app/src/main/assets/language_model/model.cng'
    with model.open('rb') as f:
        h=f.read(12);meta=json.loads(f.read(struct.unpack('<I',h[8:12])[0]))
    known_words=dictionary|{meta['tokens'][i].casefold() for i in meta['english_word_ids']}
    github,github_audit=github_rows(args.github,dictionary,known_words)
    # Stable global input/paragraph dedup before selecting any model or threshold.
    blocked_groups=set();keys=set();paragraphs=set();exclusions={}
    for previous in args.exclude_suite:
        binding=json.loads((previous/'protocol.json').read_text())
        for split in ('development','confirmation'):
            path=previous/(split+'.jsonl')
            assert core.digest(path)==binding['split_hashes'][split]
            for row in core.rows(path):
                blocked_groups.add(row['group']);keys.add((row['left'],row['typed'].casefold()));paragraphs.add(row['paragraph_hash'])
        exclusions[str(previous)]=core.digest(previous/'protocol.json')
    chosen=[];counts=collections.Counter();repos=collections.Counter()
    for row in sorted(github,key=lambda r:stable(r['id'])):
        split=row['split'];key=(row['left'],row['typed'].casefold())
        clean_key=(row['left'],row['target'].casefold())
        if row['group'] in blocked_groups or counts[split]>=args.github_per_fold or repos[row['repo']]>=4 or key in keys or clean_key in keys or row['paragraph_hash'] in paragraphs:continue
        keys.update([key,clean_key]);paragraphs.add(row['paragraph_hash']);repos[row['repo']]+=1;counts[split]+=1
        chosen.extend([row,dict(row,id=row['id']+':clean',typed=row['target'],panel='github_corrected_control')])
    recorded=ROOT/'build/real-revisit-20261025/data3/cases.jsonl'
    patterns=channel(core.rows(recorded))
    stage=Path('/Volumes/SSD/cantonese_ngram/data/shared-multilingual-v1-stage/staging.sqlite')
    training=Path('/Volumes/SSD/cantonese_ngram/data/shared-multilingual-v2-repair/training.sqlite')
    mixed,mixed_audit=mixed_rows(stage,training,patterns,dictionary)
    families=collections.defaultdict(list)
    for row in mixed:families[row['source_fp']].append(row)
    mixed_counts=collections.Counter();seen_groups=set(blocked_groups)
    for fp,family in sorted(families.items(),key=lambda item:stable(item[0])):
        first=family[0];split=first['split']
        if mixed_counts[split]>=args.mixed_documents_per_fold or first['group'] in seen_groups:continue
        family_keys={(r['left'],r['typed'].casefold()) for r in family}
        if family_keys&keys or first['paragraph_hash'] in paragraphs:continue
        keys.update(family_keys);paragraphs.add(first['paragraph_hash']);seen_groups.add(first['group']);mixed_counts[split]+=1
        chosen.extend(family)
    for split in ('development','confirmation'):
        rows=[r for r in chosen if r['split']==split]
        core.jsonl(args.output/(split+'.jsonl'),rows)
    dev=[r for r in chosen if r['split']=='development'];confirm=[r for r in chosen if r['split']=='confirmation']
    assert not {r['group'] for r in dev}&{r['group'] for r in confirm}
    assert not {(r['left'],r['typed'].casefold()) for r in dev}&{(r['left'],r['typed'].casefold()) for r in confirm}
    assert not {r['paragraph_hash'] for r in dev}&{r['paragraph_hash'] for r in confirm}
    protocol=dict(schema='independent-autocorrect-supplement-v1',seed=SEED,builder_sha256=core.digest(__file__),
        inference_started=False,parameters_selected=False,github=github_audit,mixed=mixed_audit,
        source_hashes=dict(github=core.digest(args.github),recorded_channel=core.digest(recorded),vocabulary_model=core.digest(model)),
        excluded_prior_suites=exclusions,excluded_prior_groups=len(blocked_groups),
        split_hashes={s:core.digest(args.output/(s+'.jsonl')) for s in ('development','confirmation')},
        counts={s:dict(collections.Counter(r['panel'] for r in chosen if r['split']==s)) for s in ('development','confirmation')},
        channel_patterns=[dict(operation=k[0],original=k[1],typed=k[2],count=v) for k,v in sorted(patterns.items())],
        isolation='Repository or original mixed-document group, paragraph hash and exact inference input disjoint between folds',
        confirmation_policy='Do not score confirmation until a single development-selected candidate is frozen; report once and mark exposed',
        limitations=['Repository edits have inferred typo labels and documentation domain; not real phone/chat traffic.',
            'GitHub controls are corrected-source counterparts, not sampled natural clean-word prevalence.',
            'Mixed errors are synthetic, drawn from recorded fit-fold edit patterns; original contexts are LM selection text.',
            'Mixed preservation asserts retention of recorded source spans, not independently reviewed spelling correctness.',
            'Current mixed direct training-FP overlap excluded; inherited LM exposure remains unproven.',
            'No claims of population weighting, conversational phone replay or true bilingual-chat qualification.'])
    core.write_json(args.output/'protocol.json',protocol)
    print(json.dumps({k:v for k,v in protocol.items() if k!='channel_patterns'},indent=2))


if __name__=='__main__':main()
