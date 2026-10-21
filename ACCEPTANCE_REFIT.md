# Shared acceptance refit; lexical fallback removed

2026-10-21. The user rejected the separate single-edit fallback and asked to tune
the existing rules, suggesting a word-length acceptance constant. The fallback,
its native helper, embedded dictionary, and packaged dictionary license are now
removed. **The only native diff is `english_acceptance.json`: existing coefficients,
global cutoff, and provenance.** No new feature, branch, dictionary, or length
table is shipped.

## What was tested

The existing six features remain winner score, runner margin, known-input flag,
literal score, score over literal, and apostrophe flag. Their normalizers, hard
guards, candidate generator, ranking costs, shared LM and apostrophe restoration
remain unchanged.

Compare a global refit with additive length constants for 3/4/5/6/7/8+ letters,
regularized toward the existing rule. Also test the user's suggestion directly:
keep the coefficients fixed, calibrate per-length cutoffs, and shrink those
cutoffs toward one global cutoff. In total: 24 joint-fit combinations plus 12
cutoff/shrinkage combinations, selected without evaluation results.

The new synthetic source has 4,000 word types after excluding the original 1,000
types and the subsequently exposed 2,000 types. Generate substitution, neighbouring
substitution, insertion, deletion, swap and clean cases: 24,000 observations.
Connect intended-word groups sharing an observation, leaving 3,827 groups, so
identical generated observations cannot cross partitions.

Use the existing prompt-group TRAIN/selection/evaluation split and TRAIN
fit/calibration split. Add the previously prepared TRAIN-only empty-context
observations; do not add selection/evaluation contexts to fitting. Existing real
TRAIN can naturally contain common word types; the 3,000-type exclusion applies
to the new synthetic examples, not to the historical real dataset or LM training.
Fit 14,065 eligible rows and calibrate on 1,874 TRAIN rows containing unambiguous
typos and clean controls. Synthetic source weights are tested at 0/.25/.5/1 times
the real TRAIN weight. Candidate selection prioritizes unambiguous 4–5-letter
F₀.₅ while requiring no loss of complete real selection F₀.₅.

## Did word-length constants help?

They did not justify extra complexity for the requested 4–5-letter objective.
The selected candidate is the global refit, augmentation weight .5, regularization
.001, with every length offset zero.

| Selection candidate | Unambiguous 4–5 correct / 72 | Full real selection F₀.₅ |
|---|---:|---:|
| Selected shared global refit | 24 | **0.80821** |
| Best qualifying joint refit with length offsets for the 4–5 metric | 23 | 0.80529 |
| Fixed coefficients, half-shrunk per-length cutoffs | 20 | 0.80257 |
| Fixed coefficients, independent per-length cutoffs | 25 | 0.79796 (below 0.80185 baseline) |

The joint length model has better all-length synthetic selection F₀.₅ (0.92621
versus 0.91741), but loses on both the requested 4–5-letter metric and overall
real selection compared with the chosen global refit. These finite samples do
not prove length is irrelevant; they did not support adding it to this version.

The selected normalized coefficient on absolute winner score changes
0.84960 → 0.51435; runner margin 1.27800 → 1.54411; score over literal
1.55214 → 1.88638. This reduces reliance on absolute contextual frequency and
increases reliance on relative evidence. The global threshold is calibrated on
TRAIN, not hand-lowered against the exposed examples. Four ULPs of export
rounding retain tied calibration cases across Python/Rust arithmetic.

## Frozen evaluation

The primary typo panel still includes **unambiguous neighbouring replacements
only**, determined from the full source dictionary before consulting model rank
or success. Clean controls and complete real typing remain separate reports.
Dictionary lookup is evaluation-only; it is absent from inference.

| Unambiguous 4–5-letter panel | Before | Shared refit | Wrong replacements |
|---|---:|---:|---:|
| Original exposed panel | 56/88 (63.6%) | 73/88 (83.0%) | 0 → 0 |
| Subsequently exposed word panel | 61/181 (33.7%) | 108/181 (59.7%) | 0 → 0 |
| New held-out, lower-frequency word groups | 7/63 (11.1%) | 19/63 (30.2%) | 0 → 0 |

The two exposed panels are regression checks, not fitting or selection targets.
The last panel comes from the new word groups held out during policy fitting and
selection; it has lower-frequency words and a small sample. It is not the same
population as the original common-word panel. Do not omit the poorer result.
Of its 44 remaining failures, seven targets are absent from model candidates,
six rank below first, and 31 are rejected at acceptance. This refit improves the
rule but does not solve all remaining spelling failures.

