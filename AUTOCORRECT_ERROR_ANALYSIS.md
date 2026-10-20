# Autocorrection error analysis — 2026-10-20

This records the two-feature baseline (F₀.₅ 0.76364). The subsequent
[literal-aware iteration](LITERAL_ACCEPTANCE_ITERATION.md) addresses part of the
acceptance loss and is now the accepted production-path rule.

The largest measured loss is **acceptance and literal-word handling**. Candidate
generation and ranking contribute similar, smaller losses. The edit costs have
specific weaknesses, but removing them makes ranking substantially worse.

This analysis uses the unchanged [frozen evaluation](TYPING_EVALUATION.md):
2,042 cases, including 744 typos and 1,298 preservation controls. The current rule
produces 336 correct replacements, 28 wrong replacements, and F₀.₅ **0.76364**.
No coefficients, generation rules or production code were changed for this analysis.

## Where the 408 missed fixes occur

Assign each typo to its earliest failing stage. A wrong replacement on a typo is
also a missed correct fix, so these counts include the 19 wrong typo replacements.

| Earliest failing stage | Cases | Share of missed fixes |
|---|---:|---:|
| Correct target absent from generated pool | 85 | 20.8% |
| Target generated but another candidate ranks first | 86 | 21.1% |
| Target ranks first but automatic correction rejects it | 237 | 58.1% |
| **Total** | **408** | **100%** |

The correct target is generated for **659/744 typos (88.6%)**, appears in the
top eight for **648/744 (87.1%)**, and ranks first for **573/744 (77.0%)**.
Among generated targets, 573/659 (87.0%) rank first. Only 336 become automatic
corrections. The cache contains at most 1,001 candidates per request, below the
4,096 diagnostic return limit, so absence is not caused by that output cap.

Stage counts are not independent causal effects. For example, 18 generation
misses and 13 ranking misses are also blocked by known-word preservation.
Fixing generation or ranking alone would not automatically recover them.

## Acceptance: 237 correct winners are rejected

| Reason, checked in production guard order | Cases |
|---|---:|
| Observed spelling is already in the model vocabulary | 108 |
| Learned score/margin threshold | 98 |
| Winner is a prefix completion; automatic expansion disabled | 24 |
| Observed word shorter than minimum | 6 |
| Candidate shorter than minimum | 1 |

The known-word veto rejects `hou → you` after “let me know if”, `guts → guys`
after “Hope you”, and `if → of` after “for a couple”. Some observed words are
legitimate words; others are short names, abbreviations or corpus tokens. Being
TRAIN-attested does not establish that a token is intended in the current context.

But simply removing the veto is harmful. The candidate generator excludes the
literal input, and acceptance uses the best alternative's score and margin over
another alternative. It has **no candidate-versus-literal score feature**. The
known-word veto currently compensates for that missing evidence.

| Fixed diagnostic ablation | Correct | Wrong | Clean controls changed | F₀.₅ |
|---|---:|---:|---:|---:|
| Current | 336 | 28 | 9 | **0.76364** |
| Remove known-word veto, keep fitted threshold | 374 | 166 | 145 | 0.64394 |
| Remove learned threshold, keep other guards | 434 | 172 | 52 | 0.68497 |

These ablations do not refit acceptance and are not proposed deployments.
They show why removing safeguards or accepting every top-ranked candidate is
insufficient. The more promising change is scoring the original word alongside
alternatives, then fitting acceptance from that evidence on TRAIN.

The threshold also inherits problems from the edit channel. For example,
`htin → think` after “I don't” ranks first with strong LM evidence (−2.94), but
a typing cost of 9 reduces its score to −11.94 and the rule abstains. Thus some
acceptance misses may improve through better cost calculation, not just a lower cutoff.

## Generation: 85 targets are unavailable

| Restriction | Cases |
|---|---:|
| Beyond length-dependent edit radius | 40 |
| Target absent from the generation vocabulary | 30 |
| Length difference greater than one | 11 |
| Unsupported multiword split proposal | 4 |

Examples: `estimed → estimated` is excluded by the one-character length-difference
restriction; `resne → resend` lacks an output-vocabulary entry. Multiword proposal
rules cannot produce `onnrecommendation → on recommendations` because splitting
alone does not also repair the missing plural suffix.

**17 of the 30 vocabulary misses are contractions**, including `don't`, `can't`,
`I'm` and `we're`. This is not just a rare-name problem. The shared spelling
generator relies on whole-word English entries; accepting apostrophes in the
input does not ensure that corresponding contraction outputs exist.

