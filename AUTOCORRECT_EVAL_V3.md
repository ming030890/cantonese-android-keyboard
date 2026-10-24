# Historical authored hard evaluation (v3)

Historical v3. The current [single optimization objective](AUTOCORRECT_OBJECTIVE.md)
uses the same 46 errors with 46 domain-balanced clean controls. Its headline
metric averages F₀.₅ equally across three evaluations. The v3 artifacts below
remain intact; references to 204 active controls describe that prior version.

At the user's request, **162 ordinary single-edit errors have been removed**
from the active authored evaluation. The current authored panel contains
**250 cases: 46 errors and all 204 original clean controls**. The older
[412-case v2 coverage suite](AUTOCORRECT_EVAL_V2.md) remains archived with its
original cohort, sources and scores.

Current native result on the reduced authored panel:

| Metric | Result |
|---|---:|
| F₀.₅ | **0.59701** |
| Correct fixes | **16 / 46 errors** |
| Wrong fixes | **6**, including 2 clean changes |
| Recall | **34.78%** |
| Precision | **72.73%** |
| Clean changes | **2 / 204** |
| Scenario-bootstrap F₀.₅ 95% interval | **[0.42105, 0.73398]** |

This is a cohort change, not a model regression or a score directly comparable
with v2's 0.89953. No training, threshold or scorer changed. The new version
was defined after v2 results were exposed and is an exposed development stress
panel, not independent confirmation or a representative usage sample.

## Membership

The [builder](scripts/build_autocorrect_hard_eval.py) retains an authored error
when its OSA edit distance is at least two, or its domain is apostrophes,
missing word boundaries or author-intended real-word confusion. Other
single-edit errors are removed regardless of their prediction. This retains:

- 22 multi-edit errors from the former large-distance domain.
- 12 apostrophe errors, including three with multiple edits.
- 4 missing-boundary errors.
- 8 real-word confusions.

The union contains 46 errors; categories must not be double counted. The
distance-two-or-more slice still has 25 errors. Author-intended real-word
labels can be ambiguous from left context; retain that limitation rather than
requiring the model to force changes to valid words. Keep their clean controls.

All 204 clean controls remain, including names, slang, identifiers, dialect
spellings, URLs, email, mixed language and legitimate short words. Successful
preservation does not add TP to correction F₀.₅; unwanted changes add FP.
Removing ordinary single-edit errors also reduces authored short-word coverage:
only four errors have inputs of three characters or fewer. Do not claim the
small reduced set establishes comprehensive short-word quality. Retain the
natural short-word slices and historical v2 coverage separately.

All **5,169 natural/source-copy observations**, original evaluation identities,
and whole-trial touch labels remain identical. The active expanded run totals
**5,419 observations**. The complete historical mobile score remains **0.86657**,
and real-touch replay remains **0.89134**. Do not pool these with the authored
score or remove natural cases to make a representative panel artificially hard.

## Required verification

For future autocorrection changes, cover the active authored hard panel alongside
the three unchanged natural/source-copy panels. Use a newly built native probe,
freeze the candidate before inference, and report same-case baseline/candidate
comparisons. Keep all existing requirements for F₀.₅, clean harm, short-word and
multi-edit slices, whole-trial CER, uncertainty, overlap and latency. Never fit
or select on these exposed labels. Shared scoring and limitations remain in
`AUTOCORRECT_EVAL_V2.md` and `TYPING_EVALUATION.md`.

Retained artifacts:

- [Manifest and selection rule](config/autocorrect_eval_v3/manifest.json).
- [Active authored cases](config/autocorrect_eval_v3/authored-hard.jsonl).
- [Removed authored cases and reasons](config/autocorrect_eval_v3/removed-authored.jsonl).
- [Current results](config/autocorrect_eval_v3/current-report.json).
- [Verification](config/autocorrect_eval_v3/verification.json).

Reproduce into new directories; existing frozen files must not be overwritten:

```sh
python3 -m unittest discover -s scripts -p 'test_autocorrect*.py'
cargo build --offline --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml --release --features android --bin ngram_probe
python3 scripts/build_autocorrect_hard_eval.py \
  --parent build/autocorrect-eval-v2/frozen-final \
  --training ../gboard-probe/improve-v3/cases.jsonl \
  --output build/autocorrect-eval-v3/reproduction-suite
python3 scripts/autocorrect_eval_v2.py score \
  --suite build/autocorrect-eval-v3/reproduction-suite \
  --probe ../QwenASR/crates/cantonese-ngram/target/release/ngram_probe \
  --output build/autocorrect-eval-v3/reproduction-results
```

The initial frozen suite and raw native outputs are under ignored
`build/autocorrect-eval-v3/frozen/` and `current/`. Thirteen evaluation tests
pass. Every natural panel's metrics, the whole-trial touch replay, and all
authored clean-control IDs match v2 exactly. No Android or model changes.
