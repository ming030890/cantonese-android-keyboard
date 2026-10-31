#!/usr/bin/env python3
"""Freeze conversation-disjoint public-chat preservation and typo diagnostics.

Natural spelling labels below are manual source-text review, before inference.
Copy controls assert source preservation, not independently confirmed intent.
Synthetic typo panels are reported separately from naturally observed errors.
"""
import argparse,collections,hashlib,json,re,sqlite3,unicodedata
from pathlib import Path
import autocorrect_eval_v2 as core
from build_independent_autocorrect_eval import channel,perturb
ROOT=Path(__file__).resolve().parents[1]
WORD=re.compile(r"(?<![A-Za-z0-9_'])[A-Za-z]+(?:'[A-Za-z]+)*(?![A-Za-z0-9_'])")
NATURAL={'ahd':'had','beutiful':'beautiful','bthe':'the','cagar':'cigar',
 'dont':"don't",'ejoying':'enjoying','gess':'guess','i\'l':"I'll",
 'quarentine':'quarantine','sccissor':'scissor','thats':"that's",'theres':"there's",
 'wasd':'was','woth':'with'}
# Source-attested conversational forms and independently recognizable names.
PRESERVE={'awwww','brayden','cya','goliaths','hah','haha','hahaha','maci','mmmm',
 'nite','ohhh','okk','omg'}
QUARANTINE={'eet','mato','jadw','tiime'}

def stable(s):return hashlib.sha256(('chat-keep-20261031:'+s).encode()).hexdigest()
def comparison(s):return ' '.join(unicodedata.normalize('NFC',s).split()).replace('’',"'").replace('‘',"'").casefold()

