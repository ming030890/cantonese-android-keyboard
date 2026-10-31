#!/usr/bin/env python3
"""Cache label-independent full-lexicon candidates, edit costs and shared-LM evidence."""
import argparse,collections,json,math,re,subprocess,struct
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
from build_independent_autocorrect_eval import HAN
from experiment_clean_acceptance import ENGINE,load_features
ROOT=Path(__file__).resolve().parents[1]

def logsum(values):
 if not values:return None
 mx=max(values);return mx+math.log(sum(math.exp(v-mx) for v in values))

def main():
 root=ROOT/'build/english-noisy-channel-20261030';jr=ROOT/'build/english-listwise-20261030';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=root/'data');parser.add_argument('--channel',type=Path,default=jr/'character-channel');args=parser.parse_args();out=args.output;out.mkdir(exist_ok=False);parent=json.load(open(ENGINE/'english_acceptance.json'));helper=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
 rows=core.rows(jr/'joint-features/cases.jsonl');bo=core.rows(jr/'train-outputs.jsonl')+core.rows(jr/'real-author-data/outputs.jsonl');indices=[i for i,c in enumerate(rows) if c['fit_split']=='calibration'];sets=[('calibration',[rows[i] for i in indices],[bo[i] for i in indices]),('objective',core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'),core.rows(jr/'objective-outputs.jsonl')),('development',core.rows(ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'),core.rows(jr/'development-outputs.jsonl'))]
 reg=core.rows(ROOT/'config/product_eval_v1/native_regressions.jsonl');p=subprocess.run(['/private/tmp/keyboard-release74-saved/ngram_probe',str(ROOT/'app/src/main/assets/language_model/model.cng')],input=core.native_requests(reg),text=True,capture_output=True,check=True);sets.append(('regressions',reg,[json.loads(l) for l in p.stdout.splitlines()]))
 allcases=[];before=[];routes=[];slices={}
 for name,cs,oo in sets:
  start=len(allcases);slices[name]=[start,start+len(cs)];allcases+=cs;before+=oo
  for c,o in zip(cs,oo):
   typed=c['typed'].replace('’',"'").lower();restore=helper.restored(o) or (o.get('correction') if o.get('correction') and o['correction'].casefold()==c['typed'].casefold() else None)
   routes.append(bool(c['left'].strip()) and not HAN.search(c['left']) and bool(re.fullmatch("[a-z]+(?:'[a-z]+)*",typed)) and 2<=len(typed)<=32 and not restore)
 core.jsonl(out/'cases.jsonl',allcases);core.jsonl(out/'before.jsonl',before);core.write_json(out/'slices.json',slices)
 freqdata=json.load(open(root/'word-priors.json'));counts=freqdata['counts'];lex=[l.split('\t')[0] for l in (root/'lexicon.tsv').read_text().splitlines()];freq=np.array([counts[w] for w in lex]);types=sorted({c['typed'].replace('’',"'").lower() for c,r in zip(allcases,routes) if r});print('TYPES',len(types),'CASES',len(allcases),flush=True)
 p=subprocess.run([str(root/'dictionary_candidates'),str(root/'lexicon.tsv')],input='\n'.join(types)+'\n',text=True,capture_output=True,check=True,timeout=600);lines=p.stdout.splitlines();assert len(lines)==len(types);proposals={};prefix={}
 requests=[];positions=[]
 for typed,line in zip(types,lines):
  mass,hits=line.split('\t');prefix[typed]=float(mass);ids=[int(v.split(':')[0]) for v in hits.split(',') if v and lex[int(v.split(':')[0])]!=typed];proposals[typed]=ids
  for j in ids:requests.append(typed+'\t'+lex[j]+'\n');positions.append((typed,j))
 print('RAW PAIRS',len(positions),flush=True);p=subprocess.run([str(args.channel/'score')],input=''.join(requests),text=True,capture_output=True,check=True,timeout=600);costs=np.fromstring(p.stdout,sep='\n');assert len(costs)==len(positions)
 grouped=collections.defaultdict(list)
 for (typed,j),cost in zip(positions,costs):grouped[typed].append((j,float(cost)))
 selected={}
 for typed,pairs in grouped.items():
  ii=np.array([j for j,_ in pairs]);cc=np.array([cost for _,cost in pairs]);score=np.log(freq[ii]);chosen=set()
  for weight in (.25,.5,1.,2.):chosen.update(np.argsort(score-weight*cc)[-64:].tolist())
  selected[typed]=[(int(ii[k]),float(cc[k])) for k in sorted(chosen)]
 core.write_json(out/'selected-proposals.json',selected);print('SELECTED',sum(map(len,selected.values())),flush=True)
 model=ROOT/'app/src/main/assets/language_model/model.cng'
 with model.open('rb') as f:h=f.read(12);meta=json.loads(f.read(struct.unpack('<I',h[8:12])[0]))
 wordids=collections.defaultdict(list)
 for i in meta['english_word_ids']:wordids[meta['tokens'][i].lower()].append(i)
 contexts=collections.defaultdict(set)
 for c,ok in zip(allcases,routes):
  if not ok:continue
  typed=c['typed'].replace('’',"'").lower();contexts[c['left']].update(wordids.get(typed,[]))
  for j,cost in selected.get(typed,[]):contexts[c['left']].update(wordids.get(lex[j],[]))
 probe=Path('/private/tmp/keyboard-noisy-channel-host/release/ngram_probe');requests=[dict(left='',candidate_ids=meta['english_word_ids'],distribution=True)]+[dict(left=left,candidate_ids=sorted(ids),distribution=True) for left,ids in contexts.items()];print('CONTEXTS',len(contexts),flush=True)
 p=subprocess.run([str(probe),str(model)],input=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in requests),text=True,capture_output=True,check=True,timeout=600);(out/'distribution-outputs.jsonl').write_text(p.stdout);outputs=[json.loads(l) for l in p.stdout.splitlines()];assert len(outputs)==len(requests)
 empty=dict(zip(requests[0]['candidate_ids'],outputs[0]['log_probs']));prior={w:logsum([empty[i] for i in ids]) for w,ids in wordids.items()};empty_mass=float(outputs[0]['englishLogMass']);contextdata={req['left']:(dict(zip(req['candidate_ids'],o['log_probs'])),float(o['englishLogMass'])) for req,o in zip(requests[1:],outputs[1:])}
 k=256;n=len(allcases);ids=np.full((n,k),-1,np.int32);f=np.zeros((n,k),np.float32);cost=np.zeros((n,k),np.float32);gain=np.zeros((n,k),np.float32);literal=np.full(n,-1.,np.float64);lg=np.zeros(n,np.float32);pm=np.zeros(n,np.float64);case=np.zeros(n,bool)
 for i,(c,ok) in enumerate(zip(allcases,routes)):
  if not ok:continue
  typed=c['typed'].replace('’',"'").lower();left=c['left'];lp,mass=contextdata[left]
  literal[i]=counts.get(typed,math.exp(prior[typed]-empty_mass)*freqdata['total'] if typed in prior else -1.);pm[i]=prefix[typed];end=left.rstrip()[-1:];case[i]=typed not in counts and c['typed'][:1].isupper() and bool(end) and end not in '.!?。！？'
  if typed in wordids:lg[i]=logsum([lp[j] for j in wordids[typed]])-mass-prior[typed]+empty_mass
  for p,(j,cc) in enumerate(selected.get(typed,[])):
   ids[i,p]=j;f[i,p]=math.log(freq[j]);cost[i,p]=cc;w=lex[j]
   if w in wordids:gain[i,p]=logsum([lp[t] for t in wordids[w]])-mass-prior[w]+empty_mass
 np.savez(out/'features.npz',ids=ids,log_frequency=f,cost=cost,gain=gain,literal_frequency=literal,literal_gain=lg,prefix_mass=pm,proper_case=case,routed=np.array(routes));core.write_json(out/'protocol.json',dict(schema='full-lexicon-channel-evidence-v1',candidate_limit=k,proposal_selection='Union top64 unigram-minus-channel proposals at four TRAIN-independent scales; never uses labels',probe_sha256=core.digest(probe),model_sha256=core.digest(model),lexicon_sha256=core.digest(root/'lexicon.tsv'),channel_sha256=core.digest(args.channel/'model.json'),script_sha256=core.digest(__file__),confirmation_used=False));print('COMPLETE',flush=True)
if __name__=='__main__':main()
