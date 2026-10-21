# Mixed candidate ranking — 2026-10-21

The general problem was score comparison and candidate admission. The mixed
ranker forced Han candidates ahead of English regardless of the shared LM score,
and only the first eight Chinese phrases entered that comparison. Per-unit
probability alone also favored long, implausible Chinese strings. Removing BOS
does not fix those problems.

## Implementation

Use the existing shared CNN and one four-feature linear calibration: sequence
log probability, log probability per logical unit, Chinese internal association,
and English edit distance from the observed keys. Association is the sequence
log probability minus the sum of its characters' probabilities in the original
context. It reduces the advantage of unrelated high-frequency characters.
English key distance supports exact words, edits, and adjacent transpositions.
Generated Chinese candidates already match the Quick code. No word-specific
boosts, script-priority feature, extra language classifier, or extra model.

All generated phrases (up to 100) participate in mixed ranking. One-letter roots
retain the eight-item shortlist to bound the large root dictionary. Complete
two-letter codes retain the separately agreed Chinese-first product behavior
(QK-11). Chinese results remain selectable while mixed ranking runs. Beam search,
its pruning constants, and automatic-correction acceptance are unchanged.

## Fitting and frozen evaluation

`scripts/fit_mixed_ranking.py` samples 128 Cantonese phrases from SSD TRAIN
`data/shared-multilingual-v1-phrases/phrase-supervision.jsonl` and 128 English
contexts from `data/shared-multilingual-v2-repair/training.sqlite`, under
`/Volumes/SSD/cantonese_ngram/`. User examples `continue`, `兒歌`, and `自歌`
are excluded from calibration targets and tested separately as regressions.

Seed `mixed-ranking-20261021-v1`; group by phrase or source-document fingerprint.
Hash modulo 10 assigns TRAIN 0–5, selection 6–7, evaluation 8–9. Of 151 TRAIN
rows, 150 have a generated target and fit the four coefficients using listwise
softmax. Missing targets remain in reported metrics. Choose regularization from
0.001/0.01/0.1 on the 48 selection rows, freeze, then evaluate 57 rows. There is
no evaluation-driven retuning. This calibrates scores; it does not retrain the LM.

| Panel | Before top-1 | After top-1 | Before top-8 | After top-8 |
|---|---:|---:|---:|---:|
| Selection, 48 | 14 | 43 | 22 | 48 |
| Evaluation, 57 | 30 | 52 | 32 | 57 |
| Evaluation Chinese, 32 | 30 | 29 | 32 | 32 |
| Evaluation English, 25 | 0 | 23 | 0 | 25 |

Reports: [selection](config/english_iteration/2026-10-21/mixed-selection.json),
[evaluation](config/english_iteration/2026-10-21/mixed-evaluation.json),
[compiled parity and retrieval](config/english_iteration/2026-10-21/mixed-native-verification.json).
Compiled scores and order match all 256 frozen rows. Actual Quick candidate sets
remain identical on this cohort. Preserve `build/mixed-ranking-features.json`
(SHA-256 `1166a375f244134b06593820c5b7ce6a5469f37c87ecbe09a1a4c6233da9c9db`):
it retains the pre-change generation order needed for the baseline. Do not
rebuild it with the new ranker and call that the old baseline.

## Broader generalization checks

The fit panel's English rows are correctly spelled. To check beyond that narrow
distribution, freeze the weights and separately sample real SSD data using
`scripts/check_mixed_generalization.py`, seed
`mixed-generalization-20261021-v1`, one case per prompt/document per panel.
No fitting or threshold selection occurs in these checks.

| Panel | Cases | Before top-1 | After top-1 | Before top-8 | After top-8 |
|---|---:|---:|---:|---:|---:|
| Recorded Aalto typos | 128 | 1 | 86 | 2 | 105 |
| Aalto preservation controls | 128 | 0 | 115 | 4 | 122 |
| English within Chinese context | 64 | 2 | 46 | 3 | 56 |
| Contextual Chinese single codes | 64 | 52 | 52 | 63 | 63 |
| Contextual Chinese spans | 64 | 42 | 48 | 60 | 59 |
| Short English prefixes | 64 | 0 | 7 | 18 | 18 |

