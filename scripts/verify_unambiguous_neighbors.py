#!/usr/bin/env python3
"""Verify compiled neighbor fallback, frozen parity and a new word cohort."""
import argparse
import json
from pathlib import Path

from audit_candidate_pipeline import digest, probe
from benchmark_single_edit import corruptions
from iterate_unambiguous_neighbors import fallback, load_real, panel
from iterate_english_policy import split


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--after', type=Path, required=True)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    project = Path(__file__).resolve().parents[1]
    model = project/'app/src/main/assets/language_model/model.cng'
    lexicon_file = project.parent/'QwenASR/crates/cantonese-ngram/resources/english_neighbor_lexicon.txt'
    lexicon = set(lexicon_file.read_text().splitlines())
    frozen_file = project/'config/english_iteration/2026-10-21/neighbor-repair/first-selection.json'
    frozen = json.loads(frozen_file.read_text())
    assert digest(lexicon_file) == frozen['binding']['lexicon']
    assert frozen['selection_qualified'] and frozen['require_first']
    assert digest(args.source) == frozen['binding']['source']
    real, real_outputs, real_file, cases_file, refs_file = load_real(project)
    synthetic_file = project/'build/single-edit-final-2026-10-21/predictions.jsonl'
    synthetic = [json.loads(line) for line in synthetic_file.open()]
    requests = [dict(left=c['left'], typed=c['typed'], spelling_limit=4096) for c in real+synthetic]
    print(f'Checking {len(requests)} frozen native decisions', flush=True)
    actual = probe(args.after, model, requests)
    expected = [fallback(c, o, lexicon, require_first=True) for c, o in zip(real+synthetic, real_outputs+synthetic)]
    assert [o['correction'] for o in actual] == expected, 'Compiled fallback differs from frozen rule'
    for old, new in zip(real_outputs+synthetic, actual):
        assert [r['word'] for r in old['candidates']] == [r['word'] for r in new['candidates']], 'Ranking changed'
    exposed_file = project/'config/english_iteration/2026-10-21/single-edit/words.tsv'
    exposed = {line.split()[0] for line in exposed_file.open()}
    words = sorted(((word, int(count)) for word, count in map(str.split, args.source.open())
                    if word.isascii() and word.isalpha() and 4<=len(word)<=20 and word not in exposed),
                   key=lambda row: (-row[1], row[0]))[:2000]
    fresh = [dict(id=f'fresh:{word}:{operation}', left='', typed=typed, target=word)
             for word, count in words for operation, typed in
             (('neighbor', corruptions(word)['qwerty_substitution']), ('clean', word))]
    fresh_requests = [dict(left='', typed=c['typed'], spelling_limit=4096) for c in fresh]
    print(f'Checking previously uninspected {len(words)} word types / {len(fresh)} cases', flush=True)
    before, after = [probe(binary, model, fresh_requests) for binary in (args.before, args.after)]
    assert [o['correction'] for o in after] == [fallback(c, o, lexicon, require_first=True) for c, o in zip(fresh, before)]
    assert all([r['word'] for r in a['candidates']] == [r['word'] for r in b['candidates']] for a, b in zip(before, after))
    report = dict(native_parity_cases=len(actual), decision_mismatches=0, candidate_order_changes=0,
                  frozen_selection_sha256=digest(frozen_file),
                  real_evaluation=panel([c for c in real if split(c['group'])=='evaluation'],
                                        [o for c, o in zip(real, real_outputs) if split(c['group'])=='evaluation'], lexicon, True),
                  fresh_words=len(words), fresh=panel(fresh, before, lexicon, True),
                  hashes={str(f): digest(f) for f in (Path(__file__), args.before, args.after, model, lexicon_file,
                          args.source, real_file, cases_file, refs_file, synthetic_file, exposed_file)})
    # Keep source text/predictions in ignored build output; commit aggregate report.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    predictions = args.output.with_suffix('.predictions.json')
    predictions.write_text(json.dumps(dict(frozen_outputs=actual, fresh_cases=fresh, before=before, after=after)))
    report['predictions_sha256'] = digest(predictions)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(dict(native_parity_cases=len(actual), fresh_primary=report['fresh']['primary_unambiguous'],
                          fresh_lengths=report['fresh']['primary_by_length'], fresh_controls=report['fresh']['preservation']), indent=2))


if __name__ == '__main__':
    main()
