# Expanded autocorrection evaluation

Historical v2 coverage suite. The user requested removal of easy authored
errors and reduction of clean controls after inspecting these results. The
[single optimization objective](AUTOCORRECT_OBJECTIVE.md) defines the current
92-case authored panel and aggregate score. V2/v3 sources, reports and frozen
cohorts remain unchanged for traceability; their old selection restrictions are
superseded by the user's request to hill climb the exposed aggregate.

The evaluation now separates observed errors, observed clean input, copied
controls and authored stress cases. It scores **5,581 observations**, keeps the
original 2,042-case benchmark unchanged, and adds coverage that its overall
score concealed. No model, threshold, candidate generator or Android behavior
was changed for this evaluation.

The [scorer](scripts/autocorrect_eval_v2.py),
[scenario source](config/autocorrect_eval_v2/scenarios.tsv),
[preservation source](config/autocorrect_eval_v2/preservation.tsv),
[frozen manifest](config/autocorrect_eval_v2/manifest.json) and
[initial results](config/autocorrect_eval_v2/current-report.json) are retained.
Raw observations and native outputs stay in ignored `build/autocorrect-eval-v2/`.

| Panel | Errors / clean / ambiguous | Correct / wrong fixes | Clean changes | F₀.₅ |
|---|---:|---:|---:|---:|
| Aalto observed events | 744 / 44 / 0 | 504 / 31 | 0 / 44 | 0.87379 |
| Aalto copied controls | 0 / 1,254 / 0 | — | 6 / 1,254 | — |
| Public real-touch observations | 525 / 2,556 / 46 | 356 / 12 | 0 / 2,556 | 0.89134 |
| Authored mobile challenge | 208 / 204 / 0 | 154 / 8 | 2 / 204 | 0.89953 |

Wrong fixes include changes to clean inputs. The complete historical Aalto
cohort still gives **504 correct / 37 wrong, F₀.₅ 0.86657**. Separating its
1,254 reference-copy controls reveals that only 44 of its clean inputs were
recorded clean typing events. Aalto's autocorrection-event selection does not
preserve a natural stream error rate, even in the observed-only panel.

## What is harder and better covered

The 412 cases authored by this coding agent cover 204 scenarios: short words, multiple edits, informal
chat, work, travel, food, mixed Cantonese/English, emoji and punctuation,
apostrophes, missing word boundaries, and real-word confusions. Preservation
cases include names, slang, dialect spellings, filenames, code identifiers,
URLs, email addresses and legitimate short words. Each error scenario has its
unchanged target as a clean counterpart. No candidate availability or model
outcome determines membership.

The current model fixes **50/74 short-input challenge errors**, but only
**11/25 errors with OSA edit distance ≥2**, **4/12 apostrophe errors**, and
**1/4 missing-boundary errors**. It fixes **0/8 author-intended real-word
confusions**. Those real-word labels specify author intent; left context may
not establish a unique answer, so they are an exploratory capability panel,
not a demand to force corrections of valid words. Their clean counterparts
remain in the evaluation.

The observed-touch panel adds a different distribution: **83/148 short-input
errors** and **46/106 multi-edit errors** are fixed. All 63 error targets absent
from the returned top eight remain misses. Unlabelled observations are excluded
only from word metrics, and retained in whole-trial character error. Across
480 trials, literal CER is **5.3870%**, versus **2.7036%** after current native
corrections; 319 sentences are exact. This reuses the
[existing public touch source and replay assumptions](TOUCH_EVALUATION.md).
The current shipping scorer uses text only; retaining the original taps does
not make this a touch-conditioned model evaluation.

## Comparison rules

- Keep all four panels separate. There is no pooled score with invented product
  usage weights. Keep the existing unambiguous-neighbour primary panel and its
  acceptance rules in `TYPING_EVALUATION.md`; these additional panels supplement it.
- Fit on TRAIN, calibrate on TRAIN calibration, select on selection, then freeze
  the candidate before scoring. These exposed results must not become fitting
  or threshold-selection labels. If cases later enter training, version the
  evaluation and explicitly downgrade their status to training regressions.
- Report F₀.₅, correct/wrong fixes, recall and clean changes per 1,000, with
  denominators. A wrong typo replacement contributes both FP and FN. Missing
  candidates and abstentions remain in the denominator. Scores are case-insensitive;
  this is not a capitalization-quality evaluation. Clean-only F₀.₅ is undefined
  in interpretation: use change counts/rates, even though the numeric helper
  retains the historical empty-denominator convention of zero.
- Show length, edit-distance and domain slices. Report counts for tiny panels;
  a perfect result on eight authored errors does not establish broad accuracy.
