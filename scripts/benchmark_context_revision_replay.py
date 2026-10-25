#!/usr/bin/env python3
"""Measure the selected replay's native work without the experiment's query cache."""
import argparse
import json
import statistics
from pathlib import Path

import autocorrect_eval_v2 as core
from replay_context_revision import Acceptance, Probe, measure, replay_trial


class MeasuredProbe(Probe):
    def __init__(self, *args):
        super().__init__(*args)
        self.boundaries = []

    def query(self, request):
        self.cache.clear()  # Cache belongs to experiment orchestration, not the IME.
        result = super().query(request)
        elapsed = result['elapsedMicros'] / 1000
        if 'typed' in request:
            self.boundaries.append(dict(spelling_ms=elapsed, extra_ms=0., checks=0))
        else:
            self.boundaries[-1]['extra_ms'] += elapsed
            self.boundaries[-1]['checks'] += 1
        return result


def summary(values):
    return dict(calls=len(values), median_ms=statistics.median(values) if values else None,
                p95_ms=core.percentile(values, .95) if values else None,
                max_ms=max(values) if values else None)


def run(args):
    args.output.mkdir(parents=True, exist_ok=False)
    source_report = json.loads((args.run / 'report.json').read_text())
    rule = source_report['selected_rule']
    if core.digest(args.probe) != source_report['binding']['probe_sha256'] or core.digest(args.policy) != source_report['binding']['policy_sha256']:
        raise ValueError('Timing candidate differs from the selected experiment')
    cases = {c['id']: c for c in core.rows(args.suite / 'cases.jsonl')}
    trials = core.rows(args.suite / 'trials.jsonl')
    report = dict(scope='Fresh native processes, all 480 recorded touch trials, no Python query cache; native work only, no key scheduling/cancellation or phone measurement',
                  selected_rule=rule, run_report_sha256=core.digest(args.run / 'report.json'),
                  script_sha256=core.digest(__file__), modes={})
    for name, chosen in [('baseline', None), ('candidate', rule)]:
        directory = args.output / name
        directory.mkdir()
        client = MeasuredProbe(args.probe, args.model, directory)
        acceptance = Acceptance(args.policy)
        all_cases, all_words, revisions = [], [], []
        try:
            for trial in trials:
                cc = [cases[k] for k in trial['words']]
                words, changes = replay_trial(client, acceptance, cc, chosen)
                all_cases.extend(cc)
                all_words.extend(words)
                revisions.extend(changes)
        finally:
            client.close()
        metrics = measure(all_cases, all_words)
        expected = source_report['observed_touch_closed_loop'][name]['panels']['real_touch_observed']
        for key in ['tp', 'fp', 'clean_changes', 'typos', 'clean', 'unlabelled']:
            if metrics[key] != expected[key]:
                raise ValueError('Fresh replay differs: ' + key)
        checked = [b for b in client.boundaries if b['checks']]
        report['modes'][name] = dict(metrics=metrics, word_boundaries=len(client.boundaries),
                                    boundaries_with_revision_work=len(checked),
                                    checked_fraction=len(checked) / len(client.boundaries),
                                    revision_checks=sum(b['checks'] for b in checked),
                                    applied_revisions=len(revisions),
                                    spelling=summary([b['spelling_ms'] for b in client.boundaries]),
                                    extra_on_checked_boundaries=summary([b['extra_ms'] for b in checked]),
                                    mean_extra_ms_per_all_boundaries=sum(b['extra_ms'] for b in checked) / len(client.boundaries),
                                    checked_boundaries_exceeding_40ms=sum(b['extra_ms'] > 40 for b in checked))
        core.jsonl(directory / 'boundary-costs.jsonl', client.boundaries)
    core.write_json(args.output / 'report.json', report)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--suite', type=Path, default=core.PROJECT / 'build/autocorrect-eval-v4/frozen')
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--policy', type=Path, required=True)
    parser.add_argument('--model', type=Path, default=core.PROJECT / 'app/src/main/assets/language_model/model.cng')
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args())
