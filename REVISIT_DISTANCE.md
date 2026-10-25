# Revisit distance ablation — 2026-10-25

**Current recorded data favors expiry after one following word**, correcting the
earlier recommendation to retain candidates for two following words. This is a
small-data recommendation, not proof of a universally optimal distance. Live
revisiting remains disabled.

## Isolated comparison

The existing conservative admission and acceptance policy is held fixed:
rejected whole-word proposals, confidence band 4, evidence weight 0.25, revision
margin 1, original three alternatives and two pending candidates. Only maximum
distance changes. Queue capacity and word distance are distinct; capacity is
explicitly held constant here rather than confounded with the distance sweep.

Distance counts **completed words after the candidate**. A distance of one means
revisit after the next completed word, then expire before a second following
word can trigger another check. It covers the last two committed word positions,
including the candidate. No immediate decision or typing is delayed.

The distance range, one through nine, comes from observed TRAIN/calibration
sentence lengths in the [recorded-stream cohort](REAL_REVISIT_DATA.md). An
unbounded-within-sentence variant is also tested. It still keeps at most two
pending candidates and does not cross the sentence/session scope.

Distance is selected using only the existing 53 recorded selection sentences
(348 words). F₀.₅ wins; ties use fewer sequence requests and then shorter
distance. Frozen diagnostics at distances 1, 2, 3 and unbounded are declared
before evaluation. No score threshold, queue capacity or model is refit using
the frozen evaluation outcomes.

| Maximum following words | Selection F₀.₅ | Selection TP / FP | Revisit requests |
| --- | ---: | ---: | ---: |
| **1** | **0.918367** | **90 / 2** | **32** |
| 2 | 0.918367 | 90 / 2 | 56 |
| 3 | 0.910931 | 90 / 3 | 75 |
| 4–9 | 0.910931 | 90 / 3 | 88–104 |
| Unbounded within sentence | 0.910931 | 90 / 3 | 104 |

Thus **one is chosen before evaluation**: equal selection quality to two, with
42.9% fewer checks. Three or more following words introduce a wrong selection
revision without recovering another correct word.

## Frozen evaluation

The original 480 recorded touch sentences/3,127 words are replayed with correction
feedback. The original mobile and hard-panel immediate decisions are unchanged:
neither supplies actual following text. All original cases and aggregate weights
remain fixed. This is the recorded-future replay protocol, not the static native
objective or the earlier hybrid protocol with simulated mobile futures.

| Maximum following words | Extra correct fixes | Extra wrong fixes | Overall replay F₀.₅ | Revisit requests |
| --- | ---: | ---: | ---: | ---: |
| No revisiting | 0 | 0 | 0.865735914 | 0 |
| **1, selected** | **14** | **0** | **0.868631472** | **124** |
| 2, previous control | 15 | 1 | 0.868270231 | 200 |
| 3 | 15 | 1 | 0.868270231 | 254 |
| Unbounded within sentence | 16 | 1 | 0.868471291 | 343 |

There is no additional clean-word damage in any of these conservative variants.
The selected distance gains **+0.002895558** aggregate F₀.₅ versus no revisiting,
and **+0.000361241** versus the previous distance-two control. It uses **38% fewer
sequence requests** than that control.

The two revisions first approved after two following words add one correct and
one wrong fix. Removing them improves precision-weighted F₀.₅. The unbounded
variant recovers one additional typo after eight following words; it still
scores below distance one and requires substantially more work. Therefore the
data does not say that distant context is never useful; it does favor a short
expiry under the current acceptance rule and F₀.₅ objective.

The differences involve few cases. Selection participants/prompts and the
benchmark have historical exposure, and this is not a statistical claim of
generalization across users. A stronger acceptance policy could change the
preferred distance and would require repeating this ablation.

## Native cost and checks

Fresh processes without the Python query cache exactly reproduce all word
metrics and sequence-request counts.

| Maximum following words | Checked boundaries | Mean extra native work per all boundaries | Checked-boundary p95 |
| --- | ---: | ---: | ---: |
| 1 | 124 | 0.193 ms | 18.514 ms |
| 2 | 198 | 0.274 ms | 18.199 ms |
| 3 | 250 | 0.327 ms | 11.917 ms |
| Unbounded within sentence | 330 | 0.427 ms | 10.812 ms |

These are descriptive Mac native CPU times, not phone latency or editor-deadline
measurements. Lower p95 among a different set of checked boundaries does not
mean a longer window reduces overall work.

The native probe was rebuilt and matches the immutable saved probe. Two focused
tests verify expiry by completed-word distance, rather than pending-candidate
count, and valid distance handling. `git diff --check` passes. No Android code,
model asset or live policy changed.

Reports and bindings:
[`config/english_iteration/2026-10-25/revisit-distance/`](config/english_iteration/2026-10-25/revisit-distance/).
Raw predictions, revisions, requests and timings are retained under ignored
`build/revisit-distance-20261025/{run1,timing1}/`.

```sh
OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/experiment_revisit_distance.py \
  --data build/real-revisit-20261025/data3 \
  --previous build/context-revision-refresh-20261025/run3 \
  --probe build/context-revision-replay-20261025/probe \
  --policy build/context-revision-replay-20261025/policy.json \
  --output build/revisit-distance-UNIQUE
```
