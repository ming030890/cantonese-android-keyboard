#!/usr/bin/env python3
"""Mac CPU-only correction/KEEP trees; fit and threshold selection use TRAIN."""
import argparse,collections,json,math,struct
from pathlib import Path
import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
import sklearn
import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import metrics,eligible
ROOT=Path(__file__).resolve().parents[1]

def quantize(x):return np.copysign(np.floor(np.abs(x)*1e6+.5),x)/1e6

def family(c):
 s=c.get('source','')
 if s=='real':return 'phone'
 if s.startswith('real_author'):return 'author'
 if s=='chat_train_preservation':return 'chat'
 if s.startswith('clean') or s.endswith('_clean') or s=='title_abbreviation_clean':return 'lexical'
 return 'synthetic'

def vocab_features(cases,proposals,variants):
 rows=[]
 for c,p in zip(cases,proposals):
  row=[]
  for word in (c['typed'],p or ''):
   vs=variants.get(word.lower(),[]);row.extend([float(any(w.islower() for w in vs)),float(any(w.istitle() for w in vs)),float(any(w.isupper() for w in vs)),float(bool(vs) and not any(w.islower() for w in vs))])
  rows.append(row)
 return np.array(rows,np.float64)

def export(model):
 trees=[]
 for stage in model._predictors:
  assert len(stage)==1;nodes=stage[0].nodes;tree=[]
  for n in nodes:
   assert not n['is_categorical']
   tree.append(dict(value=float(n['value']),feature=int(n['feature_idx']),threshold=float(n['num_threshold']),left=int(n['left']),right=int(n['right']),leaf=bool(n['is_leaf']),missing_left=bool(n['missing_go_to_left'])))
  trees.append(tree)
 return dict(bias=float(model._baseline_prediction[0,0]),trees=trees)

def portable_scores(policy,x):
 z=np.full(len(x),policy['bias'])
 for tree in policy['trees']:
  active=np.zeros(len(x),int)
  while True:
   done=True
   for j in np.unique(active):
    node=tree[j];mask=active==j
    if node['leaf']:continue
    done=False;values=x[mask,node['feature']];left=np.where(np.isnan(values),node['missing_left'],values<=node['threshold']);active[mask]=np.where(left,node['left'],node['right'])
   if done:break
  z+=np.array([tree[j]['value'] for j in active])
 return 1/(1+np.exp(-np.clip(z,-700,700)))

