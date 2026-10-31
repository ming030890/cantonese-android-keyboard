#!/usr/bin/env python3
"""Mac-local typed/candidate character-pair CNN; frozen shared LM and parent gate.

Compare a numeric-only residual control with a character-pair residual. Supervise
only original TRAIN labels. The residual is bounded to +/-4 logit units. Keep
confirmation unopened until one candidate passes development and native tests.
"""
import collections,copy,json,math,os,sys,time
from pathlib import Path
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE,load_features
from experiment_bilingual_acceptance import extract
from experiment_conditional_english_scores import metrics
ROOT=Path(__file__).resolve().parents[1]
CHARS="abcdefghijklmnopqrstuvwxyz' "
WIDTH=66

def encode(a,b):
 a=a.replace('’',"'").lower();b=(b or '').replace('’',"'").lower();n=len(a);m=len(b)
 if n>32 or m>32:return [842,843]+[0]*(WIDTH-2)
 d=[[0]*(m+1) for _ in range(n+1)]
 for i in range(n+1):d[i][0]=i
 for j in range(m+1):d[0][j]=j
 for i in range(1,n+1):
  for j in range(1,m+1):
   d[i][j]=min(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+(a[i-1]!=b[j-1]))
   if i>1 and j>1 and a[i-1]==b[j-2] and a[i-2]==b[j-1]:d[i][j]=min(d[i][j],d[i-2][j-2]+1)
 pairs=[];i=n;j=m
 while i or j:
  if i and j and a[i-1]==b[j-1] and d[i][j]==d[i-1][j-1]:pairs.append((a[i-1],b[j-1]));i-=1;j-=1
  elif i>1 and j>1 and a[i-1]==b[j-2] and a[i-2]==b[j-1] and d[i][j]==d[i-2][j-2]+1:
   pairs.extend([(a[i-1],b[j-1]),(a[i-2],b[j-2])]);i-=2;j-=2
  elif i and j and d[i][j]==d[i-1][j-1]+1:pairs.append((a[i-1],b[j-1]));i-=1;j-=1
  elif i and d[i][j]==d[i-1][j]+1:pairs.append((a[i-1],''));i-=1
  else:pairs.append(('',b[j-1]));j-=1
 number=lambda c:CHARS.index(c)+1 if c in CHARS and c else 0
 result=[842]+[1+29*number(a)+number(b) for a,b in reversed(pairs)]+[843]
 return (result+[0]*WIDTH)[:WIDTH]

class PairGate(nn.Module):
 def __init__(self,chars=True):
  super().__init__();self.chars=chars;self.embedding=nn.Embedding(845,16,padding_idx=0)
  self.conv1=nn.Conv1d(16,32,3,padding=1);self.conv2=nn.Conv1d(32,32,3,padding=1)
  self.head=nn.Linear(86,64);self.output=nn.Linear(64,1)
  nn.init.zeros_(self.output.weight);nn.init.zeros_(self.output.bias)
 def forward(self,features,tokens):
  mask=tokens!=0
  if self.chars:
   h=F.relu(self.conv1(self.embedding(tokens).transpose(1,2)));h=F.relu(self.conv2(h))
   mean=(h*mask[:,None]).sum(2)/mask.sum(1).clamp_min(1)[:,None];maximum=h.masked_fill(~mask[:,None],-1e4).max(2).values
   encoded=torch.cat([mean,maximum],1)
  else:encoded=torch.zeros((len(features),64),device=features.device)
  h=F.relu(self.head(torch.cat([features,encoded],1)))
  return 4*torch.tanh(self.output(h).squeeze(1)/4)

def batches(model,x,t,base,batch=2048):
 result=[]
 with torch.no_grad():
  for a in range(0,len(x),batch):result.extend((base[a:a+batch]+model(x[a:a+batch],t[a:a+batch])).cpu().numpy().tolist())
 return np.array(result)

