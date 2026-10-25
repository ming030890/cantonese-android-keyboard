#!/usr/bin/env python3
"""Prepare intact recorded streams, with no injected errors or prompt futures.

VelociTap author development logs are separate from frozen participant test
logs. Google retained tap sequences are separately tagged: not full edit logs.
References determine labels and grouping only; observed word boundaries determine
the actual stream. No error-based subsampling or clean/typo balancing.
"""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path
import re
import zipfile

import autocorrect_eval_v2 as core
import public_touch_eval as touch
from fit_tsi_touch import partition


def prompt_key(text):
    return ' '.join(re.findall('[a-z]+', text.casefold()))


def stable(value):
    return hashlib.sha256(('real-revisit-stream-v1:' + value).encode()).hexdigest()


def add_trial(trials, cases, identifier, participant, fold, text, reference, source, times):
    matches = list(re.finditer('[a-z]+', text))
    targets = touch.word_labels(' '.join(m.group() for m in matches), prompt_key(reference))
    trial = dict(id=identifier, participant=participant, split=fold, observed=text,
                 reference=reference, source=source, prompt=prompt_key(reference), words=[])
    for i, (m, target) in enumerate(zip(matches, targets, strict=True)):
        row = dict(id=identifier + ':' + str(i), participant=participant, split=fold,
                   source=source, group=trial['prompt'], trial=identifier,
                   typed=m.group(), left=text[:m.start()], target=target,
                   start=m.start(), end=m.end(),
                   boundary_ms=times[min(m.end(), len(times) - 1)] if times else None)
        cases.append(row)
        trial['words'].append(row['id'])
    trials.append(trial)


def observed_with_times(record, keys):
    chars, times = [], []
    for chunk in record['IN']:
        for event in chunk.split('|'):
            frame = event.split(';', 1)[0].split(',')
            if len(frame) != 3:
                raise ValueError('Unexpected original space-condition event')
            x, y, timestamp = map(float, frame)
            key = touch.nearest(keys, x, y)
            if key == '<b>':
                if chars:
                    chars.pop()
                    times.pop()
            else:
                if key not in 'abcdefghijklmnopqrstuvwxyz' and key != '<sp>':
                    raise ValueError('Unsupported original touch key')
                chars.append(' ' if key == '<sp>' else key)
                times.append(timestamp)
    return ''.join(chars), times


