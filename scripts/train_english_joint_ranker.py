#!/usr/bin/env python3
"""Joint candidate/KEEP NN, fitted only on isolated TRAIN groups on local Metal."""
import collections,copy,json,math,os,time
from pathlib import Path
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE
from build_independent_autocorrect_eval import HAN
from experiment_conditional_english_scores import metrics,eligible
ROOT=Path(__file__).resolve().parents[1]

class Joint(nn.Module):
 def __init__(self,p,width):
  super().__init__();self.hidden=nn.Linear(22,width);self.output=nn.Linear(width,1)
  with torch.no_grad():
   self.hidden.weight[:24]=torch.tensor(p['w1']);self.hidden.bias[:24]=torch.tensor(p['b1']);self.output.weight.zero_();self.output.weight[0,:24]=torch.tensor(p['w2']);self.output.bias.fill_(p['b2']-math.log(p['threshold']/(1-p['threshold'])));self.hidden.weight[:,1]=0.
 def forward(self,x,mask):return self.output(F.relu(self.hidden(x))).squeeze(-1).masked_fill(~mask,-1e4)

def predict(model,x,a,batch=256):
 zz=[]
 with torch.no_grad():
  for i in range(0,len(x),batch):zz.append(model(x[i:i+batch],a[i:i+batch]).cpu().numpy())
 scores=np.concatenate(zz).astype(np.float64);winner=scores.argmax(1);best=scores[np.arange(len(scores)),winner]
 # Candidate probability accounts for every other proposal and KEEP=0.
 maximum=np.maximum(scores.max(1),0.);total=np.exp(-maximum)+np.exp(scores-maximum[:,None]).sum(1)
 other=np.maximum(total-np.exp(best-maximum),1e-300);margin=best-maximum-np.log(other)
 return winner,margin

def decisions(cases,decoded,mask,winner,z,cut,before=None):
 words=decoded['words'];rest=decoded['restorations'];result=[]
 for i,(cc,j,zz) in enumerate(zip(cases,winner,z)):
  fix=rest[i] or (words[i][j] if j<len(words[i]) and mask[i,j] and zz>=cut else None)
  if before is not None and HAN.search(cc['left']):fix=before[i].get('correction')
  result.append(dict(correction=fix))
 return result

def calibrate(cases,decoded,mask,winner,z,baseline):
 budget=core.metrics(cases,baseline);fixed=core.metrics(cases,[dict(correction=r) for r in decoded['restorations']]);counts=collections.Counter(tp=fixed['tp'],fp=fixed['fp'],clean_changes=fixed['clean_changes']);variable=[]
 for i,(j,zz) in enumerate(zip(winner,z)):
  if not decoded['restorations'][i] and j<len(decoded['words'][i]) and mask[i,j] and zz>=0:variable.append(i)
 variable.sort(key=lambda i:-z[i]);best=None
 def consider(cut):
  nonlocal best
  if counts['fp']>budget['fp'] or counts['clean_changes']>budget['clean_changes']:return
  fn=budget['typos']-counts['tp'];den=1.25*counts['tp']+counts['fp']+.25*fn;s=1.25*counts['tp']/den if den else 0
  key=(s,-counts['fp'],cut)
  if best is None or key>best[0]:best=(key,cut)
 consider(max(0.,float(z.max())+1));at=0
 while at<len(variable):
  value=z[variable[at]]
  while at<len(variable) and z[variable[at]]==value:
   i=variable[at];c=cases[i];word=decoded['words'][i][winner[i]];changed=word.casefold()!=c['typed'].casefold();typo=c['typed'].casefold()!=c['target'].casefold();correct=changed and typo and word.casefold()==c['target'].casefold();counts['tp']+=correct;counts['fp']+=changed and not correct;counts['clean_changes']+=changed and not typo;at+=1
  nxt=z[variable[at]] if at<len(variable) else 0.;consider(float((value+nxt)/2))
 return best[1] if best else None,budget

