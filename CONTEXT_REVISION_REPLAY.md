# Delayed correction replay — 2026-10-25

This offline experiment asks whether later words can safely resolve an earlier
uncertain spelling. It uses the existing native LM, spelling candidates and
acceptance network. It does not enable rewriting in the live keyboard.

Follow-up: [larger-data tuning and bounded right-hand refresh](CONTEXT_REVISION_REFRESH.md)
added 27,550 recorded development events. Refresh produced no extra fixes; the
retuned rule reduced work but scored below this experiment's previous rule.

## Result

Using the frozen cases and the existing equal-weight mean of mobile, touch and
hard-set F₀.₅, overall simulated typing improves **0.865735914 → 0.869354128**,
delta **+0.003618215**. There are **28 additional correct fixes and four additional
wrong fixes**. No previously correct word is damaged in these changes; the four
wrong revisions change an already misspelled input into another wrong word and
count as both a false positive and a missed correction. Clean-control changes
remain seven in total.

| Panel | Correct fixes before → after | Wrong fixes before → after | Clean controls changed before → after |
| --- | ---: | ---: | ---: |
| Mobile snapshots with simulated continuation | 512 → 525 | 45 → 48 | 7 → 7 |
| Recorded touch sentences, closed-loop typing | 387 → 402 | 12 → 13 | 0 → 0 |
| Authored hard cases | 28 → 28 | 3 → 3 | 0 → 0 |

The touch replay includes all 480 observed sentences and 3,127 input words. Its
46 ambiguous word labels remain excluded from word F₀.₅ but included in sentence
CER. Touch CER improves **2.5557% → 2.4211%**. It applies 16 delayed revisions:
14 after one following word and two after two following words.

The baseline above already feeds immediate corrections into subsequent context;
this explains why it is higher than the existing static-context benchmark.
For direct comparison with the accepted static score, independently replaying
each frozen focal word and its continuation improves **0.857560924 →
0.861801426**, delta **+0.004240502**: 29 additional correct fixes, four additional
wrong fixes, and one changed ambiguous touch observation. This independent focal
replay does not simulate competition between multiple queued words. The full
touch-sentence replay does, and is used in the primary result above.

No cohort members, labels, or benchmark weights were removed or changed. All 92
hard cases have no supplied following text, so they remain in the denominator
with their immediate decisions. No future text was invented for them.

## Selected policy

Only initially uncorrected, whole-word spelling proposals enter the queue.
Completions and multiword revision alternatives are excluded. The queue keeps
at most **two** unsure words. The data selected expiry after **two following
words**, rather than the initial three-word suggestion.

The ordinary acceptance FFN is reproduced offline to expose its logit without
another LM call. The selected queue band is four logit units below the immediate
threshold. These scores are not claimed to be calibrated probabilities that
the original input is wrong. The original spelling and up to three existing
alternatives are scored with the typed following words.

For each hypothesis, the scorer subtracts its prefix-only likelihood from the
likelihood including the following words. Comparing these conditional suffix
likelihoods prevents the original spelling's byte-tokenization penalty alone
from looking like new contextual evidence. Existing native shared-prefix reuse
and batched output projection do this work.

The experimental revision score is:

```
initial acceptance logit - immediate threshold
+ alternative's initial score relative to the initial winner
+ 0.25 × conditional following-text likelihood gain over the original
```

The selected revision margin is 1.0, and following-text gain must be positive.
This is a development-calibrated heuristic, not a newly trained confidence
model. At most one old word changes per word boundary. A rewrite invalidates
later words' confidence snapshots; newer inference then uses the revised text.

Band widths 1/2/4, evidence weights 0.25/0.5/1/2, expiry limits 1/2/3 and margins
0/1/2/4/8 were declared before evaluation. For each band/weight/expiry combination,
the margin was calibrated on the existing 1,154 real TRAIN-calibration cases;
selection used the existing 1,949 real selection cases. The unchanged parent was
eligible, and ties favor fewer revisions and smaller workloads. No evaluation
labels selected parameters. Selection F₀.₅ improves 0.860075 → 0.866228.

## Replay method and limits

- Touch input comes exclusively from recorded observed words. Every immediate
  or delayed correction updates the simulated editor before later inference.
  Targets and sentence references are read only for scoring.
- Mobile data contains correction snapshots, not complete observed future
  keystrokes. Its continuation is explicitly simulated clean prompt typing after
  the aligned focal span. The focal target is used only for alignment/scoring;
  it is never injected as a proposed replacement. This is less realistic than
  the touch replay and must not be described as recorded future typing.
- All 9,827 native requests were checked to contain input fields only. The
  native probe, model, policy, source data, script and rule grid are hash-bound.
- Seven regression tests cover future-text boundaries, queue eviction, expiry,
  conditional likelihood subtraction, closed-loop feedback and wrong-fix F₀.₅
  accounting. A fresh no-query-cache replay exactly reproduces the touch counts.
- These are historically exposed development data, not fresh confirmation.
  The simulation assumes checks complete at word boundaries, including the
  final word. It does not simulate actual key timing, cancellations, undo,
  cursor movement or InputConnection races. It therefore does not establish
  the benefit achievable under a phone latency deadline.

## Native cost

A separate fresh-process replay disables the experiment's Python query cache.
Revision work runs on **198/3,127 boundaries (6.33%)**, with 200 checks total.
The extra native cost on checked boundaries is **2.6245 ms median, 17.2892 ms p95,
26.516 ms maximum** on this Mac. Mean additional work averaged across all word
boundaries is **0.2590 ms**. None exceeded 40 ms in this run.

Ordinary spelling-call median is 0.800 → 0.810 ms and p95 1.2694 → 1.2947 ms in
these two host passes. This is descriptive CPU work, not key-to-frame latency or
a claim that background checks are free. The next implementation experiment
should be cancellable background revision, followed by phone timing and deadline
replay before enabling editor rewrites.

Reports and selection details:
[`config/english_iteration/2026-10-25/context-revision-replay/`](config/english_iteration/2026-10-25/context-revision-replay/).
Raw episodes, native requests, timing samples and the immutable probe remain in
ignored `build/context-revision-replay-20261025/`.

Run from `cantonese-voice-keyboard/` using the existing NumPy environment:

```sh
OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/replay_context_revision.py \
  --probe build/context-revision-replay-20261025/probe \
  --policy build/context-revision-replay-20261025/policy.json \
  --output build/context-revision-replay-UNIQUE-RUN

OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 \
../dataset/cantonese-ngram/.venv/bin/python scripts/benchmark_context_revision_replay.py \
  --run build/context-revision-replay-20261025/run1 \
  --probe build/context-revision-replay-20261025/probe \
  --policy build/context-revision-replay-20261025/policy.json \
  --output build/context-revision-timing-UNIQUE-RUN
```
