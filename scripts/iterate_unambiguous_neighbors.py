#!/usr/bin/env python3
"""Select/freeze a dictionary-unique neighbor fallback, then reveal evaluation.

No learned cutoff. Uniqueness is checked against every single-edit dictionary
repair, before consulting reference labels. Existing corrections remain intact.
"""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import string

from align_mobile_targets import reference_target
from analyze_single_edit_root_cause import metrics
from audit_candidate_pipeline import digest
from benchmark_single_edit import POSITIONS
from iterate_english_policy import split

ALPHABET = string.ascii_lowercase + "'"


def neighbor_replacement(typed, word):
    if len(typed) != len(word):
        return False
    different = [(a, b) for a, b in zip(typed, word) if a != b]
    return (len(different) == 1 and all(c in POSITIONS for c in different[0])
            and all(abs(a-b) <= 1 for a, b in zip(*(POSITIONS[c] for c in different[0]))))


def repairs(typed, lexicon):
    """All distance-one replacements, insertions, deletions and adjacent swaps."""
    found = set()
    for i in range(len(typed)):
        for ch in ALPHABET:
            if ch != typed[i]:
                word = typed[:i]+ch+typed[i+1:]
                if word in lexicon:
                    found.add(word)
        word = typed[:i]+typed[i+1:]
        if word in lexicon:
            found.add(word)
        if i+1 < len(typed) and typed[i] != typed[i+1]:
            word = typed[:i]+typed[i+1]+typed[i]+typed[i+2:]
            if word in lexicon:
                found.add(word)
    for i in range(len(typed)+1):
        for ch in ALPHABET:
            word = typed[:i]+ch+typed[i:]
            if word in lexicon:
                found.add(word)
    return found


def unique_neighbor(typed, lexicon, minimum_length=4):
    if (not minimum_length <= len(typed) <= 32 or not typed.isascii()
            or not typed.isalpha() or not typed.islower() or typed in lexicon):
        return None
    found = repairs(typed, lexicon)
    if len(found) != 1:
        return None
    word = next(iter(found))
    return word if neighbor_replacement(typed, word) else None


def fallback(case, output, lexicon, require_first=False):
    if output['correction'] or output['known']:
        return output['correction']
    word = unique_neighbor(case['typed'].lower(), lexicon)
    if require_first:
        return (output['candidates'][0]['word'] if word and output['candidates']
                and output['candidates'][0]['word'].lower() == word else None)
    # Only use a generated model candidate, so unavailable targets remain misses.
    return next((r['word'] for r in output['candidates'] if r['word'].lower() == word), None)


def panel(cases, outputs, lexicon, require_first=False):
    baseline = [o['correction'] for o in outputs]
    fixes = [fallback(c, o, lexicon, require_first) for c, o in zip(cases, outputs)]
    primary = [i for i, c in enumerate(cases) if c['typed'].lower() != c['target']
               and neighbor_replacement(c['typed'].lower(), c['target'])
               and unique_neighbor(c['typed'].lower(), lexicon) == c['target']]
    controls = [i for i, c in enumerate(cases) if c['typed'].lower() == c['target']]
    def compare(indices):
        return {name: metrics([cases[i] for i in indices], [values[i] for i in indices])
                for name, values in (('before', baseline), ('after', fixes))}
    result = dict(all_regression=compare(list(range(len(cases)))),
                  primary_unambiguous=compare(primary), preservation=compare(controls))
    result['primary_by_length'] = {
        f'{lo}-{hi}': compare([i for i in primary if lo <= len(cases[i]['target']) <= hi])
        for lo, hi in ((4, 5), (6, 7), (8, 32))}
    result['changes'] = [dict(id=c.get('id'), typed=c['typed'], target=c['target'],
                              before=before, after=after)
                         for c, before, after in zip(cases, baseline, fixes) if before != after]
    return result


