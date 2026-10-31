#!/usr/bin/env python3
"""One source-disjoint confirmation after native, JNI and keyboard checks."""
import json,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import metrics,eligible
from check_autocorrect_regressions import check
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-noisy-channel-20261030';out=root/'confirmation';out.mkdir(exist_ok=False)
 suite=ROOT/'build/english-char-gate-20261030/fresh-eval';protocol=json.load(open(suite/'protocol.json'));file=suite/'confirmation.jsonl'
 assert core.digest(file)==protocol['split_hashes']['confirmation']
 probe=Path('/private/tmp/keyboard-channel-production/release/ngram_probe');base=Path('/private/tmp/keyboard-release74-saved/ngram_probe');model=ROOT/'app/src/main/assets/language_model/model.cng'
 assert 'BUILD SUCCESSFUL' in Path('/private/tmp/channel-verify-final.log').read_text()
 assert json.load(open(root/'native-parity.json'))['mismatches']==[]
 assert json.load(open(root/'native-objective/score.json'))['delta']>0
 assert check(probe,model)['passed']
 choice=dict(schema='english-noisy-channel-confirmation-v1',policy_sha256=core.digest(root/'study/replace-calrank0.json'),probe_sha256=core.digest(probe),baseline_probe_sha256=core.digest(base),confirmation_sha256=core.digest(file),script_sha256=core.digest(__file__),selection='Highest TRAIN-calibration F0.5 with per-source wrong/clean budgets; top calibration policy passed frozen objective and development guards',decision='All panels retain baseline TP, FP and clean budgets; English real-author typos must gain at least one additional correct fix. Report grouped uncertainty; this is not spontaneous phone typing.',previously_scored=False,all_product_checks_passed=True)
 core.write_json(out/'frozen-choice.json',choice)
 cs=core.rows(file);outputs={};values={}
 for name,binary in [('baseline',base),('candidate',probe)]:
  p=subprocess.run([str(binary),str(model)],input=core.native_requests(cs),text=True,capture_output=True,check=True,timeout=600);(out/(name+'-outputs.jsonl')).write_text(p.stdout);outputs[name]=[json.loads(l) for l in p.stdout.splitlines()];values[name]=metrics(cs,outputs[name]);print(name,{k:(v['tp'],v['fp'],v['clean_changes']) for k,v in values[name].items()},flush=True)
 uncertainty={}
 for panel in values['baseline']:
  ids=[i for i,c in enumerate(cs) if c['panel']==panel];uncertainty[panel]=core.uncertainty([cs[i] for i in ids],[outputs['candidate'][i] for i in ids],[outputs['baseline'][i] for i in ids])
 gain=values['candidate']['github_real_typo']['tp']-values['baseline']['github_real_typo']['tp'];passed=eligible(values['candidate'],values['baseline']) and gain>0
 report=dict(protocol=choice,metrics=values,uncertainty=uncertainty,additional_correct_english=gain,qualified=passed,confirmation_now_exposed=True)
 core.write_json(out/'report.json',report);print('QUALIFIED',passed,'EXTRA ENGLISH FIXES',gain)
if __name__=='__main__':main()
