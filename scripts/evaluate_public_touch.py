#!/usr/bin/env python3
"""One frozen diagnostic comparison on real public phone taps. Never trains.

Production, synthetic text-only refit, and synthetic touch+acceptance refit use
identical native candidate pools and observed contexts. No result promotes code.
"""
import argparse
import collections
import json
import time
from pathlib import Path

import numpy as np
from fit_tsi_touch import PROJECT, ENGINE, digest, metrics
from public_touch_eval import prepare, dump, stream_metrics
from train_synthetic_touch import rerank, decide
from verify_tsi_touch import probe
from iterate_unambiguous_neighbors import unique_neighbor


def intervals(cases, old, new):
    people=sorted({c['participant'] for c in cases})
    counts=[]
    for person in people:
        ids=[i for i,c in enumerate(cases) if c['participant']==person]
        row=[]
        for values in [old,new]:
            m=metrics([cases[i] for i in ids],[values[i] for i in ids])
            row.append([m['correct'],m['wrong'],m['typos']])
        counts.append(row)
    a=np.asarray(counts);rng=np.random.default_rng(20261022);delta=[]
    for _ in range(2000):
        totals=a[rng.integers(0,len(people),len(people))].sum(0)
        tp,fp,typos=totals.T
        f=np.divide(1.25*tp,tp+fp+.25*typos,out=np.zeros(2),where=(tp+fp+.25*typos)>0)
        delta.append(float(f[1]-f[0]))
    return dict(unit='Participant-cluster bootstrap; corpus prompts may recur across participants',replicates=2000,
                delta_f05=metrics(cases,new)['f05']-metrics(cases,old)['f05'],
                percentile_95_interval=np.percentile(delta,[2.5,97.5]).tolist())


