# Additional-edit surcharge experiment (2026-10-22)

**Retain the existing 3.0 same-length and 4.5 length-changing extra-edit
surcharges.** None of four TRAIN-fitted proposals beats v66 on selection.
No native source, acceptance policy, model, or runtime artifact changes.

This follows the accepted [five-cost calibration](EDIT_POSITION_COSTS.md).
Only the two additional-edit costs vary. The five fitted base costs, acceptance
coefficients and cutoff, generation bounds, split-word costs, and apostrophe
policy remain fixed. No new scoring feature or branch is introduced.

## Protocol

`scripts/fit_additional_edit_costs.py` reuses the frozen candidate pools and
float32 scoring replay previously verified through 24,567 native cases. It
asserts that the v66 baseline exactly reproduces the saved real native decisions.
Fit conditional ranking loss on 1,473 recorded TRAIN-fit typo cases whose target
is available, excluding the separate TRAIN calibration groups. All selection
cases, including missing candidates, abstentions and preservation controls,
remain in the metric denominator.

TRAIN has 237 gold cases using the same-length extra-edit surcharge and 75 using
the length-changing surcharge. There are 1,082 fit cases with at least one
multi-edit candidate. Compare one shared multiplier against two separate costs,
each with shrinkage regularization 0.01 or 0.1. These four proposals use the
previous experiment's fixed optimizer settings; there is no threshold search.

Select only a strict real-typing F₀.₅ improvement with no drop in either the
primary unambiguous-neighbour F₀.₅ or its 4–5-letter subset. Keep the existing
costs on ties. The [frozen selection report](config/english_iteration/2026-10-22/additional-edit-costs/selection.json)
records all proposals, data/script hashes, and the explicit no-winner decision.
A repeated selection run after adding diagnostics reproduced every proposal and
metric exactly; it added no new proposals. No evaluation metrics were computed
for the rejected fits, and none was chosen using evaluation results. These
remain historically exposed development partitions, not independent confirmation.

## Selection results

Real selection has 700 typos and 1,249 controls. Primary selection has 598
unambiguous-neighbour typos and 787 controls; its short subset has 72 typos.

| Proposal | Same-length / length-changing costs | Real correct / wrong | Real F₀.₅ | Primary F₀.₅ | Short primary correct |
|---|---|---:|---:|---:|---:|
| Existing v66 | 3.0000 / 4.5000 | 401 / 31 | **0.82578** | **0.93028** | **32/72** |
| Shared multiplier, regularization 0.01 | 2.5932 / 3.8898 | 400 / 32 | 0.82372 | 0.91576 | 25/72 |
| Shared multiplier, regularization 0.1 | 2.6881 / 4.0322 | 398 / 32 | 0.82231 | 0.91854 | 27/72 |
| Separate costs, regularization 0.01 | 2.5087 / 3.9733 | 400 / 33 | 0.82237 | 0.91520 | 25/72 |
| Separate costs, regularization 0.1 | 2.7136 / 4.1622 | 398 / 32 | 0.82231 | 0.92084 | 28/72 |

All proposals retain six unwanted real-control changes. The best proposal by
real F₀.₅ loses 30 primary correct fixes (467 → 437). **All 30 still have the
same correct top-ranked word.** Their one-edit target scores stay unchanged,
but cheaper multi-edit alternatives narrow the runner margin and cause the
fixed acceptance gate to abstain. They are confidence losses, not lost candidate
coverage or changed winners. Seven of these losses are in the 4–5-letter subset.

This rejects these four fits with the current acceptance rule; it does not prove
the existing constants globally optimal. Lower TRAIN ranking loss is insufficient
when the product metric also depends on calibrated acceptance margins.

## Runtime and accepted baseline

No production change qualified, so no candidate was rebuilt, packaged or
published. There is no runtime latency change to measure; the previously
measured v66 host baseline remains 2.752 ms median / 5.589 ms p95 (engine calls,
not phone input-to-display). The accepted real evaluation remains F₀.₅ 0.82154,
418 correct / 32 wrong corrections, eight clean changes. Primary evaluation
remains 482/617 correct; short primary remains 22/63 with two wrong corrections.
These are the previously measured baseline results, not new evaluation runs.
