# Frequency-input ablation and Quick `ir` correction

The subsequent [short-word training study](SHORT_WORD_NN_V2.md) accepts a smaller
single-input removal with additional data; this document records the earlier
broader ablations and their rejected results.

The tested removals did not improve the frozen autocorrect objective. They
preserved the five intentional `tue` examples but lost useful corrections,
including `try ir` → `try it`. No NN weights or Rust changes were promoted.

The reported missed `ir` correction had a separate confirmed Android cause:
the shared-LM adapter inherited `allowsQuickCodeCorrections=false`. In Quick,
`ir` is a valid Chinese code, so `EnglishInputCoordinator.finish()` discarded
the NN's approved `it` correction. The adapter now participates in the existing
approved-English path. This removes the veto; it adds no word-specific correction
or dictionary/length preservation rule. Explicit Chinese selection and uncertain
literal input remain unchanged.

## Frozen evaluation

Evaluation follows [AUTOCORRECT_OBJECTIVE.md](AUTOCORRECT_OBJECTIVE.md): unchanged
frozen cases/weights, saved freshly built baseline, fresh release native probes,
one aggregate score, baseline delta, and the paired prior-typo audit.

| NN | Aggregate | Delta from accepted baseline | Intentional `tue` preserved | `try ir` → `it` |
|---|---:|---:|---:|---|
| Accepted production NN | 0.857561 | 0 | 1/5 | Yes |
| Matched retrained control, all scores plus contextual gain | 0.863431 | +0.005870 | 2/5 | No |
| Remove candidate/literal scores and their raw advantage | 0.853625 | −0.003936 | 5/5 | No |
| Remove all five frequency-bearing score inputs | 0.826658 | −0.030903 | 5/5 | No |

The matched control is included because the accepted NN and the new models were
trained under different protocols. Relative to that control, the partial and full
ablations decrease the aggregate by 0.009805 and 0.036773 respectively. The
control also loses the new user regression, so it was not promoted despite its
better aggregate. These are exposed development results, not independent test
accuracy or a claim that frequency should never be used. They show that blanket
removal sacrifices useful evidence as well as suppressing the `tue` errors.
The `ir` miss occurs in all three retrained models, so that particular regression
cannot be attributed specifically to removing the frequency inputs.

The model diagnostic uses left context only: `out` in `try ir out` was recorded
but not supplied as future evidence. An isolated `ir` probe is labeled ambiguous
and is not included in the six gold diagnostic cases. The five `tue` contexts are
empty, `on `, `see you on `, `next `, and `today is `. All `tue` and `ir` typed or
target surfaces were excluded from the new fitting/calibration/selection data.

## Controlled experiment

`scripts/experiment_frequency_ablation.py` prepares 104,760 existing TRAIN and
augmentation cases, including 75,269 eligible fit rows and 5,289 real/teacher
calibration rows. It reuses the earlier short-word sweep and original noisy-context
TRAIN replay. No frozen objective case is added to training. A fixed 1,024-case
sample checks both cached ranking and acceptance against the newly built native
baseline before fitting; there are zero mismatches.

All three NNs use matched random initialization, seed 20261029, the same batches,
data weights, 24 ReLU hidden units and a sigmoid output. The grid is learning rate
0.001/0.003 and 30/100 epochs. The threshold is calibrated on the original
real/teacher cohort, not the synthetic clean/error ratio. Selection maximizes the
mean original/noisy real selection F0.5; user examples and the frozen scoring
suite are not used during fitting or candidate selection.

The 23 physical inputs are the original 22 plus contextual candidate/literal gain:

```text
[log P(candidate | context) − log P(typed | context)]
− [log P(candidate | empty) − log P(typed | empty)]
```

The partial ablation zeros and freezes input columns 0–2: candidate raw score,
literal score, and their advantage. The full ablation also zeros columns 3–4:
raw and penalized runner-up margins. Thus the models have 20 and 18 effective
inputs respectively. Removed inputs have exactly zero first-layer weights
throughout training and export; the native prototype validates this invariant.
Candidate generation and ranking remain fixed, isolating acceptance changes.

All three exported policies reproduce native decisions on all 5,261 frozen
cases with zero mismatches. The paired prior-typo audits were run for each native
candidate; their detailed operation-level regressions and recoveries are saved
with the results. Raw data, masks, policies, protocol hashes, logs and probes are
under `build/frequency-ablation-20261029/`. Compact reports are under
`config/english_iteration/2026-10-29/frequency-ablation/`.

## Latency and retained fix

In the single release-host objective passes, candidate median inference times
were 0.977 ms (control), 1.039 ms (partial), and 0.998 ms (full); their baseline
passes measured 0.985, 1.001, and 1.015 ms. These are diagnostics from individual
passes, not a phone benchmark. The rejected contextual-gain prototype adds a
one-time empty-context LM pass and approximately 256 KiB of shared cached scores
for production BOS mode. It adds no inference pass once cached.

The retained Android adapter fix uses the existing NN result, adds no model call,
and keeps the production Rust/model files byte-for-byte unchanged. The static
native objective therefore remains 0.857561 (delta 0); it does not exercise the
Android Quick veto. The fix is instead verified by the production controller and
real JNI scenario: before the change, Quick3 commits `try ir `; after it, English,
Quick3 and Quick5 commit `try it `, support undo and literal recommit, preserve
uncertain input and honor explicit Chinese selection. The new screenshot was
updated alone, visually inspected, then validated separately by
`:app:verifyKeyboard` with the freshly built host JNI library. The full gate
passes. The phone was not connected and no build was installed there.