def main():
    p=argparse.ArgumentParser(description=__doc__)
    root=PROJECT/'config/english_iteration/2026-10-22/public-touch'
    p.add_argument('--probe',type=Path,required=True)
    p.add_argument('--archive',type=Path,default=PROJECT/'build/touch-eval-public/noisy-typing.zip')
    p.add_argument('--protocol',type=Path,default=root/'protocol.json')
    p.add_argument('--frozen',type=Path,default=root/'synthetic-frozen.json')
    p.add_argument('--output',type=Path,default=root/'evaluation.json')
    p.add_argument('--prepared',type=Path,default=PROJECT/'build/touch-eval-public/prepared')
    p.add_argument('--lexicon',type=Path,default=PROJECT/'build/neighbor-repair-2026-10-21/selection.lexicon.txt')
    a=p.parse_args()
    if a.output.exists():
        raise ValueError('Evaluation already exists; preserve it, do not tune against it')
    frozen=json.loads(a.frozen.read_text())
    assert frozen['protocol_sha256']==digest(a.protocol)
    assert frozen['probe_sha256']==digest(a.probe)
    assert frozen['script_sha256']==digest(Path(__file__).with_name('train_synthetic_touch.py'))
    assert frozen['lm_sha256']==digest(PROJECT/'app/src/main/assets/language_model/model.cng')
    assert frozen['production_policy_sha256']==digest(ENGINE/'english_acceptance.json')
    # Save scoring details/code identities BEFORE reading participant test logs.
    scoring=dict(schema='public-phone-touch-scoring-v1',frozen_sha256=digest(a.frozen),
                 source_protocol_sha256=digest(a.protocol),lexicon_sha256=digest(a.lexicon),
                 primary='Unknown dictionary input, 4+ letters, exactly one single-edit dictionary repair, that repair is a neighbour substitution and equals the aligned target; clean controls separately retained',
                 controls='All observed labelled clean words, no source-copy controls added',
                 full_cohort='All uniquely labelled observed words, including unavailable model targets; whole-trial CER retains ambiguous and missing/split words',
                 systems=['production','synthetic_text','synthetic_touch'],
                 code_sha256={n:digest(Path(__file__).with_name(n)) for n in ['evaluate_public_touch.py','public_touch_eval.py','train_synthetic_touch.py','fit_tsi_touch.py']})
    scoring_file=a.output.with_name('scoring-protocol.json')
    if scoring_file.exists() and json.loads(scoring_file.read_text())!=scoring:
        raise ValueError('Scoring code differs from frozen scoring protocol')
    dump(scoring_file,scoring)
    manifest=prepare(a.archive,a.protocol,a.prepared)
    cases=[json.loads(line) for line in (a.prepared/'cases.jsonl').open()]
    trials=[json.loads(line) for line in (a.prepared/'trials.jsonl').open()]
    requests=[dict(left=c['left'],typed=c['typed'],spelling_limit=4096,timing=True) for c in cases]
    cache_file=a.prepared/'native-cache.json'
    if cache_file.exists():
        saved=json.loads(cache_file.read_text())
        assert saved['cases_sha256']==manifest['cases_sha256'] and saved['probe_sha256']==digest(a.probe)
        results=saved['results']
    else:
        results=probe(a.probe,requests)
        dump(cache_file,dict(cases_sha256=manifest['cases_sha256'],probe_sha256=digest(a.probe),results=results))
    outputs={'production':[r['correction'] for r in results]};timings={}
    for name,profile in [('synthetic_text',frozen['text_control']),('synthetic_touch',frozen['touch'])]:
        predictions=[];elapsed=[]
        for c,r in zip(cases,results):
            start=time.perf_counter_ns()
            ranked=rerank(c,r,frozen['touch_model'],profile['weight'])
            predictions.append(decide(c,r,ranked,profile['policy']))
            elapsed.append((time.perf_counter_ns()-start)/1e6)
        outputs[name]=predictions
        timings[name]=dict(python_ranking_acceptance_median_ms=float(np.median(elapsed)),
                           python_ranking_acceptance_p95_ms=float(np.percentile(elapsed,95)))
    labelled=[i for i,c in enumerate(cases) if c['target'] is not None]
    lexicon={line.strip() for line in a.lexicon.open() if line.strip()}
    clean=[i for i in labelled if cases[i]['typed']==cases[i]['target']]
    primary=[i for i in labelled if cases[i]['typed']!=cases[i]['target'] and unique_neighbor(cases[i]['typed'],lexicon)==cases[i]['target']]
    def panel(ids):
        return {name:metrics([cases[i] for i in ids],[values[i] for i in ids]) for name,values in outputs.items()}
    by_person={person:panel([i for i in labelled if cases[i]['participant']==person]) for person in sorted({c['participant'] for c in cases})}
    scores=dict(all_labelled=panel(labelled),primary_unambiguous_neighbor=panel(primary+clean),
                primary_short_4_5=panel([i for i in primary+clean if 4<=len(cases[i]['typed'])<=5]),
                preservation=panel(clean),by_participant=by_person,
                space_boundaries=panel([i for i in labelled if cases[i]['boundary']=='space']),
                submission_boundaries=panel([i for i in labelled if cases[i]['boundary']=='submission']))
    def comparison(old,new):
        return intervals([cases[i] for i in labelled],[outputs[old][i] for i in labelled],[outputs[new][i] for i in labelled])
    report=dict(schema='public-phone-touch-results-v1',production_approved=False,assets_changed=False,
                data_manifest=manifest,scoring_protocol_sha256=digest(scoring_file),
                synthetic_candidate_frozen_before_real_evaluation=True,
                synthetic_touch_beats_text_on_synthetic_selection=frozen['touch']['selection']['f05']>frozen['text_control']['selection']['f05'],
                word_metrics=scores,
                stream_metrics={name:stream_metrics(trials,cases,values) for name,values in outputs.items()},
                literal_stream=stream_metrics(trials,cases,[None]*len(cases)),
                paired_comparisons=dict(touch_vs_production=comparison('production','synthetic_touch'),
                                        touch_vs_text_refit=comparison('synthetic_text','synthetic_touch')),
                coverage=dict(unlabelled_words=len(cases)-len(labelled),
                              unavailable_target_candidates=sum(cases[i]['typed']!=cases[i]['target'] and cases[i]['target'] not in {r['word'].lower() for r in results[i]['candidates']} for i in labelled),
                              lexicon_words=len(lexicon)),
                latency=dict(scope='Release-host native generation with full candidate return plus Python experimental scoring; not phone input-to-display',
                             native_generation_median_ms=float(np.median([r['elapsedMicros']/1000 for r in results])),
                             native_generation_p95_ms=float(np.percentile([r['elapsedMicros']/1000 for r in results],95)),
                             experimental=timings),
                limits=json.loads(a.protocol.read_text())['limits'],
                hashes=dict(frozen=digest(a.frozen),native_cache=digest(cache_file),probe=digest(a.probe)))
    dump(a.output,report)
    print(json.dumps(dict(word_metrics=scores['all_labelled'],primary=scores['primary_unambiguous_neighbor'],
                          paired_comparisons=report['paired_comparisons'],data=manifest),indent=2))


if __name__=='__main__':
    main()
