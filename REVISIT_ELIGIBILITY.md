# Revisit eligibility: quality versus inference — 2026-10-25

## Short-word audit

On the fixed recorded touch replay, the distance-one control checks 124 words
and makes 14 correct revisions. Among the **65 checks for typed words of two or
three characters**, six revisions are approved and all six are correct.

The other **59 rejected revisits** contain:

| Original word status | Count |
| --- | ---: |
| Already correct | **34** |
| Still a typo | **22** |
| Ambiguous reference alignment | **3** |

Thus 34/59 (57.6%) of the rejected checks preserve a word that was already
correct. Of the 22 remaining typos, 15 have the intended target among the scored
hypotheses; seven do not. Presence in the pool is only an oracle upper bound,
not evidence that available following context identifies the target.

All 34 already-correct short words are marked known by the native vocabulary.
However, **14 of the 22 rejected typos and three of the six successful short-word
fixes are also known words**. A blanket skip-known filter would discard useful
real-word correction opportunities. Vocabulary membership is distinct from the
separate complete-word dictionary flag used by the acceptance FFN.

| Typed length | Net extra correct decisions | Revisit checks |
| --- | ---: | ---: |
| 2–3 | 6 | 65 |
| 4–5 | 7 | 47 |
| 6+ | 1 | 12 |

The 4–5-character group is more efficient per check on this exposed replay;
the gain is not concentrated in only the shortest words. These bins are a
diagnostic answering the user's question, not a policy chosen using evaluation
labels.

## Gate experiment

Keep the existing conservative acceptance rule, alternatives, pending capacity
and selected one-following-word expiry fixed. Test only cheap signals already
available from immediate autocorrect: acceptance-logit distance, typed length,
native vocabulary membership, and complete-word dictionary membership. Gating
adds no LM request and fits no new model.

Confidence cutoffs are exact observed calibration breakpoints, bounded by the
existing four-logit admission band. Length boundaries come from observed
TRAIN/calibration words. Each template has at most one lexical flag and one
length bound; no interaction of vocabulary and dictionary flags is invented.
Calibration fits a quality/request-count Pareto frontier per template family.
The original gate and no-revisit policy remain eligible. All finalists are
then replayed in full on recorded selection sentences, with corrections fed
into later context. Selection maximizes F₀.₅, then minimizes requests, then
prefers fewer gate parameters. No arbitrary quality/cost weighting is added.

Calibration uses 343 words; recorded selection uses 348. These are small cohorts,
with only a few added fixes. Values are frozen before evaluation. The search
only narrows the existing rejected-proposal admission band; it does not claim a
globally optimal eligibility policy or revisit accepted corrections.

| Recorded selection gate | Revisit checks | TP / FP | F₀.₅ |
| --- | ---: | ---: | ---: |
| No revisiting | 0 | 86 / 2 | 0.907173 |
| Very narrow confidence band | 2 | 87 / 2 | 0.910042 |
| Native-unknown words, length ≤5 | 10 | 89 / 2 | 0.915638 |
| **Dictionary-invalid words, length ≤5** | **13** | **90 / 2** | **0.918367** |
| Original eligibility | 32 | 90 / 2 | 0.918367 |

The dictionary-invalid/length-five gate wins selection at equal F₀.₅ and fewer
requests. Its band remains four logit units. The very narrow confidence-only
point uses 0.0342376883 logit units, derived from calibration; this is not a
calibrated error probability.

## Frozen quality/work tradeoff

The same 480 touch sentences, 3,127 words and original overall weights are used.
Mobile/hard immediate decisions are unchanged because no real future input is
available. All evaluation gates were either selection-frontier points or the
original control, frozen before evaluation. The native-unknown point is a
reported diagnostic, **not the selected winner**.

| Gate | Extra checks | Net extra TP | Net extra FP | Overall replay F₀.₅ |
| --- | ---: | ---: | ---: | ---: |
| No revisiting | 0 | 0 | 0 | 0.865735914 |
| Very narrow confidence band | 3 | 3 | 0 | 0.866369190 |
| Native-unknown, length ≤5 | 39 | 11 | 0 | 0.868023605 |
| Dictionary-invalid, length ≤5, selected | 40 | 9 | 0 | 0.867614603 |
| Original eligibility | 124 | 14 | 0 | **0.868631472** |

No additional clean-word damage is observed. Counts are **net final-decision
changes**, not just approved revisions: native-unknown makes ten direct correct
revisions and indirectly recovers one later decision; dictionary-invalid makes
ten direct correct revisions but loses one previously correct immediate fix in
later context. Closed-loop replay is therefore necessary.

The development-selected gate cuts checks by 67.7% but preserves only 9/14 of
the additional true positives on evaluation. The native-unknown diagnostic
preserves 11/14 with 39 checks, but selecting it because of this evaluation
would be tuning on evaluation outcomes. Three checks/three fixes in the narrow
band is promising as a low-work point, but much too little support to call its
precision established.

**No filter is a free improvement in this run.** Keep the original eligibility
as the quality-first experimental control. Cheaper operating points exist, but
they explicitly surrender gains; no cost budget was provided that would justify
automatically choosing that tradeoff. Live revisiting remains disabled.

## Native work and verification

Fresh no-query-cache processes reproduce all five policies' final word metrics
and exact sequence-call counts. The selected 40-check gate averages 0.0915 ms
extra native CPU per typed word versus 0.1851 ms for the original 124-check
gate in these host passes. The native-unknown diagnostic averages 0.0901 ms.
These are Mac native costs, not phone latency or offline Python adapter overhead.

Four gate tests pass, including exact confidence/length boundaries, separate
vocabulary/dictionary semantics, disabled-gate behavior and Pareto domination.
The native probe was rebuilt and matches the immutable control. Request input
fields and source/data/result bindings are audited. No model, Android code or
live rule was changed.

Reports:
[`config/english_iteration/2026-10-25/revisit-gate/`](config/english_iteration/2026-10-25/revisit-gate/).
Raw gate runs and timing remain under ignored
`build/revisit-gate-20261025/{run2,timing1}/`. The earlier run1 contains only
confidence/length gates and is retained as a historical narrower search.