- Use paired cluster bootstrap when comparing two binaries. Resample participants
  for touch, prompts for Aalto and scenarios for authored variants. The current
  1,000-replicate touch F₀.₅ interval is **[0.86326, 0.91809]**; authored is
  **[0.85986, 0.93300]**. These quantify sampling variation, not population bias
  or independence from previously exposed data. Repeated prompts across touch
  participants remain a limitation. Zero observed clean errors does not imply
  zero future harm.
- Inspect hypothetical 1%, 5%, 10% and 20% typo prevalence scenarios to see how
  clean harm affects precision. These scenarios are sensitivity calculations,
  not measurements of our users' error frequency.
- Report latency at its actual boundary. This single host pass covers 5,581
  calls, median/p95 **1.574/3.996 ms**, including the first call. It is not a
  paired speed comparison, a cold-start benchmark or Android input-to-display latency.

The freeze validates the original evaluation IDs and aligned target hash, binds
all suite files and scoring code, and refuses to replace an existing directory.
The scorer checks those bindings, saves binary/model identity before inference,
passes only observed `left` and `typed`, and refuses to overwrite a result.
Optional `--baseline-probe` produces same-case comparisons and paired intervals.

## Independence and remaining representativeness limits

The natural panels were already exposed in development. The authored set is new
for this run but has **3 exact input overlaps** with available acceptance
fit/calibration replay; 263/412 target labels share target word types. Selection/
development replay has zero exact input overlaps. All overlaps are reported,
not removed after scoring. This audit does not cover complete shared-LM training
or every historical experiment. The authored set is therefore a stress/regression
suite, not an independently sampled human benchmark.

Source-copy errors and phone study observations are useful, but cannot establish
modern Cantonese/English chat performance on our own keyboard. A truly
representative confirmation set still requires a prospective collection:

1. Recruit target users for ordinary chat and practical writing tasks, spanning
   English and Cantonese/English, our supported layouts, and multiple screen
   sizes. Treat copy typing as a separate condition. Record the sampling design;
   do not balance error classes and then call the resulting score natural accuracy.
2. With consent, capture complete input sequences, coordinates, layout geometry,
   timestamps, backspaces, space/submission boundaries, displayed proposals,
   accepted/rejected replacements and undo. Retain clean events and whole sessions.
   Observe privacy boundaries before collecting personal messages or credentials.
3. Obtain intended text from the writer after the task. Have ambiguity reviewed
   separately; do not treat Gboard output or a model suggestion as truth. Retain
   ambiguous events in whole-session error and intervention measures.
4. Assign participant-disjoint development/confirmation groups in advance.
   Use disjoint scripted prompts across groups; audit natural text overlap.
   Bind raw records, annotation decisions and cohort identities before inference.
   Keep confirmation labels out of model selection and report its first result.
5. Measure correction F₀.₅, clean harm, whole-session CER, undo/rejection and
   event-to-selectable-candidate latency on-device. Use participant-level paired
   uncertainty. Establish population weights from collection or usage evidence,
   rather than assuming our authored mix reflects deployment.

This collection has not happened. The expanded suite improves diagnostic
coverage and scoring honesty; it does not claim to finish product qualification.

## Reproduce

The new evaluator and its eight tests use Python's standard library. Existing
touch tests additionally require NumPy. The initial replay source is the frozen
acceptance iteration from this workspace, containing the original real cases
and explicit fit/calibration/selection labels. No protected LM test/reserve
files are read.

```sh
python3 -m unittest discover -s scripts -p 'test_autocorrect_eval_v2.py'
cargo build --offline --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml --release --features android --bin ngram_probe
python3 scripts/autocorrect_eval_v2.py freeze \
  --mobile ../gboard-probe/improve-v3/cases.jsonl \
  --training ../gboard-probe/improve-v3/cases.jsonl \
  --touch build/touch-eval-public/prepared \
  --output build/autocorrect-eval-v2/reproduction-suite
python3 scripts/autocorrect_eval_v2.py score \
  --suite build/autocorrect-eval-v2/reproduction-suite \
  --probe ../QwenASR/crates/cantonese-ngram/target/release/ngram_probe \
  --output build/autocorrect-eval-v2/reproduction-results
```

Initial final files are `build/autocorrect-eval-v2/frozen-final/` and
`current-final/`. An initial draft remains in `frozen/` and `current/`: four
semicolon-containing apostrophe typo strings collided with the TSV variant
delimiter. The source strings were corrected and the final suite re-frozen
before inspecting the draft scores. No case was removed based on performance;
both draft and final artifacts remain available.

Verification: eight new evaluation tests and ten existing touch tests pass;
whole-trial scoring matches the older evaluator on all 480 real trials, and the
full historical 2,042-case result reproduces exactly. This change affects
evaluation scripts/data/docs only; no Android build or deployment is required.