def main():
 root=ROOT/'build/english-keep-20261031';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=root/'fresh-chat');parser.add_argument('--review',type=Path);args=parser.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=False)
 source=ROOT/'build/english-chat-20261030/admitted-utterances.jsonl';rows=core.rows(source)
 review=json.load(open(args.review)) if args.review else None
 if review:assert review['source_sha256']==core.digest(source)
 overrides={r['source_id']:{w.casefold():t for w,t in r['tokens'].items()} for r in review['overrides']} if review else {}
 train_groups={r['group'] for r in rows if r['partition']=='train'}
 train_text={comparison(r['text']) for r in rows if r['partition']=='train'}
 training=Path('/Volumes/SSD/cantonese_ngram/data/shared-multilingual-v2-repair/training.sqlite')
 db=sqlite3.connect(f'file:{training}?mode=ro&immutable=1',uri=True)
 blocked=set()
 paths=[ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl',ROOT/'build/english-listwise-20261030/joint-features/cases.jsonl']
 for old in ('build/independent-autocorrect-eval-20261030/frozen-v2','build/bilingual-residual-20261030/fresh-eval','build/english-char-gate-20261030/fresh-eval'):
  paths.extend(ROOT/old/(part+'.jsonl') for part in ('development','confirmation'))
 for path in paths:blocked.update((c['left'],c['typed'].casefold()) for c in core.rows(path))
 dictionary=set(l.split('\t')[0] for l in (ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_channel_data/lexicon.tsv').read_text().splitlines())
 patterns=channel(core.rows(ROOT/'build/real-revisit-20261025/data3/cases.jsonl'))
 eligible=[];audit=collections.Counter()
 for r in rows:
  if r['partition']!='selection':continue
  audit['selection_origins']+=1
  if r['group'] in train_groups:audit['train_conversation_excluded']+=1;continue
  text=comparison(r['text']);fp=hashlib.sha256(text.encode()).hexdigest()
  if text in train_text or db.execute('select 1 from docs where fp=? limit 1',(fp,)).fetchone():audit['train_text_or_model_fp_excluded']+=1;continue
  eligible.append(r)
 db.close()
 seen_text=set();seen_inputs=set(blocked);chosen=[];quarantine=[]
 for r in sorted(eligible,key=lambda r:stable(r['group']+r['id'])):
  text=unicodedata.normalize('NFC',r['text']).replace('’',"'").replace('‘',"'")
  key=comparison(text)
  if key in seen_text:audit['duplicate_utterance_excluded']+=1;continue
  seen_text.add(key);split='development' if int(stable(r['group'])[:8],16)%2==0 else 'confirmation'
  for m in WORD.finditer(text):
   typed=m.group();w=typed.casefold()
   if not 2<=len(w)<=24:continue
   left=text[:m.start()][-512:];ik=(left,w)
   if ik in seen_inputs:audit['previous_or_duplicate_input_excluded']+=1;continue
   base=dict(id='chat:'+stable(r['group']+r['id']+str(m.start())),group=r['group'],source='photochat',source_utterance_sha256=stable(key),left=left,typed=typed,target=typed,split=split,source_id=r['id'],source_partition='selection',origin='public_chat_source',prior_words=len(WORD.findall(left)))
   override=overrides.get(r['id'],{})
   if w in override:
    target=override[w]
    if target is None:quarantine.append(dict(base,utterance=text,label_basis='Full source-text review: uncertain intent'));continue
    chosen.append(dict(base,target=target,panel='chat_natural_spelling',label_basis='Full source-text manual spelling/punctuation review before candidate inference'));seen_inputs.add(ik);continue
   if w in NATURAL:
    target=NATURAL[w]
    if typed.isupper():target=target.upper()
    elif typed[:1].isupper():target=target[:1].upper()+target[1:]
    chosen.append(dict(base,target=target,panel='chat_natural_spelling',label_basis='Manual unambiguous spelling/punctuation review before model inference'));seen_inputs.add(ik);continue
   if w in QUARANTINE or (w not in dictionary and w not in PRESERVE):
    quarantine.append(dict(base,utterance=text,label_basis='Uncertain intended spelling or segmentation'));continue
   family=[dict(base,panel='chat_preservation',label_basis='Retain valid original public-chat spelling; no independently captured intent')]
   if w in dictionary and "'" not in w and len(w)>=3:
    operation=('substitute','delete','insert','transpose')[int(stable(base['id'])[:8],16)%4]
    typo=perturb(w,operation,patterns,base['id'])
    if typo and typo not in ('tue','ir'):
     if typed.isupper():typo=typo.upper()
     elif typed[:1].isupper():typo=typo[:1].upper()+typo[1:]
     if (left,typo.casefold()) not in seen_inputs and (left,typo.casefold())!=ik:
      family.append(dict(base,id=base['id']+':synthetic',typed=typo,panel='chat_synthetic_typo',operation=operation,label_basis='One TRAIN-recorded phone-error operation applied to source word; not natural observed typo'))
   for c in family:
    ck=(c['left'],c['typed'].casefold())
    if ck not in seen_inputs:chosen.append(c);seen_inputs.add(ck)
 audit['eligible_source_conversations']=len({r['group'] for r in eligible});audit['eligible_unique_utterances']=len(seen_text)
 hashes={};counts={}
 for split in ('development','confirmation'):
  values=[r for r in chosen if r['split']==split];core.jsonl(out/(split+'.jsonl'),values);hashes[split]=core.digest(out/(split+'.jsonl'));counts[split]=dict(collections.Counter(r['panel'] for r in values))
 assert not {r['group'] for r in chosen}&train_groups
 assert not {(r['left'],r['typed'].casefold()) for r in chosen}&blocked
 assert not {r['group'] for r in chosen if r['split']=='development'}&{r['group'] for r in chosen if r['split']=='confirmation'}
 core.jsonl(out/'quarantine.jsonl',quarantine)
 core.write_json(out/'protocol.json',dict(schema='public-chat-keep-eval-v2' if review else 'public-chat-keep-eval-v1',source_sha256=core.digest(source),script_sha256=core.digest(__file__),review_sha256=core.digest(args.review) if review else None,split_hashes=hashes,counts=counts,audit=dict(audit),natural_spelling_annotations=NATURAL,conversational_preservation=sorted(PRESERVE),quarantined_forms=sorted(QUARANTINE),train_group_overlap=0,train_model_fingerprint_overlap=0,previous_exact_input_overlap=0,model_outputs_used_for_sampling=False,confirmation_scored=False,limitations=['Small public PhotoChat source, not private user typing or tap traces','Natural typo labels manually reviewed from source, not supplied by authors','Preservation asserts retaining source text, not confirmed user intent; real-word source mistakes may remain','Synthetic typo panel is not natural error prevalence','Direct current CNN TRAIN fingerprints and conversation groups excluded; inherited exposure remains incompletely audited']))
 print(json.dumps(dict(counts=counts,audit=dict(audit)),indent=2))
if __name__=='__main__':main()
