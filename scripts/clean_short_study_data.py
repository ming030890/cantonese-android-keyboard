#!/usr/bin/env python3
"""Quarantine frozen evaluation inputs and audit variants from every study split."""
import argparse
import collections
import json
from pathlib import Path

import numpy as np

import autocorrect_eval_v2 as core
from audit_noisy_context import pairs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--suite', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    cases = core.rows(args.data / 'cases.jsonl')
    data = np.load(args.data / 'features.npz')
    decoded = json.loads((args.data / 'decisions.json').read_text())
    frozen = core.rows(args.suite / 'cases.jsonl')
    audit = pairs(frozen)
    forbidden = {(c['left'], c['typed'].casefold()) for c in frozen + audit}
    keep = [i for i, c in enumerate(cases) if (c['left'], c['typed'].casefold()) not in forbidden]
    kept_indices = set(keep)
    blocked = [i for i in range(len(cases)) if i not in kept_indices]
    clean = [cases[i] for i in keep]
    assert not {(c['left'], c['typed'].casefold()) for c in clean} & forbidden
    assert all(c['typed'].casefold() not in {'tue', 'ir'} and c['target'].casefold() not in {'tue', 'ir'} for c in clean)
    old_count = int(data['old_count'])
    args.output.mkdir(parents=True, exist_ok=False)
    core.jsonl(args.output / 'cases.jsonl', clean)
    core.jsonl(args.output / 'quarantined-cases.jsonl', [cases[i] for i in blocked])
    core.jsonl(args.output / 'new-cases.jsonl', [cases[i] for i in keep if i >= old_count])
    np.savez(args.output / 'features.npz', raw=data['raw'][keep], labels=data['labels'][keep],
             eligible=data['eligible'][keep], old_count=sum(i < old_count for i in keep))
    core.write_json(args.output / 'decisions.json', {k: [v[i] for i in keep] for k, v in decoded.items()})
    (args.output / 'parent-policy.json').write_bytes((args.data / 'parent-policy.json').read_bytes())
    protocol = json.loads((args.data / 'protocol.json').read_text())
    protocol.update(schema='short-feature-study-clean-v2', evaluation_inputs_excluded=True,
                    cases=len(clean), source_protocol_sha256=core.digest(args.data / 'protocol.json'),
                    source_features_sha256=core.digest(args.data / 'features.npz'),
                    feature_sha256=core.digest(args.output / 'features.npz'),
                    exclusion='Input (exact left context, case-folded typed text), independent of target label',
                    frozen_cases_sha256=core.digest(args.suite / 'cases.jsonl'),
                    quarantined_rows=len(blocked),
                    quarantined_by_split=dict(collections.Counter(cases[i]['fit_split'] for i in blocked)),
                    effective_overlap=0, baseline_prior_exposure=True,
                    script_sha256=core.digest(__file__))
    core.write_json(args.output / 'protocol.json', protocol)
    print(json.dumps({k:protocol[k] for k in ['cases','quarantined_rows','quarantined_by_split','effective_overlap']}))


if __name__ == '__main__':
    main()
