# Noisy-context acceptance experiment — 2026-10-25

The integrated change warm-starts the existing 22–24–1 English acceptance
network on corrupted prior context plus clean replay. It adds no runtime feature,
model pass, dictionary, candidate generator or ranking change. The shared LM,
apostrophe policy, bilingual ranking and local inference remain unchanged.

The unchanged frozen objective improves from **0.857262417162** to
**0.857560924438**, a delta of **+0.000298507276**, against the saved freshly
rebuilt starting executable. This is a small development improvement, not an
independent accuracy or production-qualification claim. No examples, cohort
weights or objective code were changed.

## Training and selection

`scripts/experiment_noisy_acceptance.py` uses the existing grouped acceptance
replay in `../gboard-probe/improve-v3/cases.jsonl`. It excludes evaluation and
development rows before generating features or noise. For real fit, calibration
and selection rows, it generates four prior-context variants using the audit's
fixed input-only corruption rule. The current word and clean intended target
stay fixed; selection variants are never fit or used to calibrate the threshold.

The run scores 51,249 original/noisy development examples. Nine existing JNI
uncertainty, literal-preservation and completion characterizations form separate
selection guards; they do not enter fitting or cutoff calibration. Original
prompt/source groups retain their fit/calibration/selection assignments.

The final fit uses weighted binary cross entropy, original clean/generated/new
replay and replay-logit anchoring of 0.2. Learning rates 0.0001/0.0003, noise
weights 0.25/0.5/1.0 and 20/60 epochs were compared. One global cutoff is
calibrated on original real/new calibration rows plus noisy real calibration
rows. Each noisy variant receives one quarter of the selected noise weight.
The selected checkpoint is **learning rate 0.0001, noise weight 0.25, 60 epochs**.
It maximizes the mean of original and noisy real selection F₀.₅ among guard-passing
candidates, with original selection and smaller weight change as tie-breaks.
The unchanged parent is always an eligible alternative.

This was iterative development: an initial fit improved robustness but regressed
the aggregate to 0.849059. A more strongly anchored, original-selection-first
fit scored 0.859527 but changed ambiguous `butger` to `burger`, violating an
existing JNI assertion. Both were rejected. Adding the existing preservation
guards left the parent best under original-first selection. Restoring the
initial balanced original/noisy selection objective with those guards selected
the smaller final fit. Its checkpoint and cutoff were fixed before its native
benchmark run. No evaluation labels were used for fitting or cutoff selection;
the repeated exposed benchmark is a development acceptance check.

The offline feature code initially missed the native space-key adjacency rule.
That experiment was stopped before fitting. After correcting the adapter,
Python/native starting-policy decisions match on all **51,258** development and
guard requests. The script binds the policy, source data, feature code, spelling
source, model, probe, seed and generated inputs before inference. Native caches
are reused only when input, model and executable hashes match. The final
checkpoint retains the original pinned dictionary metadata and notices.

## Prior-typo diagnostic

Each row below uses the same 4,145 eligible labelled evaluation cases, including
1,118 current-word typos. These are synthetic repeated variants, not independent
samples or measured error prevalence.

| Prior-context noise | Correct fixes before → after | Wrong fixes before → after | Clean words changed before → after |
| --- | ---: | ---: | ---: |
| Deletion | 591 → 597 | 55 → 58 | 11 → 11 |
| Substitution | 554 → 566 | 46 → 46 | 6 → 6 |
| Duplication | 563 → 571 | 50 → 47 | 7 → 7 |
| Transposition | 535 → 550 | 41 → 42 | 4 → 4 |

All four diagnostic F₀.₅ values improve. Wrong fixes increase for deletion and
transposition; clean-word damage is unchanged within each noisy cohort. In the
official original-context benchmark, mobile gains five correct fixes but adds
three wrong changes, including one additional clean-control change. Touch
retains its correct fixes and removes one wrong clean-control change. The hard
set is unchanged. These tradeoffs are retained in the aggregate and reports.

## Integration and verification

The policy is compiled from
`../QwenASR/crates/cantonese-ngram/src/english_acceptance.json`; the selected-model
manifest binds its new hash. The Android engine is rebuilt using the canonical
deploy toolchain and staged in `app/src/main/jniLibs/arm64-v8a/`. Generated native
libraries and APKs remain build artifacts.

`noisyContextAcceptanceAndUndoAcrossLayoutsThroughJni` uses the production
controller and actual native inference: after `Let me know if yyou `, `heve`
corrects to `have` on Space; Backspace restores `heve`, and the next Space keeps
the literal. It covers English, Quick3 and Quick5 and asserts candidates,
composition, committed text and toolbar state. This evaluation variant was
excluded from fitting and calibration. A separate fake-model screenshot fixture
verifies presentation; its baseline was rendered and inspected before validation.

The existing model-characterization table now reflects `mwrge → merge` automatic
acceptance. Its stale `tyoes` and `wrror` automatic expectations are corrected to
abstention, which was already the saved starting policy's behavior. Candidate
availability assertions remain. A stale contextual `exmaole` literal expectation
is also aligned with the saved starting policy's existing `example` correction;
the following sequence now exercises the resulting context. No word-specific
runtime rules were introduced.

Native verification on all 5,261 frozen cases finds **zero Python/native decision
mismatches** and **zero differences in candidate pools or scores**. Reports,
training protocols, rejected candidates and final checks are in
[`config/english_iteration/2026-10-25/noisy-context/`](config/english_iteration/2026-10-25/noisy-context/).
Detailed inputs, outputs, policy checkpoints, logs and the baseline executable
remain in ignored `build/noisy-acceptance-20261025/`.

The required `:app:verifyKeyboard` gate passes with actual JNI enabled: **249
unit/scenario tests, zero failures or skips**, screenshot verification and debug
APK assembly. Android-test Kotlin compilation passes, as do **38/38 native tests**
(including the model-dependent tests) and **13 Python tests**. The APK's stripped
native library matches the rebuilt library exactly and contains the selected
policy and pinned dictionary. The packaged model and license hashes are verified.

In paired ABBA release-host timing, warm median is **1.547 → 1.5455 ms** and p95
**3.03725 → 3.01725 ms**, with 2,016 measured calls per binary after warmup. This is
effectively unchanged latency in this small host measurement, not a speed claim.
First-call timings vary between runs; phone latency remains unmeasured.

Reproduction uses the NumPy environment at
`../dataset/cantonese-ngram/.venv/bin/python` and the existing sibling feature
implementation, whose hash is recorded in the protocol:

```sh
OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/experiment_noisy_acceptance.py \
  --policy build/noisy-acceptance-20261025/baseline-policy.json \
  --probe build/noisy-acceptance-20261025/baseline-probe \
  --anchor .2 --preservation-guard --output build/noisy-acceptance-UNIQUE-RUN
```

The broader shared model remains experimental. This work does not establish
phone key-to-frame latency, deploy to a phone, enable touch personalization or
make undo feedback persistent across later words.
