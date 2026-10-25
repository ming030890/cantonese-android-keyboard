# Right-hand refresh and larger revision-rule tuning — 2026-10-25

Neither experiment justifies changing production. The previous delayed rule
remains the best overall replay candidate; delayed editor rewrites remain disabled.

Follow-up: [recorded-stream development and simpler policies](REAL_REVISIT_DATA.md)
replaces correction-event-heavy tuning and simulated mobile future with actual
observed following input. The existing conservative experimental rule wins;
the learned and broader simple replacements are rejected.

## Frozen-suite result

The same 5,261 cases, panel weights and labels as the
[initial replay](CONTEXT_REVISION_REPLAY.md) are retained. As before, touch uses
complete observed sentences with correction feedback, mobile future is simulated
clean prompt typing, and hard cases have no future text. These scores are
supplemental replay scores, not replacements for the static native objective.

| Rule | Overall F₀.₅ | Delta vs no delayed correction | Mobile TP / FP | Touch TP / FP |
| --- | ---: | ---: | ---: | ---: |
| No delayed correction | 0.865735914 | 0 | 512 / 45 | 387 / 12 |
| Previous rule | **0.869354128** | **+0.003618215** | 525 / 48 | 402 / 13 |
| Larger-data retune | 0.868805160 | +0.003069247 | 524 / 49 | 402 / 13 |

Hard cases stay at 28 TP / 3 FP. Clean-control changes stay at seven mobile,
zero touch and zero hard. Relative to the previous rule, the retune loses one
correct fix and adds one wrong fix overall, delta **−0.000548968**. On touch,
individual fixes differ despite identical TP/FP totals. CER remains 2.4211%;
exact sentences change from 341 to 340 of 480.

Both right-refresh variants produce exactly the same final evaluation words as
their respective no-refresh rule. This is weak evidence about the mechanism:
the previous rule has only **one** opportunity to refresh a queued right word,
and the retuned rule has none, across all 3,127 recorded word boundaries.

## More development data

Scanned all 489,414 Aalto correction-event records and added **27,550 distinct,
aligned recorded events**, capped at 24 additional events per prompt. This
removes the earlier eight-event cap and includes unused prompts assigned to
TRAIN/selection by the original prompt hash. Input extraction uses the observed
current text and typed word. Reference spans supply labels and continuation
alignment; neither reference labels nor the original keyboard's correction are
native inference inputs.

The final pool has **34,300 cases across 1,195 prompts**:

- 25,132 TRAIN fit/calibration cases to fit margins;
- 9,168 selection cases to choose band, evidence weight and expiry;
- 883 original development inputs matching evaluation inputs removed;
- 53 TRAIN rows matching selection inputs removed;
- zero evaluation-prompt, exact evaluation-input or exact fit/selection overlap.

These are additional events from a historically exposed corpus, not newly
collected users or an independent test. They are selected autocorrection events,
with many more errors than clean words; they do not establish deployment error
prevalence. Existing clean controls remain in the pool. No frozen touch data or
evaluation variants are used for fitting or parameter selection.

The original grid and unchanged parent/previous rule are eligible. TRAIN
fit/calibration picks a margin for each band/weight/expiry; selection picks the
rule by F₀.₅, then fewer revisions and smaller workload. The retuned choice is
frozen before its evaluation decisions are scored:

| Parameter | Previous | Retuned |
| --- | ---: | ---: |
| Admission band below immediate logit threshold | 4 | 1 |
| Conditional suffix evidence weight | 0.25 | 0.5 |
| Maximum following words | 2 | 2 |
| Revision margin | 1 | 2 |

Expanded selection F₀.₅ is 0.846332 with no revisions, 0.848001 with the previous
rule, and 0.849196 with the retune. The retune gains 21 TP and removes one FP
versus the previous rule on selection, but does not generalize to an overall
frozen-suite improvement. No further rule search was performed against those
evaluation outcomes.

## Right-hand refresh experiment

After revising a queued word, requery only remaining queued unsure words to its
right using the corrected prefix. Keep at most two queued words and allow one
bounded extra pass: at most two total rewrites per boundary, no retry loop and
no reconsideration of confident or already corrected right words.