def load_real(project):
    iteration = project/'build/english-iteration-2026-10-20'
    cases_file = iteration/'cases.jsonl'
    refs_file = Path('/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/sentences.csv')
    refs = {r['SENTENCE_ID']: r['SENTENCE'] for r in csv.DictReader(refs_file.open())}
    cases = []
    for c in map(json.loads, cases_file.open()):
        if c['category'].startswith('prefix_'):
            continue
        target = c['target'] if c['category']=='source_preservation' else reference_target(c, refs[c['group']])
        if target is not None:
            cases.append(dict(c, target=target))
    cache_file = project/'build/apostrophe-native-cache.json'
    cache = json.loads(cache_file.read_text())
    assert cache['binding']['cases'] == digest(cases_file)
    assert len(cases) == len(cache['outputs']) == 9728
    return cases, cache['outputs'], cache_file, cases_file, refs_file


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--frozen-selection', type=Path)
    p.add_argument('--require-first', action='store_true', help='Isolate acceptance repair; retain model ranking')
    args = p.parse_args()
    project = Path(__file__).resolve().parents[1]
    lexicon = {line.split()[0] for line in args.source.open()
               if len(line.split()[0]) <= 32}
    lexicon_bytes = (''.join(word+'\n' for word in sorted(lexicon))).encode()
    cases, outputs, cache_file, cases_file, refs_file = load_real(project)
    native = project.parent/'QwenASR/crates/cantonese-ngram/src'
    for key, path in (('policy', native/'english_acceptance.json'),
                      ('apostrophe_policy', native/'english_apostrophe_acceptance.json'),
                      ('model', project/'app/src/main/assets/language_model/model.cng')):
        assert json.loads(cache_file.read_text())['binding'][key] == digest(path)
    binding = dict(lexicon=hashlib.sha256(lexicon_bytes).hexdigest(), source=digest(args.source),
                   cache=digest(cache_file), cases=digest(cases_file), references=digest(refs_file), script=digest(Path(__file__)))
    selected = {}
    for partition in ('train', 'selection'):
        indices = [i for i, c in enumerate(cases) if split(c['group']) == partition]
        selected[partition] = panel([cases[i] for i in indices], [outputs[i] for i in indices], lexicon, args.require_first)
    qualified = (selected['selection']['all_regression']['after']['f05'] >
                 selected['selection']['all_regression']['before']['f05'])
    report = dict(rule='Only on abstention and unknown input, ASCII letters length 4–32: '
                  'exactly one dictionary repair across all single replacements, insertions, '
                  'deletions and swaps, and it is a neighboring replacement present in model suggestions. '
                  'Existing corrections and known inputs are preserved.',
                  ambiguity='Full source dictionary, independent of LM ranking and reference labels. '
                  'Lexical uniqueness is not a guarantee of free-typing intent, names or multi-edit errors.',
                  require_first=args.require_first, binding=binding, lexicon_words=len(lexicon), selection_qualified=qualified, partitions=selected)
    if args.frozen_selection:
        frozen = json.loads(args.frozen_selection.read_text())
        assert {k: report[k] for k in report} == frozen, 'Selection/binding changed after freezing'
        assert qualified, 'No qualified candidate'
        indices = [i for i, c in enumerate(cases) if split(c['group']) == 'evaluation']
        report['evaluation'] = panel([cases[i] for i in indices], [outputs[i] for i in indices], lexicon, args.require_first)
        synthetic_file = project/'build/single-edit-final-2026-10-21/predictions.jsonl'
        rows = [json.loads(line) for line in synthetic_file.open()]
        report['synthetic_neighbors'] = panel([r for r in rows if r['operation'] in ('qwerty_substitution', 'clean')],
                                              [r for r in rows if r['operation'] in ('qwerty_substitution', 'clean')], lexicon, args.require_first)
        report['frozen_selection_sha256'] = digest(args.frozen_selection)
        report['synthetic_source_sha256'] = digest(synthetic_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    # Prepared resource is not installed by this experiment.
    args.output.with_suffix('.lexicon.txt').write_bytes(lexicon_bytes)
    print(json.dumps({k: v for k, v in report.items() if k not in ('partitions',)}, indent=2)[:1000])
    for partition, result in selected.items():
        print(partition, json.dumps(result['all_regression']))


if __name__ == '__main__':
    main()
