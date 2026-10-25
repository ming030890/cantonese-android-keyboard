# Short-word acceptance experiment — 2026-10-25

**The initial broad-threshold policy is rejected.** A later one-edit candidate
below improves the exposed objective, but is experimental and not promoted to
the accepted baseline or deployed.

Lower thresholds recover short typos, but the
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

## Error analysis and simple edit-count follow-up

The initial sixteen added correct fixes and nine added wrong corrections have
substantial overlap in edit count:

| Operation | Correct fixes | Wrong corrections |
| --- | ---: | ---: |
| Substitution | 14 | 7 |
| Transposition | 0 | 1 |
| Deletion | 1 | 1 |
| Prefix completion | 1 | 0 |

Fifteen of sixteen correct fixes and all nine wrong corrections have native
edit count one. The remaining correct completion has native count zero and two
completed characters: effective character edits must include `completedChars`.
Thus total edit count alone cannot separate these examples. Four of the nine
wrong observations repeat the same `tim → him` edit and context; they are not
four independent lexical failure modes. Other wrong repairs include `mo → no`
when the target is `not`, and `bad → had` when the target is `has`. These are
wrong repairs of existing typos, not only damage to correctly typed words.

An intermediate experiment used the candidate's context score minus its
empty-context LM score. Its cached prior table contained model probabilities,
not word-specific correction rules. That entire CNN/cache integration was
removed following the user's request for simpler edit features. No new LM prior
table or prior-scoring inference remains in the candidate implementation.

The replacement experiment compares four general families: a shared cutoff,
cutoffs by effective edit count, substitution versus other operations, and one
neighbor substitution versus other operations. It uses 5,418 real fit plus
calibration words, with 3,316 two/three-character inputs (764 typos, 2,544 clean
controls and eight ambiguous labels). An exact Pareto search chooses cutoffs
from observed scores, constrained by native FP, clean-damage and precision
budgets. It can reject existing risky decisions as well as add corrections.
No new model weights, dictionary gate, word exceptions or inference are added.

Separate selection has 348 observed-stream words and 714 real mobile short-word
snapshots. All four fitted families tie native baseline on both selection
cohorts; selection alone therefore retains baseline. Already-frozen families are
then compared on the repository's explicitly exposed development objective,
as allowed by `AUTOCORRECT_OBJECTIVE.md`. This is development hill climbing,
not independent evidence of generalization. Evaluation labels do not fit the
cutoffs, but prior evaluation errors motivated feature design.

| Fitted family | Frozen objective F0.5 | Short TP / FP |
| --- | ---: | ---: |
| Shared / native baseline | 0.857560924 | 236 / 18 |
| Effective edit-count buckets | 0.857386339 | 235 / 18 |
| Substitution bucket | 0.858040765 | 238 / 18 |
| One-neighbor-substitution bucket | 0.858040765 | 238 / 18 |

The narrow candidate changes just one threshold for two/three-character inputs
whose existing features report `edits == 1` and `neighbor_replacements == 1`.
The cutoff is **1.1049790959480634 logit units**, versus the original
1.152882543391148. Minimum lengths and all other decisions remain unchanged.
The maximum length three defines this user-requested scope; the cutoff is
derived from development data. No word text is tested in the new rule.

Fresh native inference adds exactly `tun → run` and `ny → by` on the static
suite, with zero added FP or clean damage. All 5,261 candidate pools and scores
are unchanged. The aggregate delta is **+0.00047984030650316534**. Closed-loop
observed touch replay adds one net TP (387 → 388), retaining twelve FP and zero
clean damage. The separate 107-word Google cohort remains fifteen TP / one FP;
it provides no positive gain evidence. The gain is small and uses an exposed
objective; it does not establish broader coverage or population precision.

The supplemental synthetic prior-typo audit also matters. Relative to the saved
baseline, noisy-context TP/FP deltas are: deletion +2/0, substitution +2/0,
duplication +2/+1, transposition +2/0. Clean damage is unchanged in every
operation. The extra wrong repair under duplicated context is a reason to keep
this candidate experimental instead of claiming precision is universally
preserved. Do not tune an exception to that exposed stress case.

The native source contains the optional small count-based branch and its
candidate policy; the original FFN weights are unchanged. Actual JNI tests pass
correction, undo and undo suppression across English, Quick 3 and Quick 5,
including preservation of `tim`, `mo` and `bad`. All 35 ordinary native tests
pass. The new keyboard screenshot was rendered and visually inspected.

ABBA native Mac timing gives median 1.529 → 1.5295 ms and p95 2.99425 → 3.00325
ms, with zero extra inference calls. This is effectively unchanged host timing,
not a phone-latency measurement. The ARM64 library and APK were rebuilt and
their packaged-library hashes verified. Full `verifyKeyboard` remains red on
seven unrelated Settings screenshots whose baselines lack the already-added
auto-capitalization switch; those baselines were not changed. The accepted
baseline remains 0.857561 and no device installation was performed.

Reproduction: [experiment_short_edit_counts.py](scripts/experiment_short_edit_counts.py).
Saved [count-experiment reports](config/english_iteration/2026-10-25/short-edit-counts/).
Raw input-only native caches, prior experiments and source snapshots remain
under ignored `build/{short-edit-counts,short-error-analysis}-20261025/`.
