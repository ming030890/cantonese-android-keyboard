#!/usr/bin/env python3
"""Original-stream phone touch evaluation. References are labels, never features.

Protocol freezes a metadata whitelist before opening participant test logs.
Training is separate and synthetic-only. This module never fits on real taps.
"""
import argparse
import collections
import hashlib
import json
import re
import zipfile
from pathlib import Path

from align_mobile_targets import osa
from fit_tsi_touch import PROJECT, LETTERS, digest, metrics

STUDY = 'noisy_typing/phone/vt_exp2/'
POLICY = dict(schema='public-phone-touch-eval-v1',
              primary_study='vt_exp2', condition='space', device='Nexus 4, portrait, full size',
              archive_url='https://osf.io/download/7ev5z/', source='https://osf.io/5xwng/',
              training='Synthetic taps only; no real evaluation taps, references or outcomes may fit parameters',
              observations='First frame of each original tap; reconstruct closest key by rectangle distance with centre tie-break; apply recorded backspaces',
              context='Previous observed input within this trial only; LEFT/RIGHT/ORIG fields are never inference inputs',
              boundaries='Recorded spaces and explicit end-of-trial submission; word spans come from observed text',
              labels='Unique optimal word alignment, allowing merges/splits up to three words; ambiguous labels excluded from F0.5, retained in whole-trial CER',
              metrics=['F0.5', 'precision', 'recall', 'clean_changes', 'whole_trial_CER', 'per_participant', 'latency'],
              excluded_conditions=['blindfolded', 'no feedback', 'no spaces', 'swipe spaces', 'small/tiny keyboard', 'watch', 'midair', 'desktop'],
              limits=['English mobile-email copy typing on older study devices; not modern chat or Cantonese/English qualification',
                      'Recognition occurred after sentences in original study; replay at word boundaries differs from original interaction',
                      'Key outputs reconstructed from geometry, not logged Gboard outputs',
                      'No usage-population weights are known; report corpus and participant results, not deployment precision',
                      'No per-key intended labels inferred for model fitting from these evaluation references'])


def dump(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2)+'\n')


def layout(text):
    keys = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        c, *values = line.strip().split(';')
        if len(values) != 4:
            raise ValueError('Malformed key geometry')
        keys[c] = tuple(map(float, values))
    if not set(LETTERS+' ').issubset({(' ' if c == '<sp>' else c) for c in keys}):
        raise ValueError('Incomplete keyboard')
    return keys


def nearest(keys, x, y):
    def cost(c):
        cx,cy,w,h=keys[c]
        return (max(abs(x-cx)-w/2,0)**2+max(abs(y-cy)-h/2,0)**2,
                (x-cx)**2+(y-cy)**2,c)
    return min(keys, key=cost)


def records(text):
    record = None
    for line in text.splitlines()+['ID: __end__']:
        if line.startswith('ID: '):
            if record is not None:
                if 'REF' not in record or not record['IN']:
                    raise ValueError('Missing reference/input')
                yield record
            record = {'ID':line[4:], 'IN':[]}
        elif line.startswith('IN: '):
            record['IN'].append(line[4:])
        elif ': ' in line and record is not None:
            k,v=line.split(': ',1)
            record[k]=v


def observed_stream(record, keys):
    chars=[]; points=[]; audit=collections.Counter()
    for chunk in record['IN']:
        for event in chunk.split('|'):
            # No word-aligned or locked/swipe streams admitted by this protocol.
            frame=event.split(';',1)[0].split(',')
            if len(frame)!=3:
                raise ValueError('Unsupported input event: '+event[:60])
            x,y,t=map(float,frame)
            if not all(__import__('math').isfinite(v) for v in (x,y,t)):
                raise ValueError('Nonfinite input event')
            key=nearest(keys,x,y)
            audit['tap_events']+=1
            if key=='<b>':
                audit['backspaces']+=1
                if chars:
                    chars.pop();points.pop()
            else:
                if key not in LETTERS and key!='<sp>':
                    raise ValueError('Unsupported key: '+key)
                chars.append(' ' if key=='<sp>' else key);points.append([x,y])
    return ''.join(chars),points,dict(audit)


def word_labels(observed, reference):
    """All optimal word alignments; never silently choose a convenient tie.

    Reference word insertions cost their length. Observed insertions likewise.
    A merged observed word can label up to 3 reference words. Split observations
    retain ambiguous labels; whole-trial CER still counts their effect.
    """
    obs=observed.split(); gold=reference.split(); n,m=len(obs),len(gold)
    edges=collections.defaultdict(list)
    for i in range(n+1):
        for j in range(m+1):
            if i<n:
                edges[i,j].append((i+1,j,len(obs[i]),(i,'')))
            if j<m:
                edges[i,j].append((i,j+1,len(gold[j]),None))
            if i<n:
                for k in range(1,min(3,m-j)+1):
                    text=' '.join(gold[j:j+k])
                    edges[i,j].append((i+1,j+k,osa(obs[i],text),(i,text)))
            if j<m:
                for k in range(2,min(3,n-i)+1):
                    edges[i,j].append((i+k,j+1,osa(' '.join(obs[i:i+k]),gold[j]),('split',i,k)))
    f=[[10**9]*(m+1) for _ in range(n+1)]; f[0][0]=0
    for i in range(n+1):
        for j in range(m+1):
            for a,b,c,_ in edges[i,j]:
                f[a][b]=min(f[a][b],f[i][j]+c)
    b=[[10**9]*(m+1) for _ in range(n+1)]; b[n][m]=0
    for i in range(n,-1,-1):
        for j in range(m,-1,-1):
            for a,z,c,_ in edges[i,j]:
                b[i][j]=min(b[i][j],c+b[a][z])
    possible=[set() for _ in obs]
    for (i,j), transitions in edges.items():
        for a,z,c,label in transitions:
            if f[i][j]+c+b[a][z]!=f[n][m] or label is None:
                continue
            if label[0]=='split':
                for index in range(label[1],label[1]+label[2]):
                    possible[index].add(None)
            else:
                possible[label[0]].add(label[1])
    return [next(iter(s)) if len(s)==1 and None not in s else None for s in possible]


