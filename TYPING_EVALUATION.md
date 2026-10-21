# Candidate and autocorrect evaluation protocol

Updated 2026-10-21. Keep this protocol for future iterations. The acceptance
metrics requested by the user are **latency and F₀.₅**.

Current mixed-candidate iteration: [protocol, generalization checks and BOS
ablation](MIXED_CANDIDATE_RANKING.md). Autocorrection F₀.₅ remains 0.79407;
candidate retrieval improves overall, with explicitly recorded Chinese
regressions and increased full-pool ranking latency. Keep BOS enabled. User
examples are regression tests, excluded from calibration targets. Do not retune
the four coefficients against the exposed evaluation/generalization panels.

## Acceptance

- Use **F₀.₅** as the primary automatic-correction quality metric. Accept an
  improvement over the current accepted baseline; there is no separate 95%
  precision cutoff. Report precision, recall, correct/wrong replacements and
  unwanted changes to clean controls alongside it.
- Measure **latency** alongside quality: report median and p95 for first
  selectable candidates, plus full-ranking latency separately. Lower is better.
  For latency-only changes, preserve correction quality and candidate coverage.
- Report any quality/latency tradeoff explicitly. No numeric phone latency
  budget or combined latency/F₀.₅ weighting has been agreed; do not invent one
  or quietly hide a latency regression behind a quality gain.
- Fit on TRAIN, calibrate cutoffs on TRAIN calibration groups, and select on
  selection groups. Freeze the candidate before evaluation. Do not tune against
  evaluation labels or change cohort membership to improve the score.
- Rebuild the actual native library and run production-path checks. Old-engine
  experiments, skipped tests, or a stale packaged library do not establish a pass.

## Fixed autocorrection evaluation set

Source: SSD Aalto mobile typing data at
`/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/`:

- `ac_words_en.csv`: observed events. `TYPED_WORD` is the observation;
  `ORIGINAL_WORD` supplies the initial reference word. **`AC_WORD` is another
  keyboard's output, not ground truth.**
- `sentences.csv`: reference sentences for conservative target-span alignment.
  Remove the observed suffix from `CURRENT_INPUT` before using left context;
  never feed reference text into inference.

Extraction is defined by `scripts/audit_candidate_pipeline.py::mobile_cases`:
seed `occam-pipeline-20261020-v1`, default `reserved=False`, at most eight events
per prompt, deterministic ordering, 6,000 events plus 6,000 source-copy controls.
The separate hash-reserved prompt partition is not part of this evaluation.
`scripts/align_mobile_targets.py::reference_target` excludes unmatched/ambiguous
spans and resolves uniquely aligned multiword targets. Controls retain their
original labels. Alignment retains 9,728 cases across all partitions.

Partition with `scripts/iterate_english_policy.py::split`, seed
`english-iteration-20261020-v1`: hash modulo 10 gives TRAIN 0–5, selection 6–7,
evaluation 8–9. The grouping key is the sentence/prompt ID, **not a participant**.
Within TRAIN, `hashed("calibration:" + group)` modulo 5 equal to zero selects
cutoff calibration; the remainder fits coefficients.

The fixed **evaluation partition has 2,042 cases: 744 typos and 1,298
preservation controls**. Missing candidates and abstentions remain in the
denominator. Do not evaluate only examples whose gold target is in the vocabulary.

Identity checks (SHA-256):

| Input | SHA-256 |
|---|---|
| Full local `cases.jsonl`, including prefix examples | `5f51fa164e2bf2b59080fd44affb20b8340557a498b822e6423d3b20f1d8f958` |
| `ac_words_en.csv` | `bbfff38259647e652f350c0c6821029c958adafbe245ece60e888953bcb01cc2` |
| `sentences.csv` | `506741069ee7f2b0238f8e9f4035677d762b6aa119641396af3d1db2dc059d2a` |
| Sorted evaluation IDs, joined by newline with trailing newline | `2a6361cda8ee35c00bfed060b409561d4abe6f863597df8eb029edea18271e5c` |
| Sorted `(id, aligned_target)` pairs, compact UTF-8 JSON, no trailing newline | `ab9432f658fd7be9647f6e782d820ed75ac781266a5dba090152c746bb6ac1a3` |

