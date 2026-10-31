#!/usr/bin/env python3
"""Fit letter-specific edits from actual TRAIN author/phone errors, not evals."""
import argparse,ast,collections,json,math,subprocess
from pathlib import Path
import numpy as np
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1];CHARS="abcdefghijklmnopqrstuvwxyz' ";WIDTH=66
# Reuse exactly the tested OSA alignment used by the character experiment.
tree=ast.parse((ROOT/'scripts/train_english_pair_cnn.py').read_text());node=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='encode');exec(compile(ast.Module(body=[node],type_ignores=[]),'<alignment>','exec'))

def main():
 root=ROOT/'build/english-listwise-20261030';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=root/'character-channel');args=parser.parse_args();out=args.output;out.mkdir(exist_ok=False);cs=core.rows(root/'joint-features/cases.jsonl')
 blocked=set()
 for suite in (ROOT/'build/autocorrect-eval-v4/frozen',ROOT/'build/independent-autocorrect-eval-20261030/frozen-v2',ROOT/'build/bilingual-residual-20261030/fresh-eval',ROOT/'build/english-char-gate-20261030/fresh-eval'):
  for file in ([suite/'cases.jsonl'] if (suite/'cases.jsonl').exists() else [suite/'development.jsonl',suite/'confirmation.jsonl']):
   blocked.update((c['left'],c['typed'].casefold()) for c in core.rows(file))
 excluded=sum((c['left'],c['typed'].casefold()) in blocked for c in cs);cs=[c for c in cs if (c['left'],c['typed'].casefold()) not in blocked];fitted=[]
 ref=np.zeros(29);delete_ref=np.zeros((29,2));pairs_ref=np.zeros((29,29));subs=np.zeros((29,29));deletes=np.zeros((29,2));inserts=np.zeros((29,29));swaps=np.zeros((29,29));seen=set();words=0
 for c in cs:
  if c['fit_split']!='fit' or c.get('noise') or c['source'] not in ('real','real_author_typo','real_author_clean'):continue
  key=(c['group'],c['left'],c['typed'].casefold(),c['target'].casefold())
  if key in seen:continue
  seen.add(key);target=c['target'].replace('’',"'").lower();observed=c['typed'].replace('’',"'").lower()
  if not target or not observed or len(target)>32 or len(observed)>32 or any(ch not in CHARS for ch in target+observed):continue
  fitted.append(c);words+=1;number=lambda c:CHARS.index(c)+1 if c else 0;ref[0]+=1
  for i,ch in enumerate(target):
   j=number(ch);ref[j]+=1;repeat=int((i>0 and target[i-1]==ch) or (i+1<len(target) and target[i+1]==ch));delete_ref[j,repeat]+=1
   if i:pairs_ref[number(target[i-1]),j]+=1
  aligned=[((v-1)//29,(v-1)%29) for v in encode(observed,target) if v not in (0,842,843)];i=0;previous_observed=0
  while i<len(aligned):
   t,candidate=aligned[i]
   if i+1<len(aligned) and t and candidate and t!=candidate and aligned[i+1]==(candidate,t):swaps[candidate,t]+=1;previous_observed=candidate;i+=2;continue
   if not candidate:inserts[previous_observed,t]+=1
   elif not t:
    prev=next((y for _,y in reversed(aligned[:i]) if y),0);nxt=next((y for _,y in aligned[i+1:] if y),0);deletes[candidate,int(prev==candidate or nxt==candidate)]+=1
   elif t!=candidate:subs[candidate,t]+=1
   if t:previous_observed=t
   i+=1
 positions={ch:(r,k) for r,row in enumerate(['qwertyuiop','asdfghjkl','zxcvbnm']) for k,ch in enumerate(row)}
 def adjacent(a,b):
  if a==b:return False
  if a==' ':return b in 'cvbnm'
  if b==' ':return a in 'cvbnm'
  return a in positions and b in positions and max(abs(x-y) for x,y in zip(positions[a],positions[b]))<=1
 subrate=max(subs.sum()/ref.sum(),1e-5);delrate=np.maximum(deletes.sum(0)/np.maximum(delete_ref.sum(0),1),1e-5);insrate=max(inserts.sum()/ref.sum(),1e-5);swaprate=max(swaps.sum()/max(1,pairs_ref.sum()),1e-5)
 sc=np.ones((29,29))*15.;dc=np.ones((29,2))*15.;ic=np.ones((29,29))*15.;tc=np.ones((29,29))*15.
 for c in range(1,29):
  prior=np.array([0. if t==c or t==0 else 1. if adjacent(CHARS[c-1],CHARS[t-1]) else .05 for t in range(29)]);prior/=prior.sum()
  for t in range(1,29):sc[c,t]=0. if c==t else -math.log(max(1e-9,(subs[c,t]+30*subrate*prior[t])/(ref[c]+30)))
  for repeat in (0,1):dc[c,repeat]=-math.log(max(1e-9,(deletes[c,repeat]+30*delrate[repeat])/(delete_ref[c,repeat]+30)))
 for p in range(29):
  prior=np.array([0.]+[1. if p and t==p else .2 if p and adjacent(CHARS[p-1],CHARS[t-1]) else .02 for t in range(1,29)]);prior/=prior.sum()
  for t in range(1,29):ic[p,t]=-math.log(max(1e-9,(inserts[p,t]+30*insrate*prior[t])/(ref[p]+30)))
 for a in range(1,29):
  for b in range(1,29):tc[a,b]=-math.log(max(1e-9,(swaps[a,b]+20*swaprate)/(pairs_ref[a,b]+20)))
 core.jsonl(out/'fitted-cases.jsonl',fitted)
 model=dict(schema='english-character-error-channel-v1',characters=CHARS,substitution=sc.tolist(),deletion=dc.tolist(),insertion=ic.tolist(),transposition=tc.tolist(),fit_words=words,fit_operations=dict(substitution=float(subs.sum()),deletion=float(deletes.sum()),insertion=float(inserts.sum()),transposition=float(swaps.sum())),train_cases_sha256=core.digest(out/'fitted-cases.jsonl'),source_cases_sha256=core.digest(root/'joint-features/cases.jsonl'),excluded_eval_inputs=excluded,eval_examples_used=False)
 core.write_json(out/'model.json',model)
 # Compile an offline scorer; the same recurrence will be ported and verified in Rust if useful.
 arrays='\n'.join('static const double '+name+'['+str(arr.shape[0])+']['+str(arr.shape[1])+']='+json.dumps(arr.tolist()).replace('[','{').replace(']','}')+';' for name,arr in [('S',sc),('D',dc),('I',ic),('T',tc)])
 code=r'''#include <iostream>
#include <string>
#include <algorithm>
#include <iomanip>
'''+arrays+r'''
int id(char c){if(c>='a'&&c<='z')return c-'a'+1;if(c=='\'')return 27;if(c==' ')return 28;return 0;}
int main(){std::string line;std::cout<<std::setprecision(9);while(std::getline(std::cin,line)){auto sep=line.find('\t');auto a=line.substr(0,sep),b=line.substr(sep+1);double x[33][33];int n=a.size(),m=b.size();x[0][0]=0;
for(int i=0;i<=n;i++)for(int j=0;j<=m;j++){if(i==0&&j==0)continue;double v=1e9;if(i){int p=i>1?id(a[i-2]):0;v=std::min(v,x[i-1][j]+I[p][id(a[i-1])]);}if(j){int c=id(b[j-1]);int repeat=(j>1&&b[j-2]==b[j-1])||(j<m&&b[j]==b[j-1]);v=std::min(v,x[i][j-1]+D[c][repeat]);}if(i&&j)v=std::min(v,x[i-1][j-1]+S[id(b[j-1])][id(a[i-1])]);if(i>1&&j>1&&a[i-1]==b[j-2]&&a[i-2]==b[j-1])v=std::min(v,x[i-2][j-2]+T[id(b[j-2])][id(b[j-1])]);x[i][j]=v;}std::cout<<(float)x[n][m]<<'\n';}}
'''
 # Raw Python string uses ordinary C++ escapes.
 code=code.replace("'\\\\t'","'\\t'").replace("'\\\\n'","'\\n'").replace("'\\\\''","'\\''")
 (out/'score.cpp').write_text(code);subprocess.run(['clang++','-O3','-std=c++17',str(out/'score.cpp'),'-o',str(out/'score')],check=True)
 print('FITTED',words,model['fit_operations'],flush=True)
if __name__=='__main__':main()