def tune(cases,before,proposal,p,allowed):
 typo=np.array([c['typed'].casefold()!=c['target'].casefold() for c in cases]);clean=~typo;changed=np.array([bool(o.get('correction')) and o['correction'].casefold()!=c['typed'].casefold() for c,o in zip(cases,before)]);correct=np.array([bool(o.get('correction')) and typo[i] and o['correction'].casefold()==c['target'].casefold() for i,(c,o) in enumerate(zip(cases,before))]);wrong=changed&~correct;y=np.array([bool(w) and typo[i] and w.casefold()==c['target'].casefold() for i,(c,w) in enumerate(zip(cases,proposal))]);fam=np.array([family(c) for c in cases]);groups=sorted(set(fam));gm=[fam==g for g in groups]
 completing=np.array([bool(w) and w.lower().startswith(c['typed'].lower()) and len(w)-len(c['typed'])>1 for c,w in zip(cases,proposal)])
 def curve(mask,descending,remove):
  ids=np.flatnonzero(mask);ids=ids[np.argsort(-p[ids] if descending else p[ids],kind='stable')]
  ends=np.r_[np.flatnonzero(np.diff(p[ids])!=0),len(ids)-1] if len(ids) else np.array([],int)
  sign=-1 if remove else 1
  dt=(correct if remove else y)[ids].astype(int)*sign;df=(wrong if remove else ~y)[ids].astype(int)*sign;dc=((wrong if remove else ~y)&clean)[ids].astype(int)*sign
  # Midpoints preserve complete equal-score groups and avoid a one-ULP
  # Python/Rust sigmoid difference deciding a calibrated boundary case.
  cuts=[]
  for end in ends:
   value=p[ids[end]]
   if end+1<len(ids):cuts.append(float((value+p[ids[end+1]])/2))
   else:cuts.append(float(value+1e-6 if remove else max(0.,value-1e-6)))
  result=dict(tp=np.r_[0,np.cumsum(dt)[ends]],fp=np.r_[0,np.cumsum(df)[ends]],clean=np.r_[0,np.cumsum(dc)[ends]],cut=np.r_[0. if remove else 1.+1e-6,cuts])
  result['group']=[dict(tp=np.r_[0,np.cumsum(dt*m[ids])[ends]],fp=np.r_[0,np.cumsum(df*m[ids])[ends]],clean=np.r_[0,np.cumsum(dc*m[ids])[ends]]) for m in gm]
  return result
 veto=curve(allowed&changed,False,True);rescue=curve(allowed&~changed&~completing,True,False);bt=int(correct.sum());bf=int(wrong.sum());bc=int((wrong&clean).sum());nt=int(typo.sum());base=1.25*bt/(bt+bf+.25*nt);best=dict(score=base,veto_cutoff=0.,rescue_cutoff=1.000001,tp=bt,fp=bf,clean_changes=bc)
 for vi in range(len(veto['cut'])):
  tp=bt+veto['tp'][vi]+rescue['tp'];fp=bf+veto['fp'][vi]+rescue['fp'];cc=bc+veto['clean'][vi]+rescue['clean'];ok=(tp>=bt)&(fp<=bf)&(cc<=bc)&(rescue['cut']>=veto['cut'][vi]);score=1.25*tp/(tp+fp+.25*nt)
  for gi,g in enumerate(groups):
   a=veto['group'][gi];b=rescue['group'][gi];ok&=(a['fp'][vi]+b['fp']<=0)&(a['clean'][vi]+b['clean']<=0)
   if g in ('phone','author','synthetic'):ok&=a['tp'][vi]+b['tp']>=0
  score[~ok]=-1;j=int(score.argmax())
  if score[j]>best['score']+1e-12:best=dict(score=float(score[j]),veto_cutoff=float(veto['cut'][vi]),rescue_cutoff=float(rescue['cut'][j]),tp=int(tp[j]),fp=int(fp[j]),clean_changes=int(cc[j]))
 best['baseline']=dict(score=base,tp=bt,fp=bf,clean_changes=bc);return best

def predictions(cases,before,proposal,p,allowed,policy):
 result=[]
 for c,b,w,prob,ok in zip(cases,before,proposal,p,allowed):
  fix=b.get('correction')
  if ok:
   if fix and prob<policy['veto_cutoff']:fix=None
   elif not fix and prob>=policy['rescue_cutoff'] and not (w.lower().startswith(c['typed'].lower()) and len(w)-len(c['typed'])>1):fix=w
  result.append(dict(correction=fix))
 return result

