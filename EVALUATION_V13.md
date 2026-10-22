# Repaired keyboard evaluation v13

The evaluation is now larger, reports vocabulary coverage, measures actual
keyboard candidate ranking, and includes paired group uncertainty. It reveals
meaningful unigram/bigram replacement gains that the old full-vocabulary score
largely missed. It does not establish a broad advantage from adding a third
residual block. The deployed v12model and all calibration weights are unchanged;
no model training or automatic architecture selection occurred.

## Data and quality

Canonical frozen text suite:
`/Volumes/SSD/cantonese_ngram/experiments/keyboard-eval-v13-complete`.
The original validation/test benchmark remains a control. Only original validation
text builds the new text suite. No text is rewritten. Low-Han English/mixed text
remains eligible. Empty/boilerplate, markup, excessive repetition/links and
duplicate lines are quarantined with reason counts; exact/near-group duplicates
are deduplicated. These mechanical checks are not a full human linguistic review.
Samples use deterministic min-hash priorities spanning whole documents, with
per-group caps preventing long documents from dominating. Original source files
and every frozen case/model/dictionary are hashed.

| Development panel | Positions | Document groups | Purpose |
|---|---:|---:|---|
|Source-balanced natural|50,000|8,960|Normal targets,10,000per source|
|English|5,000|2,239|English targets including OOV|
|Rare all-token|5,000|2,223|Original TRAIN frequency1..99|
|Rare Han|2,339|1,033|Chinese character tail, TRAIN frequency1..99|
|Newly supported|4,217|1,188|Targets absent from v7vocabulary|
|Current OOV|5,000|1,881|Explicit representational gap|
|Quick candidates|5,000|2,523|Actual two-letter Quick3/5dictionary ranking|

There are 67,268unique text positions;
panels overlap and must not be summed. Rare Han/new-token quotas were not filled
with fabricated examples or training data: only2339/4217eligible positions were
available under the declared sampling rules. Added targets cover180distinct
new IDs, not all4319added IDs. Only128of those have qualifying Quick codes.
An additional16794cases are frozen in document-disjoint confirmation groups
and remain unscored. These originate from historically reused validation;
reservation is prospective and cannot make prior exposure disappear.
The first supported-only v13preparations remain historical controls; the canonical
complete suite retains unknown targets rather than silently dropping them.

Natural scoring includes364current-OOV positions as misses; English includes
1031/5000OOV targets. Probability losses are labelled conditional on support,
with common-support CE separately available for fair comparisons. All modes
reuse the same positions; they are not independent extra examples. Target panels
retain observed source proportions for uncertainty: a two-row English source
never receives20%of the weight. Natural's equal10,000/source yields a balanced
macro/micro estimate. Reference vocabulary prefixes and runtime IDs are checked.

## Actual deployment-policy gains

Quick ranking runs the actual release Rust code against the frozen app dictionaries,
using a valid code that contains the intended target and at least two candidates.
Baseline v7uses its legacy count/NN policy; v11/v12use the deployed neural-only
policy. Standalone full-vocabulary text scores are separately labelled and never
substituted for actual Quick/handwriting candidate ranking.

| Metric | v7policy | Two-block v11 | Three-block v12 |
|---|---:|---:|---:|
|Quick3one-left top3|88.01%|92.65%|92.36%|
|Quick5one-left top3|89.34%|93.40%|93.16%|
|Rare Quick5one-left top3|9.17%|23.75%|24.05%|
|Quick5eight-left top3|94.32%|94.32%|94.46%|
|Handwriting one-left top1|69.00%|76.65%|76.65%|
|Handwriting two-left top1|76.10%|77.25%|77.20%|
|Handwriting empty top1|67.65%|67.55%|67.40%|

On the natural Quick panel, three versus two blocks changes one-left top3by
−0.293pp for Quick3(nominal95%interval−0.562to−0.027) and−0.240pp for Quick5
(interval−0.473to−0.018). Top1moves slightly in the opposite direction, while
one-left handwriting top1is identical. This is a small task-dependent tradeoff,
not evidence that the extra block broadly improves quality. Multiple comparisons
are exploratory; these nominal intervals are not family-wise corrected.

