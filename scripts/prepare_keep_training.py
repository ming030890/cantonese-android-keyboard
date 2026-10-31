#!/usr/bin/env python3
"""TRAIN correction anchors plus diverse, conversation-disjoint chat controls."""
import collections,json,re
from pathlib import Path
import autocorrect_eval_v2 as core
from build_chat_keep_eval import stable,WORD
ROOT=Path(__file__).resolve().parents[1]
SLANG=set('lol haha hahaha hmm hmmm ohh ohhh aww awww awwww omg ttyl idk cya thx thnx nite okey okie dokie kk yay yea woah hehe fam chillin yall doin goin lmao gf ikr soo sooo soooo sooooo mmm mmmm umm ummm hii yess yaa oo ooo oooh ooooh'.split())

def main():
 root=ROOT/'build/english-keep-20261031';out=root/'training';out.mkdir(exist_ok=False)
 blocked=set();evalgroups=set();paths=[ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl']
 for old in ('build/independent-autocorrect-eval-20261030/frozen-v2','build/bilingual-residual-20261030/fresh-eval','build/english-char-gate-20261030/fresh-eval','build/english-keep-20261031/fresh-chat-v2'):
  paths.extend(ROOT/old/(p+'.jsonl') for p in ('development','confirmation'))
 for p in paths:
  for c in core.rows(p):blocked.add((c['left'],c['typed'].replace('’',"'").casefold()));evalgroups.add(c['group'])
 legacy=core.rows(ROOT/'build/english-listwise-20261030/joint-features/cases.jsonl');chosen=[];seen=set(blocked);excluded=collections.Counter()
 for c in legacy:
  c=dict(c,group=c.get('group','legacy:'+c['id']))
  key=(c['left'],c['typed'].replace('’',"'").casefold())
  if c['fit_split'] not in ('fit','calibration') or not c.get('target') or c.get('noise'):continue
  if key in seen or c['group'] in evalgroups or c['typed'].casefold() in ('tue','ir') or (c.get('target') or '').casefold() in ('tue','ir'):excluded['legacy_blocked_or_duplicate']+=1;continue
  chosen.append(c);seen.add(key)
 dictionary=set(l.split('\t')[0] for l in (ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_channel_data/lexicon.tsv').read_text().splitlines())
 forms=json.load(open(ROOT.parent/'QwenASR/crates/cantonese-ngram/src/english_apostrophe_forms.json'))
 bare={r['word'].replace("'",'').casefold() for r in forms}
 source=ROOT/'build/english-chat-20261030/admitted-utterances.jsonl';rs=core.rows(source);per_word=collections.Counter();per_group=collections.Counter();chat=[];utterances=set()
 for r in sorted((r for r in rs if r['partition']=='train'),key=lambda r:stable('training:'+r['group']+r['id'])):
  if r['group'] in evalgroups:continue
  text=r['text'].replace('’',"'");textkey=' '.join(text.casefold().split())
  if textkey in utterances:continue
  utterances.add(textkey);local=[]
  for m in WORD.finditer(text):
   typed=m.group();w=typed.casefold();left=text[:m.start()][-512:];key=(left,w)
   if not left.strip() or not typed.isascii() or not typed.isalpha() or not 2<=len(w)<=24 or w in bare or w in ('tue','ir') or (w not in dictionary and w not in SLANG) or key in seen:continue
   local.append((stable(r['id']+str(m.start())),m,key))
  for _,m,key in sorted(local)[:3]:
   w=m.group().lower()
   if per_word[w]>=100 or per_group[r['group']]>=100:continue
   split='fit' if int(stable('fit:'+r['group'])[:8],16)%5 else 'calibration'
   c=dict(id='chat-train:'+stable(r['group']+r['id']+str(m.start())),group=r['group'],left=key[0],typed=m.group(),target=m.group(),source='chat_train_preservation',fit_split=split,noise=False,label_basis='Source-copy TRAIN dictionary word or independently TRAIN-reviewed conversational form; bare apostrophe forms quarantined')
   chat.append(c);seen.add(key);per_word[w]+=1;per_group[r['group']]+=1
   if len(chat)>=20000:break
  if len(chat)>=20000:break
 chosen+=chat;core.jsonl(out/'cases.jsonl',chosen)
 assert not {(c['left'],c['typed'].replace('’',"'").casefold()) for c in chosen}&blocked
 assert not {c['group'] for c in chosen}&evalgroups
 core.write_json(out/'protocol.json',dict(schema='bidirectional-keep-training-v1',cases_sha256=core.digest(out/'cases.jsonl'),source_chat_sha256=core.digest(source),counts={str(k):v for k,v in collections.Counter((c['fit_split'],c['source']) for c in chosen).items()},chat_controls=len(chat),excluded=dict(excluded),eval_exact_input_overlap=0,eval_group_overlap=0,chat_slang_review=sorted(SLANG),sampling='Input-only hash order, max3 words per unique source utterance, max100 per word and conversation; all selected source origins TRAIN',limitations=['Public chat copy labels are noisy source-preservation supervision, not independently confirmed intent','Inherited neural training exposure is unchanged','No user-supplied tue/ir examples used for new fitting']))
 print('TRAIN',len(chosen),'CHAT',len(chat),dict(collections.Counter(c['fit_split'] for c in chosen)),flush=True)
if __name__=='__main__':main()
