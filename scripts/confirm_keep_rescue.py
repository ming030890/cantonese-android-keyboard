#!/usr/bin/env python3
"""One source-disjoint chat confirmation after all native/product checks."""
import json,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
from experiment_conditional_english_scores import metrics,eligible
from check_autocorrect_regressions import check
ROOT=Path(__file__).resolve().parents[1]

def main():
 root=ROOT/'build/english-keep-20261031';out=root/'confirmation';out.mkdir(exist_ok=False);suite=root/'fresh-chat-v2';file=suite/'confirmation.jsonl';protocol=json.load(open(suite/'protocol.json'));probe=Path('/private/tmp/keyboard-keep-production/release/ngram_probe');baseline=Path('/private/tmp/keyboard-channel-repaired/release/ngram_probe');model=ROOT/'app/src/main/assets/language_model/model.cng'
 assert core.digest(file)==protocol['split_hashes']['confirmation']
 assert 'BUILD SUCCESSFUL' in Path('/private/tmp/keep-verify-final.log').read_text()
 assert not json.load(open(root/'native-parity-stable/report.json'))['mismatches']
 assert json.load(open(root/'production-objective/score.json'))['delta']>0
 assert check(probe,model)['passed']
 native=ROOT.parent/'QwenASR/crates/cantonese-ngram'
 for rel in ['src/cnn.rs','src/english_channel.rs','src/english_keep.rs','src/english_keep_trees.json','src/lib.rs','src/suggestions.rs','src/bin/ngram_probe.rs','Cargo.toml']:
  assert core.digest(native/rel)==core.digest(root/'candidate-source-stable'/rel)
 frozen=dict(schema='keep-rescue-chat-confirmation-v1',confirmation_sha256=core.digest(file),policy_sha256=core.digest(native/'src/english_keep_trees.json'),probe_sha256=core.digest(probe),baseline_probe_sha256=core.digest(baseline),script_sha256=core.digest(__file__),selection='Best of four models by TRAIN-only calibration F0.5 with source-family wrong/clean budgets and TP retention; stable numeric port passed development and unchanged JNI guards',decision='Every chat panel must retain baseline TP, FP and clean-word budgets, with at least one improved count; natural and synthetic errors reported separately',previously_scored=False,product_checks_passed=True)
 core.write_json(out/'frozen-choice.json',frozen);cs=core.rows(file);values={};outputs={}
 for name,binary in [('baseline',baseline),('candidate',probe)]:
  p=subprocess.run([str(binary),str(model)],input=core.native_requests(cs),text=True,capture_output=True,check=True,timeout=600);(out/(name+'-outputs.jsonl')).write_text(p.stdout);outputs[name]=[json.loads(l) for l in p.stdout.splitlines()];values[name]=metrics(cs,outputs[name]);print(name,{k:(v['tp'],v['fp'],v['clean_changes'],v['typos']) for k,v in values[name].items()},flush=True)
 uncertainty={}
 for panel in values['baseline']:
  ids=[i for i,c in enumerate(cs) if c['panel']==panel];uncertainty[panel]=core.uncertainty([cs[i] for i in ids],[outputs['candidate'][i] for i in ids],[outputs['baseline'][i] for i in ids])
 improvement=any(values['candidate'][k]['tp']>v['tp'] or values['candidate'][k]['fp']<v['fp'] for k,v in values['baseline'].items());passed=eligible(values['candidate'],values['baseline']) and improvement
 report=dict(protocol=frozen,metrics=values,uncertainty=uncertainty,qualified=passed,confirmation_now_exposed=True,limitations=protocol['limitations']);core.write_json(out/'report.json',report);print('QUALIFIED',passed,flush=True)
if __name__=='__main__':main()
