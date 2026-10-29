# Short-word training and feature study, 2026-10-29

Accepted development aggregate: **0.8736146656392934**, improving the previous
**0.857560924437909** by **0.016053741201384364**. The frozen suite, weights and
scoring implementation are unchanged. These are exposed development results,
not independent confirmation of population accuracy.

The retained change is deliberately small: more training data and removal of the
separate literal-LM-score input from the existing acceptance NN. Its 24 hidden
units, physical 22-input interface, vocabulary, candidate ranking, edit costs and
acceptance threshold are unchanged. All 24 weights on input column 1 are exactly
zero. The NN still receives candidate/literal advantage and dictionary evidence;
this does not eliminate frequency information. No word-specific, dictionary or
length preservation override was added. No optional feature code was retained.

## Data

The new generator produced **26,132 cases**, including **8,001** synthetic
real-word errors whose intended target is known from the source text. Removing
observations already present in the previous training pool leaves **22,879** new
unique cases (13,053 fit; 3,410 calibration; 6,416 selection), including **7,643**
real-word errors. The initial combined study contained **127,639** observations.

A final audit found shared-input collisions in the inherited cache: splitting
full recorded sentences by group does not prevent identical short prefixes from
occurring in different partitions. Before the accepted retrain, **1,117 rows**
were physically quarantined by input `(left context, case-folded typed text)`,
regardless of target label, against both the original frozen cases and every
frozen prior-typo variant. This removes 705 fit, 114 calibration and 298 selection
rows. The accepted study has **126,522** observations, with zero matching
evaluation inputs in any update/normalization/calibration/selection split.
Its retained new additions number **22,873** (13,050 fit, 3,410 calibration,
6,413 selection), including **7,642** real-word errors. The legacy baseline
weights predate this audit and retain their prior exposure; this remains an
exposed development comparison, not a fresh independent test.

The new data uses authentic recorded TRAIN sentence prefixes with intended words
of length two or three. It includes clean controls, nearby-key edits, omissions,
duplications, swaps, empty contexts and truncated prefixes. Valid-word corruptions
are omitted without sentence evidence because the intended choice is unresolved.
The intended source word supplies each label, never the native model's answer.

Additional clean abbreviation controls use title-only vocabulary forms as short
prefixes of title-only full forms found in recorded TRAIN text. Both must lack a
lowercase word token. There is no list of weekdays or named runtime exceptions.
These are labeled synthetic abbreviation examples, not measured usage frequencies.
Only 75 of the new inventory examples come from this category.

All `tue` and `ir` typed/target surfaces stay outside fitting, calibration and
selection. Exact frozen evaluation triples are excluded. Original recorded group
splits are preserved; duplicate new observations are assigned once with held-out
partitions taking precedence. New examples already in the previous pool are
removed before merging. `clean_short_study_data.py` physically quarantines
evaluation-input collisions in matrices and labels together; the trainer rejects
unclean inherited caches by default. Synthetic prevalence is not used to infer a phone typo
rate. The accepted NN keeps the previously calibrated threshold, exactly
**0.7600370313468201**.

Raw inputs, native outputs and split/parent provenance are under
`build/short-nn-v2-20261029/new-data/`; the deduplicated additions and matrices are
under `data/`. Hashes and counts are saved in
`config/english_iteration/2026-10-29/short-nn-v2/`.

## Feature study

Thirteen predeclared or follow-up families were trained with matched data/order
and 24 hidden units. The grid uses learning rates 0.0001/0.0003 and 30/100 epochs.
Warm starts preserve the original NN function when adding zero-weight features or
recentering inputs. Old-data logit replay anchors apply equally across families.
Calibration uses the original real/teacher cohort. Checkpoints are selected on
the mean original/noisy real selection F0.5, with generated-context short-word
selection F0.5 as the tie-break. Existing regression tests are then qualification
checks, not fitting examples. For the accepted retrain, frozen inputs and their
audit variants are physically absent from all update and selection folds.
Reported user surfaces are excluded throughout. The inherited-cache exploratory
stage is explicitly distinguished below and is not eligible for promotion.

The families include data-only controls, removal of individual candidate/literal
score or raw-runner-margin inputs, lexical case-vocabulary evidence, edit position,
keyboard geometry, context length, contextual gain, and small combinations.
Optional additions are model inputs, not hand-written acceptance gates. The
exploratory family scores below precede the inherited-cache quarantine and are
not eligible for promotion. The final literal-score-removal family alone was
retrained and selected from the physically quarantined data. It retains the same
aggregate and all 5,261 native decisions shown here, with a different weight hash.

