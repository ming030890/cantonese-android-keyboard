#!/usr/bin/env python3
"""Fit a small shared-LM score calibration on SSD TRAIN examples, group-disjoint."""
import argparse
import hashlib
import json
import math
import sqlite3
import struct
from pathlib import Path
import numpy as np
from audit_candidate_pipeline import digest, dictionary, probe
from audit_prediction_context import decode_context

SEED='mixed-ranking-20261021-v1'
def h(s):return hashlib.sha256((SEED+s).encode()).hexdigest()
def part(group):
    n=int(h(group)[:8],16)%10
    return 'train' if n<6 else 'selection' if n<8 else 'evaluation'
def metrics(rows,orders,partition):
    selected=[(r,o) for r,o in zip(rows,orders) if part(r['group'])==partition]
    ranks=[o.index(r['target'])+1 if r['target'] in o else math.inf for r,o in selected]
    result=dict(n=len(ranks),top1=sum(n==1 for n in ranks),top8=sum(n<=8 for n in ranks),
                generated=sum(math.isfinite(n) for n in ranks),mrr=sum(1/n for n in ranks))
    return dict(result,**{k+'_rate':result[k]/max(1,result['n']) for k in ('top1','top8','generated','mrr')})

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--policy',type=Path);p.add_argument('--evaluate',action='store_true');p.add_argument('--frozen-selection',type=Path)
    a=p.parse_args()
    if a.evaluate and not a.frozen_selection:p.error('--evaluate requires --frozen-selection')
    root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets';ssd=Path('/Volumes/SSD/cantonese_ngram')
    model=assets/'language_model/model.cng';raw=model.read_bytes();meta=json.loads(raw[12:12+struct.unpack('<I',raw[8:12])[0]])
    phrase_path=ssd/'data/shared-multilingual-v1-phrases/phrase-supervision.jsonl'
    train_path=ssd/'data/shared-multilingual-v2-repair/training.sqlite'
    cache_path=root/'build/mixed-ranking-features.json'
    # Feature extraction is independent of fitted ranking weights. Keep this cohort/cache frozen.
    if cache_path.exists():cache=json.loads(cache_path.read_text())
    else:
        tables={v:dictionary(assets/f'quick/quick{v}.bin') for v in (3,5)};codes={}
        for v,table in tables.items():
            options={}
            for i,chars in enumerate(table[26:]):
                code=chr(97+i//26)+chr(97+i%26)
                for rank,char in enumerate(chars):
                    if char not in options or (rank,code)<options[char]:options[char]=(rank,code)
            codes[v]={c:t[1] for c,t in options.items()}
        chinese={}
        for line in phrase_path.open():
            r=json.loads(line);word=r['text'];v=3 if int(h(word)[:8],16)%2 else 5
            if r['language']!='cantonese' or not 2<=len(word)<=4 or word in ('兒歌','自歌'):continue
            if all(c in codes[v] for c in word):
                chinese[word]=dict(group='phrase:'+word,target=word,observed=''.join(codes[v][c] for c in word),
                                   version=v,left='',right='',language='chinese')
        rows=sorted(chinese.values(),key=lambda r:h(r['group']))[:128]
        con=sqlite3.connect('file:'+str(train_path)+'?immutable=1',uri=True);english=[]
        for fp,ids in con.execute("SELECT fp,ids FROM docs WHERE language='english' ORDER BY id LIMIT 1200"):
            ids=json.loads(ids);options=[i for i,t in enumerate(ids[:64]) if meta['tokens'][t].isascii()
                and meta['tokens'][t].isalpha() and 3<=len(meta['tokens'][t])<=12 and meta['tokens'][t].lower()!='continue']
            if not options:continue
            i=min(options,key=lambda i:h(fp+str(i)))
            try:left=decode_context([meta['tokens'][t] for t in ids[:i] if t>=8])
            except UnicodeError:continue
            word=meta['tokens'][ids[i]].lower()
            english.append(dict(group='doc:'+fp,target=word,observed=word,version=3 if int(h(fp)[:8],16)%2 else 5,
                                left=left,right='',language='english'))
        rows+=sorted(english,key=lambda r:h(r['group']))[:128]
        print(f'Extracting {len(rows)} frozen SSD TRAIN examples',flush=True)
        extras=('-',assets/'handwriting/labels.txt',assets/'quick/quick3.bin',assets/'quick/quick5.bin')
        all_features=[]
        for i,r in enumerate(rows):
            chinese=probe(a.probe,model,[dict(code=r['observed'],left=r['left'],version=r['version'])],extra=extras)[0]['candidates']
            english=probe(a.probe,model,[dict(typed=r['observed'],left=r['left'])])[0]['candidates']
            candidates=list(dict.fromkeys(chinese+[r['observed']]+[c['word'] for c in english]))
            out=probe(a.probe,model,[dict(mixed=candidates,observed=r['observed'],left=r['left'])])[0]
            r['candidates']=candidates;all_features.append(out['features'])
            if (i+1)%16==0:print(f'{i+1}/{len(rows)} scored',flush=True)
        cache=dict(rows=rows,features=all_features,seed=SEED,model_sha256=digest(model),phrase_source_sha256=digest(phrase_path),
                   training_source=str(train_path),training_bytes=train_path.stat().st_size,training_mtime_ns=train_path.stat().st_mtime_ns,
                   feature_probe_sha256=digest(a.probe))
        cache_path.write_text(json.dumps(cache))
    assert cache['model_sha256']==digest(model)
    rows=cache['rows'];xs=[np.array(x) for x in cache['features']]
    def han(text):return any(0x3400<=ord(c)<=0x9fff or 0xf900<=ord(c)<=0xfaff or 0x20000<=ord(c)<=0x323af for c in text)
    before=[]
    for r,x in zip(rows,xs):
        n_chinese=next((i for i,c in enumerate(r['candidates']) if not han(c)),len(x))
        active=list(range(min(8,n_chinese)))+list(range(n_chinese,len(x)))
        indices=sorted(active,key=lambda i:(not han(r['candidates'][i]),-x[i,1],i))+list(range(min(8,n_chinese),n_chinese))
        before.append([r['candidates'][i] for i in indices])
    train=[i for i,r in enumerate(rows) if part(r['group'])=='train' and r['target'] in r['candidates']]
    scales=np.maximum(np.std(np.vstack([xs[i] for i in train]),axis=0),.1)
    # Listwise softmax ranking. No language-priority feature or lexical exception.
    trials=[]
    for reg in (.001,.01,.1):
        w=np.zeros(4)
        for epoch in range(800):
            g=np.zeros(4)
            for i in train:
                x=xs[i]/scales;z=x@w;z-=z.max();q=np.exp(z);q/=q.sum()
                g+=q@x-x[rows[i]['candidates'].index(rows[i]['target'])]
            w-=.15/(1+epoch/400)*(g/len(train)+reg*w)
        weights=(w/scales).tolist()
        orders=[[r['candidates'][i] for i in sorted(range(len(x)),key=lambda i:(-float(x[i]@np.array(weights)),i))] for r,x in zip(rows,xs)]
        trials.append(dict(weights=weights,regularization=reg,selection=metrics(rows,orders,'selection')))
    winner=max(trials,key=lambda t:(t['selection']['top1_rate'],t['selection']['mrr_rate'],t['regularization']))
    policy=dict(weights=winner['weights'],features=['sequence_log_probability','unit_log_probability','cohesion_log_probability','english_key_edit_distance'],
                model_sha256=digest(model),cohort_sha256=digest(cache_path),seed=SEED,fit_rows=len(train),
                scope='SSD TRAIN-derived phrases and natural English contexts; group-disjoint calibration; developmental, not an untouched test')
    report=dict(policy=policy,trials=trials,selection=winner['selection'],baseline_selection=metrics(rows,before,'selection'),
                cohorts={k:sum(part(r['group'])==k for r in rows) for k in ('train','selection','evaluation')})
    orders=[[r['candidates'][i] for i in sorted(range(len(x)),key=lambda i:(-float(x[i]@np.array(policy['weights'])),i))] for r,x in zip(rows,xs)]
    report['selection_by_language']={lang:dict(before=metrics([r for r in rows if r['language']==lang],[o for r,o in zip(rows,before) if r['language']==lang],'selection'),
                                            after=metrics([r for r in rows if r['language']==lang],[o for r,o in zip(rows,orders) if r['language']==lang],'selection')) for lang in ('chinese','english')}
    if a.evaluate:
        assert json.loads(a.frozen_selection.read_text())==report,'Selection changed after freeze'
        orders=[[r['candidates'][i] for i in sorted(range(len(x)),key=lambda i:(-float(x[i]@np.array(policy['weights'])),i))] for r,x in zip(rows,xs)]
        report['evaluation_by_language']={lang:dict(before=metrics([r for r in rows if r['language']==lang],[o for r,o in zip(rows,before) if r['language']==lang],'evaluation'), after=metrics([r for r in rows if r['language']==lang],[o for r,o in zip(rows,orders) if r['language']==lang],'evaluation')) for lang in ('chinese','english')}
        report.update(evaluation=metrics(rows,orders,'evaluation'),baseline_evaluation=metrics(rows,before,'evaluation'),frozen_selection_sha256=digest(a.frozen_selection))
    if a.policy:a.policy.write_text(json.dumps(policy,indent=2)+'\n')
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))

if __name__=='__main__':main()
