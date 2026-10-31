# Product autocorrect evaluation

## Current evaluation policy

As agreed on 2026-10-31, **whole-message ITE plus mixed Cantonese/English script
are the primary evaluation going forward**. Follow
[AUTOCORRECT_OBJECTIVE.md](AUTOCORRECT_OBJECTIVE.md) for acceptance gates.
Report recovery and clean preservation separately for each panel and layout;
do not pool English copy typing with authored mixed-script cases into a claimed
population score. The old v4 word-level aggregate remains a regression check.

The initial mixed-script panel is the 9 `mixed_cantonese_english` scenario groups
in [frozen product sessions](config/product_eval_v1/sessions.jsonl), each run on
English, Quick3 and Quick5. The current accepted engine recovers **1/4** correction
scenarios and preserves **5/5** clean scenarios: **6/9** exact successes per
layout, verified during the ITE iteration's complete keyboard gate. These are
9 authored groups, not 27 independent cases or fresh confirmation. Expand the
mixed panel with versioned, model-independent cases before evaluating outputs;
real Chinese selections and spontaneous bilingual typing remain coverage gaps.

The [whole-message ITE evaluation](ITE_MESSAGE_EVAL_20261031.md) now adds
838 source-recorded mobile attempts, with disjoint participant and prompt
folds. Its development baseline measures complete message recovery and clean
preservation through the production controller; the confirmation fold is closed.
This extends our earlier ITE AC-event subset, rather than introducing an unused
source. It remains English copy typing, not spontaneous bilingual chat.

The [2026-10-31 KEEP/rescue study](KEEP_RESCUE_EXPERIMENT_20261031.md) added a
reviewed public-chat cohort from 45 conversations excluded from TRAIN: 2,489
preservation controls, 29 natural spelling errors and 1,475 separately reported
synthetic typos. Its stronger natural-error guard rejected a candidate despite a
higher frozen aggregate. Both folds are now exposed. The small natural-error
panel has manually inferred intent, not author-confirmed corrections or tap traces.

## What the legacy word-level score establishes

The subsequent [evaluation/experiment iteration](AUTOCORRECT_ITERATION_20261030.md)
adds 2,825 source-disjoint supplemental cases and 38 configurations. Its best
scored candidate failed an existing ambiguity regression, so all experimental
native changes were withdrawn and the accepted engine restored.

The accepted **0.893921** is an exposed, precision-weighted development F₀.₅,
not 89.4% typo recall, message success, or real-user quality. The frozen 5,261
cases contain 1,315 labelled typos, 3,900 clean controls and 46 ambiguous labels.
The accepted model fixes **920/1,315 typos (70.0%)** in pooled descriptive counts.

The [coverage audit](config/product_eval_v1/coverage-audit.json) finds:

- Only **three** cases have Han in their preceding context. All three are
  authored; the natural panels have none. No target/input contains Han.
- Natural observations are English email/copy typing. The objective has **zero
  natural bilingual typing streams or spontaneous chat sessions**.
- Word scoring is case-insensitive. It cannot detect capitalization regressions.
- Native word calls do not exercise the actual keyboard layout, editor events,
  cursor/undo behavior, cancellation or phone scheduling deadlines.
- Equal benchmark weights and authored-error prevalence are optimization
  conventions, not measured usage proportions.
- The latest acceptance update quarantines exact frozen inputs (1,117 rows),
  but inherited weights have prior exposure and the benchmark has been reused
  for selection. This is not an independently held-out confirmation set.

Keep the legacy cases and weights unchanged as a regression tool. Promoting the
whole-message and mixed-script panels does not retroactively make any exposed
panel independent.

## Recorded-stream evidence

The historical pre-channel replay feeds its actual committed corrections forward on all
480 recorded touch sentences (3,127 observed words), without inventing future
text or injecting earlier errors:

| Measurement | Static observed context | Own correction feedback |
| --- | ---: | ---: |
| Correct fixes / labelled typos | 364/525 | 390/525 |
| Wrong corrections | 14 | 11 |
| Clean words damaged | 1 | 0 |
| Typo recall | 69.3% | 74.3% |

Only **337/480 messages (70.2%)** exactly match the reference after correction.
Of 135 remaining misses, 107 occur with all earlier words already correct.
An oracle repairing all labelled earlier errors recovers four current misses
and adds one wrong correction elsewhere. These oracle probes are separate from
the replay editor; they never inject intended words into actual replay state.

This lowers the priority of synthetic prior-noise sensitivity relative to
immediate missed corrections. It does not prove that robust handling of valid
rare words or mixed context is unnecessary.

The subsequently accepted [typing-channel model](ENGLISH_NOISY_CHANNEL_20261031.md)
reaches **394/525** corrected typos, **10** wrong corrections, no clean-word
damage and **340/480** exact messages on this same exposed replay. The table
above records the earlier experiment and is not the current baseline.

The secondary 695-trial development replay preserves all 3,761 observations.
The source reused prompt IDs for repeated attempts: 34 IDs represented 69
attempts. The new replay identifies each attempt separately and checks each
observed word span. The first secondary replay was invalidated; use
`build/closed-loop-error-analysis-20261030-v2/`.

## Product-controller diagnostics now available