def prepare(archive, protocol_file, output):
    protocol=json.loads(protocol_file.read_text())
    if digest(archive)!=protocol['archive_sha256']:
        raise ValueError('Archive differs from frozen protocol')
    trials=[];cases=[];audit=collections.Counter()
    with zipfile.ZipFile(archive) as z:
        keys=layout(z.read(STUDY+'keyboard_nexus4.txt').decode())
        geometry=[v for c in LETTERS for v in keys[c]]
        for name in protocol['evaluation_files']:
            participant=Path(name).stem.split('_')[0]
            for r in records(z.read(name).decode()):
                text,points,counts=observed_stream(r,keys)
                reference=r['REF'].strip().lower()
                if re.search('[^a-z ]',reference):
                    raise ValueError('Unexpected reference alphabet')
                audit.update(counts)
                matches=list(re.finditer('[a-z]+',text))
                labels=word_labels(text,reference)
                assert len(matches)==len(labels)
                identifier=f'vt_exp2:{participant}:{r["ID"]}'
                trial=dict(id=identifier,participant=participant,observed=text,reference=reference,words=[])
                for index,(match,target) in enumerate(zip(matches,labels)):
                    case=dict(id=identifier+f':{index}',participant=participant,trial=identifier,
                              typed=match.group(),left=text[:match.start()],target=target,
                              points=[v for p in points[match.start():match.end()] for v in p],geometry=geometry,
                              start=match.start(),end=match.end(),
                              boundary='space' if match.end()<len(text) else 'submission')
                    trial['words'].append(case['id']); cases.append(case)
                    audit['observed_words']+=1
                    audit['ambiguous_labels' if target is None else 'labelled_words']+=1
                audit['trials']+=1;trials.append(trial)
    output.mkdir(parents=True,exist_ok=True)
    for name,rows in [('cases.jsonl',cases),('trials.jsonl',trials)]:
        (output/name).write_text(''.join(json.dumps(r)+'\n' for r in rows))
    manifest=dict(protocol_sha256=digest(protocol_file),archive_sha256=digest(archive),audit=dict(audit),
                  participants=len({t['participant'] for t in trials}),
                  cases_sha256=digest(output/'cases.jsonl'),trials_sha256=digest(output/'trials.jsonl'),
                  inference_fields=['typed','left','points','geometry'],label_fields=['target','reference'],
                  real_data_used_for_training=False)
    dump(output/'manifest.json',manifest)
    return manifest


def freeze(archive, output):
    # Inventory and study documentation only. No participant log contents read.
    with zipfile.ZipFile(archive) as z:
        files=sorted(n for n in z.namelist() if n.startswith(STUDY+'test/') and n.endswith('_space.log'))
        if len(files)!=24:
            raise ValueError('Unexpected participant count')
        p=dict(POLICY,archive_sha256=digest(archive),evaluation_files=files,
               metadata_sha256={n:hashlib.sha256(z.read(n)).hexdigest() for n in [STUDY+'readme.txt',STUDY+'keyboard_nexus4.txt']})
    if output.exists() and json.loads(output.read_text())!=p:
        raise ValueError('Refusing to overwrite a different frozen protocol')
    dump(output,p)
    return p


def stream_metrics(trials, cases, predictions):
    replacements={c['id']:p for c,p in zip(cases,predictions)}
    by_id={c['id']:c for c in cases}
    total=collections.Counter()
    participants=collections.defaultdict(collections.Counter)
    for trial in trials:
        output=trial['observed']
        for identifier in reversed(trial['words']):
            p=replacements[identifier]
            c=by_id[identifier]
            if p is not None:
                output=output[:c['start']]+p+output[c['end']:]
        values=dict(character_errors=levenshtein(output,trial['reference']),
                    reference_characters=len(trial['reference']),exact_trials=int(output==trial['reference']),trials=1)
        total.update(values);participants[trial['participant']].update(values)
    def report(c):
        return dict(c,cer=c['character_errors']/max(1,c['reference_characters']))
    return dict(overall=report(total),participants={p:report(c) for p,c in participants.items()})


def levenshtein(a,b):
    row=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        new=[i]
        for j,y in enumerate(b,1):
            new.append(min(row[j]+1,new[-1]+1,row[j-1]+(x!=y)))
        row=new
    return row[-1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['freeze','prepare'])
    p.add_argument('--archive',type=Path,default=PROJECT/'build/touch-eval-public/noisy-typing.zip')
    p.add_argument('--protocol',type=Path,default=PROJECT/'config/english_iteration/2026-10-22/public-touch/protocol.json')
    p.add_argument('--output',type=Path,default=PROJECT/'build/touch-eval-public/prepared')
    args=p.parse_args()
    result=freeze(args.archive,args.protocol) if args.action=='freeze' else prepare(args.archive,args.protocol,args.output)
    print(json.dumps(result,indent=2))


if __name__=='__main__':
    main()