def main():
 root=ROOT/'build/english-keep-20261031';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=root/'tree-study');args=parser.parse_args();out=args.output;out.mkdir(exist_ok=False);fd=root/'features';schema=json.load(open(root/'diagnostic-features.json'));modelpath=ROOT/'app/src/main/assets/language_model/model.cng'
 with modelpath.open('rb') as f:h=f.read(12);meta=json.loads(f.read(struct.unpack('<I',h[8:])[0]))
 variants=collections.defaultdict(list)
 for i in meta['english_word_ids']:variants[meta['tokens'][i].lower()].append(meta['tokens'][i])
 cs=core.rows(fd/'train-cases.jsonl');before=core.rows(fd/'train-before.jsonl');proposal=json.load(open(fd/'train-proposals.json'));data=np.load(fd/'train.npz');base_x=data['x'];a=data['allowed'];y=data['y'];case_x=vocab_features(cs,proposal,variants);fit=np.array([c['fit_split']=='fit' for c in cs])&a;cal=np.array([c['fit_split']=='calibration' for c in cs]);fitfamilies=np.array([family(c) for c in cs]);counts=collections.Counter(fitfamilies[fit]);weights=np.array([1/counts[g] if fit[i] else 1. for i,g in enumerate(fitfamilies)]);weights[fit]/=weights[fit].mean();report=dict(training_only_selection=True,confirmation_used=False,sklearn_version=sklearn.__version__,features_sha256=core.digest(fd/'train.npz'),models={});winner=None
 for use_case in (False,True):
  x=quantize(np.column_stack([base_x,case_x]) if use_case else base_x);names=schema['features']+(['typed_vocabulary_lower','typed_vocabulary_title','typed_vocabulary_upper','typed_vocabulary_no_lower','candidate_vocabulary_lower','candidate_vocabulary_title','candidate_vocabulary_upper','candidate_vocabulary_no_lower'] if use_case else [])
  for leaves,iterations in ((7,150),(15,300)):
   name=f'case{int(use_case)}-leaves{leaves}-trees{iterations}';m=HistGradientBoostingClassifier(learning_rate=.05,max_iter=iterations,max_leaf_nodes=leaves,min_samples_leaf=40,l2_regularization=10.,early_stopping=False,random_state=20261031);m.fit(x[fit],y[fit],sample_weight=weights[fit]);prob=m.predict_proba(x)[:,1];policy=export(m);parity=portable_scores(policy,x[cal]);assert np.max(np.abs(parity-prob[cal]))<1e-12
   threshold=tune([c for c,b in zip(cs,cal) if b],[c for c,b in zip(before,cal) if b],[c for c,b in zip(proposal,cal) if b],prob[cal],a[cal]);policy.update(threshold,feature_names=names,use_vocabulary_case=use_case,feature_quantization=1000000.,schema='shared-english-keep-rescue-trees-v1',model_sha256=core.digest(modelpath),features_sha256=core.digest(fd/'train.npz'),source_sha256=core.digest(__file__));core.write_json(out/(name+'.json'),policy);report['models'][name]=threshold;print(name,threshold,flush=True)
   if winner is None or threshold['score']>winner[0]+1e-12:winner=(threshold['score'],name,policy,m)
 report['winner']=winner[1];policy=winner[2];m=winner[3];values={};bases={}
 for name in ('objective','development','chat_development','regressions'):
  cc=core.rows(fd/(name+'-cases.jsonl'));bb=core.rows(fd/(name+'-before.jsonl'));pp=json.load(open(fd/(name+'-proposals.json')));dd=np.load(fd/(name+'.npz'));xx=quantize(np.column_stack([dd['x'],vocab_features(cc,pp,variants)]) if policy['use_vocabulary_case'] else dd['x']);prob=m.predict_proba(xx)[:,1];assert np.max(np.abs(portable_scores(policy,xx)-prob))<1e-12;pred=predictions(cc,bb,pp,prob,dd['allowed'],policy);core.jsonl(out/(name+'-outputs.jsonl'),pred)
  if name=='regressions':values[name]=dict(passed=all((o.get('correction') or c['typed']).casefold()==c['expected'].casefold() for c,o in zip(cc,pred)));continue
  if name=='objective':cc=[dict(c,panel='historical_full_mobile') if c['panel'].startswith('mobile_') else c for c in cc]
  values[name]=metrics(cc,pred);bases[name]=metrics(cc,bb)
 score=lambda m:sum(m[k]['f05'] for k in ('historical_full_mobile','real_touch_observed','authored_hard'))/3
 report.update(metrics=values,baseline=bases,score=score(values['objective']),delta=score(values['objective'])-score(bases['objective']),qualified=score(values['objective'])>score(bases['objective']) and eligible(values['development'],bases['development']) and eligible(values['chat_development'],bases['chat_development']) and values['regressions']['passed']);core.write_json(out/'report.json',report);print('WINNER',winner[1],'SCORE',report['score'],'QUALIFIED',report['qualified'],flush=True)
 for name in ('objective','development','chat_development'):print(name,[(k,v['tp'],v['fp'],v['clean_changes']) for k,v in values[name].items()],flush=True)
if __name__=='__main__':main()
