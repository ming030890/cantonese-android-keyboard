#!/usr/bin/env python3
"""Execute the frozen mixed-case candidate once, after its full product gate."""
import json,sys
from pathlib import Path
import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import infer,metrics,eligible
from check_autocorrect_regressions import check
ROOT=Path(__file__).resolve().parents[1]
r=ROOT/'build/bilingual-residual-20261030';out=r/'confirmation';out.mkdir(exist_ok=False)
probe=Path('/private/tmp/keyboard-mixed-case-20261030/release/ngram_probe');base=Path('/private/tmp/keyboard-release74-saved/ngram_probe');model=ROOT/'app/src/main/assets/language_model/model.cng'
protocol=json.loads((r/'confirmation-protocol.json').read_text());assert core.digest(r/'fresh-eval/confirmation.jsonl')==protocol['confirmation_cases_sha256']
assert 'BUILD SUCCESSFUL' in Path('/private/tmp/mixed-verify-final.log').read_text()
assert json.loads((r/'native-feature-parity.json').read_text())['mismatches']==[]
assert json.loads((r/'final-objective/score.json').read_text())['delta']>0
assert check(probe,model)['passed']
core.write_json(out/'frozen-choice.json',dict(protocol,probe_sha256=core.digest(probe),script_sha256=core.digest(__file__),all_checks_passed_before_confirmation=True))
cases=core.rows(r/'fresh-eval/confirmation.jsonl');results={};outputs={}
for name,binary in [('baseline',base),('candidate',probe)]:
 outputs[name]=infer(binary,model,cases,'candidate',0);core.jsonl(out/(name+'-outputs.jsonl'),outputs[name]);results[name]=metrics(cases,outputs[name]);print(name,{k:(v['tp'],v['fp'],v['clean_changes']) for k,v in results[name].items()},flush=True)
uncertainty={p:core.uncertainty([c for c in cases if c['panel']==p],[o for c,o in zip(cases,outputs['candidate']) if c['panel']==p],[o for c,o in zip(cases,outputs['baseline']) if c['panel']==p]) for p in results['baseline']}
gain=results['candidate']['mixed_synthetic_typo']['recall']-results['baseline']['mixed_synthetic_typo']['recall'];passed=eligible(results['candidate'],results['baseline']) and gain>=.05
report=dict(protocol=protocol,results=results,uncertainty=uncertainty,mixed_recall_delta=gain,qualified=passed,confirmation_now_exposed=True)
core.write_json(out/'report.json',report);print('QUALIFIED',passed,'MIXED RECALL DELTA',gain)
