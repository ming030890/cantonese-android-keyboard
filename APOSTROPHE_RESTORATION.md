# Apostrophe restoration — 2026-10-21

`dont` now offers `don't` and corrects to it on Space in English and Quick.
The reported sentence regression requires
`when i tyoe i don't really know what it meant ` on Quick3 and Quick5, both with
ready suggestions and with lookup pending at Space. Backspace restores `dont`;
recommitting after undo honors the user's rejection.

## Why this was missing

The shared CNN has a `dont` word token but no `don't` word token. Its byte fallback
can score the latter, but the old word-vocabulary-only search never generated it.
No confidence threshold change could correct that generation gap.

The engine now generates exact missing-apostrophe forms attested on TRAIN. It
scores the complete byte sequence with the existing shared CNN, using the current
left context. A small fitted punctuation decision compares full-sequence score
against the literal and the best ordinary word-edit candidate, plus TRAIN evidence
for apostrophized versus bare spellings. The existing word decision remains intact
when restoration abstains. Accepted restoration is displayed first among alternatives.

Word-token scores and byte averages have different units. An initial experiment
using a byte average with the word acceptance rule introduced `were` → `we're`
errors and lowered selection F₀.₅ to 0.79825; that path was removed. Current code
uses full byte-sequence log probability and a separately calibrated decision.
There are no new neural weights, model tokens or handwritten contraction rules.
Forms/counts and three fitted features are compiled into the same native engine.

## Data and calibration

- Source is SSD Aalto `sentences.csv`, restricted to 702 unique prompt groups
  in the existing TRAIN partition. Neither selection nor evaluation contributes
  surface spellings, counts, fit rows or calibration labels.
- `scripts/build_apostrophe_forms.py` exports 23 apostrophe forms whose bare
  spellings already exist in the shared vocabulary. It also counts bare spellings
  so common words such as `were`, `well`, and `its` provide ambiguity evidence.
- Training combines original TRAIN observed/control cases with synthetic missing
  apostrophes and clean words from those TRAIN prompts: 112 fit rows and 20
  calibration rows, separated by the existing prompt-group calibration hash.
  Synthetic contexts are the preceding TRAIN reference text; actual frozen
  evaluation continues to use observed left context, with no reference suffix.
- `scripts/fit_apostrophe_acceptance.py` fits a three-feature logistic decision.
  Coefficients, regularization and threshold are chosen using TRAIN fit/calibration
  only. The observed user sentence is a regression, not a training/evaluation row.
- Selection was recorded and frozen before evaluation. Reports retain both results;
  this developmental cohort has historical exposure and is not a fresh test.

## Frozen quality result

| Metric | Word-only baseline | With restoration |
|---|---:|---:|
| Evaluation F₀.₅ (2,042 cases) | 0.79167 | **0.79407** |
| Correct replacements | 361 | **364** |
| Wrong replacements | 23 | **23** |
| Changed preservation controls | 5 / 1,298 | **5 / 1,298** |
| Precision | 94.01% | 94.06% |
| Recall | 48.52% | 48.92% |
| Selection F₀.₅ (1,949 cases) | **0.80452** | 0.80185 |

Adopted under the user's F₀.₅ criterion: three extra evaluation fixes with no
extra wrong changes. The selection regression remains a limitation: two extra
correct replacements and three extra wrong ones, including `dont` intended as
`done`, and `im` intended as `in`. Restoration still needs context discrimination.
These are native decision metrics; Quick's existing one/two-letter Chinese-code
protection still applies and is not included in this offline metric.

The word rule, main CNN and evaluation cohort are unchanged. All 9,728 aligned
native decisions match the combined Python word/restoration policies.
Reports: [forms](config/english_iteration/2026-10-21/apostrophe-forms.json),
[fit](config/english_iteration/2026-10-21/apostrophe-fit.json),
[frozen selection](config/english_iteration/2026-10-21/apostrophe-selection.json),
[evaluation](config/english_iteration/2026-10-21/apostrophe-evaluation.json),
[native parity](config/english_iteration/2026-10-21/apostrophe-native-verification.json).

## Latency tradeoff

Alternating release-host processes; 16 warmup calls, four paired rounds:

| Host English call panel | Before median / p95 | After median / p95 |
|---|---:|---:|
| Existing 256-case panel, 1,024 calls per binary | 2.878 / 5.865 ms | 2.917 / 5.945 ms |
| TRAIN apostrophe panel, 528 calls per binary | 1.483 / 3.826 ms | 5.705 / 9.572 ms |

This pays several extra CNN steps on words with an attested missing-apostrophe
form; ordinary words use the original word scoring. It is not free. These are
host engine-call times, excluding Android scheduling, editor/rendering and cold
initialization; they do not establish phone latency or a device deadline pass.
See [general timings](config/english_iteration/2026-10-21/apostrophe-english-latency.json)
and [target timings](config/english_iteration/2026-10-21/apostrophe-target-latency.json).

## Verification and scope

23 native unit tests and 15 real-model JNI/controller scenarios pass. The new
regressions cover the full reported sentence, selectable `don't`, Space correction
and undo in English/Quick. A fixed-response Quick screenshot verifies the candidate
presentation separately from native inference. ARM64 is rebuilt and staged for
packaging; there is no connected phone verification. This change is not yet
published to Play; internal build 64 contains the earlier contention fix.

Only exact missing-apostrophe forms attested in this TRAIN export are restored.
`tyoe` still abstains in the reported sentence. No grammar correction is added.
