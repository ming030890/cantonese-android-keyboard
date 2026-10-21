# Existing edit-cost calibration (2026-10-21)

The accepted change tunes five constants in the existing native edit penalty.
Candidate generation, the six-feature acceptance rule and its cutoff, completion
veto, apostrophe policy, and model remain unchanged. No first-position term,
length table, edit-distance fallback, or runtime dictionary is added.

## Fit and frozen selection

Fit uses 1,473 recorded TRAIN-fit typo cases whose target is in the existing
candidate pool, minimizing conditional ranking loss with shrinkage toward the
previous costs. Missing candidates remain in quality evaluation denominators.
TRAIN calibration groups are separate. No synthetic corruption positions are
used to learn a human position prior.

Six cost models were tested: one shared multiplier, five operation costs, or
those five plus a nonnegative first-character surcharge, each with regularization
0.01 or 0.1. Each was compared with the existing acceptance cutoff or a cutoff
calibrated on TRAIN, giving 12 selection proposals. Selection requires better
real-typing F₀.₅ and no decline in primary unambiguous-neighbour F₀.₅. The highest
real selection F₀.₅ selects five operation costs, regularization 0.01, with the
existing cutoff. The [selection report](config/english_iteration/2026-10-21/edit-position-costs/selection.json)
was frozen before opening evaluation results.

| Existing cost | Before | Selected |
|---|---:|---:|
| Neighbouring-key replacement | 1.0000 | 0.5744 |
| Other replacement / foreign-character surcharge | 2.5000 | 3.7162 |
| Swap or alternation, per OSA edit | 1.0000 | 0.9147 |
| Extra observed character | 4.5000 | 4.5411 |
| Missing observed character | 4.5000 | 1.9126 |

The old length-change expression `1.5*edits + 3*length_difference + 3*(edits-1)`
is rewritten as the directional base plus `4.5*(edits-1)`; the generator already
bounds length difference to one. The extra same-length edit surcharge stays 3.
Existing swap/alternation handling remains an approximation of the edit path.

Selection real F₀.₅ improves 0.80821 → 0.82578. Primary unambiguous-neighbour
F₀.₅ improves 0.91741 → 0.93028; 4–5-letter fixes improve 24/72 → 32/72.
The matching fit with a first-character surcharge scores 0.82508 on real
selection, so the extra parameter is omitted. This small difference does not
establish that first-position information can never help.

TRAIN has 1,160 single-replacement events, including 346 first-letter errors
(29.8%; a uniform-position comparison would expect 303, or 26.1%). This is a
selected recorded autocorrection-event cohort, not an unbiased estimate of all
human typing errors. It does not support imposing the proposed first-letter
reliability prior without further evidence. Absolute word position is distinct
from keyboard-key distance, which the scorer already considers.

## Frozen evaluation

| Panel | Before correct / wrong | After correct / wrong | Before → after F₀.₅ |
|---|---:|---:|---:|
| Real typing: 744 typos + 1,298 controls | 397 / 33 | 418 / 32 | 0.80560 → 0.82154 |
| Unambiguous neighbour: 617 typos + 808 controls | 468 / 16 | 482 / 19 | 0.91657 → 0.91950 |
| Unambiguous neighbour, 4–5 letters: 63 typos | 19 / 0 | 22 / 2 | 0.68345 → 0.69182 |

Real precision improves 92.33% → 92.89%, recall 53.36% → 56.18%; unwanted clean
changes remain 8/1,298. Primary neighbour recall improves 75.85% → 78.12%, but
wrong corrections increase, including clean changes 12 → 13/808. Short-word
recall improves only 30.2% → 34.9%, with two new wrong corrections; substantial
short-word coverage remains unresolved. These rare held-out word groups are
harder than the earlier exposed common-word panels. Keep both results.

These are development evaluation groups with historical exposure, not an
independent confirmation dataset. No protected LM confirmation/test/reserve files
were opened. The [evaluation report](config/english_iteration/2026-10-21/edit-position-costs/evaluation.json)
contains input hashes and all selected parameters.

## Runtime verification

`scripts/verify_edit_costs.py` reruns the compiled engine over real events,
original corruptions, previously exposed new words, and held-out synthetic word
groups. It checks candidate-pool preservation and compares actual decisions with
the frozen float32 cost replay, including the effect on apostrophe evidence.

Controller characterizations now include `helo → hello`, `tyoes → types`,
`varely → barely`, and `engloaj → english`. `exmaole → example` remains selectable
but loses automatic acceptance in the empty-context regression example. These
examples were not used to fit or select costs. Existing correction undo and
literal-preservation tests remain required.

The exposed common-word 4–5-letter panel improves 73/88 → 77/88, with no wrong
fixes; the second exposed panel improves 108/181 → 125/181, also with no wrong
fixes. These exposed panels are regression checks, not the selection objective.

Compiled decisions match frozen predictions on **24,567** cases with zero
decision mismatches and identical full candidate pools. Actual ranking changes.
All four Rust spelling tests and all 18 real JNI/controller tests pass with no
skips in the JNI run. The [native report](config/english_iteration/2026-10-21/edit-position-costs/native-verification.json)
records code/binary/model hashes and the separate panels.

Paired release-host English timings on 1,024 calls per binary change median
**2.735 → 2.752 ms**, p95 **5.553 → 5.589 ms** (about +0.6% each). Four process
pairs alternate before/after order with 16 warmup requests each; this excludes
model loading and Android input-to-display latency. See the
[latency report](config/english_iteration/2026-10-21/edit-position-costs/latency.json).

The required `verifyKeyboard` gate passes: 218 unit cases (10 skipped in the
standard run; the separate actual-JNI run above covers all 18 JNI cases without
skips), 80 screenshot cases, debug APK build and Android test Kotlin compilation.
The [package report](config/english_iteration/2026-10-21/edit-position-costs/package-verification.json)
checks that the compiled ARM library equals the staged and merged libraries,
and that the stripped library equals the APK entry. The withdrawn dictionary
and license are absent. The APK has not been installed on a phone.
