#!/usr/bin/env python3
"""Unused author-edit repositories supply TRAIN only, paired with corrected controls."""
import collections,json
from pathlib import Path
import autocorrect_eval_v2 as core
from build_independent_autocorrect_eval import github_rows,stable
ROOT=Path(__file__).resolve().parents[1];out=ROOT/'build/english-listwise-20261030/real-author-data';out.mkdir(exist_ok=False)
groups=set();inputs=set()
for suite in (ROOT/'build/autocorrect-eval-v4/frozen',ROOT/'build/independent-autocorrect-eval-20261030/frozen-v2',ROOT/'build/bilingual-residual-20261030/fresh-eval',ROOT/'build/english-char-gate-20261030/fresh-eval'):
 for file in ([suite/'cases.jsonl'] if (suite/'cases.jsonl').exists() else [suite/'development.jsonl',suite/'confirmation.jsonl']):
  for c in core.rows(file):groups.add(c['group']);inputs.add((c['left'],c['typed'].casefold()))
dictionary=set((ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_valid_words.txt').read_text().splitlines())
import struct
with (ROOT/'app/src/main/assets/language_model/model.cng').open('rb') as f:
 h=f.read(12);meta=json.loads(f.read(struct.unpack('<I',h[8:12])[0]))
known=dictionary|{meta['tokens'][i].casefold() for i in meta['english_word_ids']};rows,audit=github_rows(ROOT/'build/independent-autocorrect-eval-20261030/raw/github-typo-corpus.v1.0.0.jsonl.gz',dictionary,known)
chosen=[];per=collections.Counter()
for c in sorted(rows,key=lambda c:stable('training:'+c['id'])):
 if c['group'] in groups or per[c['group']]>=4 or (c['left'],c['typed'].casefold()) in inputs or (c['left'],c['target'].casefold()) in inputs:continue
 part='fit' if int(stable('train-group:'+c['group'])[:8],16)%5 else 'calibration';per[c['group']]+=1
 common=dict(c,source='real_author_typo',fit_split=part,noise=False)
 chosen.extend([common,dict(common,id=c['id']+':clean',source='real_author_clean',typed=c['target'])])
 if len(chosen)>=8192:break
assert not inputs&{(c['left'],c['typed'].casefold()) for c in chosen}
core.jsonl(out/'cases.jsonl',chosen);core.write_json(out/'protocol.json',dict(schema='real-author-train-v1',rows=len(chosen),source_groups=len(per),eval_source_group_overlap=0,eval_input_overlap=0,upstream=audit,case_sha256=core.digest(out/'cases.jsonl'),limitation='Automatically labelled repository edits; not phone taps or natural chat'))
print('TRAIN',len(chosen),dict(collections.Counter(c['fit_split'] for c in chosen)),flush=True)
