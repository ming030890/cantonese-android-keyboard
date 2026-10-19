"""Development-only spelling audit using the actual native shared keyboard LM.
Synthetic edits and unchanged corpus words are diagnostics, not fresh qualification.
"""
import json,random,struct,subprocess,time
from pathlib import Path
import argparse, hashlib
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
parser.add_argument('--suite',type=Path,required=True)
parser.add_argument('--probe',type=Path,required=True)
parser.add_argument('--report',type=Path,required=True)
args=parser.parse_args()
app=args.project
model=app/'app/src/main/assets/language_model/model.cng'
with model.open('rb') as f:
 header=f.read(12);meta=json.loads(f.read(struct.unpack('<I',header[8:12])[0]))
tokens=meta['tokens'];known={tokens[i].lower() for i in meta['english_word_ids']}
def decode(ids):
 parts=[];raw=bytearray()
 def flush():
  if raw:parts.append(raw.decode('utf-8',errors='replace'));raw.clear()
 for i in ids:
  text=tokens[i]
  if text.startswith('[byte:'):raw.append(int(text[6:8],16))
  else:flush();parts.append(text)
 flush();return ' '.join(parts)
rows=[]
for line in args.suite.open():
 c=json.loads(line)
 if c['language']=='english':
  if c['task']=='prefix':rows.append(c)
  elif c['task']=='contextual' and len(c['target_ids'])==1:
   text=tokens[c['target_ids'][0]]
   if text.lower() in known:c['target']=text;rows.append(c)
rng=random.Random(57);rng.shuffle(rows)
requests=[];labels=[]
for c in rows:
 word=c['target'];lower=word.lower()
 if not word.isascii() or not word.isalpha() or not 4<=len(word)<=32:continue
 left=decode(c['context_ids'])
 requests.append(dict(left=left,typed=word));labels.append(('unchanged',word,word,c['source']))
 if lower not in known:continue
 position=rng.randrange(len(lower));operation=rng.randrange(4)
 if operation==0:typo=lower[:position]+rng.choice('abcdefghijklmnopqrstuvwxyz'.replace(lower[position],''))+lower[position+1:]
 elif operation==1:typo=lower[:position]+lower[position+1:]
 elif operation==2:typo=lower[:position]+lower[position]+lower[position:]
 else:
  position=min(position,len(lower)-2);typo=lower[:position]+lower[position+1]+lower[position]+lower[position+2:]
 if typo==lower or typo in known:continue
 requests.append(dict(left=left,typed=typo));labels.append(('synthetic_typo',word,typo,c['source']))
started=time.monotonic()
p=subprocess.run([str(args.probe),str(model)],input=''.join(json.dumps(r)+'\n' for r in requests),text=True,capture_output=True,check=True)
outputs=[json.loads(l) for l in p.stdout.splitlines()]
assert len(outputs)==len(labels)

n=hits=applied=correct=clean=changed=0;errors=[];clean_changes=[]
for label,out in zip(labels,outputs):
 kind,target,typed,source=label;fix=out['correction']
 if kind=='unchanged':
  clean+=1
  if fix and fix.lower()!=target.lower():
   changed+=1
   if len(clean_changes)<12:clean_changes.append(dict(target=target,correction=fix,source=source))
 else:
  n+=1;hits+=any(c['word'].lower()==target.lower() for c in out['candidates'])
  if fix:
   applied+=1
   if fix.lower()==target.lower():correct+=1
   elif len(errors)<12:errors.append(dict(typed=typed,target=target,correction=fix,source=source))
report=dict(scope='Development-only synthetic single-edit smoke audit on English finalist words; no fresh held-out qualification. Unchanged corpus words may themselves contain typos.',
 synthetic_cases=n,target_in_top8=hits/n,automatic_rate=applied/n,automatic_precision=correct/applied if applied else None,
 automatic_applied=applied,automatic_correct=correct,unchanged_corpus_words=clean,corpus_words_automatically_changed=changed,
 example_wrong_automatic_corrections=errors,example_corpus_word_changes=clean_changes,seconds=time.monotonic()-started)
report['model_sha256']=hashlib.sha256(model.read_bytes()).hexdigest()
report['probe_sha256']=hashlib.sha256(args.probe.read_bytes()).hexdigest()
report['policy']=json.loads((app/'config/language_model/selected_model.json').read_text())['english_policy']
args.report.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
