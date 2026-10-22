# Simple autocorrect experiment, 2026-10-17

The new profiles are **diagnostic only**. The default v80 assets are unchanged;
nothing was installed on a phone or uploaded. Neither experimental profile
passes the keyboard's acceptance tests.

## What changed

An optional runtime ranks candidates with one contextual LM score plus one native
typing/error score. Acceptance uses a small logistic fit over score margins.
It does not use dictionary familiarity categories, the separate spelling model,
boosted trees, a future-word count, or a legacy correction fallback. Later words
are passed directly into the LM and the same decision rule is rerun.

The hill climb tested raw margins, character-normalized margins, and an optional
observed-length calibration input. The retained diagnostic candidates use just
the two raw margins. Raw finger coordinates are not classifier features: the
typing channel derives a candidate-relative proximity/edit score. Its current
touch calculation is heuristic, not a newly fitted per-key Gaussian model.

## Data and results

The expanded corpus has 363,675 rows, including 236,313 natural-source edit/copy
requests, 117,523 physical-touch simulations and 9,839 existing recorded-touch
requests. All word/document variants stay together. Only eight recorded people
are in TRAIN, with four historical evaluation people; synthetic data does not
increase that count. A second confirmation cohort contains 22,873 requests from
previously unused validation groups. TEST and selection-half validation remain
reserved. Chinese script is not converted. Reported user sentences are never fit.

At a fixed .975 cutoff on the first 44,394-row confirmation, expanding the data
improves the previous two-margin model from 2,073/2,153 correct replacements
(96.28%) to 2,020/2,053 (98.39%). Recall is still only 7.18%, and the model fails
the preset independent-group reliability guard. This is not a production gain.

The first full-network LM pilot fails language retention and is rejected. The
corrected pilots exclude confidence/cutoff groups from corrupted-context LM
supervision. The retained v85 step200 export improves noisy-context development
CE from 8.2932 to 7.7233 with declared Cantonese/mixed/English CE retention guards
passing. It uses the same 69,632-token vocabulary and single-model architecture.
The separate Quick/handwriting Cantonese model remains unchanged.

After refitting the small acceptance rule, the new model's TRAIN-selected .95
cutoff gives **1,071/1,090 correct replacements (98.26%)** on the fresh 22,873-row
confirmation, fixing **7.35%** of typos with **3 clean-input false changes**.
The historical real-touch guard is only **43/46 correct (93.48%)**. Neither result
qualifies promotion. Scores are estimates, not calibrated per-user guarantees.

Actual controller/JNI replay still fails `teh → the` and all 14 complete reported
sentences. The isolated candidate fails 2/4 JUnit methods; the full report retains
the final editor outputs. The existing default-profile keyboard gate passes
**212 unit/scenario tests, screenshots and debug build**, with no skipped tests.
These results must not be conflated.

## Verification and artifacts

- `MinimalTypingPolicyTest`: bounded schemas, original/rival competition,
  normalized/length calibration, no dictionary/spelling dependence, one LM call
  for later context, exhaustion/unsupported-evidence guards, no legacy fallback.
- Eleven Python corruption/model-feature checks and five pipeline-parser checks
  pass. Failed test runs now retain fresh JUnit output without reusing stale XML.
- New-model native parity: 512/512 sampled automatic actions agree between MPS
  calibration scoring and actual Rust inference; 511/512 alternative rankings
  agree. Scores are not numerically identical; absolute LM difference p95 .0860,
  maximum .3107; probability difference maximum .01149. No cutoff was adjusted.
- Isolated deployed resource total: **27,115,632 bytes**, including both models,
  vocabulary/candidate resources and notices; below 30,000,000 bytes.

SSD reports: `simple-policy-v82a`, `robust-main-lm-v84`, `robust-main-lm-v85`,
`robust-lm-selection-v86.json`, `robust-policy-v86`, `robust-confirmation-v86`.
The main follow-up report is `robust-policy-v86/confirmation-report.json`;
actual keyboard failures are in `robust-policy-v86/diagnostic-e2e/report.json`.
Reproduction/data limits: sibling dataset `keyboard-lm/CORRUPTED_TYPING_DATA_V82.md`.
