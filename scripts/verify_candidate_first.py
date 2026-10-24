#!/usr/bin/env python3
"""Evaluate the production joined pipeline on frozen bilingual cohorts; no fitting."""
import argparse
import hashlib
import json
import statistics
import subprocess
from pathlib import Path


def evaluate(binary, assets, rows, preview):
    queries=[dict(code=r['observed'],left=r['left'],right=r.get('right',''),version=r['version'],preview=preview) for r in rows]
    p=subprocess.run([str(binary.resolve()),str(assets.resolve())],input=''.join(json.dumps(q,ensure_ascii=False)+'\n' for q in queries),text=True,capture_output=True,check=True)
    out=[json.loads(l) for l in p.stdout.splitlines()]
    assert len(out)==len(rows)
    return out


def metrics(rows, outputs):
    ranks=[o['display'].index(r['target'])+1 if r['target'] in o['display'] else float('inf') for r,o in zip(rows,outputs)]
    return dict(n=len(rows),top1=sum(x==1 for x in ranks),top8=sum(x<=8 for x in ranks),generated=sum(x!=float('inf') for x in ranks),mrr=sum(1/x for x in ranks)/max(1,len(rows)),median_ms=statistics.median(o['elapsed_ms'] for o in outputs))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline',type=Path,required=True);p.add_argument('--candidate',type=Path,required=True)
    p.add_argument('--cohort',type=Path,action='append',required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--preview',action='store_true');a=p.parse_args();assets=Path('app/src/main/assets')
    mismatches=0
    report=dict(cohorts={},preview=a.preview,baseline_sha256=hashlib.sha256(a.baseline.read_bytes()).hexdigest(),candidate_sha256=hashlib.sha256(a.candidate.read_bytes()).hexdigest())
    for path in a.cohort:
        rows=json.loads(path.read_text())['rows']
        old=evaluate(a.baseline,assets,rows,a.preview);print(f'{path.name} baseline done',flush=True)
        new=evaluate(a.candidate,assets,rows,a.preview)
        clean=lambda output:{k:v for k,v in output.items() if k not in ('elapsed_ms','scoring')}
        changed=[i for i,(before,after) in enumerate(zip(old,new)) if clean(before)!=clean(after)]
        mismatches+=len(changed)
        assert all(o['scoring']['generation_complete'] and o['scoring']['generation_forwards']==0 for o in new)
        result=dict(changed_rows=changed,before=metrics(rows,old),after=metrics(rows,new),cohort_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),rows=rows,baseline=old,candidate=new)
        panels=sorted({r.get('panel',r.get('language','all')) for r in rows})
        result['panels']={panel:{label:metrics([r for r in rows if r.get('panel',r.get('language','all'))==panel],[o for r,o in zip(rows,outs) if r.get('panel',r.get('language','all'))==panel]) for label,outs in [('before',old),('after',new)]} for panel in panels}
        report['cohorts'][path.name]=result
        print(path.name,json.dumps({k:result[k] for k in ['before','after','panels']}),flush=True)
        a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')

    print(f'Exact output mismatches: {mismatches}',flush=True)
    if mismatches: raise SystemExit(1)


if __name__=='__main__':main()