Two predeclared variants are reported without selecting one on evaluation:

- **Strict:** rebuild the seed with corrected left context, then require the
  delayed rule's positive following-word evidence and margin. With no following
  word yet, retain a refreshed eligible seed until the next boundary.
- **Ordinary:** also allow a fresh native immediate autocorrection to revise the
  queued right word. Otherwise use the strict path.

The earlier behavior discards later seeds after a prefix rewrite. Both variants
avoid using stale confidence snapshots. New incoming words use corrected
history in all modes.

To exercise adjacent uncertainty beyond the rare recorded opportunity, a separate
development-only stress set pairs **1,000 uncertain recorded focal events** with
one deterministic synthetic typo in the preceding prompt word. It also replays
the same 1,000 events with the original preceding word. At most two examples per
prompt are included; input alignment, seed eligibility and fixed hash order
determine membership, without selecting correction outcomes. Future words are
clean prompt simulation. These are enriched synthetic stress trajectories, not
1,000 recorded adjacent-typo streams or natural-population metrics.

No refresh mode changes any final word relative to dropping later seeds. Under
the previous rule there are five refresh predictions in each condition; the
retuned rule has one in the noisy condition and none in the clean-prefix
condition. Thus even this stress set supplies few actual cascade opportunities.
Regression tests force a successful cascade to verify the mechanism, corrected
prefix use, strict waiting for future evidence, exclusion of confident words
and the bounded pass. More natural consecutive-uncertainty data would be needed
to establish a practical cascade benefit.

## Native work and verification

Fresh no-query-cache touch passes use the same process/model and attribute
refresh spelling calls to the existing boundary rather than inventing another
typed word. All modes retain 3,127 boundaries.

| Host native work | Previous rule | Retuned rule |
| --- | ---: | ---: |
| Boundaries checked | 198 (6.33%) | 54 (1.73%) |
| Sequence checks | 200 | 54 |
| Extra time on checked boundaries, median | 2.732 ms | 3.113 ms |
| Extra time on checked boundaries, p95 | 17.814 ms | 7.275 ms |
| Extra time averaged across all boundaries | 0.268 ms | 0.071 ms |

No checked boundary exceeds 40 ms. These are Mac native CPU costs, not phone
key latency, background scheduling or deadline measurements. The previous
refresh variants add one spelling query and no fix; their checked-boundary p95
is about 18.15 ms. The smaller rule reduces checked boundaries by 72.7%, but its
lower overall F₀.₅ means it does not replace the previous rule under the quality
objective. Production rules, models, APKs and editor behavior were not changed
by this experiment.

The native probe was rebuilt from current source and hashes identically to the
saved probe (`48485004…`). Eleven Python regression tests pass; `git diff --check`
passes. No Android code changed in this phase, so Android build/UI gates were
not rerun. Exported metrics omit unpopulated candidate-coverage fields from the
final-word scorer; those fields must not be interpreted as candidate misses.

Reports, data/code hashes, rule selection and opportunity audit:
[`config/english_iteration/2026-10-25/context-revision-refresh/`](config/english_iteration/2026-10-25/context-revision-refresh/).
Raw development rows, episodes, requests, predictions and timings remain under
ignored `build/context-revision-refresh-20261025/{run3,adjacent1}/`. Earlier
attempts are retained there; run3 is the final overlap-cleaned run. The native
query cache reused during development is hash-bound; touch timing never uses it.

```sh
OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/experiment_context_revision_refresh.py \
  --previous build/context-revision-replay-20261025/run1 \
  --probe build/context-revision-replay-20261025/probe \
  --policy build/context-revision-replay-20261025/policy.json \
  --output build/context-revision-refresh-UNIQUE

OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/diagnose_adjacent_revision.py \
  --run build/context-revision-refresh-UNIQUE \
  --probe build/context-revision-replay-20261025/probe \
  --policy build/context-revision-replay-20261025/policy.json \
  --output build/context-revision-adjacent-UNIQUE
```
