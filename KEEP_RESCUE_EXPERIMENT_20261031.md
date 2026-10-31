# Bidirectional correction / KEEP experiment

**Rejected and withdrawn.** The accepted decoder remains **0.8939210767471755**,
with no production score or latency change. The experimental decoder reached
**0.9032455049082594** (+0.00932442816108392) on the frozen development objective,
but failed the predeclared fresh natural-chat guard. Native source, manifest,
ARM64 library, APK and original regression scenarios were restored and verified.

## Hypothesis and implementation

The accepted typing channel can introduce a confident replacement but cannot
veto a bad legacy correction. Its earlier symmetric KEEP threshold was dominated
by missing-space examples outside the single-word channel's hypothesis space.
Restricting the comparison to representable ordinary words reduced the cutoff
from 27.25 to 5.55, but produced no additional development improvement.

The next candidate used a small CPU-trained boosted-tree classifier for two
actions: reject an accepted correction, or rescue the best declined channel
proposal. Its inputs included letter-specific edit likelihoods, literal and
candidate unigram priors, contextual gains, case/vocabulary evidence, original
candidate scores and competing channel scores. It added no word-specific rule.
The reported `tue` and `ir` examples were excluded from new fitting.

Training used 41,471 isolated rows, including 20,000 public-chat TRAIN copy
controls: 30,812 fit and 10,659 calibration. Source conversation groups and exact
inputs from every evaluation fold were excluded. Bare contraction forms were
quarantined from new chat copy supervision. Copy labels are noisy preservation
supervision, not independently confirmed typing intent.

Four configurations varied tree size and vocabulary-case features. TRAIN-only
calibration selected 300 trees, at most 15 leaves each, with 52 numeric features.
Its two thresholds were selected under source-family wrong/clean budgets and
correction-retention constraints. All training ran on the Mac mini CPU, using
locally installed scikit-learn 1.7.2. No remote hardware or additional LM was used.

The first native port exposed floating-point threshold discrepancies. Features
were quantized to six decimals, threshold choices moved between score groups,
and JSON double parsing used round-trip precision. The complete TRAIN-only
procedure was rerun. Native features and probabilities then matched Python
exactly over 8,756 development/regression cases, before opening confirmation.

## Stronger chat evaluation

The new cohort uses only admitted public PhotoChat selection text. Excluding
every conversation with any TRAIN origin removed 2,849 of 3,388 selection
origins. Direct current-CNN TRAIN fingerprints and all previous fitting/eval
inputs were also excluded. The remaining 45 conversations contain 522 unique
utterances; conversation groups and exact inputs stay disjoint across folds.

The first source-copy labels mistakenly treated valid dictionary strings in
obvious typo contexts as clean, including `ike` in `do you ike` and bare `Ive`.
After initial baseline development inspection, all 522 utterances were manually
reviewed in conversation order. Unambiguous spelling/punctuation and real-word
errors were labelled; uncertain edits were quarantined. This review happened
before candidate inference and before any confirmation output. The initial
cohort remains archived; version 2 was frozen before the learned experiment.
This is explicitly documented label repair, not an untouched baseline study.

| Panel | Development | Confirmation |
| --- | ---: | ---: |
| Reviewed source preservation | 1,293 | 1,196 |
| Naturally observed spelling errors | 14 | 15 |
| Separately reported synthetic typos | 765 | 710 |

Natural labels infer intent from public source text; they are not author-supplied
intent or recorded taps. Synthetic errors reuse TRAIN phone-error operations and
do not estimate natural error prevalence. The natural panel is small, and complete
inherited-model exposure remains unproven. Full private bilingual chat streams
are still missing.

## Results and the rejection

The frozen development objective improved as follows:

| Panel | Correct fixes before → after | Wrong corrections before → after | Clean damage before → after |
| --- | ---: | ---: | ---: |
| Aalto | 520 → 518 | 40 → 34 | 7 → 6 |
| Phone touch | 369 → 379 | 13 → 13 | 1 → 2 |
| Authored hard | 31 → 33 | 0 → 0 | 0 → 0 |

