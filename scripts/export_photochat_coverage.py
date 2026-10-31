#!/usr/bin/env python3
"""Audit admitted public chat TRAIN/selection words without model predictions."""
import collections,json,re,sqlite3
from pathlib import Path
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1];out=ROOT/'build/english-chat-20261030';out.mkdir(exist_ok=False)
p=Path('/Volumes/SSD/cantonese_ngram/data/shared-multilingual-v1-stage/staging.sqlite');db=sqlite3.connect(f'file:{p}?mode=ro&immutable=1',uri=True)
rs=[]
for row in db.execute("select o.id,o.grp,o.partition,o.metadata,p.text from origins o join payloads p on p.fp=o.fp where o.partition in ('train','selection') and o.source='photochat'"):
 i,g,part,meta,text=row;rs.append(dict(id=i,group=g,partition=part,metadata=json.loads(meta),text=text))
core.jsonl(out/'admitted-utterances.jsonl',rs);dictionary=set((ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_valid_words.txt').read_text().splitlines());counts=collections.Counter();examples=collections.defaultdict(list);word=re.compile(r"(?<![A-Za-z])[A-Za-z]+(?:'[A-Za-z]+)*(?![A-Za-z])")
for r in rs:
 if r['partition']!='train':continue
 for m in word.finditer(r['text']):
  w=m.group().lower()
  if w in dictionary or len(w)<2 or len(w)>20:continue
  counts[w]+=1
  if len(examples[w])<3:examples[w].append(r['text'])
core.write_json(out/'unknown-word-types.json',dict(counts=counts,examples=examples));print('UTTERANCES',len(rs),dict(collections.Counter(r['partition'] for r in rs)),flush=True)
for w,n in counts.most_common(120):print(w,n,examples[w][:2],flush=True)
