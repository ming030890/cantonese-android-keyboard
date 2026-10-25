# Revisiting decisions with recorded following input — 2026-10-25

The initial autocorrect decision is immediate. This experiment revisits a
previous decision after observed following words arrive; it never holds the
initial decision back. Live editor revisiting remains disabled.

## Decision

Follow-up: the [distance-only ablation](REVISIT_DISTANCE.md) selects **one
following word**, replacing the previous distance-two recommendation. It ties
distance two on recorded selection quality, uses fewer checks and scores higher
on the frozen replay. The original results below are preserved as controls.

The existing conservative experimental rule wins on the new recorded-stream
selection data. The learned replacement and the broader simple replacement are
rejected. More correction-event snapshots did not establish a better rule:
the former development pool contained mostly typos and simulated clean prompt
continuations. That distribution encouraged changes which did not generalize.

The first learned replacement selected on that pool scored 0.864817809 on the
earlier hybrid frozen replay, below its 0.869354128 incumbent. It is not adopted.
The new experiment below uses actual recorded later input, without injecting
errors, generating clean future words or balancing clean/typo counts.

## More realistic development data

Two existing public sources supply complete word sequences in their recorded
order. References supply labels and prompt-group identities only.

Sources: Vertanen and Kristensson's [Noisy Typing dataset](https://osf.io/5xwng/)
(IUI 2023), and Lertvittayakumjorn et al.'s
[Google Tap Typing with Touch Sensing Images](https://github.com/google-research-datasets/tap-typing-with-touch-sensing-images)
(UIST 2024, CC BY 4.0; pinned source revision and file hashes in the raw manifest).

- **VelociTap original Space-condition development logs:** 405 recorded
  sentences from three author participants, independent of the 24 frozen test
  participants. Coordinates and recorded backspaces reconstruct observed text;
  no reference-forced word segments or no-space/swipe conditions are admitted.
- **Google TSI phrase trials:** recorded Pixel 6 Pro taps decoded using the
  provided key rectangles and centre tie-breaks, ordered by their timestamps.
  Original autocorrection and prediction were disabled in this study. The
  retained-tap CSV omits deleted and potentially unaligned events, so this source
  is a post-edit centroid sequence, not a complete keystroke/edit log.

Participants and normalized full prompts are disjoint between folds. The
VelociTap fold with the most unlabelled trials supplies fit; the other author
identities supply calibration and selection. Prompt groups are deterministically
distributed between those folds, retaining matching participant/prompt
intersections. Google retains its previous participant assignment and likewise
keeps only matching prompt-fold intersections. Cross-source prompt overlap and
all original frozen touch-evaluation prompts are excluded from development.
This reduces volume but prevents repeated prompts leaking between folds.

| Fold | Trials | Words | Typos | Clean | Ambiguous |
| --- | ---: | ---: | ---: | ---: | ---: |
| Fit | 569 | 2,963 | 742 | 2,212 | 9 |
| Calibration | 53 | 343 | 89 | 241 | 13 |
| Selection | 53 | 348 | 122 | 222 | 4 |
| Separate Google evaluation | 20 | 107 | 22 | 85 | 0 |

Total: **3,761 words**, with 2,760 clean observations. No error-based sampling or
synthetic clean copies are added. Ambiguous alignments stay in sentence CER and
are excluded from word F₀.₅. The data remains copy typing, and the development
participant/prompt count is small. It does not represent spontaneous chat or
user reactions to our hypothetical corrections.

The VelociTap subset contains 69 fit, 33 calibration and 33 selection trials;
Google supplies the remainder. Google random-string tasks are outside this
natural-language cohort. This does not imply that OOV preservation is solved.

## Occam comparison and data-derived cutoffs

The proposed family uses **no learned model, fitted evidence weight, collection
confidence band, separate queue-size setting or new candidate-count setting**.
It tests a conditional following-text likelihood gain, and the same gain plus
prefix likelihood gain (ordinary joint likelihood, with unit coefficients).

It considers kept spellings, accepted corrections, or both. The original
spelling remains available to undo a bad correction. Confidence admission can
be unrestricted or use an absolute distance from the original acceptance-logit
threshold. It is a score band, not an asserted calibrated error probability.

All revision and confidence cutoffs are exact observed calibration breakpoints,
not guessed score grids. Revisit ages range over **all observed calibration
following-word lengths (one through nine)**. All available whole-word candidates
from the existing native pool are reused. That pool comes from the existing
`spelling_limit=8` interface; this experiment does not establish that eight is
optimal for native candidate generation. A selected age bounds queue retention,
so there is no independent last-two/last-four eviction constant.

Calibration picks cutoff values. Full sentence replay on selection then chooses
between 108 calibrated templates and the unchanged/no-revisit parents by F₀.₅;
ties favor fewer mechanisms and then fewer actual native requests, including
requests served by the experiment cache. Every revision changes the simulated
editor before later immediate inference. A prefix rewrite invalidates later
confidence snapshots. No target or reference enters this state machine.

The best new template uses both decision classes, no confidence band, conditional
following-text gain ≥ **7.615761280**, and one following word. These values are
experimental calibration results, not adopted keyboard defaults.

| Recorded selection policy | TP | FP | Clean damage | F₀.₅ |
| --- | ---: | ---: | ---: | ---: |
| No revisiting | 86 | 2 | 0 | 0.907173 |
| New simple template | 90 | 3 | 0 | 0.910931 |
| Existing conservative rule | **90** | **2** | **0** | **0.918367** |

An accepted-only template adds no correct fixes on this selection cohort.
Allowing both classes ties a kept-only template's outcomes while doing more
potential work. The data supports retaining conservative eligibility; it does
not establish that accepted corrections can never benefit from revisiting.

The retained control's band 4, evidence weight 0.25, age 2 and margin 1 were
selected in the earlier development search. This round validates that control
against newly calibrated alternatives; it does not claim those four constants
have been exhaustively reoptimized on this small new cohort.

## Frozen evaluation, with observed future only

The original 5,261 cases and equal mobile/touch/hard weights remain fixed.
Mobile snapshots have no recorded continuation, so their immediate decisions
stay unchanged for **all** policies. Hard cases also have no following input.
Only the 480 recorded touch sentences receive revisiting. This removes the
previous mobile prompt-future simulation from the aggregate comparison.

| Policy | Touch TP / FP | Additional clean touch damage | Touch F₀.₅ | Overall replay F₀.₅ |
| --- | ---: | ---: | ---: | ---: |
| No revisiting | 387 / 12 | 0 | 0.912306 | 0.865735914 |
| Existing conservative rule | **402 / 13** | **0** | **0.919908** | **0.868270231** |
| Rejected new simple template | 396 / 20 | 2 | 0.904523 | 0.863141613 |

The retained rule adds **15 correct fixes and one wrong fix**, with no extra
clean-word damage: aggregate delta **+0.002534317** versus no revisiting.
Touch CER improves 2.5557% → 2.4211%. The simple template loses
**0.005128618** aggregate F₀.₅ versus that control and damages two clean words.

The retained rule's score is lower than the previously reported 0.869354128
because mobile simulated futures are now excluded, not because touch behavior
changed. These are closed-loop replay scores, not the static native objective;
native source/models/policies were unchanged.

The separate Google evaluation has only 107 words and 22 typos. All three
policies produce the same 15 TP / 1 FP and F₀.₅ 0.872093, with no revisions.
This small result supplies no evidence of a Pixel-specific revisit benefit.

## Cost and checks

Fresh native processes replay all 3,127 frozen touch boundaries without the
Python query cache. Results exactly reproduce the experiment's word counts.

| Native work on this Mac | Conservative control | Rejected broad template |
| --- | ---: | ---: |
| Boundaries checked | 198 (6.33%) | 2,269 (72.56%) |
| Sequence calls | 200 | 2,269 |
| Extra work, mean across all boundaries | 0.272 ms | 3.480 ms |
| Extra work, checked-boundary median | 2.782 ms | 3.407 ms |
| Extra work, checked-boundary p95 | 18.099 ms | 12.744 ms |
| Maximum extra work | 27.914 ms | 70.987 ms |

The broad template costs about 12.8 times as much native work on average and
has worse quality. Its lower checked-boundary p95 does not offset its much
higher check frequency. Seventeen of its checked boundaries exceed 40 ms;
none of the control's do. These are host CPU measurements, not Android key
latency or editor-deadline guarantees.

The current native probe was freshly rebuilt and matches the saved probe SHA
`48485004…`. Twenty-four focused Python tests pass, including recorded backspace
reconstruction, reference-independent observed segmentation, accepted-correction
undo, calibration/fit separation and retention through the selected age without
an arbitrary queue eviction. Source hashes, data splits, request input fields
and result bindings are audited. No Android code changed in this phase; no
shipping/UI gates, asset replacement or device installation were performed.

Reports: [`config/english_iteration/2026-10-25/real-revisit/`](config/english_iteration/2026-10-25/real-revisit/).
Raw cohorts, episodes, request caches and timings remain in ignored
`build/real-revisit-20261025/`; final runs are `data3`, `run2` and `timing1`.
The failed correction-event learner remains under ignored
`build/revisit-policy-20261025/run1/` and is never selected for integration.

```sh
OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/prepare_real_revisit_streams.py \
  --output build/real-revisit-DATA

OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/experiment_real_revisit.py \
  --data build/real-revisit-DATA \
  --previous build/context-revision-refresh-20261025/run3 \
  --probe build/context-revision-replay-20261025/probe \
  --policy build/context-revision-replay-20261025/policy.json \
  --output build/real-revisit-RUN
```