These numbers are from the new controlled cohort and are not comparable as
absolute scores with the earlier78%TRAIN handwriting audit. One-token gains
are clear; two versus three blocks remain close overall. A nominal0.4pp infill
handwriting gain exists in one condition, but multiple exploratory modes,
seed variance and source/domain uncertainty preclude declaring broad superiority.

## Weaknesses now visible

| Standalone next-token top3 | v7NN | v11NN | v12NN |
|---|---:|---:|---:|
|Natural|30.63%|30.03%|30.07%|
|English|1.74%|1.44%|1.46%|
|Rare Han|0.90%|0.77%|0.47%|
|Added tokens|0.00%|0.33%|0.33%|

Three-block minus two-block natural next-token top3is+0.046pp,
with a nominal95%paired group interval [-0.070, +0.163]pp. It remains
unresolved. English and added-token prediction are weak, even though the actual
Chinese candidate tasks improve.21%of English targets are outside the vocabulary.
The English panel primarily samples mixed-language web/review text and names,
not a representative pure-English production keyboard corpus. No vocabulary was
padded or increased to improve a benchmark score.

## Handwriting repair and remaining limits

Canonical stroke/context suite:
`/Volumes/SSD/cantonese_ngram/experiments/keyboard-eval-v13-handwriting`.
It uses2000unique trajectories from designated `test_datas.npy`, paired with2000
unique authentic validation document groups. Existing visual weights and all
fusion coefficients were frozen before scoring; none were fitted on this cohort.
Each of six context conditions reuses those2000strokes. Document-group and
stroke-source-group paired intervals are both reported, rather than treating
12000conditioned cases as12000independent strokes.9test-source groups are reserved.
The source groups are not independently verified physical writers, prior visual
validation/test usage was not audited, and pairing isolated strokes with text is
synthetic—not captured production input. Added-token stroke coverage is only10;
that panel is too small for conclusions. Truly fresh production strokes/text,
pure-English data and broad rare/new-label coverage still require new collection.

## Verification and future use

Eight evaluation regression checks pass: English retention, structural rejection,
whole-document reservoir selection, small-source weighting, unknown-target support,
context truncation, token boundaries and preservation of every Quick-task interval.
All16Rust tests pass. Export/checkpoint/tokenizer parity is checked on144contexts
per model (432total); max log-probability discrepancy is0.000225under a0.0005FP32
reduction tolerance. A shuffled-prediction control must degrade accuracy. Hashes,
strata, dictionary target inclusion and document partition disjointness all pass.
The current keyboard model SHA256remains
`01955e2824ee8597709f9061563e39e131a33327238a0609f56cb070a241858f`.

Use `../dataset/cantonese-ngram/EVALUATION_PROTOCOL_V13.json` as the canonical
pointer. Future comparisons must use this suite and paired intervals before
architecture/deployment conclusions. Legacy `evaluate.py` remains a coarse
training guard/control, not sufficient evidence for tiny architecture gains.
For future frozen candidates, provide a JSON model manifest mapping each model
name to checkpoint/export paths and optional legacy count path. Use a new SSD
result directory; checkpoint/export hashes freeze before scoring. Verify the
result directory with the runtime checker. Do not combine oversampled diagnostic
panels into an invented overall score.

```bash
python evaluate_keyboard_v13.py --data /Volumes/SSD/cantonese_ngram/experiments/keyboard-eval-v13-complete --models /path/to/frozen-models.json --out /Volumes/SSD/cantonese_ngram/experiments/new-eval-comparison
python verify_keyboard_eval_v13.py --data /Volumes/SSD/cantonese_ngram/experiments/keyboard-eval-v13-complete --results /Volumes/SSD/cantonese_ngram/experiments/new-eval-comparison
```

Confirmation requires a separate output directory and an explicit frozen-finalist
hash manifest via `--confirmation-finalists`. A completed confirmation run cannot
be overwritten. It was not invoked here. Keep every bulk case, trajectory and
prediction cache on the external SSD. Old text and handwriting controls are
preserved. No model training, recalibration, asset replacement or device install
is part of this evaluation repair.