This is ten additional correct fixes and six fewer wrong corrections overall,
with unchanged total clean damage but a worse phone clean slice. Supplemental
English author-edit development improved 467 → 479 / 512 fixes and 15 → 10 wrong
corrections. Reviewed chat development retained natural-error recall, eliminated
its remaining clean miscorrection, and reduced synthetic wrong corrections
15 → 4. These exposed development gains did not justify acceptance on their own.

The frozen confirmation rule required every chat panel to retain baseline TP,
FP and clean-word budgets, with at least one improved count. It was opened once,
after 40 native tests, actual JNI scenarios across English/Quick3/Quick5, undo,
the inspected screenshot and the complete keyboard gate had passed.

| Fresh confirmation panel | Correct before → after | Wrong before → after |
| --- | ---: | ---: |
| Natural spelling, 15 errors | 9 → 9 | **1 → 2** |
| Preservation, 1,196 controls | — | 2 → 2 |
| Synthetic typos, 710 errors | 540 → 544 | 17 → 12 |

The candidate newly accepted `sccissor → successor` where the source compound
was `scissor-tailed`. It preserved the valid plural `goliaths`, but also newly
changed a valid `he` to `the`. The natural-error FP budget failed, so the
candidate was withdrawn without tuning on this confirmation. Native word probes
do not establish what a physical keyboard would commit at a hyphen boundary.

Closed-loop phone replay also mixed gains with harm: 394 → 402 fixes and
340 → 347 exact messages, but wrong corrections increased 10 → 12 and one
previously clean word was damaged. Authored controller exact-session counts
were unchanged. These reinforce the need to retain separate precision and
preservation checks.

## Root cause and next useful experiment

The correct `scissor` proposal was already generated and had a cheaper edit
path. Its unigram floor was one and it had no whole-word CNN token. `successor`
had count 43 and positive contextual LM gain:

| Evidence | scissor | successor |
| --- | ---: | ---: |
| Learned edit cost, lower is better | 7.443 | 10.066 |
| TRAIN count / pseudocount | 1 | 43 |
| Contextual gain | 0 | 2.086 |
| Combined channel score, higher is better | −7.443 | −4.218 |

The classifier assigned the wrong winner a score of 0.908. Thus generation was
adequate for this failure; frequency-biased ranking and overconfident acceptance
were not. A binary gate on a selected winner did not reliably recognize a
stronger spelling alternative, especially when that alternative lacked an LM
word token. The independent `he → the` regression also confirms the danger of
rescuing valid-word inputs without stronger evidence.

The next high-value test is to compare the proposed winner explicitly with the
strongest edit-likelihood alternative and its LM coverage, rather than adding
another acceptance threshold. Fit any frequency tempering and ambiguity features
on TRAIN, and retain all currently exposed chat cases as development only. A new
source-disjoint confirmation is required for another tuned candidate. No exception
for `scissor`, `tue`, or any other example should be added.

## Verification and retained artifacts

The withdrawn model added about 1.7 MB of embedded tree data. On fixed Mac native
inputs, median/p95 prediction latency was approximately 2.31/6.61 → 2.44/6.80 ms;
this is not Android latency. The final accepted implementation has no added cost.

The accepted checkpoint was restored byte for byte, the full keyboard gate was
rerun successfully, and the rebuilt local APK was checked against its restored
native library and assets. Experimental tests and screenshot changes were removed;
their patches remain with the isolated candidate for reproducibility. Nothing was
deployed. The new evaluation, annotation audit, training and verification tools
remain available.

Reports: [outcome](config/english_iteration/2026-10-31/keep-rescue/outcome.json),
[development](config/english_iteration/2026-10-31/keep-rescue/development-report.json),
[confirmation](config/english_iteration/2026-10-31/keep-rescue/confirmation-report.json),
[rare-term evidence](config/english_iteration/2026-10-31/keep-rescue/rare-term-failure.json).
Bulk artifacts and withdrawn patches: `build/english-keep-20261031/`.
