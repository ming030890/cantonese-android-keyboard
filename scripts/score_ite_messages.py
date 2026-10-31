#!/usr/bin/env python3
"""Score frozen ITE attempts using the text captured at submit time.

Error-bearing message recovery and clean preservation are separate. The
severity-balanced sample is not a deployment-prevalence estimate. No labels
are supplied to the keyboard; this script only joins completed replays.
"""
import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path

from build_ite_message_eval import canonical, distance, words


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def normalized(text):
    return unicodedata.normalize('NFC', canonical(text)).strip()


def summarize(metadata, sessions, report):
    if len({r['id'] for r in sessions}) != len(sessions):
        raise ValueError('Duplicate session IDs')
    if len({r['id'] for r in metadata}) != len(metadata):
        raise ValueError('Duplicate source attempts')
    results = report['results']
    by_id = {r['id']: r for r in results}
    if len(by_id) != len(results) or set(by_id) != {r['id'] for r in sessions}:
        raise ValueError('Replay membership differs')
    sources = {r['id']: r for r in metadata}
    if {s['group'] for s in sessions} != set(sources):
        raise ValueError('Source membership differs')
    if len({s['layout'] for s in sessions}) != 1:
        raise ValueError('Report one layout at a time; variants are correlated')
    scored = []
    for session in sessions:
        result = by_id[session['id']]
        source = sources[session['group']]
        if session['expected_text'] != source['reference']:
            raise ValueError('Reference differs')
        pieces = []
        if session.get('initial_text', ''):
            raise ValueError('Copy replay must start empty')
        events = session['events']
        if not events or events[-1]['kind'] != 'submit':
            raise ValueError('Copy replay must end with submit')
        for event in events[:-1]:
            if event['kind'] == 'type':
                pieces.append(event['text'])
            elif event['kind'] in ('space', 'enter'):
                pieces.append(' ' if event['kind'] == 'space' else '\n')
            else:
                raise ValueError('Unexpected source-copy event')
        if ''.join(pieces) != source['observed']:
            raise ValueError('Replay input differs from source observation')
        if (result.get('submit_actions') != 1 or
                len(result.get('submitted_texts', [])) != 1 or result['composing']):
            raise ValueError('Invalid submit replay: ' + session['id'])
        output = result['submitted_texts'][0]
        observed, target = source['observed'], source['reference']
        before = distance(words(observed), words(target))
        after = distance(words(output), words(target))
        # Model-independent scope labels, not claims of uncorrectability.
        # Unequal word count also includes missing/extra spaces.
        numbers_differ = re.findall(r'\d+', observed) != re.findall(r'\d+', target)
        count_differs = len(words(observed)) != len(words(target))
        scored.append(dict(id=session['id'], group=session['group'],
            participant=source['participant'], prompt=source['prompt'],
            stratum=source['stratum'], platform=source['platform'],
            prompt_source=source['prompt_source'], observed=observed,
            reference=target, submitted=output, word_errors_before=before,
            word_errors_after=after, lexical_success=after == 0,
            exact_success=normalized(output) == normalized(target),
            lexical_preserved=words(output) == words(observed),
            text_preserved=normalized(output) == normalized(observed),
            numeric_change_in_source=numbers_differ,
            source_word_count_differs=count_differs,
            word_substitution_scope=not numbers_differ and not count_differs))

    def metrics(rows):
        errors = [r for r in rows if r['word_errors_before']]
        clean = [r for r in rows if r['stratum'] == 'clean']
        lexical_controls = [r for r in rows if not r['word_errors_before']]
        before = sum(r['word_errors_before'] for r in errors)
        after = sum(r['word_errors_after'] for r in errors)
        fixed = sum(r['lexical_success'] for r in errors)
        return dict(attempts=len(rows), participants=len({r['participant'] for r in rows}),
            prompts=len({r['prompt'] for r in rows}), error_attempts=len(errors),
            complete_lexical_recovery=fixed,
            complete_lexical_recovery_fraction=fixed / len(errors) if errors else None,
            exact_error_recovery=sum(r['exact_success'] for r in errors),
            word_errors_before=before, word_errors_after=after,
            word_error_reduction_fraction=(before-after)/before if before else None,
            error_attempts_worsened=sum(r['word_errors_after'] > r['word_errors_before'] for r in errors),
            clean_attempts=len(clean), clean_lexical_damage=sum(not r['lexical_preserved'] for r in clean),
            clean_text_damage=sum(not r['text_preserved'] for r in clean),
            lexical_control_attempts=len(lexical_controls),
            lexical_controls_damaged=sum(not r['lexical_preserved'] for r in lexical_controls),
            all_exact_success=sum(r['exact_success'] for r in rows),
            source_numeric_change_attempts=sum(r['numeric_change_in_source'] for r in rows),
            source_word_count_change_attempts=sum(r['source_word_count_differs'] for r in rows))

    output = dict(overall=metrics(scored),
        word_substitution_scope=metrics([r for r in scored if r['word_substitution_scope']]))
    for field in ('stratum', 'platform', 'prompt_source'):
        output[field] = {v: metrics([r for r in scored if r[field] == v])
                         for v in sorted({r[field] for r in scored})}
    output['attempts'] = scored
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--sessions', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    load = lambda p: [json.loads(line) for line in p.read_text().splitlines() if line]
    report = json.loads(args.report.read_text())
    if report['sessions_sha256'] != digest(args.sessions):
        raise ValueError('Session file changed')
    result = summarize(load(args.metadata), load(args.sessions), report)
    result['provenance'] = dict(metadata_sha256=digest(args.metadata),
        sessions_sha256=digest(args.sessions), report_sha256=digest(args.report),
        scorer_sha256=digest(__file__), native_library_sha256=report['native_library_sha256'],
        model_sha256=report['model_sha256'], scope=report['scope'])
    result['limits'] = [
        'Balanced unassisted mobile copy attempts; not spontaneous chat or deployment prevalence.',
        'Complete lexical recovery ignores case/punctuation; exact recovery reported separately.',
        'Numeric and word-count changes remain in all-attempt results and are flagged separately.',
        'Copy-task divergences may be valid words or paraphrases; recovering the prompt is not always a safe automatic edit.',
        'Word-substitution scope is an input/reference filter, not a vocabulary/model-success filter.',
        'Layouts share attempts; do not pool them as independent samples.',
        'Confirmation remains unscored; development is exposed after this baseline.',
        'No physical-phone timing or Cantonese/English chat accuracy established.']
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if k in ('overall', 'stratum', 'word_substitution_scope')}, indent=2))


if __name__ == '__main__':
    main()
