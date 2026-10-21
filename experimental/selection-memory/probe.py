#!/usr/bin/env python3
"""Exercise learn/undo on real native candidates; measure cached lookup only."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import sys
import time

from selection_memory import SelectionMemory


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summary(values):
    return dict(n=len(values), median_us=statistics.median(values),
                p95_us=sorted(values)[int(len(values)*.95)])


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    root=Path(__file__).resolve().parents[2]
    sys.path.insert(0,str(root/'scripts'))
    from audit_candidate_pipeline import probe
    assets=root/'app/src/main/assets';model=assets/'language_model/model.cng'
    source=root/'build/mixed-ranking-features.json'
    rows=json.loads(source.read_text())['rows']
    memory=SelectionMemory()
    smoke={}
    for version in (3,5):
        cs=probe(a.probe,model,[dict(code='humo',version=version)],
            extra=('-',assets/'handwriting/labels.txt',assets/'quick/quick3.bin',assets/'quick/quick5.bin'))[0]['candidates']
        en=probe(a.probe,model,[dict(typed='humo',spelling_limit=8)])[0]['candidates']
        pool=list(dict.fromkeys(cs+['humo']+[r['word'] for r in en]))
        ranked=probe(a.probe,model,[dict(mixed=pool,observed='humo')])[0]
        base=[pool[i] for i in ranked['order']]
        event=memory.record(f'quick{version}','humo','兒歌',base,explicit=True,committed=True,eligible=True)
        learned=memory.rank_recent(f'quick{version}','humo',base)
        assert learned[0]=='兒歌' and set(learned)==set(base)
        assert memory.undo(event)
        restored=memory.rank_recent(f'quick{version}','humo',base)
        assert restored==base
        smoke[str(version)]=dict(before=base[:8],after_explicit_selection=learned[:8],after_undo=restored[:8])
    # These are real frozen SSD candidate pools. This is a cache microbenchmark,
    # not a replay of observed user choices or a personalization accuracy score.
    for r in rows:
        if r['target'] in r['candidates']:
            memory.record(f"quick{r['version']}",r['observed'],r['target'],r['candidates'],
                explicit=True,committed=True,eligible=True)
    baseline=[];personalized=[]
    for repeat in range(8):
        for r in rows:
            for name in (('base','memory') if repeat%2==0 else ('memory','base')):
                started=time.perf_counter_ns()
                if name=='base':out=list(r['candidates'])
                else:out=memory.rank_recent(f"quick{r['version']}",r['observed'],r['candidates'])
                (baseline if name=='base' else personalized).append((time.perf_counter_ns()-started)/1000)
                assert len(out)==len(r['candidates'])
    result=dict(scope='Experimental host cache microbenchmark and learn/undo smoke; no Android integration or user-replay quality claim.',
        binding=dict(model=digest(model),probe=digest(a.probe),cohort=digest(source),implementation=digest(Path(__file__).with_name('selection_memory.py'))),
        smoke=smoke,latency=dict(base_copy=summary(baseline),cached_selection_memory=summary(personalized)),
        no_new_model=True,no_production_changes=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    memory.close()
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
