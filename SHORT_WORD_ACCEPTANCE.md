# Short-word acceptance experiment — 2026-10-25

**Reject the tested policy.** Lower thresholds recover short typos, but the
development-selected rule adds false corrections on the larger frozen suite.
No native policy, model, Android code or packaged library was changed.

## Scope and selection

Only typed lengths two and three are eligible for additional corrections.
Existing native corrections and minimum candidate-length eligibility remain
unchanged. One-character corrections are outside this experiment.

Four families use existing inference results:

1. One shared acceptance-logit threshold for lengths two and three.
2. Separate thresholds for the two lengths.
3. A shared threshold plus a minimum candidate LM advantage over the literal.
4. A shared threshold plus a minimum penalized runner-up score margin.

There is no additional dictionary gate, new learned model, candidate expansion
or revisit inference. The existing acceptance FFN retains its dictionary inputs.
All cutoffs come from actual observed development values. Thresholds can only
add decisions; original accepted corrections are preserved. Ambiguous labels
are excluded from word quality constraints and metrics.

The first completed run (`run2`) tuned on 343 calibration words, including only
23 labelled two/three-character typos. Selection used 348 recorded words with
corrections fed into later context. It selected a length-three logit threshold
of -0.814479, keeping length two unchanged. On the exposed frozen suite this
added 22 TP and 23 FP, so it was rejected. `run1` stopped at an adapter assertion
for case-only native normalization; the parity comparison was corrected to
compare resulting words rather than optional correction values.

The final run (`run3`) uses the larger existing **fit plus calibration** cohort:
3,306 recorded words, including 1,204 two/three-character inputs, 168 labelled
short typos and 1,028 clean short controls. These folds jointly tune parameters;
they are not independent validation folds. Selection remains the separate 348
recorded words, separated by participant and prompt under the existing real
stream manifest. Tuning admits no added FP. Selection requires no increase in
FP or clean-word damage and no precision decrease, for both short words and all
words. Select maximum F0.5; ties retain the first simpler family or baseline.

The selected final rule is one shared logit threshold **0.59694543658458**, versus
the current **1.152882543391148**. Each family yields 88 TP / 2 FP on selection,
versus 86 TP / 2 FP for native baseline. Neither simple feature nor splitting
length thresholds earns a selection advantage. The shared threshold is frozen
before final evaluation. The final run was conducted after observing the first
failed run; both use the already exposed repository development objective,
not a newly untouched test set. No evaluation labels enter threshold fitting.

## Final frozen results

The unchanged static objective contains 5,261 cases. Its equal panel weights
are preserved, and fresh native baseline output reproduces the accepted score.

| Measure | Current native | Selected threshold |
| --- | ---: | ---: |
| Overall objective F0.5 | 0.857560924 | 0.856474176 |
| Short-word correct fixes | 236 | 252 |
| Short-word wrong corrections | 18 | 27 |
| Short-word clean damage | 1 | 6 |
| Short-word precision | 92.91% | 90.32% |
| Short-word recall | 65.56% | 70.00% |

Aggregate delta: **-0.0010867488264030278**. Mobile adds four TP and three FP;
touch adds twelve TP and six FP; the authored hard panel is unchanged. Across
the suite this is sixteen extra correct fixes and nine extra wrong corrections,
including five additional changes to already-correct words.

Recorded touch closed-loop replay also rejects the rule: 387 TP / 12 FP becomes
394 TP / 18 FP, including five added clean-word changes. Its touch F0.5 falls
from 0.912305516 to 0.906580764. This replay is separate from the static objective.

Among the baseline's 360 labelled short-word typos, 236 are fixed. Of the 124
remaining misses, **48 have the target as the rejected top candidate** and **76
have a different top candidate**. The latter category combines generation and
ranking failures; this audit does not separate those two causes. Acceptance
thresholds alone cannot select the correct target in those 76 cases.

## Verification and limits

The native probe was freshly rebuilt offline. The Python adapter asserts native
decision parity for every retained typed/output pair, allowing case-only native
normalization. The final run makes 7,101 fresh spelling requests and zero
sequence-scoring requests. Source, model, policy and script hashes match the
saved protocol; Python compilation and `git diff --check` pass.

These rules add no LM inference per keystroke; threshold/feature checks use
existing outputs. No phone latency was measured, and there is no shipping
latency claim. Android verification is not applicable to this offline-only
experiment. Small development cohorts still failed to capture enough harmful
short-word decisions, despite zero added FP during tuning and selection.

Saved results: [short-acceptance reports](config/english_iteration/2026-10-25/short-acceptance/).
Script: [experiment_short_acceptance.py](scripts/experiment_short_acceptance.py).
Raw runs remain under ignored `build/short-acceptance-20261025/`.
