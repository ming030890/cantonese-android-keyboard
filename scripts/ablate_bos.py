#!/usr/bin/env python3
"""Compare trained BOS with a no-BOS inference ablation on fixed development rows."""
import argparse,csv,json
from pathlib import Path
from audit_candidate_pipeline import digest,probe
from align_mobile_targets import reference_target
from iterate_english_policy import metric
from fit_mixed_ranking import metrics

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--probe',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets';model=assets/'language_model/model.cng'
 frozen=json.loads((root/'build/mixed-ranking-features.json').read_text());rows=frozen['rows'];results={}
 for name,extra in [('bos',()),('no_bos',('--no-bos',))]:
  out=probe(a.probe,model,[dict(mixed=r['candidates'],observed=r['observed'],left=r['left'],timing=True) for r in rows],extra=extra)
  orders=[[r['candidates'][i] for i in o['order']] for r,o in zip(rows,out)]
  results[name]=dict(all={q:metrics(rows,orders,q) for q in ['train','selection','evaluation']},
   evaluation_by_language={lang:metrics([r for r in rows if r['language']==lang],[o for r,o in zip(rows,orders) if r['language']==lang],'evaluation') for lang in ['chinese','english']})
  print(name,'mixed',json.dumps(results[name]),flush=True)
 iteration=root/'build/english-iteration-2026-10-20';refs={r['SENTENCE_ID']:r['SENTENCE'] for r in csv.DictReader(open('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv'))};cases=[]
 for c in map(json.loads,(iteration/'cases.jsonl').open()):
  if c['category'].startswith('prefix_'):continue
  target=c['target'] if c['category']=='source_preservation' else reference_target(c,refs[c['group']])
  if target is not None:cases.append(dict(c,target=target))
 baseline=json.loads((root/'build/apostrophe-native-verification.predictions.json').read_text())
 assert baseline['case_ids']==[c['id'] for c in cases]
 print('Scoring no-BOS autocorrect on',len(cases),'cases',flush=True)
 cache_path=root/'build/no-bos-autocorrect.json';binding=dict(probe=digest(a.probe),model=digest(model))
 cache=json.loads(cache_path.read_text()) if cache_path.exists() else {}
 if cache.get('binding')!=binding:
  actual=probe(a.probe,model,[dict(left=c['left'],typed=c['typed'],spelling_limit=8) for c in cases],extra=('--no-bos',));cache=dict(binding=binding,outputs=actual);cache_path.write_text(json.dumps(cache))
 else:actual=cache['outputs']
 for name,outs in [('bos',baseline['outputs']),('no_bos',actual)]:results[name]['autocorrect']=metric(cases,outs,[o['candidates'] for o in outs],[o['correction'] for o in outs],'evaluation')
 smoke={}
 for name,extra in [('bos',()),('no_bos',('--no-bos',))]:
  cs=['兒歌','自歌','自兩','兜兩','continue'];o=probe(a.probe,model,[dict(sequences=cs),dict(mixed=cs,observed='humo')],extra=extra)
  smoke[name]=dict(scores=dict(zip(cs,o[0]['evidence'])),order=[cs[i] for i in o[1]['order']])
 report=dict(binding=binding,cohort_sha256=digest(root/'build/mixed-ranking-features.json'),results=results,smoke=smoke,
  method='Same frozen candidate sets, weights and acceptance policies; no refitting. No-BOS removes the prepended token; empty input uses output-bias prior. This is an inference mismatch with BOS-trained weights, not a no-BOS retrained model.')
 a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({n:r['autocorrect'] for n,r in results.items()},indent=2))
if __name__=='__main__':main()
