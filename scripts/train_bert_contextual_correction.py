#!/usr/bin/env python3
"""Fine-tune a compact pretrained contextual correction/KEEP scorer on this Mac."""
import collections,json,math,os,time
from pathlib import Path
import numpy as np
import torch
import torch.nn.functional as F
from bert_tiny_pair import PairRanker
from train_english_joint_ranker import calibrate
from experiment_conditional_english_scores import metrics,eligible
from build_independent_autocorrect_eval import HAN
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]


def routed(c):return bool(c['left'].strip()) and not HAN.search(c['left'])

def infer(model,d,batch=64):
 values=[]
 with torch.no_grad():
  for at in range(0,len(d['ids']),batch):
   ix=slice(at,at+batch);z,_=model(*(d[k][ix] for k in ('ids','segments','mask','numeric','base')));z=z.masked_fill(~d['eligible'][ix],-1e4);values.append(z.cpu().numpy())
 z=np.concatenate(values).astype(np.float64);winner=z[:,1:].argmax(1);top=z[np.arange(len(z)),winner+1];maximum=z.max(1);other=np.exp(z-maximum[:,None]).sum(1)-np.exp(top-maximum);margin=top-maximum-np.log(np.maximum(other,1e-300));return winner,margin

def predictions(cs,dc,mask,w,z,cut,before):
 result=[]
 for i,(c,j,value) in enumerate(zip(cs,w,z)):
  fix=dc['restorations'][i] or (dc['words'][i][j] if j<len(dc['words'][i]) and mask[i,j+1] and value>=cut else None)
  if not routed(c):fix=before[i].get('correction')
  result.append(dict(correction=fix))
 return result

def tensors(npz,device):
 return {k:torch.tensor(npz[k],dtype=torch.long if k in ('ids','segments','labels') else torch.bool if k in ('mask','eligible') else torch.float32,device=device) for k in npz.files}

