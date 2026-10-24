# Gboard-supported autocorrection training — 2026-10-24

The integrated update trains the existing automatic-replacement network with new
Gboard-supported spelling examples and adds dictionary validity evidence. It
improves the freshly rebuilt starting implementation on the unchanged 2,042-case
mobile development evaluation. The shared language-model weights, candidate
ranking, apostrophe restoration and candidate search remain unchanged.

| Fixed mobile evaluation | Starting FFN | Integrated FFN |
|---|---:|---:|
| Correct replacements | 502 | 504 |
| Wrong replacements | 48 | 37 |
| Clean controls changed / 1,298 | 8 | 6 |
| Precision | 91.27% | 93.16% |
| Recall | 67.47% | 67.74% |
| F₀.₅ | 0.85258 | **0.86657** |

These before values come from a fresh build of the starting source and current
shared model. Earlier reports retain their dated rules, coefficients and caches.
The cohort IDs and aligned targets match the canonical protocol hashes exactly.
All missing targets and abstentions remain in the denominator. The weights and
cutoff were frozen before this evaluation; no evaluation labels chose a parameter.

## Training and dictionary

The acceptance FFN now has 22 inputs, 24 ReLU hidden units and one output. The
original 20 features retain their exact native float32/f64 definitions. Two new
features identify whether the input and proposed word occur in the pinned
SCOWL-derived US spelling vocabulary. Its 121,182 sorted ASCII forms include
source-affix forms. It supplies learned evidence, without a blanket known-word
veto or a new replacement-candidate dictionary. Source revision, derived hashes
and all upstream notices are retained; the notice is also included in the APK.
The dictionary contributes 1,173,178 bytes to the native library's prediction data.

The fit has 17,897 eligible rows: 4,547 real TRAIN examples, 11,285 generated
TRAIN examples, 804 empty-context controls and 1,261 new Gboard-supported examples
(593 word queries and 668 contextual queries). Existing real rows remain intact;
repeated editor inputs from added replay/new data are removed. Independent source
and artifact groups define fit/calibration/development. The threshold is calibrated
on 1,323 real/new TRAIN-calibration rows; model selection uses the fixed 1,949-case
real selection partition. The separate new development panel has 94 examples.

Warm-start and fresh models, learning rates, new-data weights and epoch counts
were compared using fit/calibration/selection only. Fresh training's strongest
selection candidate failed the existing literal-preservation regression and was
rejected. The accepted warm-start uses learning rate 0.0001, relative new-data
weight 0.25 and 60 epochs, with replay-logit anchoring. A single global cutoff is
used; no word-length rule or per-example exception is introduced. Wider length
bounds and a two-edit short-word radius lost selection F₀.₅ and were rejected.

The Gboard data provides **spelling suggestions**, obtained through its Android
spell-check service. It contains no touch positions, keyboard autocorrection
actions or teacher probabilities. Independent synthetic intended targets supply
labels; Gboard candidate/dictionary agreement supplies supporting evidence.
Improvements combine the new examples, replay/refitting and dictionary features;
this experiment does not isolate the causal contribution of each component.

## Short words and larger distances

On the fixed real evaluation, observed input lengths 2–3 improve F₀.₅
0.86227 → 0.87321: correct fixes fall 149 → 146 while wrong fixes fall 13 → 9.
For OSA edit distance at least two, F₀.₅ improves 0.58422 → 0.59468, with correct
fixes 77 → 76 and wrong fixes 30 → 26. Thus quality improves through fewer wrong
changes, while recall in these slices decreases slightly. Lengths 4–5 improve
F₀.₅ 0.83799 → 0.85238.

The historical primary unambiguous-neighbour panel (617 typos + 808 controls)
improves F₀.₅ 0.93477 → 0.95628, correct fixes 493 → 525 and wrong fixes 12 → 7.
Its 63 short 4–5-letter typos improve correct fixes 23 → 27 with no wrong fixes.
Twelve target word types overlap the new teacher fit, so this historical panel
is not a fresh type-disjoint confirmation. No held-out cohort was removed or
changed after observing outcomes.

New-data development improves correct fixes 29 → 44 / 65 typos, with wrong fixes
1 → 0, and F₀.₅ 0.78378 → 0.91286. This small, synthetic, teacher-agreement subset
is a development diagnostic rather than a natural typing accuracy estimate.

Generation misses remain: 29/90 generation misses in the selected contextual
workload have targets outside the model's English vocabulary. Dictionary evidence
and acceptance training cannot introduce those missing output words.

## Verification and latency

Python/native decisions match on 11,261 real/new cases with zero mismatches and
identical candidate lists. Thirty native unit tests pass. The required Android
gate passes 243 unit/scenario cases with zero skips, including all 19 actual JNI
controller tests, screenshot verification, debug APK assembly and Android-test
Kotlin compilation. A new real-JNI `qtab → tab` regression checks Space/Undo and
rejection persistence on English and both Quick layouts. Its separate screenshot
fixture was rendered and inspected before verification. The existing `exmaole`
characterization now expects the improved `example` automatic decision; other
proper-name and ambiguous-word characterization outputs retain their prior values.

On 1,024 paired warm release-host calls per binary, median changes
2.5435 → 2.5360 ms and p95 4.698 → 4.721 ms. The first internal suggestion call
changes about 2.33 → 3.90 ms, including lazy dictionary initialization. These are
engine timings, excluding model load, Android scheduling and rendering; phone
input-to-display latency remains unmeasured.

The ARM64 JNI library was rebuilt using the canonical deploy toolchain and
packaged in the debug APK. Verification checks the exact embedded policy and
dictionary, packaged notices, unchanged model hash and stripped APK library
hashes. The app's broader experimental-model qualification remains unchanged.
No physical-phone installation or Play publication was performed.

Aggregate reports, frozen selection, cohort identities and resource provenance:
[`config/english_iteration/2026-10-24/gboard/`](config/english_iteration/2026-10-24/gboard/).
Raw datasets, source snapshots, training scripts, predictions and reproducible
experiment artifacts remain in the workspace sibling `../gboard-probe/`;
`improve-v3/` contains the acceptance experiment and `diverse-v2/` the contextual
teacher data. The selected policy is compiled from the sibling native repository.