def run(a):
    a.output.mkdir(parents=True, exist_ok=False)
    frozen = core.rows(a.suite / 'trials.jsonl')
    forbidden = {prompt_key(t['reference']) for t in frozen}
    trials, cases = [], []
    audit = collections.Counter()
    with zipfile.ZipFile(a.archive) as z:
        root = 'noisy_typing/phone/vt_exp2/'
        files = sorted(p for p in z.namelist() if p.startswith(root + 'dev/') and p.endswith('_space.log'))
        keys = touch.layout(z.read(root + 'keyboard_nexus4.txt').decode())
        by_person = {Path(p).stem.split('_')[0]: list(touch.records(z.read(p).decode())) for p in files}
        # Largest unlabelled stream count goes to fit; remaining participants get
        # calibration/selection by identity. No outcomes enter this assignment.
        people = sorted(by_person, key=lambda p: (-len(by_person[p]), p))
        if len(people) != 3:
            raise ValueError('Expected original three author development participants')
        folds = dict(zip(people, ['fit', 'calibration', 'selection']))
        prompts = sorted({prompt_key(record['REF']) for records in by_person.values() for record in records}, key=stable)
        prompt_folds = {prompt: ['fit', 'calibration', 'selection'][i % len(people)] for i, prompt in enumerate(prompts)}
        for person, records in by_person.items():
            for record in records:
                audit['velocitap_source_trials'] += 1
                prompt = prompt_key(record['REF'])
                if prompt in forbidden:
                    audit['velocitap_excluded_frozen_prompt_overlap'] += 1
                    continue
                if prompt_folds[prompt] != folds[person]:
                    audit['velocitap_excluded_participant_prompt_intersection'] += 1
                    continue
                text, times = observed_with_times(record, keys)
                add_trial(trials, cases, 'vt_dev:' + person + ':' + record['ID'],
                          'vt_dev:' + person, folds[person], text, record['REF'].strip().lower(),
                          'velocitap_original_space', times)
    google_manifest = json.loads((a.google / 'manifest.json').read_text())
    for name in ['touch_data.csv', 'prompt_data.csv', 'keyboard_data.json']:
        assert core.digest(a.google / name) == google_manifest['files'][name]['sha256']
    with (a.google / 'prompt_data.csv').open() as stream:
        prompts = [r for r in csv.DictReader(stream) if r['prompt_type'] == 'phrase']
    people = sorted({r['participant_id'] for r in prompts})
    people_by_fold = collections.Counter(partition(p) for p in people)
    schedule = [fold for fold in ['fit', 'calibration', 'selection', 'evaluation'] for _ in range(people_by_fold[fold])]
    unique = sorted({prompt_key(r['prompt']) for r in prompts}, key=stable)
    # Disjoint participants AND disjoint prompts: retain matching intersections.
    prompt_fold = {p: schedule[i % len(schedule)] for i, p in enumerate(unique)}
    observed = collections.defaultdict(list)
    layout = json.loads((a.google / 'keyboard_data.json').read_text())['keys_info']
    def nearest(x, y):
        def distance(c):
            k = layout[c]
            dx, dy = abs(x - k['key_center_x']), abs(y - k['key_center_y'])
            return max(0, dx-k['key_width']/2)**2 + max(0, dy-k['key_height']/2)**2, dx*dx+dy*dy, c
        k = min(layout, key=distance)
        return ' ' if k == 'SPACE' else '.' if k == '.' or k == 'PERIOD' else k.lower()
    with (a.google / 'touch_data.csv').open() as stream:
        for r in csv.DictReader(stream):
            audit['google_source_taps'] += 1
            if r['was_deleted'] != 'False':
                audit['google_deleted_taps_not_reconstructable'] += 1
                continue
            # No reference character, reference index or upstream LM scores read.
            key = tuple(r[k] for k in ['participant_id', 'task_id', 'trial_id'])
            observed[key].append((int(r['timestamp_ms']), nearest(float(r['first_frame_touch_x']), float(r['first_frame_touch_y']))))
    google_excluded_overlap = forbidden | {t['prompt'] for t in trials}
    for p in prompts:
        audit['google_phrase_source_trials'] += 1
        participant = p['participant_id']
        fold = partition(participant)
        prompt = prompt_key(p['prompt'])
        if prompt in google_excluded_overlap:
            audit['google_excluded_other_source_prompt_overlap'] += 1
            continue
        if prompt_fold[prompt] != fold:
            audit['google_excluded_participant_prompt_intersection'] += 1
            continue
        key = tuple(p[k] for k in ['participant_id', 'task_id', 'trial_id'])
        points = sorted(observed[key])
        text = ''.join(k for _, k in points)
        add_trial(trials, cases, 'google:' + ':'.join(key), 'google:' + participant, fold,
                  text, p['prompt'].lower(), 'google_retained_centroid_sequence', [t for t, _ in points])
    for folds_to_compare in [('fit','calibration'), ('fit','selection'), ('fit','evaluation'),
                              ('calibration','selection'), ('calibration','evaluation'), ('selection','evaluation')]:
        aa = [t for t in trials if t['split'] == folds_to_compare[0]]
        bb = [t for t in trials if t['split'] == folds_to_compare[1]]
        assert not ({t['participant'] for t in aa} & {t['participant'] for t in bb})
        assert not ({t['prompt'] for t in aa} & {t['prompt'] for t in bb})
    assert not ({t['prompt'] for t in trials if t['split'] != 'evaluation'} & forbidden)
    core.jsonl(a.output / 'cases.jsonl', cases)
    core.jsonl(a.output / 'trials.jsonl', trials)
    manifest = dict(schema='real-revisit-streams-v1', script_sha256=core.digest(__file__),
                    source_hashes=dict(velocitap_archive=core.digest(a.archive), google_manifest=core.digest(a.google / 'manifest.json')),
                    cases_sha256=core.digest(a.output / 'cases.jsonl'), trials_sha256=core.digest(a.output / 'trials.jsonl'),
                    counts=dict(audit), participants=dict(folds),
                    fold_counts={fold:dict(trials=sum(t['split']==fold for t in trials),
                                           words=sum(c['split']==fold for c in cases),
                                           typos=sum(c['split']==fold and c['target'] is not None and c['typed'] != c['target'] for c in cases),
                                           clean=sum(c['split']==fold and c['target']==c['typed'] for c in cases),
                                           ambiguous=sum(c['split']==fold and c['target'] is None for c in cases))
                                 for fold in ['fit','calibration','selection','evaluation']},
                    future='Observed subsequent words in the same recorded trial; never prompt continuation',
                    inference='Raw coordinates and observed input only; labels/reference/upstream LM are excluded',
                    split='Participant and normalized prompt disjoint; frozen touch evaluation prompt overlap removed from development',
                    limitations=['Copy typing, not spontaneous chat', 'VelociTap edits applied to reconstruct final observed stream; interactive response to our corrections not known',
                                 'Google retained aligned taps omit deleted/unaligned input; not a raw backspace/cursor replay',
                                 'Centroid hit test reconstructs observed keys, not unavailable original Gboard hit-target expansion',
                                 'Small development participant count; Google source has historical exposure'], production_changed=False)
    core.write_json(a.output / 'manifest.json', manifest)
    print(json.dumps(manifest['fold_counts'], indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--archive', type=Path, default=core.PROJECT / 'build/touch-eval-public/noisy-typing.zip')
    p.add_argument('--google', type=Path, default=Path('/Volumes/SSD/cantonese_ngram/raw/google-touch-v28'))
    p.add_argument('--suite', type=Path, default=core.PROJECT / 'build/autocorrect-eval-v4/frozen')
    p.add_argument('--output', type=Path, required=True)
    run(p.parse_args())