def main():
 root=ROOT/'build/english-bert-20261030';out=root/'contextual-study';out.mkdir(exist_ok=False);(out/'pid').write_text(str(os.getpid()));assert torch.backends.mps.is_available();device=torch.device('mps');torch.set_num_threads(4);torch.manual_seed(20261030);torch.mps.manual_seed(20261030)
 jr=ROOT/'build/english-listwise-20261030';cs=core.rows(jr/'joint-features/cases.jsonl');before=core.rows(jr/'train-outputs.jsonl')+core.rows(jr/'real-author-data/outputs.jsonl');decoded=json.load(open(root/'contextual-data/train-decoded.json'));data=np.load(root/'contextual-data/train.npz')
 fit=np.array([c['fit_split']=='fit' and not bool(r) and bool(a[1:].any()) for c,r,a in zip(cs,decoded['restorations'],data['eligible'])]);ci=np.array([i for i,c in enumerate(cs) if c['fit_split']=='calibration']);ids=np.flatnonzero(fit)
 counts=collections.Counter(cs[i]['source'] for i in ids);mass={'real':1.,'generated':.25,'blank':.1,'gboard_word':.1,'gboard_context':.16,'gboard_touch':.19,'clean_dictionary':.25,'clean_dropout':.25,'clean_short':.1,'generated_short':.25,'authentic_short_clean':.5,'authentic_short_typo':.5,'title_abbreviation_clean':.25,'real_author_typo':.5,'real_author_clean':.5};freq=collections.Counter((cs[i]['source'],cs[i]['target'].casefold()) for i in ids)
 ww=np.array([mass[cs[i]['source']]/counts[cs[i]['source']]/math.sqrt(freq[(cs[i]['source'],cs[i]['target'].casefold())]) for i in ids]);ww/=ww.mean();weights=torch.tensor(ww,dtype=torch.float32,device=device)
 raw={k:data[k] for k in data.files};train=tensors({k:v[ids] for k,v in raw.items()} if False else data,device);train={k:v[ids] for k,v in train.items()};cal=tensors(data,device);cal={k:v[ci] for k,v in cal.items()};del data
 calcases=[cs[i] for i in ci];calbefore=[before[i] for i in ci];caldecoded={k:[v[i] for i in ci] for k,v in decoded.items()};calmask=raw['eligible'][ci,1:].copy()
 # Context-free requests retain the accepted decoder; this is a contextual refinement.
 for i,c in enumerate(calcases):
  if not routed(c):calmask[i]=False;caldecoded['restorations'][i]=calbefore[i].get('correction')
 cohorts={}
 for name,path,bpath in [('objective',ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl',jr/'objective-outputs.jsonl'),('development',ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl',jr/'development-outputs.jsonl'),('regressions',ROOT/'config/product_eval_v1/native_regressions.jsonl',None)]:
  cc=core.rows(path);npz=np.load(root/'contextual-data'/(name+'.npz'));dc=json.load(open(root/'contextual-data'/(name+'-decoded.json')));oo=core.rows(bpath) if bpath else [dict(correction=None) for c in cc]
  if name=='regressions':
   oo=[dict(correction=c['expected'] if c['expected']!=c['typed'] else None) for c in cc]
  if name=='objective':cc=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in cc]
  cohorts[name]=(cc,dc,npz['eligible'],tensors(npz,device),oo)
 score=lambda m:sum(m[p]['f05'] for p in ('historical_full_mobile','real_touch_observed','authored_hard'))/3;baseobj=metrics(cohorts['objective'][0],cohorts['objective'][4]);basedev=metrics(cohorts['development'][0],cohorts['development'][4]);report={'baseline_score':score(baseobj),'baseline_development':basedev,'models':{}};best=(report['baseline_score'],'parent')
 core.write_json(out/'protocol.json',dict(schema='contextual-bert-tiny-v2',numeric_features=29,input_format='[CLS] left+candidate [SEP] observed [SEP]',character_channel_sha256=core.digest(ROOT/'build/english-listwise-20261030/character-channel/model.json'),pretrained=json.load(open(root/'pretrained/upstream.json')),training_cases_sha256=core.digest(jr/'joint-features/cases.jsonl'),fit_rows=len(ids),calibration_rows=len(ci),source_sha256=core.digest(__file__),model_code_sha256=core.digest(ROOT/'scripts/bert_tiny_pair.py'),proposals=4,max_tokens=48,hardware='Only this Mac mini Metal GPU',residual_bound=4.,routing='Non-empty English left context only; accepted decoder elsewhere',confirmation_used=False,learning_rate=dict(encoder=.00005,head=.001),epochs=4,calibration='TRAIN calibration under accepted wrong and clean budgets; candidate posterior>=0.5'))
 model=PairRanker(29);model.bert.load_google(root/'pretrained/model.safetensors');model=model.to(device);opt=torch.optim.AdamW([dict(params=model.bert.parameters(),lr=5e-5),dict(params=list(model.head.parameters())+list(model.output.parameters()),lr=.001)],weight_decay=.01);protect=torch.tensor([((before[i].get('correction') or cs[i]['typed']).casefold()==cs[i]['target'].casefold()) for i in ids],device=device);start=time.time();step=0
 for epoch in range(1,5):
  model.train();order=torch.randperm(len(ids),device=device);loss_sum=0.
  for at in range(0,len(order),16):
   ix=order[at:at+16];z,delta=model(*(train[k][ix] for k in ('ids','segments','mask','numeric','base')));z=z.masked_fill(~train['eligible'][ix],-1e4);reg=(delta.square()*train['eligible'][ix]).sum(1)/train['eligible'][ix].sum(1).clamp_min(1);loss=((F.cross_entropy(z,train['labels'][ix],reduction='none')+.005*protect[ix]*reg)*weights[ix]).mean();opt.zero_grad(set_to_none=True);loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),1.);opt.step();loss_sum+=float(loss.detach().cpu());step+=1
   if step%200==0:print('TRAIN',epoch,at,len(order),'loss',round(loss_sum/(at//16+1),4),'seconds',round(time.time()-start,1),flush=True);core.write_json(out/'progress.json',dict(epoch=epoch,at=at,fit_rows=len(ids),elapsed=time.time()-start))
  model.eval();cw,cz=infer(model,cal);cut,budget=calibrate(calcases,caldecoded,calmask,cw,cz,calbefore)
  if cut is None:print('NO CALIBRATION',epoch,flush=True);continue
  values={};guard=True
  for name,(cc,dc,mask,d,oo) in cohorts.items():
   w,z=infer(model,d);pred=predictions(cc,dc,mask,w,z,cut,oo)
   if name=='regressions':guard=all((o['correction'] or c['typed']).casefold()==c['expected'].casefold() for c,o in zip(cc,pred));values[name]={'passed':guard,'actual':[p['correction'] for p in pred]}
   else:values[name]=metrics(cc,pred)
  s=score(values['objective']);ok=guard and eligible(values['development'],basedev) and s>report['baseline_score'];name=f'epoch{epoch}';report['models'][name]={'score':s,'cutoff_logit':cut,'metrics':values,'eligible':ok,'elapsed':time.time()-start};torch.save(dict(state=model.state_dict(),cutoff_logit=cut,protocol=json.load(open(out/'protocol.json'))),out/(name+'.pt'));print('EVALUATE',name,s,'ELIGIBLE',ok,'GUARDS',guard,'CUT',cut,'SEC',round(time.time()-start,1),[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in values['objective'].items()],flush=True)
  if ok and s>best[0]:best=(s,name)
  report['winner']=best;core.write_json(out/'report.json',report)
if __name__=='__main__':main()