Across all new held-out unambiguous lengths, correct fixes increase 348/617
(56.4%) → 468/617 (75.9%). Including its 808 controls, F₀.₅ improves
0.84919 → 0.91657; wrong replacements increase 10 → 16, entirely from clean
controls (6 → 12). The synthetic cases are grouped by word/observation and can
include different corruptions of one intended word; they are not independent
natural typing events.

| Full frozen real evaluation, 2,042 cases | Original | Withdrawn fallback | Current refit |
|---|---:|---:|---:|
| Correct fixes | 364 | 372 | **397** |
| Wrong replacements | 23 | 25 | **33** |
| Clean controls changed / 1,298 | 5 | 6 | **8** |
| Precision | 94.06% | 93.70% | **92.33%** |
| Recall | 48.92% | 50.00% | **53.36%** |
| F₀.₅ | 0.79407 | 0.79760 | **0.80560** |

The current rule adds 33 correct fixes and 10 wrong replacements relative to the
original, including three additional clean changes. It does not preserve every
control. The withdrawn fallback's 98.9%/95.6% synthetic ratios are **not** the
current rule's ratios. Historical real-evaluation exposure remains; this is
development validation, not a new independent natural-typing test.

## Native and keyboard verification

- Compiled decisions match the exported shared rule on **24,567 cases**, including
  4,848 new held-out word-group cases. Candidate lists remain identical.
- The accepted-policy verifier separately reproduces all 9,728 real decisions and
  the 397/33/F₀.₅ 0.80560 evaluation result.
- Three native spelling tests pass. Actual JNI/controller tests pass with **18
  tests, zero skips/failures**, including both Quick versions, Space, undo, and
  undo suppression.
- Correct former abstentions now include `tyoe → type`, `lokk → look`,
  `exmaole → example`, and `troggee → trigger`. These regression examples were
  not individually patched into the model. Old-abstention characterization
  assertions were updated only after measuring these improved decisions.
- Full keyboard verification, Android-test compilation, 218 unit tests and 80
  screenshot validations pass. Ten JNI-dependent cases skip in the default gate
  and are covered by the explicit zero-skip JNI run. Existing scenario rendering
  is unchanged from the inspected screenshot.

Release-host general English calls, four alternating process pairs and 1,024 calls
per binary, measure median/p95 2.726/5.575 ms → 2.755/5.648 ms. Candidate lists
are identical. These small measured differences are not an Android scheduling,
display, or cold-start measurement. The withdrawn dictionary/index overhead is
removed; no phone latency improvement is claimed. Host/ARM64 libraries are rebuilt
and the debug APK is checked against the rebuilt stripped library. No phone
installation was performed.

Current policy SHA-256:
`1d5733cc3f09778485318cb5c579086f67977b19dc52577cd6dbd298ae4784da`.
The shared LM is unchanged. Native source comparison confirms that only the
policy JSON differs from the pre-experiment source.

Reports: [selection](config/english_iteration/2026-10-21/acceptance-refit/selection.json),
[frozen evaluation](config/english_iteration/2026-10-21/acceptance-refit/evaluation.json),
[compiled verification](config/english_iteration/2026-10-21/acceptance-refit/native-verification.json),
[accepted-policy parity](config/english_iteration/2026-10-21/acceptance-refit/accepted-native-parity.json),
[latency](config/english_iteration/2026-10-21/acceptance-refit/latency.json),
[package verification](config/english_iteration/2026-10-21/acceptance-refit/package-verification.json).

## Reproduce

Use the bundled Python runtime with NumPy for fitting. Preserve the archived
before probe, before-policy JSON, and frozen source caches. Do not recalibrate
against the exposed evaluation panels.

```sh
OPENBLAS_NUM_THREADS=1 /path/to/python-with-numpy scripts/refit_length_acceptance.py --source /Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt --probe build/neighbor-repair-2026-10-21/ngram_probe.before --output-dir build/acceptance-refit-2026-10-21 --evaluate --frozen-selection config/english_iteration/2026-10-21/acceptance-refit/selection.json
python3 scripts/verify_acceptance_refit.py --probe app/build/english-host/release/ngram_probe --source /Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt --output build/acceptance-refit-2026-10-21/native-verification.json
python3 scripts/verify_english_acceptance.py --iteration build/english-iteration-2026-10-20 --probe app/build/english-host/release/ngram_probe --output build/acceptance-refit-2026-10-21/accepted-native-parity.json
bash scripts/test_english_autocorrect.sh
./gradlew :app:verifyKeyboard :app:compileDebugAndroidTestKotlin
```
