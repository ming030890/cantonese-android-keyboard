# Real-touch evaluation, 2026-10-22

A real public phone-touch benchmark and a synthetic-only training experiment are
now available. Production remains unchanged. The observed F₀.₅ improvement is
small and uncertain; sentence character error is slightly worse. This experiment
does not establish that touch improves our shipping keyboard.

## Source and frozen cohort

[Vertanen and Kristensson, IUI 2023](https://www.keithv.com/pub/noisytyping/)
release original touch streams, layouts, and reference text at
[OSF](https://osf.io/5xwng/). The downloaded archive's SHA-256 is recorded in
[the source protocol](config/english_iteration/2026-10-22/public-touch/protocol.json).

The primary cohort is **only `phone/vt_exp2/test/*_space.log`**: 24 participants,
480 sentences on a full-sized portrait Nexus 4 keyboard, with key popups,
closest-key feedback, sound/vibration and explicit spaces. The whitelist was
saved from filenames and study documentation before reading test-log contents.
Other phone conditions (blindfolded, no spaces, swipe spacing, small/tiny
keyboards, no feedback) and watch/midair/desktop records are excluded by metadata,
not by performance. All 480 selected trials are retained.

This is a useful benchmark for English mobile-email copy typing with real taps.
It is **not representative qualification for our whole product**: the source
uses an older single device model, strips punctuation from reference text, and
does not cover contemporary chat, Cantonese/English switching or our IME layout.
Original recognition occurred after sentences rather than at every word boundary.
No product usage weights are known.

## Observations and labels

Use the first recorded frame of each original tap, the original geometry and
recorded backspaces. Reconstruct key outputs by distance to key rectangles,
breaking ties by distance to centres. Backspace removes the preceding retained
character and its associated tap. Geometry and reconstructed key outputs are
an explicit replay assumption: these are not logged Gboard key outputs.

Input segmentation uses **observed** spaces. Context is the previous observed
text in the same trial; `LEFT`, `RIGHT`, `ORIG` and reference labels never enter
candidate generation. Sentence submission supplies the last boundary. Previous
automatic replacements are not fed back into subsequent contexts; this is an
open-loop comparison, not a controller/closed-loop trial simulation.

Reference text supplies labels only. Keep a label only when all optimal word
alignments agree, allowing up to three reference words per merged observation.
Ambiguous split/other alignments stay out of word F₀.₅ but remain in whole-trial
CER. Missing reference words likewise remain in CER even if no observed word
event exists. Do not use supplied `test_word` slices: their boundaries were
force aligned using reference text.

The cohort has **15,662 taps, 435 Backspace events, 3,127 observed words**:
3,081 uniquely labelled words (525 errors and 2,556 clean controls), and 46
ambiguous word labels. Fifty-nine typo targets are absent from the candidate
pool and remain in the word metric denominator. Clean controls are observed
records; no reference-copy controls or error balancing is added.

The requested primary unambiguous-neighbour panel uses the existing 120,321-word
offline ambiguity dictionary: unknown input, 4+ letters, one dictionary repair
across single replacements/insertions/deletions/swaps, and that repair is a
neighbour replacement equal to the reference target. All clean controls are
reported alongside it. The dictionary is hashed in the
[scoring protocol](config/english_iteration/2026-10-22/public-touch/scoring-protocol.json);
it is not installed as a runtime fallback. The full labelled cohort is a
separate regression panel.

## Synthetic-only training

The new coordinate and acceptance models use **no real touch training records**.
Generate 2,400 synthetic words from existing Aalto TRAIN text/context, grouped by
prompt into 1,509 fit / 392 calibration / 499 synthetic selection examples.
Assumed noise varies by trial, with a small broad-noise component; its prevalence
is not claimed to match real typing. Real public labels/outcomes never fit a
parameter or choose a threshold in this run. The existing shared LM is unchanged.

Compare three systems on identical native candidate pools:

1. Existing production scores and acceptance.
2. Text-only scores with acceptance refitted on synthetic examples.
3. Synthetic-trained relative-coordinate evidence **replacing**, rather than
   adding to, fixed same-length substitution penalties, with jointly refitted
   acceptance. Unsupported insertion/deletion/swap/completion paths retain their
   existing scores. This replay requires complete coordinate records; production
   keeps its existing text-only path, and the earlier native pilot separately
   verifies missing-touch fallback.

Fit/cutoff/selection partitions are distinct synthetic prompt groups. Five touch
weights were tried on synthetic data only; weight 1.0 is the best touch variant.
Its synthetic selection F₀.₅ is 0.9414 versus 0.9490 for the text refit: it did not
beat that control. Both profiles were frozen before the one-time real-data
diagnostic. This is not a successful model-selection/production qualification.
The complete [frozen experiment](config/english_iteration/2026-10-22/public-touch/synthetic-frozen.json)
contains every attempt and all fitted parameters.

The subsequent [text-overlap audit](config/english_iteration/2026-10-22/public-touch/overlap-audit.json)
finds 137/480 real trials (64 distinct reference texts) share source prompt text
with the synthetic text source. No real taps trained the model, but this limits
prompt-independent generalization. This exposure is retained and disclosed;
no evaluation cases were removed and no refit used these outcomes. Future
confirmation needs a source/prompt-disjoint protocol frozen in advance.

## Results

| System | Correct / wrong corrections | Clean changes | Full labelled F₀.₅ | Whole-trial CER |
|---|---:|---:|---:|---:|
| Production | 332 / 15 | 3 / 2,556 | 0.86775 | 2.8448% |
| Synthetic text refit | 303 / 8 | 1 / 2,556 | 0.85642 | 3.0937% |
| Synthetic touch + acceptance | 325 / 11 | 1 / 2,556 | 0.86945 | 2.8650% |

Literal input CER is 5.3870%. The touch model improves on the synthetic text
control, but compared with production it fixes seven fewer typos and makes four
fewer wrong corrections. Its full F₀.₅ rises by 0.00170; participant-cluster
bootstrap 95% interval for the difference is **[-0.01010, +0.01458]**. This is not
convincing evidence of improvement. Exact corrected sentences fall 308 → 307.
Report the CER regression alongside the F₀.₅ gain.

On the primary unambiguous-neighbour panel (86 errors plus clean controls),
production makes 80 correct / 3 wrong corrections, versus touch 75 / 1;
F₀.₅ is 0.95694 → 0.96154. The short 4–5-letter panel has only 19 typo cases:
18 → 19 fixes, no wrong corrections. Its small denominator does not establish
general short-word accuracy.

[The full report](config/english_iteration/2026-10-22/public-touch/evaluation.json)
includes per-participant results, space/submission boundary panels, paired
uncertainty and coverage. Participant bootstrapping does not remove the source
prompt-overlap limitation or establish our target users' typing distribution.

Experimental ranking/acceptance currently runs in Python over native candidate
pools: median/p95 0.238/0.933 ms per word versus 0.113/0.423 ms for text refit.
Native full-pool generation measures 1.284/2.666 ms median/p95. These are separate
host boundaries; none measures phone input-to-displayed-candidate latency. No
Android touch decoder, replacement policy, asset or release was enabled.

## Earlier TSI pilot

The first Google TSI experiment used a touch likelihood adjustment on top of the
old edit penalties and acceptance policy. It lost one selection correction
(99 → 98; F₀.₅ 0.91328 → 0.91078), with three wrong corrections in both.
Native/Python scores and decisions match on 677 cases, coordinate resizing and
translation leave results identical, and no-touch outputs match on the existing
2,042-case Aalto cohort. The separate optional native host API remains experimental.

The older SSD `human-touch-v28` collection is the **same Google TSI source**,
already used by earlier experiments across all 16 participants. Although this
pilot did not additionally score users 15–16, they cannot be called globally
unexposed/fresh confirmation. Neither the TSI pilot nor the newly scored public
cohort may be repeatedly retuned and then presented as independent confirmation.

## Reproduce

Use Python with NumPy. Raw records, synthetic cases and native caches live under
ignored `build/`; committed reports contain their hashes. The baseline probe was
freshly built from the current accepted native scorer before touch changes.

```sh
python3 -m unittest discover -s scripts -p 'test_*touch*.py'
python3 scripts/public_touch_eval.py freeze
python3 scripts/train_synthetic_touch.py --probe build/tsi-touch/baseline-probe
python3 scripts/evaluate_public_touch.py --probe build/tsi-touch/baseline-probe
python3 scripts/audit_touch_prompt_overlap.py
```

The trainer refuses to replace an existing frozen experiment. The evaluator
refuses to overwrite an existing result and checks frozen model/protocol hashes.
For a mechanical reproduction use separate output paths; do not discard the
original reports or use the exposed outcomes to retune the candidate.

Verification: ten Python touch/evaluation tests, all 27 native library tests,
and `./gradlew :app:verifyKeyboard` pass. The Android gate covers existing
behavior; the new synthetic scoring path has not been integrated into Android.