[72 frozen sessions](config/product_eval_v1/sessions.jsonl) represent **24
authored scenarios**, each replayed in English, Quick3 and Quick5. They cover
informal spellings, names, identifiers, Cantonese/English mixing, multiword
typing, real-word confusions, missing spaces, capitalization and undo/literal
override. Labels and operations were frozen before inference.

`ProductAutocorrectEvalTest` drives the production controller and native JNI
through the existing fake editor/OS and virtual scheduler. Expected text never
enters inference. It records exact editor text, composition and candidate rows
after each event. JUnit success means replay completed; **it does not mean the
model satisfied the labelled intents**. Inspect the separate JSON quality report.

| Independent authored scenarios | Accepted baseline | Radius candidate |
| --- | ---: | ---: |
| English exact success | 12/15 | 13/15 |
| Mixed Cantonese/English exact success | 6/9 | 6/9 |
| Clean preservation | 14/14 | 14/14 |

All three layouts have the same outcomes. Their variants are correlated and
must not be presented as 72 independent samples. These are authored diagnostics,
**not recorded user traffic or fresh confirmation**. They must remain outside
training. Under the current policy, the mixed-script slice is now a primary
development panel for model selection; do not reuse it as fresh confirmation.

The candidate improves the old development score to **0.879070 (+0.005455)** but
does not fix the new mixed-language failures. For example, `tommorow`, `meting`
and `teh` after the authored Chinese contexts already rank `tomorrow`, `meeting`
and `the` first in the English scorer, but acceptance withholds them. Missing
space and authored real-word intent cases also remain. These observations
motivate broader bilingual acceptance analysis; they are not training examples
or evidence that these few words describe population error frequencies.

The radius candidate was subsequently withdrawn during the broader study.
The accepted engine and old objective's weights remain unchanged.

The initial radius prototype passed verification with its rebuilt host/Android
native engine: 40 native
tests, 285 unit/scenario tests with zero failures/skips, screenshot verification
and debug assembly. The integrated native engine exactly reproduces all 5,261
prototype outputs. Paired Mac spelling-call timing increases median
**1.524 → 1.893 ms** and p95 **3.085 → 3.436 ms**, over four alternating
1,024-case rounds. This is native host work, not phone key-to-commit latency.
The [candidate artifacts](config/english_iteration/2026-10-30/candidate-radius/verification.json)
record binaries, source and verification details.

## Fresh real-product data still needed

The missing qualification resource is actual English/Cantonese chat typing with
confirmed intended text. Neither an LM-generated conversation nor synthetic
typos can honestly replace it. The session schema and validator are ready for
such data; no keyboard recorder or background text collection was enabled.

Collect consecutive consenting internal-test conversations rather than only
reported failures. Retain clean input, abbreviations, proper names, code
switches, corrections, explicit selections, undo and cursor edits. Include
device/build/layout and monotonic event timestamps. Separate English,
Cantonese-dominant mixed and English-dominant mixed results; Chinese Quick
selection is a distinct intent from automatic English correction.

Intended final text requires human confirmation. A model's own replacement,
accepted suggestion or absence of an undo is not an intent label. Unreviewed
sessions remain in replay/coverage counts and outside labelled quality metrics.

Assign entire participants and conversations to development or confirmation
before reviewing outcomes. Keep confirmation out of fitting and model choice;
once repeatedly inspected for improvement decisions, treat it as development
and replenish it. Audit source/prompt overlap against LM and acceptance data;
the schema cannot prove independence from an undocumented training history.

Report a dashboard rather than inventing new population weights:

- Intended typo fixes and wrong replacements, separately for known/unknown
  observed words and each language/layout slice.
- Unwanted changes per 1,000 clean words, including case/name/identifier damage.
- Exact message success and character error, retaining ambiguous observations.
- Undo and manual-repair outcomes, with replay assumptions stated; new model
  responses do not establish how a real user would have reacted.
- Physical-phone key-to-commit latency, deadline misses and cancellation losses,
  separately from native Mac timings and virtual-scheduler correctness.

Use participant/conversation clustering for uncertainty. Keep all modes of a
scenario together. Choose sampling amounts and release budgets before seeing
candidate results; a small zero-error panel does not establish a low harm rate.

## Reproduce

```sh
python3 scripts/audit_autocorrect_eval_coverage.py --output config/product_eval_v1
python3 -m unittest discover -s scripts -p 'test_product_session_eval.py'

./gradlew :app:testDebugUnitTest \
  -PenglishNativeLibrary=/absolute/path/to/libcantonese_engine.dylib \
  -PproductEvalSessions="$PWD/config/product_eval_v1/sessions.jsonl" \
  -PproductEvalReport="$PWD/build/product-eval-v1/run.json" \
  --tests '*ProductAutocorrectEvalTest'

python3 scripts/product_session_eval.py \
  --sessions config/product_eval_v1/sessions.jsonl \
  --baseline build/product-eval-v1/baseline.json \
  --candidate build/product-eval-v1/candidate.json \
  --output config/product_eval_v1/comparison.json
```

For imported recorded chat, set `origin=recorded_chat`, supply opaque
`participant_id`, `conversation_id`, `device_family`, `capture_app_version`,
`split` (`development`/`confirmation`) and an `at_ms` on every event. Use
`intent_status=human_confirmed`, or `unreviewed` with `expected_text=null`.
The validator rejects missing provenance, duplicate IDs, unsupported events,
unordered times and participants/conversations crossing splits. It preserves
unreviewed-session counts rather than silently labelling or dropping them.
