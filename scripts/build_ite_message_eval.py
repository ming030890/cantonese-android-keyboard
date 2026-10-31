#!/usr/bin/env python3
"""Freeze whole unassisted ITE attempts, independent of model performance.

Uses processed public snapshots, not AC_WORD outputs. Selects declared
unassisted mobile participants and no-edit/no-ITE trials, then verifies their
chronological text trajectory. Severity balancing is an engineering stress
sample; source-frequency counts are retained separately.
"""
import argparse,collections,csv,hashlib,json,re
from pathlib import Path
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]
SEED='ite-whole-message-v5-20261031'

def stable(x):return hashlib.sha256((SEED+x).encode()).hexdigest()
def ident(x):return str(int(float(x)))
def repair_encoding(s):
 # Published UTF-8 CSVs contain C1 characters from Windows-1252 punctuation.
 # Repair only printable mappings, in both observations and labels.
 def repair(m):
  try:return m.group().encode('latin1').decode('cp1252')
  except UnicodeDecodeError:return m.group()
 return re.sub('[\u0080-\u009f]',repair,s)
def canonical(s):return repair_encoding(s).replace('’',"'").replace('‘',"'")
def words(s):return re.findall(r"[A-Za-z0-9]+(?:'[A-Za-z0-9]+)*",canonical(s).casefold())
def distance(a,b):
 row=list(range(len(b)+1))
 for i,x in enumerate(a):
  new=[i+1]
  for j,y in enumerate(b):new.append(min(new[-1]+1,row[j+1]+1,row[j]+(x!=y)))
  row=new
 return row[-1]
def severity(observed,target):
 n=distance(words(observed),words(target))
 if n:return 'one_error' if n==1 else 'two_errors' if n==2 else 'burst_3plus'
 return 'clean' if canonical(observed).strip()==canonical(target).strip() else 'formatting_only'

def reconstruct(logs,final):
 """Validate monotonic append-only entry; never infer chars from reference."""
 logs=sorted(logs,key=lambda r:(int(float(r['TIMESTAMP'])),len(r['INPUT']),r['_ordinal']))
 previous='';pieces=[];first=True;noops=0;seed=0;last_ms=-1
 for row in logs:
  current=repair_encoding(row['INPUT']);label=row['OBSERVED_KEY'].strip()
  if label not in ('','SPACE','SHIFT','END'):return None,'non_tap_or_ite_label:'+label
  if current==previous:noops+=1;continue
  if not current and previous and previous==final:continue # terminal study reset
  if not current.startswith(previous):return None,'non_append_snapshot'
  added=current[len(previous):]
  if first:
   if len(added)>8 or any(c.isspace() for c in added):return None,'large_initial_snapshot'
   seed=len(added);first=False
  elif len(added)!=1:return None,'batched_append_after_initial'
  if any(ord(c)<32 and c not in '\n\t' for c in added):return None,'control_character'
  timestamp=int(float(row['TIMESTAMP']))
  if timestamp<last_ms:raise AssertionError('Timestamp sort failed')
  pieces.append(dict(text=added,timestamp=timestamp,source_row=row['_ordinal']));last_ms=timestamp;previous=current
 if previous!=final:return None,'metadata_final_mismatch'
 if not pieces:return None,'empty_log'
 return dict(pieces=pieces,initial_snapshot_chars=seed,noops=noops),None