Local cases/cache: `build/english-iteration-2026-10-20/` (ignored build output).
The committed report records their hashes. Preserve this frozen baseline cache:
it contains pre-change scores; rebuilding it with the current scorer and then
adding back the old completion penalty would invalidate comparisons.

This is development evaluation with historical exposure, not a fresh independent
test. Prompt-disjoint splits are not participant-disjoint, and source-copy controls
are not a complete clean-typing dataset. Do not open protected LM confirmation,
test or reserve files for this work.

## F₀.₅ definition and accepted baseline

TP = correct automatic replacements. FP = all wrong automatic replacements,
including changes to clean controls. FN = typo cases minus TP. A wrong replacement
on a typo contributes both FP and FN. Abstaining on a typo contributes FN.

```text
precision = TP / (TP + FP)
recall    = TP / (TP + FN)
F₀.₅      = 1.25 TP / (1.25 TP + FP + 0.25 FN)
```

Use zero for an empty denominator, as in the evaluator.

| Rule | TP | FP | FN | Precision | Recall | F₀.₅ |
|---|---:|---:|---:|---:|---:|---:|
| Original heuristic | 399 | 112 | 345 | 78.08% | 53.63% | 0.71557 |
| Earlier precision-focused rule | 224 | 12 | 520 | 94.92% | 30.11% | 0.66351 |
| Two-feature F₀.₅ rule | 336 | 28 | 408 | 92.31% | 45.16% | 0.76364 |
| Literal-aware word rule | 361 | 23 | 383 | 94.01% | 48.52% | 0.79167 |
| **Current word + apostrophe restoration** | **364** | **23** | **380** | **94.06%** | **48.92%** | **0.79407** |

Current pipeline changes 5/1,298 preservation controls. Its compiled native decisions
match the frozen rule on all 9,728 aligned cases. Model SHA-256:
`915bc80d53d225469e8bfe90d328ac1565647181e485cb3f0e9c32f47c946322`.
Policy SHA-256:
`d97608cbbbd36bc257858760c3e70ed38269efae9efb9c16e0a236fe73947ffd`.

The word rule retains that policy hash. Current restoration policy SHA-256:
`0de96ace3507ddb0aeb9944b0ece3cd389aa434f2bc87640bd9044be4cf25d96`;
TRAIN surface/count data SHA-256:
`610b016b874e823de73b41a1c9fb62705e7af15a463f6e3a5542e3102cdbc9a8`.
Current [restoration report](APOSTROPHE_RESTORATION.md) records the frozen
[evaluation](config/english_iteration/2026-10-21/apostrophe-evaluation.json).
Restoration gains three evaluation fixes with no additional wrong changes, while
selection F₀.₅ declines from 0.80452 to 0.80185. Retain both results.

The current [error analysis](AUTOCORRECT_ERROR_ANALYSIS.md) attributes missed
fixes to generation, ranking and acceptance, with fixed diagnostic ablations.

Source reports: [frozen selection](config/english_iteration/2026-10-20/literal-selection.json),
[evaluation](config/english_iteration/2026-10-20/literal-evaluation.json),
and [native parity](config/english_iteration/2026-10-20/literal-native-verification.json).
See [the literal-aware iteration](LITERAL_ACCEPTANCE_ITERATION.md) for behavior,
latency and remaining weaknesses. Older dated reports retain previous baselines.
Update this accepted baseline and its hashes when a new improvement is accepted;
retain dated previous reports.

## Latency evaluation

The product metric is **input event to first displayed, selectable candidates**.
Measure cold initialization separately from warm typing. Keep first-preview and
full-ranking timestamps separate; include worker contention and rendering in
device measurements. Report device/build, model/library hashes, sample count,
contexts, input sequence and median/p95. Include rapid typing, cancellation and
an occupied English worker so engine speed does not conceal UI waiting.

