# Evaluation sensitivity audit

The current evaluations can detect large effects and enforce coarse context
retention. They do not establish the superiority of the third residual block.
The model remains deployed as v12; this read-only audit changes no training,
selection, calibration, assets or frozen test. The earlier inference that a
0.22pp improvement in worst test regression proves the block is worthwhile
was too confident; that was a point estimate, not a paired uncertainty result.

## What was checked

Recomputed all seven modes for v7 and actual row-INT8 reconstructed v11/v12 on
1000deterministically sampled validation rows from each of five sources. Model
checkpoint hashes are distinct; original vocabulary prefixes match exactly.
Document/group IDs were reconstructed from the original text, with sampled
positions/targets and full shard lengths checked. Aggregates reproduce both
saved frozen audits with maximum error below0.000001. Teacher-bank checks find
4988unique eligible inputs, no BOS/UNK context IDs, target probability sums
within4.5e−8of one and entropy reconstruction within9.2e−7. No stale-model,
ID-remapping, normalization or aggregate-calculation bug was found by these checks.
This is not proof that every data/runtime path is correct.

A negative control shuffles the three-block model's predictions across examples:
next-word top3falls30.96%→3.58%, infill45.76%→2.10%. The evaluator is responsive
to prediction changes. Three- versus two-block next-word top3sets differ on
1157of5000examples (23.14%), but only32new correct hits and39lost correct hits
change correctness. Many altered predictions remain wrong, or both include
the correct target; a similar aggregate score is not identical model output.

## Paired uncertainty

3000paired bootstrap replicates resample original document groups within each
source, then take the source-macro average. Intervals describe uncertainty from
this reused development sample. They do not include training-seed variance,
production-domain uncertainty, or correction for comparing multiple modes.

| Context mode | Three minus two top3 (pp) | Nominal95% interval (pp) | Correctness gains / losses |
|---|---:|---:|---:|
|One-token context|+0.00|[-0.31, +0.29]|31 / 31|
|Two-token context|+0.12|[-0.21, +0.46]|39 / 33|
|Four-token context|+0.02|[-0.28, +0.32]|31 / 30|
|Next-word (8 left)|-0.14|[-0.48, +0.18]|32 / 39|
|Infill (2 left / 2 right)|+0.14|[-0.26, +0.54]|56 / 49|
|Missing (8 left / 4 right)|-0.06|[-0.51, +0.36]|65 / 68|

Every contextual interval includes zero. The development sample does not
resolve an advantage for either depth. A strict −1pp checkpoint gate can also
flip near its boundary because the sample is finite; passing more short-run
guards is not by itself evidence of superior architecture. The original
256rows/source pilot subset had still less precision. These results audit
validation only and do not attach confidence intervals to the separately
reported frozen-test point estimates.

## Coverage and interpretation gaps

Of5000targets,4370are characters (87.4%),472punctuation (9.44%),88English
(1.76%) and70other (1.4%). There are no word-category targets in this character
benchmark. Every target is from v7's original vocabulary; new dictionary and
handwriting-label target coverage is exactly zero. Thus this sample cannot
measure gains on newly supported tokens and provides little English evidence.
It reports full-vocabulary top3 rather than Quick code-constrained candidate
ranking. The separate handwriting audit reuses only500unique TRAIN strokes
across six contexts and has no paired uncertainty analysis; it is not a fresh
production evaluation. Fixed sampled rows and public lexical/test reuse further
limit broad claims about keyboard gains.

The complete4988-context count diagnostic has no row-sampling noise: it evaluates
the whole declared training/lexical teacher bank. Small differences there are
real for that bank and precision, but do not establish held-out typing gains or
robustness across training seeds. Top1/top3measure argmax ordering, not equality
of conditional probabilities; probability-fit trends can improve with little
change in these accuracies. Count and context metrics must not be conflated.

## Consequence

Do not rank small architecture differences from aggregate point scores alone.
Retain the existing benchmark as a control, report paired intervals and target
slices, expand development coverage of English/rare/new targets, and evaluate
actual Quick/handwriting confusion candidates. A promising architecture needs
repeat seeds or another protected confirmation cohort before claiming a small
advantage. More training or more parameters is not justified by this audit alone.
No additional model training is queued.

Canonical audit: external SSD
`/Volumes/SSD/cantonese_ngram/experiments/eval-sensitivity-v12/audit.json`.
Tool: `../dataset/cantonese-ngram/audit_eval_sensitivity.py`.
Chart: `config/language_model/v12_eval_uncertainty.png`.

## Available quantity and text-quality audit

The backing corpus is substantially larger than the scored samples:

| Split | Documents | Supported target rows available | Target rows scored per mode |
|---|---:|---:|---:|
|Validation|14,266|2,944,015|5,000|
|Test|14,411|3,024,445|10,000|

Only0.17%of available validation targets and0.33%of available test targets were
scored in these reports. Original early pilots used1280rows, even fewer. These
are token positions, not independent examples: the seven modes reuse the same
positions and documents, and the latest5000validation targets span4015document
groups. The stored dataset verification checks shard integrity and exact
hash/duplicate-group split isolation; that does not establish exhaustive semantic
near-duplicate exclusion or freshness after downstream test reuse.

A read-only application of the existing training hygiene filter to all validation
and test text flags585/14266validation documents (4.10%) and621/14411test
documents (4.31%). Validation flag fractions by source: wiki2.65%, LIHKG25.74%,
OpenRice1.79%, CommonVoice0%, CommonCrawl0%. LIHKG flags are mostly low Han ratio
(264documents) and excessive links (159); wiki also has duplicate lines and
residual markup. These are mechanical signals, not a human linguistic-quality
grade. Low-Han documents may contain useful English/code switching and should
not be automatically rejected from a bilingual keyboard evaluation. CommonVoice
and CommonCrawl having zero flags does not certify linguistic/domain quality.
Another228validation and253test documents would be changed by that cleaner,
but cleaner normalization is not automatically evidence of bad labels.

The amount available is sufficient to build a stronger coarse benchmark; the
amount actually scored, target/domain mix, repeated data use and lack of paired
uncertainty in the original reports limit tiny-gain claims. More rows of the same
biased supported-vocabulary sample will not evaluate new tokens or ensure enough
English. A revised development protocol should use substantially more sampled
document groups/targets, explicitly cover English/rare/added tokens, preserve a
natural-use slice, and score actual keyboard candidate-confusion tasks. Review
hygiene-flagged documents by source, preserve original metrics as controls, and
freeze any new clean confirmation set before comparing models. No eval documents
were filtered, split or overwritten by this audit.

Receipt: `config/language_model/v12_eval_quality_amount.json`.