def events_for(text):
 result=[]
 # Word-sized text events still drive the production per-character key receiver.
 # Settle at delimiters; this measures eventual text quality, not phone timing.
 for chunk in re.findall(r'[^ \n]+| |\n',text):
  if chunk==' ':result.append(dict(kind='space'))
  elif chunk=='\n':result.append(dict(kind='enter'))
  else:result.append(dict(kind='type',text=chunk,settle=False))
 result.append(dict(kind='submit'))
 return result

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--raw',type=Path,default=Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-full-v5'));parser.add_argument('--output',type=Path,default=ROOT/'build/representative-eval-v5/ite-messages-v3');parser.add_argument('--per-stratum',type=int,default=96);a=parser.parse_args();a.output.mkdir(parents=True,exist_ok=False)
 intake=json.load(open(a.raw/'intake.json'));assert len(intake['members'])==3
 for info in intake['members'].values():assert Path(info['path']).stat().st_size==info['bytes']
 old=json.load(open(ROOT/'build/representative-eval-v5/known-ite-prompts.json'));blocked_prompts=set().union(*(set(v) for v in old.values()))
 with Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv').open() as f:sentences={r['SENTENCE_ID']:r for r in csv.DictReader(f)}
 people={};audit=collections.Counter()
 with (a.raw/'participants_labeled.csv').open() as f:
  for r in csv.DictReader(f):
   audit['participants_total']+=1
   if r['DEVICE']=='mobile' and r['KEYBOARD_TYPE']=='mobile' and r['USING_FEATURES']=='["no"]' and float(r['AC'] or 0)==0 and float(r['PREDICTION'] or 0)==0:
    people[ident(r['PARTICIPANT_ID'])]=r
 trial_people={};pool=collections.defaultdict(list)
 with (a.raw/'test_sections_labeled.csv').open() as f:
  for r in csv.DictReader(f):
   actor=r['PARTICIPANT_ID'];trial_people[r['TEST_SECTION_ID']]=actor
   if actor not in people:continue
   audit['unassisted_trials']+=1
   if any(float(r[k] or 0)!=0 for k in ('AC','PREDICTION','BACKSPACES')):audit['edited_or_assisted_excluded']+=1;continue
   if r['SENTENCE_ID'] in blocked_prompts:audit['previous_prompt_excluded']+=1;continue
   target=repair_encoding(sentences[r['SENTENCE_ID']]['SENTENCE']);observed=repair_encoding(r['USER_INPUT'])
   if observed!=r['USER_INPUT']:audit['repaired_windows_punctuation_trials']+=1
   ps=int(stable('person:'+actor)[:8],16)%2;ss=int(stable('prompt:'+r['SENTENCE_ID'])[:8],16)%2
   if ps!=ss:audit['cross_split_pair_excluded']+=1;continue
   split='development' if ps==0 else 'confirmation';tier=severity(observed,target)
   row=dict(id='ite-message:'+r['TEST_SECTION_ID'],trial=r['TEST_SECTION_ID'],participant=actor,prompt=r['SENTENCE_ID'],reference=target,observed=observed,stratum=tier,split=split,prompt_source=sentences[r['SENTENCE_ID']]['SOURCE'],platform='ios' if any(s in people[actor]['BROWSER'] for s in ('iPhone','iPad')) else 'android' if 'Android' in people[actor]['BROWSER'] else 'other_mobile')
   pool[split,tier].append(row)
 # Verify no admitted actor supplied any real-source AC-table training event.
 ac_actors=set();unmapped=0
 with Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/ac_words_en.csv').open() as f:
  for r in csv.DictReader(f):
   person=trial_people.get(ident(r['TEST_SECTION_ID']))
   if person:ac_actors.add(person)
   else:unmapped+=1
 candidates={}
 for key,rows in pool.items():
  count=0;perperson=collections.Counter();perprompt=collections.Counter()
  for r in sorted(rows,key=lambda r:stable(r['id'])):
   if r['participant'] in ac_actors:continue
   if perperson[r['participant']]>=4 or perprompt[r['prompt']]>=16:continue
   candidates[r['trial']]=r;perperson[r['participant']]+=1;perprompt[r['prompt']]+=1;count+=1
   if count>=a.per_stratum*5:break
 print('LOG CANDIDATES',len(candidates),'POOL',{str(k):len(v) for k,v in pool.items()},flush=True)
 logs=collections.defaultdict(list)
 with (a.raw/'log_data_labeled.csv').open() as f:
  for i,row in enumerate(csv.DictReader(f)):
   if row['TEST_SECTION_ID'] in candidates:row['_ordinal']=i;logs[row['TEST_SECTION_ID']].append(row)
   if i and i%5000000==0:print('SCANNED',i,'SELECTED ROWS',sum(map(len,logs.values())),flush=True)
 good=collections.defaultdict(list);trace={};rejected=collections.Counter()
 for trial,r in candidates.items():
  result,reason=reconstruct(logs[trial],r['observed'])
  if reason:rejected[reason]+=1;continue
  r=dict(r,initial_snapshot_chars=result['initial_snapshot_chars'],logged_events=len(logs[trial]));trace[trial]=result;good[r['split'],r['stratum']].append(r)
 selected=[]
 for key,rows in sorted(good.items()):
  pp=collections.Counter();pq=collections.Counter()
  for r in sorted(rows,key=lambda r:stable(r['id'])):
   if pp[r['participant']]>=2 or pq[r['prompt']]>=8:continue
   selected.append(r);pp[r['participant']]+=1;pq[r['prompt']]+=1
   if sum(x['split']==key[0] and x['stratum']==key[1] for x in selected)>=a.per_stratum:break
 assert not {r['participant'] for r in selected}&ac_actors
 assert not {r['prompt'] for r in selected}&blocked_prompts
 assert not {r['participant'] for r in selected if r['split']=='development'}&{r['participant'] for r in selected if r['split']=='confirmation'}
 hashes={};counts={}
 for split in ('development','confirmation'):
  chosen=[r for r in selected if r['split']==split];core.jsonl(a.output/(split+'.jsonl'),chosen);hashes[split]=core.digest(a.output/(split+'.jsonl'));counts[split]=dict(collections.Counter(r['stratum'] for r in chosen));sessions=[]
  for r in chosen:
   for layout in ('english','quick3','quick5'):
    sessions.append(dict(schema='keyboard-product-session-v1',id=r['id']+':'+layout,group=r['id'],origin='recorded_copy_typing',intent_status='task_reference',language='english',layout=layout,initial_text='',events=events_for(r['observed']),expected_text=r['reference'],expected_composing='',intent='preservation' if r['stratum']=='clean' else 'correction',tags=['ite_full_attempt',r['stratum'],r['platform'],r['prompt_source']],auto_capitalization=False,split=split,participant_id=r['participant'],prompt_id=r['prompt'],label_basis='Published copy prompt specifies intended text; observed source snapshots reconstruct actual entry, not spontaneous intent labels'))
  core.jsonl(a.output/(split+'-sessions.jsonl'),sessions)
 core.jsonl(a.output/'selected-traces.jsonl',[dict(trial=r['trial'],**trace[r['trial']]) for r in selected]);core.write_json(a.output/'protocol.json',dict(schema='ite-whole-message-eval-v5',source='https://zenodo.org/records/12528163',source_intake=intake,seed=SEED,script_sha256=core.digest(__file__),split_hashes=hashes,counts=counts,metadata_audit=dict(audit),candidate_pool={str(k):len(v) for k,v in pool.items()},trace_rejections=dict(rejected),target_per_stratum=a.per_stratum,known_ac_actor_overlap=0,known_prompt_overlap=0,ac_rows_without_processed_actor=unmapped,frozen_before_inference=True,encoding='Known printable Windows-1252 C1 punctuation mapped consistently in input snapshots, observed text and references before severity/sampling',sampling='Fixed hash, balanced input/reference word edit distance; no model/vocabulary/target-availability filtering. Participant and prompt folds must agree; both remain disjoint.',limits=['Unassisted no-backspace mobile copy typing, not spontaneous chat or all assisted-keyboard usage','Initial short input snapshot may seed multiple characters; physical key timing is not inferred','Append-only trace validation excludes ambiguous/system-driven edits; all attrition reported','Known source-AC participants and previously used prompt groups excluded; undocumented inherited training exposure remains unproven','Word edit distance is an error-burden measure, not a count of independent physical typos','Error-severity balancing is a stress-design convention; original candidate-pool counts retained, not deployment prevalence','English only; cannot establish Cantonese/English mixing','Controller replay settles at word boundaries and submits final composition; not physical-phone timing']))
 print('FROZEN',counts,'REJECTED',dict(rejected),flush=True)
if __name__=='__main__':main()