Repeatable host panel: `scripts/benchmark_quick_short_codes.py`, both Quick
versions, codes `d`, `df`, `of`, `hi`, `ab`, `zz`, `DF`, and contexts
`("", "")`, `("I use ", "")`, `("我想", "")`, `("你", "好")`.
Default two alternating process runs produce 112 paired comparisons. Require
identical full candidate lists and identical preview candidate sets.

Recorded release-host `df` baseline: full ranking median **159.1 ms**, p95
**212.7 ms**; preview median **0.72 ms**, p95 **0.75 ms**. These engine-call
numbers exclude startup, Android scheduling/editor calls and rendering. **Phone
input-to-display latency remains unmeasured.** Do not compare host numbers with
phone numbers as if they measured the same boundary. See
[QUICK_CANDIDATE_LATENCY.md](QUICK_CANDIDATE_LATENCY.md).

For the current literal-aware acceptance iteration, the paired English engine
panel measures 2.759 → 2.775 ms median and 5.623 → 5.616 ms p95 (1,024 calls per
binary); current `df` preview is 0.712 ms median / 0.739 ms p95. See
[LITERAL_ACCEPTANCE_ITERATION.md](LITERAL_ACCEPTANCE_ITERATION.md) and its reports.

Real-context candidate panels must come from SSD development data:
`/Volumes/SSD/cantonese_ngram/experiments/keyboard-eval-v13-final/development.jsonl`.
Keep selected IDs/document groups fixed across comparisons. Prefix development
data is `data/shared-multilingual-v1-stage/eval/finalist_suite.jsonl` under the same
SSD root; top-1/top-8 retrieval metrics supplement correction F₀.₅.

## Commands

```sh
python3 scripts/fit_english_acceptance.py --iteration build/english-iteration-2026-10-20 --output build/new-policy-fit.json
python3 scripts/verify_english_acceptance.py --iteration build/english-iteration-2026-10-20 --probe /path/to/current/ngram_probe --output build/new-policy-native.json
python3 scripts/benchmark_quick_short_codes.py /path/to/before/ngram_probe /path/to/after/ngram_probe --output build/quick-latency.json
bash scripts/test_english_autocorrect.sh
./gradlew :app:verifyKeyboard :app:compileDebugAndroidTestKotlin
```

The fitter writes an experimental report; it does not install coefficients.
For the current literal-aware family, use `scripts/iterate_literal_acceptance.py`
with `--baseline-policy config/english_iteration/2026-10-20/acceptance-policy-v1.json`.
First write a selection report; reveal evaluation only with `--evaluate` and
`--frozen-selection` pointing to that report. This fitter requires NumPy.
After accepting a fit, update the native policy, rebuild host/ARM64 libraries,
verify native parity and the packaged APK, and retain the before/after reports.

## Quick runtime contention regression (2026-10-21)

[Quick autocorrect contention](QUICK_AUTOCORRECT_CONTENTION.md) isolates inference
state while retaining shared weights and the accepted policy. Host English JNI
latency during Chinese `df` ranking improves from 277.55/301.12 ms median/p95 to
1.17/1.37 ms (32 calls per binary). This is separate from phone input-to-display
latency. Frozen F₀.₅ stays 0.79167; native policy decisions retain parity.

## Apostrophe restoration latency (2026-10-21)

The current pipeline restores TRAIN-attested missing apostrophes through the
existing CNN byte fallback. General host median/p95 changes from 2.878/5.865 ms
to 2.917/5.945 ms; the TRAIN apostrophe panel rises from 1.483/3.826 ms to
5.705/9.572 ms. This is an explicit latency cost for the F₀.₅ improvement.
See [method, quality and limitations](APOSTROPHE_RESTORATION.md). Phone latency
remains unmeasured.
