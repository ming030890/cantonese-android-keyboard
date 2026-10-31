#!/usr/bin/env python3
"""Pair encoder inputs include the observed word and each candidate, including KEEP."""
import json,math
from pathlib import Path
import numpy as np
from tokenizers import BertWordPieceTokenizer,Tokenizer
import autocorrect_eval_v2 as core
from experiment_clean_acceptance import ENGINE
ROOT=Path(__file__).resolve().parents[1];K=4;L=48

def main():
 root=ROOT/'build/english-bert-20261030';out=root/'contextual-data';out.mkdir(exist_ok=False);src=ROOT/'build/english-listwise-20261030/joint-features';parent=json.load(open(ENGINE/'english_acceptance.json'));tokenizer=Tokenizer.from_str(BertWordPieceTokenizer(str(root/'pretrained/vocab.txt'),lowercase=True).to_str());tokenizer.enable_truncation(max_length=L,direction='left');tokenizer.enable_padding(length=L)
 cohorts=[('train',core.rows(src/'cases.jsonl')),('objective',core.rows(ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl')),('development',core.rows(ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl')),('regressions',core.rows(ROOT/'config/product_eval_v1/native_regressions.jsonl'))]
 for name,cs in cohorts:
  dd=np.load(src/(name+'.npz'));raw=dd['raw'];costs=np.load(ROOT/'build/english-listwise-20261030/channel-features'/(name+'.npz'))['raw'][:,:,31];elig=dd['eligible'];dec=json.load(open(src/(name+'-decoded.json')));n=len(cs);ids=np.zeros((n,K+1,L),np.int32);segments=np.zeros_like(ids,dtype=np.int8);mask=np.zeros_like(ids,dtype=bool);numeric=np.zeros((n,K+1,29),np.float32);base=np.zeros((n,K+1),np.float32);allowed=np.zeros((n,K+1),bool);allowed[:,0]=True;labels=np.zeros(n,np.int64);pairs=[];positions=[]
  normalized=(raw-parent['means'])/parent['scales'];oldlogits=np.maximum(normalized[:,0,:]@np.array(parent['w1']).T+parent['b1'],0)@parent['w2']+parent['b2'];cut=math.log(parent['threshold']/(1-parent['threshold']))
  words=[]
  for i,c in enumerate(cs):
   proposals=dec['words'][i][:K];words.append(proposals);typed=c['typed'];left=c['left'];start=not left.strip() or left.rstrip()[-1:] in '.!?。！？\n';case=[float(typed[:1].isupper()),float(typed.isupper()),float(any(ch.isupper() for ch in typed[1:])),float(start)]
   # KEEP carries typed-word properties; replacement-dependent inputs are zero.
   copy=raw[i,0].copy();copy[[0,1,2,3,4,8,9,10,11,12,13,14,15,16,17,18,19]]=0;copy[7]=copy[6];copy[21]=copy[20];numeric[i,0,:22]=(copy-parent['means'])/parent['scales'];numeric[i,0,22:27]=case+[1.];numeric[i,0,28]=-costs[i,0]/10.;numeric[i,0,1]=0
   for j,word in enumerate([typed]+proposals):
    pairs.append((left[-256:]+word,typed));positions.append((i,j))
    if j:
     numeric[i,j,:22]=normalized[i,j-1];numeric[i,j,1]=0;numeric[i,j,22:27]=case+[0.];numeric[i,j,27]=costs[i,j-1]/10.;numeric[i,j,28]=(costs[i,j-1]-costs[i,0])/10.;allowed[i,j]=elig[i,j-1]
     # Old rank score difference is already encoded in feature4 for this rival.
     gap=0. if j==1 else min(0.,float(raw[i,j-1,4]));base[i,j]=max(-12.,min(4.,float(oldlogits[i]-cut)))+max(-12.,gap)
     if allowed[i,j] and c.get('target') and c['typed'].casefold()!=c['target'].casefold() and word.casefold()==c['target'].casefold():labels[i]=j
   if i%2000==0:print(name,'token requests',i,n,flush=True)
  for at in range(0,len(pairs),2048):
   encoded=tokenizer.encode_batch(pairs[at:at+2048])
   for (i,j),e in zip(positions[at:at+2048],encoded):ids[i,j]=e.ids;segments[i,j]=e.type_ids;mask[i,j]=e.attention_mask
  np.savez(out/(name+'.npz'),ids=ids,segments=segments,mask=mask,numeric=numeric,base=base,eligible=allowed,labels=labels);core.write_json(out/(name+'-decoded.json'),dict(words=words,restorations=dec['restorations']));print(name,'COMPLETE',n,'gold',int((labels>0).sum()),flush=True)
 core.write_json(out/'protocol.json',dict(schema='bert-candidate-sentence-keep-v2',input_format='[CLS] left+candidate [SEP] observed [SEP]',numeric_features=29,character_channel_sha256=core.digest(ROOT/'build/english-listwise-20261030/character-channel/model.json'),proposals=K,max_tokens=L,base_model=json.load(open(root/'pretrained/upstream.json')),training_source_sha256=core.digest(src/'cases.jsonl'),no_new_labels_used=True,script_sha256=core.digest(__file__),confirmation_used=False))
if __name__=='__main__':main()