def main():
 out=ROOT/'build/english-pair-cnn-20261030';out.mkdir(exist_ok=False)
 (out/'pid').write_text(str(os.getpid()));print('PID',os.getpid(),'MPS',torch.backends.mps.is_available(),flush=True)
 if not torch.backends.mps.is_available():raise RuntimeError('Local Metal GPU is required for this experiment')
 device=torch.device('mps');torch.set_num_threads(4)
 src=ROOT/'build/short-nn-v2-20261029/input-clean-data';cs=core.rows(src/'cases.jsonl');data=np.load(src/'features.npz');dec=json.load(open(src/'decisions.json'));raw=data['raw'][:,:22];parent=json.load(open(ENGINE/'english_acceptance.json'));helper=load_features(parent,ROOT.parent/'gboard-probe/improve-v3/features.py')
 block=set()
 for suite in (ROOT/'build/autocorrect-eval-v4/frozen',ROOT/'build/independent-autocorrect-eval-20261030/frozen-v2',ROOT/'build/bilingual-residual-20261030/fresh-eval',ROOT/'build/english-char-gate-20261030/fresh-eval'):
  for file in ([suite/'cases.jsonl'] if (suite/'cases.jsonl').exists() else [suite/'development.jsonl',suite/'confirmation.jsonl']):
   for c in core.rows(file):block.add((c['left'],c['typed'].casefold()))
 fit=np.array([c['fit_split']=='fit' and e and not rest and (c['left'],c['typed'].casefold()) not in block for c,e,rest in zip(cs,data['eligible'],dec['restorations'])]);ids=np.flatnonzero(fit)
 counts=collections.Counter(cs[i]['source'] for i in ids);mass={'real':1.,'generated':.25,'blank':.1,'gboard_word':.1,'gboard_context':.16,'gboard_touch':.19,'clean_dictionary':.25,'clean_dropout':.25,'clean_short':.1,'generated_short':.25,'authentic_short_clean':.5,'authentic_short_typo':.5,'title_abbreviation_clean':.25}
 weights=np.array([(.125 if cs[i].get('noise') else mass[cs[i]['source']])/counts[cs[i]['source']] for i in ids]);weights/=weights.mean()
 norm=(raw-parent['means'])/parent['scales'];norm[:,1]=0
 base=helper.predict_logits(helper.parameters(parent),(raw-parent['means'])/parent['scales'])
 pairs=np.array([encode(cs[i]['typed'],dec['words'][i]) for i in ids],dtype=np.int64)
 x=torch.tensor(norm[ids],dtype=torch.float32,device=device);t=torch.tensor(pairs,device=device);b=torch.tensor(base[ids],dtype=torch.float32,device=device);y=torch.tensor(data['labels'][ids],dtype=torch.float32,device=device);w=torch.tensor(weights,dtype=torch.float32,device=device)
 dev=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl')];oo=core.rows(ROOT/'build/bilingual-residual-20261030/final-objective/baseline-outputs.jsonl');dx,da,dw,dr=extract(dev,oo,parent,helper);dx=dx[:,:22];db=helper.predict_logits(helper.parameters(parent),(dx-parent['means'])/parent['scales']);dn=(dx-parent['means'])/parent['scales'];dn[:,1]=0;dt=np.array([encode(c['typed'],ww) for c,ww in zip(dev,dw)],dtype=np.int64)
 dx=torch.tensor(dn,dtype=torch.float32,device=device);dt=torch.tensor(dt,device=device);db=torch.tensor(db,dtype=torch.float32,device=device)
 cutoff=math.log(parent['threshold']/(1-parent['threshold']));score=lambda m:sum(m[p]['f05'] for p in ('historical_full_mobile','real_touch_observed','authored_hard'))/3
 protocol={'schema':'english-pair-cnn-v1','training_data_sha256':core.digest(src/'cases.jsonl'),'script_sha256':core.digest(__file__),'parent_sha256':core.digest(ENGINE/'english_acceptance.json'),'fit_rows':len(ids),'fit_input_overlap':0,'families':['numeric_control','pair_cnn'],'epochs':30,'learning_rate':.001,'batch':1024,'hardware':'This Mac mini Metal GPU only','confirmation_used':False};core.write_json(out/'protocol.json',protocol)
 report={'baseline_score':score(metrics(dev,oo)),'models':{}};best=(report['baseline_score'],'parent')
 for family in ('numeric_control','pair_cnn'):
  torch.manual_seed(20261030);torch.mps.manual_seed(20261030);model=PairGate(family=='pair_cnn').to(device);opt=torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.001);start=time.time()
  for epoch in range(1,31):
   model.train();order=torch.randperm(len(x),device=device);loss_sum=0.
   for a in range(0,len(order),1024):
    ix=order[a:a+1024];delta=model(x[ix],t[ix]);loss=((F.binary_cross_entropy_with_logits(b[ix]+delta,y[ix],reduction='none')+.02*delta.square())*w[ix]).mean();opt.zero_grad(set_to_none=True);loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),2.);opt.step();loss_sum+=float(loss.detach().cpu())
   if epoch in (1,5,10,20,30):
    model.eval();z=batches(model,dx,dt,db);pred=[dict(o,correction=rest or (word if ok and zz>=cutoff else None)) for o,rest,word,ok,zz in zip(oo,dr,dw,da,z)];met=metrics(dev,pred);s=score(met);name=f'{family}-e{epoch}';report['models'][name]={'score':s,'metrics':met,'elapsed':time.time()-start};weights_json={k:v.detach().cpu().tolist() for k,v in model.state_dict().items()};core.write_json(out/(name+'.json'),dict(schema='english-pair-cnn-residual-v1',character_enabled=model.chars,weights=weights_json,max_delta=4.,feature_count=22,disabled_features=[1],parent_sha256=protocol['parent_sha256']));torch.save(model.state_dict(),out/(name+'.pt'));print(name,'score',s,'loss',loss_sum,'sec',round(time.time()-start,1),[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in met.items()],flush=True)
    if s>best[0]:best=(s,name)
   core.write_json(out/'progress.json',dict(family=family,epoch=epoch,elapsed=time.time()-start))
 report['winner']=best;core.write_json(out/'report.json',report)
if __name__=='__main__':main()