| Frozen native proposal | Aggregate | Previous-baseline delta | Decision |
|---|---:|---:|---|
| Matched older-data control | 0.859477 | +0.001916 | Existing ambiguity regression |
| Extra data, original inputs | 0.862091 | +0.004530 | Existing ambiguity regression |
| Keyboard geometry | 0.864256 | +0.006695 | Existing ambiguity regression |
| Context length | 0.863101 | +0.005540 | Existing ambiguity regression |
| Lexical case evidence | 0.862744 | +0.005183 | Existing ambiguity regression |
| Remove raw runner-up margin | 0.872285 | +0.014724 | Loses existing medium-word fixes |
| Conservative candidate-score-removal checkpoint | 0.849096 | −0.008464 | Aggregate regression |
| Conservative contextual-gain checkpoint | 0.866182 | +0.008621 | More runtime work; not needed |
| **Remove literal score; retain parent threshold** | **0.873615** | **+0.016054** | **Accepted; all existing tests pass** |

The raw-runner-margin proposal failed two unchanged real-JNI cases: noisy-context
`heve` → `have` and `mwrge` → `merge`. A representation replacing raw runner-up
margin with an edit-penalty gap preserved the NN's initial function exactly but
did not yield a passing trained checkpoint. No existing test expectations were
weakened to admit a new NN. A parameter-free calibration ablation retained the
accepted parent threshold instead of fitting a new cutoff. Its strongest passing
literal-score-removal checkpoint wins the frozen comparison above.

Full candidate tables, validation-only families, fixed-threshold comparisons and
rejection reasons are recorded with the study. Extra features did not justify
retaining their runtime code; the final Rust source change is the policy JSON only.

## Outcomes and limits

The frozen two-/three-letter slice (a diagnostic, not a new acceptance objective)
improves F0.5 **0.857558 → 0.862069**: correct automatic fixes **236 → 240**,
wrong fixes **18 → 18**, with the same one clean-word change. Newly recovered
short fixes include `iw` → `is`, `oug` → `out`, `zet` → `set`, `aeb` → `web`,
`ny` → `by` and `usi` → `us` in their original contexts. Two previously corrected
short errors are lost, producing the net gain of four.

The user-reported `try ir` → `try it` and all previous required correction/undo
cases remain intact. **The original `tue` problem is not solved**: the new NN
still preserves only `next tue` among the five supplemental contexts and changes
the other four to `the`. The study does not claim otherwise.

There are individual tradeoffs outside the short-word slice. Seven formerly
correct outputs become wrong/uncorrected, including one new clean split of
`deerfield` into `deer field` after a corrupted prefix. The exact paired changes
are saved in the report. Acceptance follows the documented aggregate objective,
not an additional unpublished per-slice cutoff.

## Verification and packaging

The new exported NN matches the fresh native probe on all **5,261** frozen cases
with **zero decision mismatches**. The required prior-typo audit was run for the
final NN and scored finalists; it remains a diagnostic and was not trained on.
Existing Rust classifier tests pass (34 passed; three unrelated fixture-dependent
tests ignored). The complete `verifyKeyboard` gate passes with actual host JNI,
including the added `oug` correction/undo scenario in all three layouts. The new
screenshot alone was updated, visually inspected, then validated separately.

The ARM64 JNI engine was rebuilt using the engine command from `deploy.sh`, staged
to `jniLibs`, and verified against the policy/dictionary bytes and model manifest.
The APK is rebuilt and package hashes are checked. Model vocabulary and dictionary
assets are unchanged. This is a local development acceptance; no phone installation
or production qualification is claimed.

No additional model pass, cache, dictionary lookup or NN layer is introduced.
The final latency report uses alternating baseline/candidate release-host process
pairs and 1,024 measured calls per binary after warmup. It is not phone latency;
the small timing difference should not be interpreted as an architectural speedup.
Median is **1.423 → 1.429 ms** and p95 is **3.107 → 3.104 ms** after the training,
build and QA jobs finished. No additional inference work is introduced.

Reproduction tools:

- `scripts/build_short_context_training.py`: additional labeled data.
- `scripts/experiment_short_features.py`: data preparation and feature families.
- `scripts/clean_short_study_data.py`: mandatory evaluation-input quarantine before
  any promotable training run.
- `scripts/qualify_short_feature_models.py`: unchanged regression qualification and
  explicit retained-threshold ablation.
- `scripts/verify_clean_acceptance.py`: exported/native decision parity.

Run the unchanged aggregate command in `AUTOCORRECT_OBJECTIVE.md` with a newly
built probe and a saved baseline binary. The pre-update baseline for this study
is retained at `build/short-nn-v2-20261029/baseline-probe`; do not accidentally
compare the updated model against itself.