def main():
 root=ROOT/'build/english-listwise-20261030';src=root/'joint-features';out=root/'joint-study';out.mkdir(exist_ok=False);(out/'pid').write_text(str(os.getpid()))
 assert torch.backends.mps.is_available();device=torch.device('mps');torch.set_num_threads(4);parent=json.load(open(ENGINE/'english_acceptance.json'))
 cases=core.rows(src/'cases.jsonl');data=np.load(src/'train.npz');decoded=json.load(open(src/'train-decoded.json'));fit=np.array([c['fit_split']=='fit' and not bool(r) for c,r in zip(cases,decoded['restorations'])]);ci=np.array([i for i,c in enumerate(cases) if c['fit_split']=='calibration']);calcases=[cases[i] for i in ci];caldecoded={k:[v[i] for i in ci] for k,v in decoded.items() if k in ('words','restorations')}
 original=core.rows(root/'train-outputs.jsonl')+core.rows(root/'real-author-data/outputs.jsonl');calbefore=[original[i] for i in ci]
 counts=collections.Counter(c['source'] for c,m in zip(cases,fit) if m);mass={'real':1.,'generated':.25,'blank':.1,'gboard_word':.1,'gboard_context':.16,'gboard_touch':.19,'clean_dictionary':.25,'clean_dropout':.25,'clean_short':.1,'generated_short':.25,'authentic_short_clean':.5,'authentic_short_typo':.5,'title_abbreviation_clean':.25,'real_author_typo':.5,'real_author_clean':.5}
 weights=np.array([mass[c['source']]/max(1,counts[c['source']]) for c in cases])[fit];weights/=weights.mean()
 raw=(data['raw']-np.array(parent['means']))/parent['scales'];raw[:,:,1]=0.;x=torch.tensor(raw[fit],dtype=torch.float32,device=device);a=torch.tensor(data['eligible'][fit],device=device);labels=torch.tensor(data['labels'][fit],device=device);w=torch.tensor(weights,dtype=torch.float32,device=device)
 calx=torch.tensor(raw[ci],dtype=torch.float32,device=device);cala=torch.tensor(data['eligible'][ci],device=device)
 cohorts={}
 for name,path,bpath in [('objective',ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl',root/'objective-outputs.jsonl'),('development',ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl',root/'development-outputs.jsonl'),('regressions',ROOT/'config/product_eval_v1/native_regressions.jsonl',None)]:
  cc=core.rows(path);dd=np.load(src/(name+'.npz'));dc=json.load(open(src/(name+'-decoded.json')));norm=(dd['raw']-np.array(parent['means']))/parent['scales'];norm[:,:,1]=0.;oo=core.rows(bpath) if bpath else None
  if name=='objective':cc=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in cc]
  cohorts[name]=(cc,dc,dd['eligible'],torch.tensor(norm,dtype=torch.float32,device=device),torch.tensor(dd['eligible'],device=device),oo)
 score=lambda m:sum(m[k]['f05'] for k in ('historical_full_mobile','real_touch_observed','authored_hard'))/3
 baseobj=metrics(cohorts['objective'][0],cohorts['objective'][5]);basedev=metrics(cohorts['development'][0],cohorts['development'][5]);report={'baseline_score':score(baseobj),'baseline_development':basedev,'models':{}};best=(report['baseline_score'],'parent')
 core.write_json(out/'protocol.json',dict(schema='joint-candidate-keep-v1',features_sha256=core.digest(src/'train.npz'),source_sha256=core.digest(__file__),fit_rows=int(fit.sum()),calibration_rows=len(ci),candidate_limit=64,loss='Listwise cross entropy over every proposal plus KEEP',cutoff='TRAIN calibration F0.5 under parent wrong/clean budgets; posterior>=0.5',hardware='This Mac mini Metal GPU only',confirmation_used=False,selection='Frozen aggregate improves; all known regression assertions pass; supplemental English dev TP/FP/clean Pareto guard'))
 for width in (24,48):
  for lr in (.0003,.001):
   torch.manual_seed(20261030);torch.mps.manual_seed(20261030);model=Joint(parent,width).to(device);opt=torch.optim.AdamW(model.parameters(),lr=lr,weight_decay=.001);start=time.time()
   for epoch in range(1,31):
    order=torch.randperm(len(x),device=device);loss_sum=0.
    for at in range(0,len(order),64):
     ix=order[at:at+64];scores=model(x[ix],a[ix]);all_scores=torch.cat([torch.zeros((len(ix),1),device=device),scores],1);loss=(F.cross_entropy(all_scores,labels[ix],reduction='none')*w[ix]).mean();opt.zero_grad(set_to_none=True);loss.backward();model.hidden.weight.grad[:,1]=0.;torch.nn.utils.clip_grad_norm_(model.parameters(),5.);opt.step();loss_sum+=float(loss.detach().cpu())
    if epoch in (1,5,10,20,30):
     cw,cz=predict(model,calx,cala);cut,budget=calibrate(calcases,caldecoded,data['eligible'][ci],cw,cz,calbefore)
     if cut is None:continue
     values={};guard=True
     for name,(cc,dc,mask,xx,aa,oo) in cohorts.items():
      ww,zz=predict(model,xx,aa);pred=decisions(cc,dc,mask,ww,zz,cut,oo)
      if name=='regressions':guard=all((o['correction'] or c['typed']).casefold()==c['expected'].casefold() for c,o in zip(cc,pred));values[name]={'passed':guard}
      else:values[name]=metrics(cc,pred)
     s=score(values['objective']);devok=eligible(values['development'],basedev);ok=guard and devok and s>report['baseline_score'];name=f'w{width}-lr{lr}-e{epoch}';report['models'][name]={'score':s,'cutoff_logit':cut,'metrics':values,'eligible':ok,'elapsed':time.time()-start};p={k:v.detach().cpu().tolist() for k,v in model.state_dict().items()};policy=dict(schema='english-joint-candidate-keep-v1',means=parent['means'],scales=parent['scales'],w1=p['hidden.weight'],b1=p['hidden.bias'],w2=p['output.weight'][0],b2=p['output.bias'][0],cutoff_logit=cut,candidate_limit=64,disabled_input_indices=[1],parent_sha256=core.digest(ENGINE/'english_acceptance.json'),model_sha256=core.digest(ROOT/'app/src/main/assets/language_model/model.cng'));core.write_json(out/(name+'.json'),policy)
     print(name,'score',s,'ok',ok,'guards',guard,devok,'cut',round(cut,4),'loss',round(loss_sum,3),'sec',round(time.time()-start,1),[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in values['objective'].items()],flush=True)
     if ok and s>best[0]:best=(s,name)
     report['winner']=best;core.write_json(out/'report.json',report)
    core.write_json(out/'progress.json',dict(width=width,lr=lr,epoch=epoch,elapsed=time.time()-start))
if __name__=='__main__':main()
