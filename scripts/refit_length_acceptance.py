#!/usr/bin/env python3
"""Refit the shared acceptance gate; optional regularized word-length offsets.

No runtime dictionary/edit-distance exception. Existing feature definitions and
hard guards remain intact. New word groups exclude both exposed synthetic panels.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import numpy as np

from audit_candidate_pipeline import digest, probe
from benchmark_single_edit import corruptions
from iterate_english_policy import split, hashed, f05
from iterate_unambiguous_neighbors import load_real, unique_neighbor
from analyze_single_edit_root_cause import restoration, metrics

FEATURES = ['winner_score', 'runner_margin', 'known_word', 'literal_score', 'score_over_literal', 'observed_apostrophe']


def evidence(case, output):
    surfaces = {e['word'] for e in output.get('apostropheEvidence', [])}
    ranked = sorted((r for r in output['candidates'] if r['word'] not in surfaces), key=lambda r: (-r['score'], r['word']))
    if not ranked:
        return [0.]*6, None
    b = ranked[0]
    literal = output['literalScore']
    values = [b['score'], min(b['score']-ranked[1]['score'], 20.) if len(ranked)>1 else 20.,
              int(output['known']), literal or 0., b['score']-literal if literal is not None else 0.,
              int("'" in case['typed'] or '’' in case['typed'])]
    eligible = len(case['typed'])>=3 and len(b['word'])>=3 and not b['completedChars'] and not (output['known'] and literal is None)
    return values, b['word'] if eligible else None


def make_new_cases(source, project):
    exposed = {line.split()[0] for line in (project/'config/english_iteration/2026-10-21/single-edit/words.tsv').open()}
    words = sorted(((word, int(count)) for word, count in map(str.split, source.open())
                    if word.isascii() and word.isalpha() and 4<=len(word)<=20 and word not in exposed),
                   key=lambda row: (-row[1], row[0]))
    # The first 2,000 remaining types were exposed by the rejected fallback.
    excluded = exposed | {w for w,n in words[:2000]}
    selected = words[2000:6000]
    assert not ({w for w,n in selected} & excluded)
    cases, parent, owner = [], {}, {}
    def root(word):
        parent.setdefault(word, word)
        if parent[word] != word:
            parent[word] = root(parent[word])
        return parent[word]
    for word, count in selected:
        for operation, typed in dict(clean=word, **corruptions(word)).items():
            a, b = root(word), root(owner.setdefault(typed, word))
            parent[max(a,b)] = min(a,b)
            cases.append(dict(id=f'new:{word}:{operation}', typed=typed, target=word, left='',
                              source='generated', operation=operation))
    # Connected target groups keep identical observations out of different splits.
    for case in cases:
        case['group'] = 'length-refit-word:'+root(case['target'])
    return cases, dict(selected_word_types=len(selected), excluded_exposed_types=len(excluded),
                       groups=len({c['group'] for c in cases}), cases=len(cases))


def fit(x, labels, weights, start, regularization, offsets):
    design = x if offsets else x[:,:7]
    parameters = start.copy() if offsets else start[:7].copy()
    target = parameters.copy()
    normalizer = weights.sum()
    for epoch in range(900):
        probabilities = 1/(1+np.exp(-np.clip(design@parameters, -40, 40)))
        penalty = regularization*(parameters-target)
        penalty[0] = 0.
        parameters -= .2/(1+epoch/450)*(design.T@(weights*(probabilities-labels))/normalizer+penalty)
    if not offsets:
        parameters = np.concatenate([parameters, np.zeros(6)])
    return parameters


def calibrate(logits, labels, eligible, restored_correct, restored_wrong, typos, weights, indices):
    indices = np.array(indices, dtype=int)
    typo_count = float((weights[indices]*typos[indices]).sum())
    tp = float((weights[indices]*restored_correct[indices]).sum())
    fp = float((weights[indices]*restored_wrong[indices]).sum())
    order = [i for i in indices if eligible[i]]
    order.sort(key=lambda i: -logits[i])
    cutoffs = [(f05(tp,fp,typo_count-tp), float('inf'))]
    for pos, i in enumerate(order):
        tp += weights[i]*labels[i]
        fp += weights[i]*(1-labels[i])
        if pos+1<len(order) and logits[order[pos+1]]==logits[i]:
            continue
        cutoffs.append((f05(tp,fp,typo_count-tp), float(logits[i])))
    return max(cutoffs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True, help='Archived probe before the rejected dictionary fallback')
    p.add_argument('--output-dir', type=Path, required=True)
    p.add_argument('--evaluate', action='store_true')
    p.add_argument('--frozen-selection', type=Path)
    args = p.parse_args()
    if args.evaluate and not args.frozen_selection:
        p.error('--evaluate requires --frozen-selection')
    project = Path(__file__).resolve().parents[1]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    model = project/'app/src/main/assets/language_model/model.cng'
    old_file = args.output_dir/'before-policy.json'
    policy = json.loads(old_file.read_text())
    apostrophe_file = project.parent/'QwenASR/crates/cantonese-ngram/src/english_apostrophe_acceptance.json'
    apostrophe = json.loads(apostrophe_file.read_text())
    real, real_outputs, real_file, cases_file, refs_file = load_real(project)
    real = [dict(c,source='real') for c in real]
    generated, intake = make_new_cases(args.source, project)
    requests = [dict(left='', typed=c['typed'], spelling_limit=4096) for c in generated]
    binding = dict(probe=digest(args.probe), model=digest(model), source=digest(args.source),
                   requests=hashlib.sha256(json.dumps(requests,sort_keys=True).encode()).hexdigest())
    cache_file = args.output_dir/'generated-native.json'
    if cache_file.exists() and json.loads(cache_file.read_text())['binding']==binding:
        generated_outputs = json.loads(cache_file.read_text())['outputs']
    else:
        print(f'Scoring {len(generated)} new mixed-operation cases; exposed words excluded',flush=True)
        generated_outputs = probe(args.probe, model, requests)
        cache_file.write_text(json.dumps(dict(binding=binding,outputs=generated_outputs)))
    blank_file = project/'build/single-edit-root-cause-2026-10-21/empty-context-native.json'
    blank_cache = json.loads(blank_file.read_text())
    lookup = {c['id']:c for c in real}
    blank_cases = [dict(lookup[cid],source='blank',left='') for cid in blank_cache['binding']['panel']]
    assert all(split(c['group'])=='train' for c in blank_cases)
    cases = real+generated+blank_cases
    outputs = real_outputs+generated_outputs+blank_cache['outputs']
    assert len(cases)==len(outputs)
    lexicon = {line.split()[0] for line in args.source.open() if len(line.split()[0])<=32}
    primary = np.array([c['typed'].lower()!=c['target'] and unique_neighbor(c['typed'].lower(),lexicon)==c['target'] for c in cases])
    typos = np.array([c['typed'].lower()!=c['target'] for c in cases])
    evidence_rows = [evidence(c,o) for c,o in zip(cases,outputs)]
    features = np.array([f for f,b in evidence_rows])
    best = [b for f,b in evidence_rows]
    restores = [restoration(apostrophe,o) for o in outputs]
    eligible = np.array([b is not None and not r for b,r in zip(best,restores)])
    labels = np.array([bool(b) and b.lower()==c['target'] and typo for b,c,typo in zip(best,cases,typos)],dtype=float)
    restored_correct = np.array([bool(r) and r.lower()==c['target'] and typo for r,c,typo in zip(restores,cases,typos)])
    restored_wrong = np.array([bool(r) and r.lower()!=c['typed'].lower() and not ok for r,c,ok in zip(restores,cases,restored_correct)])
    lengths = np.array([min(8,len(c['typed'])) for c in cases])
    normalized = (features-np.array(policy['means']))/np.array(policy['scales'])
    design = np.column_stack([np.ones(len(cases)), normalized, *[(lengths==n).astype(float) for n in range(3,9)]])
    start = np.array(policy['weights']+[0.]*6)
    partitions = [split(c['group']) for c in cases]
    fit_indices = np.array([i for i,c in enumerate(cases) if partitions[i]=='train' and eligible[i]
                           and int(hashed('calibration:'+c['group'])[:8],16)%5!=0])
    calibration = [i for i,c in enumerate(cases) if partitions[i]=='train'
                   and int(hashed('calibration:'+c['group'])[:8],16)%5==0 and (not typos[i] or primary[i])]
    panels = {}
    for partition in ('selection','evaluation'):
        panels[partition+'_real'] = [i for i,c in enumerate(cases) if c['source']=='real' and partitions[i]==partition]
        panels[partition+'_primary'] = [i for i,c in enumerate(cases) if c['source']=='generated' and partitions[i]==partition and (primary[i] or not typos[i])]
        panels[partition+'_primary_4_5'] = [i for i,c in enumerate(cases) if c['source']=='generated' and partitions[i]==partition and primary[i] and 4<=len(c['typed'])<=5]
    def summarize(fixes,name):
        ids=panels[name]
        return metrics([cases[i] for i in ids],[fixes[i] for i in ids])
    baseline = [o['correction'] for o in outputs]
    trials=[]
    counts=Counter(c['source'] for c in cases if split(c['group'])=='train')
    for augmentation in (0.,.25,.5,1.):
        weights=np.array([1. if c['source']=='real' else augmentation*.5*counts['real']/counts[c['source']] for c in cases])
        for regularization in (.001,.01,.1):
            for offsets in (False,True):
                params=fit(design[fit_indices],labels[fit_indices],weights[fit_indices],start,regularization,offsets)
                logits=design@params
                cal,cutoff=calibrate(logits,labels,eligible,restored_correct,restored_wrong,typos,weights,calibration)
                fixes=[r or (b if ok and z>=cutoff else None) for r,b,ok,z in zip(restores,best,eligible,logits)]
                trial=dict(method='joint_refit',augmentation=augmentation,regularization=regularization,length_adjustment=offsets,
                           parameters=params.tolist(),cutoff_logit=cutoff,calibration_f05=cal,
                           selection_real=summarize(fixes,'selection_real'),
                           selection_primary=summarize(fixes,'selection_primary'),
                           selection_primary_4_5=summarize(fixes,'selection_primary_4_5'))
                trials.append(trial)
                print(f'augment={augmentation} reg={regularization} length={offsets}: real={trial["selection_real"]["f05"]:.5f} primary={trial["selection_primary"]["f05"]:.5f}',flush=True)
        # Direct test of the user's hypothesis: preserve all six coefficients and
        # calibrate acceptance constants by length, with shrinkage toward one cutoff.
        original_logits=design@start
        cal,global_cutoff=calibrate(original_logits,labels,eligible,restored_correct,restored_wrong,typos,weights,calibration)
        length_cutoffs={}
        for length in range(3,9):
            indices=[i for i in calibration if lengths[i]==length]
            _,cutoff=calibrate(original_logits,labels,eligible,restored_correct,restored_wrong,typos,weights,indices)
            length_cutoffs[length]=cutoff if np.isfinite(cutoff) else global_cutoff
        for shrinkage in (0.,.5,1.):
            params=start.copy()
            for length,cutoff in length_cutoffs.items():
                params[7+length-3]=shrinkage*(global_cutoff-cutoff)
            logits=design@params
            fixes=[r or (b if ok and z>=global_cutoff else None) for r,b,ok,z in zip(restores,best,eligible,logits)]
            trial=dict(method='cutoffs_only',augmentation=augmentation,regularization=None,
                       length_adjustment=bool(shrinkage),shrinkage=shrinkage,
                       parameters=params.tolist(),cutoff_logit=global_cutoff,calibration_f05=cal,
                       selection_real=summarize(fixes,'selection_real'),selection_primary=summarize(fixes,'selection_primary'),
                       selection_primary_4_5=summarize(fixes,'selection_primary_4_5'))
            trials.append(trial)
            print(f'cutoffs only augment={augmentation} shrink={shrinkage}: real={trial["selection_real"]["f05"]:.5f} 4/5={trial["selection_primary_4_5"]["correct"]}',flush=True)
    base_real=summarize(baseline,'selection_real')
    base_primary=summarize(baseline,'selection_primary')
    passing=[t for t in trials if t['selection_real']['f05']>=base_real['f05'] and t['selection_primary']['f05']>base_primary['f05']]
    winner=max(passing,key=lambda t:(t['selection_primary_4_5']['f05'],t['selection_primary']['f05'],t['selection_real']['f05'],not t['length_adjustment'])) if passing else None
    report=dict(method='Same six features and hard guards; optional length-3/4/5/6/7/8+ additive logit constants. '
                'Regularized toward the accepted shared rule. TRAIN fit/calibration; selection excludes evaluation. '
                '24 joint-refit trials plus 12 direct cutoff/shrinkage trials; no evaluation used to select. '
                'Selection prioritizes unambiguous 4/5-letter F0.5, requires preserved overall real selection F0.5. '
                'Synthetic word/observation groups disjoint; exposed 3,000 types excluded.',
                intake=intake,fit_rows=len(fit_indices),calibration_rows=len(calibration),
                baseline_selection_real=base_real,baseline_selection_primary=base_primary,
                trials=trials,champion=winner,
                hashes={str(f):digest(f) for f in (Path(__file__),old_file,apostrophe_file,model,args.source,cache_file,blank_file,real_file,cases_file,refs_file)})
    if args.evaluate:
        frozen=json.loads(args.frozen_selection.read_text())
        assert frozen==report,'Selection changed after freezing'
        assert winner,'No qualified candidate'
        logits=design@np.array(winner['parameters'])
        fixes=[r or (b if ok and z>=winner['cutoff_logit'] else None) for r,b,ok,z in zip(restores,best,eligible,logits)]
        report.update(evaluation_real=summarize(fixes,'evaluation_real'),baseline_evaluation_real=summarize(baseline,'evaluation_real'),
                      evaluation_primary=summarize(fixes,'evaluation_primary'),baseline_evaluation_primary=summarize(baseline,'evaluation_primary'),
                      evaluation_primary_4_5=summarize(fixes,'evaluation_primary_4_5'),baseline_evaluation_primary_4_5=summarize(baseline,'evaluation_primary_4_5'))
    (args.output_dir/('evaluation.json' if args.evaluate else 'selection.json')).write_text(json.dumps(report,indent=2)+'\n')
    (args.output_dir/'cases.jsonl').write_text(''.join(json.dumps(c)+'\n' for c in cases))
    print(json.dumps(dict(champion=winner,baseline_real=base_real,baseline_primary=base_primary),indent=2))


if __name__=='__main__':
    main()
