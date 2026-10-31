#!/usr/bin/env python3
"""Audit the isolation repair and verify final native decisions; no new fitting."""
import json,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]

def main():
 r=ROOT/'build/english-noisy-channel-20261030';out=r/'repair-verification';out.mkdir(exist_ok=False)
 probe=Path('/private/tmp/keyboard-channel-repaired/release/ngram_probe');model=ROOT/'app/src/main/assets/language_model/model.cng'
 fitted=core.rows(r/'repaired-channel/fitted-cases.jsonl');calibration=core.rows(r/'repaired-data/cases.jsonl')[slice(*json.load(open(r/'repaired-data/slices.json'))['calibration'])];inputs={(c['left'],c['typed'].replace('’',"'").casefold()) for c in fitted+calibration};overlap={}
 for name,path in [('frozen',ROOT/'build/autocorrect-eval-v4/frozen/cases.jsonl'),('development',ROOT/'build/english-char-gate-20261030/fresh-eval/development.jsonl'),('confirmation',ROOT/'build/english-char-gate-20261030/fresh-eval/confirmation.jsonl')]:
  overlap[name]=len(inputs&{(c['left'],c['typed'].replace('’',"'").casefold()) for c in core.rows(path)})
 assert not any(overlap.values()),overlap
 report=dict(exact_canonical_inference_input_overlap=overlap,fit_cases=len(fitted),fit_cases_sha256=core.digest(r/'repaired-channel/fitted-cases.jsonl'),policy_sha256=core.digest(r/'repaired-study/replace-calrank0.json'),probe_sha256=core.digest(probe),initial_confirmation_report_sha256=core.digest(r/'confirmation/report.json'),confirmation_already_exposed=True,reason='Remove one leaked frozen clean control; rerun identical TRAIN-only calibration. No decisions or labels from confirmation selected the repair.',cohorts={})
 for name,path,expectedpath in [('development_calibration_regressions',r/'repaired-data/cases.jsonl',r/'repaired-study/replace-calrank0-outputs.jsonl'),('confirmation',ROOT/'build/english-char-gate-20261030/fresh-eval/confirmation.jsonl',r/'confirmation/candidate-outputs.jsonl')]:
  cs=core.rows(path);p=subprocess.run([str(probe),str(model)],input=core.native_requests(cs),text=True,capture_output=True,check=True,timeout=600);(out/(name+'-outputs.jsonl')).write_text(p.stdout);actual=[json.loads(l) for l in p.stdout.splitlines()];expected=core.rows(expectedpath);changes=[]
  for c,a,b in zip(cs,actual,expected,strict=True):
   if (a.get('correction') or c['typed']).casefold()!=(b.get('correction') or c['typed']).casefold():changes.append(dict(id=c['id'],actual=a.get('correction'),expected=b.get('correction')))
  report['cohorts'][name]=dict(cases=len(cs),decision_changes=changes);print(name,len(cs),'CHANGES',len(changes),flush=True)
 assert not any(v['decision_changes'] for v in report['cohorts'].values())
 core.write_json(out/'report.json',report)
if __name__=='__main__':main()
