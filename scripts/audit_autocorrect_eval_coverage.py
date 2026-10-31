#!/usr/bin/env python3
"""Report what the exposed development objective measures and leaves untested."""
import argparse
import collections
import json
from pathlib import Path
import re

import autocorrect_eval_v2 as core

ROOT=Path(__file__).resolve().parents[1]
HAN=re.compile(r'[\u3400-\u9fff]')


def coverage(cases):
    labelled=[c for c in cases if c['target'] is not None]
    return dict(cases=len(cases),labelled=len(labelled),
        typos=sum(core.norm(c['typed'])!=core.norm(c['target']) for c in labelled),
        clean=sum(core.norm(c['typed'])==core.norm(c['target']) for c in labelled),
        han_in_context=sum(bool(HAN.search(c['left'])) for c in cases),
        han_in_typed_or_target=sum(bool(HAN.search(c['typed']+(c['target'] or ''))) for c in cases),
        non_ascii_typed=sum(not c['typed'].isascii() for c in cases),
        nonletter_typed=sum(not c['typed'].isalpha() for c in cases),
        uppercase_in_typed=sum(any(ch.isupper() for ch in c['typed']) for c in cases),
        domains=dict(collections.Counter(c.get('domain','unspecified') for c in cases)))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    suite=ROOT/'build/autocorrect-eval-v4/frozen'
    manifest=json.loads((suite/'manifest.json').read_text())
    assert core.digest(suite/'cases.jsonl')==manifest['cases_sha256']
    cases=core.rows(suite/'cases.jsonl')
    accepted=core.rows(ROOT/'build/error-analysis-20261030/frozen-outputs.jsonl')
    loop=json.loads((ROOT/'build/closed-loop-error-analysis-20261030-v2/report.json').read_text())
    recent=json.loads((ROOT/'config/english_iteration/2026-10-29/short-nn-v2/clean-data-protocol.json').read_text())
    m=core.metrics(cases,accepted)
    result=dict(schema='autocorrect-eval-coverage-audit-v1',
        binding=dict(cases_sha256=core.digest(suite/'cases.jsonl'),script_sha256=core.digest(__file__),
            accepted_outputs_sha256=core.digest(ROOT/'build/error-analysis-20261030/frozen-outputs.jsonl')),
        pooled_descriptive_metrics=m,
        official_development_score=json.loads((ROOT/'config/english_iteration/2026-10-29/short-nn-v2/accepted-score.json').read_text())['score'],
        panels={panel:coverage([c for c in cases if c['panel']==panel]) for panel in sorted({c['panel'] for c in cases})},
        word_scoring=dict(case_sensitive=False,capitalization_quality_measured=False,
            observed_key_geometry_used=False,actual_layout_used=False,wall_clock_deadlines_measured=False),
        production_session_coverage=dict(natural_spontaneous_chat_sessions=0,natural_bilingual_typing_streams=0,
            android_inputconnection_replays_in_objective=0,
            recorded_copy_typing_trials=loop['cohorts']['frozen_touch']['trials']),
        touch_closed_loop=loop['cohorts']['frozen_touch'],
        exposure=dict(status='Exposed development, not independent confirmation',
            latest_update_excludes_frozen_exact_inputs=recent['evaluation_inputs_excluded'],
            latest_update_quarantined_rows=recent['quarantined_rows'],
            latest_update_effective_exact_input_overlap=recent['effective_overlap'],
            inherited_checkpoint_prior_exposure=recent['baseline_prior_exposure'],
            limitation='Latest-fit exclusion does not erase inherited weights or repeated model-selection exposure; complete LM-history disjointness is unproven'),
        blockers=[
            'No natural Cantonese/English chat typing streams with confirmed intent.',
            'No fresh participant/conversation/source-separated confirmation cohort.',
            'Word F0.5 ignores case and does not measure actual event-boundary/editor behavior.',
            'Equal benchmark weights and authored challenge prevalence are not measured usage weights.',
            'Word outcomes cannot establish recovery effort, cursor/undo success or phone deadline losses.',
        ])
    core.write_json(args.output/'coverage-audit.json',result)
    print(json.dumps(result,indent=2,ensure_ascii=False))


if __name__=='__main__':main()