Of nine unwanted changes to clean controls, **five remove apostrophes**:
`don't → dont`, `It's → Its`, and `didn't → didnt`. The remaining four are
`Gascoigne → gasoline`, `mechanised → mechanized`, `fashionably → fashionable`,
and `intercompany → inter company`. These identify vocabulary coverage,
names/dialect preservation, and split handling as concrete weaknesses.

Wider search must be tested for both F₀.₅ and latency. It can add the right target
and also introduce more competing wrong candidates; coverage alone is not acceptance.

## Ranking and edit costs: 86 targets lose first place

| Score relationship between target and current winner | Cases |
|---|---:|
| LM favors target; edit cost reverses the decision | 25 |
| Edit cost favors target; LM reverses the decision | 17 |
| Both favor the wrong winner, or tie | 44 |

For `settongs`, the reference is `setting`, but the engine applies `settings`:

| Candidate | LM evidence | Edit cost | Final score |
|---|---:|---:|---:|
| `setting` | **−6.97** | 9.0 | −15.97 |
| `settings` | −8.94 | **1.0** | **−9.94** |

The hand-written length-change and multiple-edit surcharges outweigh better
context evidence. Similar errors include `iber → uber` instead of `over`, and
`thks → this` instead of `the`. These three are actual wrong automatic replacements;
the other 22 cost-overrides produce abstentions.

However, the current cost distinctions are useful overall. Re-ranking the same
659 covered typo pools gives:

| Ranking score | Correct first-ranked targets |
|---|---:|
| Current LM minus current edit cost | **573** |
| LM minus uniform cost of 2 per edit | 510 |
| LM only | 409 |
| Edit cost only, lexical tie-break | 287 |

These are ranking diagnostics, not calibrated F₀.₅ comparisons. Removing costs
or replacing them with one uniform constant loses useful typing evidence.
The earlier fitted four-operation channel also worsened ranking; learning a
value is not sufficient evidence to deploy it.

In **16/86 ranking misses**, the winner is an ineligible prefix completion.
The engine chooses a winner across spelling alternatives and completions, then
rejects automatic completion without considering another spelling candidate.
Candidate-display ranking and automatic-correction eligibility interact here.
This deserves a TRAIN/selection experiment rather than enabling automatic completion.

Split proposals additionally use `logp(first) + 0.5 × logp(second)` from the
same left context. That is a heuristic, not the causal probability of the phrase.
This analysis attributes its score as implemented; it does not validate that formula.

## The 28 wrong replacements

- 9 occur when the reference target is not generated.
- 10 occur when the target is generated but another candidate wins: 3 cost
  overrides and 7 cases where LM and costs both favor the wrong word or tie.
- 9 change preservation controls, including the five apostrophe removals above.

Some noisy observations are intrinsically ambiguous: `yhe` is aligned to the
reference `new`, while the model applies `the`. Copy-typing references define the
benchmark target; they do not prove what a free-typing user would have intended.
Keep these cases in the fixed evaluation rather than deleting difficult examples.

## Recommended next experiments

1. Repair contraction representation/output coverage and measure preservation
   of apostrophes, names and dialect variants using the existing shared model.
2. Add literal-versus-alternative evidence, then refit the small acceptance rule.
   This addresses the largest measured loss without simply disabling protection.
3. Learn a more expressive typing channel with alignment/confusion evidence,
   especially length changes and multiple edits; jointly recalibrate acceptance.
4. Test bounded generation expansion and completion-versus-correction eligibility
   separately. Compare latency and F₀.₅ with the accepted baseline.

All experiments must fit/select on the existing TRAIN/selection partitions.
This error inspection exposes evaluation examples and must not become a loop
of hand-writing rules against those examples. The Aalto cohort contains no tap
coordinates, so it cannot establish the benefit of actual touch/layout scoring.
No phone latency or UI behavior is measured here.

## Reproduce

```sh
python3 scripts/analyze_autocorrect_errors.py --iteration build/english-iteration-2026-10-20 --output build/english-iteration-2026-10-20/error-analysis.json
python3 -m unittest discover -s scripts -p 'test_analyze_autocorrect_errors.py'
```

The script verifies frozen cohort/cache identities and reproduces the accepted
336/28/F₀.₅ metrics before reporting attribution. Hash-bound aggregate results:
[`config/english_iteration/2026-10-20/error-analysis.json`](config/english_iteration/2026-10-20/error-analysis.json).
Per-case public-dataset text remains in ignored `build/`. Three diagnostic tests
pass. The production policy and its latency remain unchanged by this analysis.