Mobile rows are conservatively aligned cases from the existing autocorrect
evaluation partition. Natural mixed text and Chinese contexts come from SSD
`experiments/keyboard-eval-v13-final/development.jsonl`. Chinese spans are two or
three contiguous Han characters from real text, not asserted to be segmented
words; inference receives only preceding text. Prefixes come from the selection
partition of `data/shared-multilingual-v1-stage/eval/finalist_suite.jsonl`; that
source contains only one/two-letter English prefixes. Two-letter behavior follows
QK-11, which limits their displayed English retrieval.

Reports retain input/cohort/model/policy hashes:
[384 general cases](config/english_iteration/2026-10-21/mixed-generalization.json),
[64 Chinese spans](config/english_iteration/2026-10-21/mixed-phrase-generalization.json),
[64 prefixes](config/english_iteration/2026-10-21/mixed-prefix-generalization.json).
Candidate sets remain unchanged. Generation misses stay in the denominator.
These are development checks with possible historical exposure, not an
independent production test. No protected LM test/reserve data was opened.

The Chinese regressions are real: one fewer top-1 result in the initial holdout
and one fewer top-8 result on contextual spans. Do not hide them behind English
gains. No further coefficient adjustment was made after these results.

## BOS ablation

`scripts/ablate_bos.py` compares the same frozen weights, candidate sets, and
acceptance policies. No-BOS omits the prepended token; an empty context uses the
output-bias prior. This is an inference mismatch with BOS-trained weights, not
a comparison with a model trained without BOS.

| Metric | BOS | No BOS |
|---|---:|---:|
| Autocorrect evaluation F₀.₅ | 0.79407 | 0.78704 |
| Correct replacements | 364 | 357 |
| Wrong replacements | 23 | 24 |
| Changes to clean controls | 5 | 6 |
| Mixed evaluation top-1 | 52/57 | 49/57 |
| Chinese top-1 | 29/32 | 26/32 |
| English top-1 | 23/25 | 23/25 |

No-BOS does make raw `兒歌` probability exceed `自歌`, but it loses overall and
does not make `兒歌` first. Keep BOS in production. The diagnostic switch is
available only through the probe; production construction enables BOS.
[Full ablation](config/english_iteration/2026-10-21/bos-ablation.json).

## Latency and automatic correction

Host timings exclude Android scheduling, rendering, and cold model loading.
On the 256-row SSD panel, Quick full-search median/p95 is 108.37/889.70 ms before
and 110.73/896.77 ms after. Full-pool mixed scoring takes 27.87/163.59 ms. The
same current scorer with the old shortlist takes 17.87/91.84 ms in a separate
pass: admitting more candidates has a measurable full-ranking cost. This pool
comparison is not a paired old-binary benchmark or phone latency measurement.

The alternating short-code panel preserves all 112 candidate lists and preview
sets. `df` full ranking median/p95 is 154.79/206.59 → 154.59/203.65 ms, with
preview 0.713/0.751 ms. These are earlier selectable candidates, separate from
full mixed ordering. [Short-code report](config/english_iteration/2026-10-21/mixed-short-code-latency.json).

Automatic correction retains F₀.₅ **0.79407**, 364 correct and 23 wrong
replacements. Native decisions retain parity on all 9,728 aligned cases.
[Native verification](config/english_iteration/2026-10-21/mixed-autocorrect-verification.json).

## Runtime checks and remaining weakness

Actual JNI/controller regressions exercise both Quick versions: `continue`
ranks first and commits on Space; `humo` includes `兒歌` in the first eight,
above `自歌`, and tapping commits it. An independent fake-boundary test verifies
observed keys reach the ranker and every generated phrase beyond the old eight
participates. Screenshots show English first and `兒歌` before `自歌` on the
second candidate page. They use fixed model responses; JNI tests verify inference.

`兒歌` is still fifth behind implausible strings. The model's phrase probabilities
remain weak. This calibration is a general ranking improvement, not proof that
the shared LM has learned sufficient Chinese phrase quality. Future iterations
must use broader TRAIN data and newly frozen evaluation groups, retaining
latency, F₀.₅, and separate Chinese/English retrieval results.

Host native tests, Android keyboard regression/screenshot/build checks, and
rebuilt ARM64 package verification pass. This change is not yet published to
Play; phone input-to-display latency remains unmeasured.
