# Why obvious single-edit typos are not autocorrected

Current follow-up: [shared acceptance refit and measured results](ACCEPTANCE_REFIT.md).
The earlier [lexical fallback experiment](UNAMBIGUOUS_NEIGHBOR_REPAIR.md) was withdrawn.

2026-10-21. The low rate comes from specific scoring and eligibility failures.
The earlier reference to a generic precision/recall tradeoff did not explain
these failures. This investigation traces the actual decisions and isolates
their dependencies; it does not install a new correction policy.

Full [hash-bound measurements and traces](config/english_iteration/2026-10-21/single-edit/root-cause.json)
cover the 5,991 synthetic cases and 9,728 aligned real-typing cases. The Python
decision replay exactly matches native autocorrection on every case, including
apostrophe restoration. The accepted real-typing evaluation reproduces 364
correct / 23 wrong / F₀.₅ 0.79407. No correction coefficients were fitted here.

## 1. The decision rule does not directly model spelling reliability

`QwenASR/crates/cantonese-ngram/src/cnn.rs::english_suggestions` computes:

```text
candidate score = context LM log-probability − handwritten edit penalty
```

`cnn_spelling.rs::accept` feeds this score and the margin over the runner into
a fitted logistic gate. For an unknown lowercase word without apostrophes,
literal evidence is absent. The six-feature rule reduces exactly to:

```text
logit = 1.857791 + 0.167487 × candidate_score + 0.289608 × runner_margin
accept if logit >= 1.117647     (sigmoid cutoff 0.753552)
```

There is no separate input for edit distance, operation type, repeated-letter
error, number of close spelling neighbours, or strength of evidence that the
typed string is a typo. Those distinctions only reach the rule indirectly through
the handwritten candidate scores. The LM estimates words from the preceding
context; it does not estimate the intended word conditional on the typed error.
A clear spelling repair can therefore fail because its context frequency or
runner margin is insufficient. The sigmoid value is a decision score, not a
demonstrated per-case probability of correctness.

Exact current decisions, all with intended targets ranked first:

| Typed → intended | Edit penalty | Decision score | Immediate rejection |
|---|---:|---:|---|
| `plizza → pizza` | 4.5 | 0.737821 | Below 0.753552 cutoff |
| `piza → pizza` | 4.5 | 0.594210 | Below cutoff |
| `butger → burger` | 1.0 | 0.594278 | Below cutoff |
| `gruop → group` | 1.0 | 0.712813 | Below cutoff |
| `provied → provide` | 1.0 | 0.748975 | Below cutoff |
| `lunc → lunch` | 0.0 | 0.836232 | Completion hard veto, despite passing score |

For `plizza`, pizza's raw LM score is −9.099627. The length-change cost reduces
it to −13.599627. Its lead over `pizzas` is 5.022817, but the gate requires
5.309316 at that score. Holding ranking and runner margin fixed and removing
only the winner's edit penalty from its acceptance score makes this case pass.
The same intervention fixes `piza` and `provied`; it does not fix `butger` or
`gruop`, whose remaining margin requirements still fail. This is a concrete
feature/cutoff failure, not a missing dictionary entry.

## 2. Single missing/extra letters receive a large fixed surcharge

`cnn_spelling.rs::edit_penalty` assigns a single insertion/deletion:

```text
1.5 × edit_count + 3.0 × length_difference = 4.5
```

A neighbouring-key replacement or a simple adjacent swap costs 1.0. An arbitrary
non-neighbour substitution costs 2.5. The length surcharge is handwritten; the
current policy explicitly records ranking penalties as remaining unlearned.

The 4.5 cost affects candidate ranking, absolute acceptance score, and potentially
the margin against differently penalized competitors. In the synthetic data,
112 missing-letter and 127 extra-letter ranking failures have better raw LM
evidence for the intended target, but the edit costs reverse that preference.
These counts diagnose cost overrides; they do not prove all such targets should
win in free typing.

The surcharge is a cause of specific failures, but changing this constant alone
is not an established repair: removing it for all candidates also promotes wrong
length-changing competitors and reduces correct fixes overall (see ablations).

## 3. Completion and autocorrection share one candidate decision

Any candidate beginning with the typed string is labelled completion, assigned
zero edits and zero penalty, and then categorically barred from autocorrection.
That includes a word missing exactly its last letter. No boundary/commit signal
is passed into this engine call to distinguish unfinished typing from a committed
misspelling.

Of 1,000 missing-letter cases, **246 intended targets are labelled completions**.
Among them, 118 targets rank first but are rejected by the completion guard.
The other 128 lose ranking too. All 246 are ineligible for the ordinary word
autocorrection path under their current representation.

The engine also chooses the winner across eligible spelling candidates and
ineligible completions, then tests only that winner. It never tries a second
eligible spelling candidate after rejecting a completion. Completion winners
occur in 151 of the missing-letter ranking failures. This contributes to the
particularly low missing-letter correction rate (39/1,000).

## 4. The fitted gate has little standalone-nonword exposure

Reconstructing the actual frozen fitting cohort gives **2,644 eligible fitting
rows**, of which only **88** are unknown inputs with empty left context (3.3%).
There are 208 empty-context fitting rows overall and 1,364 unknown inputs overall.
The synthetic panel consists entirely of empty-context inputs. This is an
observed coverage gap; it does not by itself prove the cause of each rejection.

A paired, hash-selected TRAIN-only panel holds the same 2,048 observations and
targets fixed and removes their actual left context:

| Context supplied | Correct / 749 typos | Wrong replacements | Clean changes / 1,299 |
|---|---:|---:|---:|
| Recorded left context | 395 (52.74%) | 27 | 9 |
| Empty left context | 247 (32.98%) | 22 | 4 |

That is 148 fewer correct autocorrections solely from changing the context input.
This intervention affects ranking and acceptance together; it is not an isolated
classifier-only effect. It demonstrates dependence on contextual prediction and
explains part of the standalone-word benchmark weakness. It does not excuse
clear spelling repairs being missed or establish that phone users always have
empty context.

## Exact attribution of the 3,528 synthetic missed intended fixes

| Immediate cause | Cases |
|---|---:|
| Target first, learned decision below cutoff | 1,832 |
| Target first, completion veto | 118 |
| Target first, input shorter than three letters | 39 |
| Target first, candidate shorter than three letters | 20 |
| Target returned below first | 1,511 |
| Target not returned | 8 |

The guard order here matches production: completion precedes the short-input
check. Earlier exploratory counts checked those two guards in the opposite order;
the eight overlapping cases belong to completion under production ordering.

## Fixed causal ablations, not deployment candidates

All coefficients/cutoffs stay fixed. No variant is fitted, selected, or adopted.
Both selection and evaluation are shown to avoid hiding contrary evidence.

| Intervention | Synthetic correct / wrong | Real selection F₀.₅ | Real evaluation correct / wrong | Real evaluation F₀.₅ |
|---|---:|---:|---:|---:|
| Current | 1,463 / 103 | 0.80185 | 364 / 23 | 0.79407 |
| Use raw winner LM score only in acceptance; preserve ranking/margin | 1,880 / 232 | 0.78770 | 421 / 52 | 0.79856 |
| Remove length surcharge and rerank | 1,430 / 100 | 0.78636 | 347 / 24 | 0.77873 |
| Exclude completions only from autocorrection candidate pool | 1,468 / 117 | 0.79663 | 367 / 26 | 0.79231 |
| Treat one-letter completions as penalized single edits | 1,465 / 105 | 0.79843 | 368 / 24 | 0.79585 |
| Remove learned gate; retain hard guards | 3,295 / 1,437 | 0.44275 | 481 / 712 | 0.43600 |

These establish why simply deleting one safeguard is insufficient. They do not
establish an unavoidable tradeoff between catching obvious typos and preserving
correct input. The current feature representation and mixed candidate pool are
the limitations to address. In particular, blindly lowering the gate would also
admit unrelated wrong alternatives to clean words.

A repair experiment should distinguish committed single-edit spelling hypotheses
from completions, represent operation evidence explicitly, and calibrate acceptance
on data covering standalone nonwords as well as contextual typing and preservation
controls. Fit on TRAIN and select before revealing evaluation. Do not handwrite
exceptions for the exposed examples. Any accepted runtime change still requires
the established F₀.₅, latency, native/JNI, keyboard and packaging checks.

No product-path code changed here, so no phone behavior or latency improvement is
claimed. The investigation adds only the reproducible analyzer and diagnostic
tests. Native decisions and all counterfactual evidence remain reviewable.

## Reproduce

```sh
python3 scripts/analyze_single_edit_root_cause.py --probe app/build/english-host/release/ngram_probe --output build/single-edit-root-cause-2026-10-21
python3 -m unittest discover -s scripts -p test_single_edit_root_cause.py
python3 -m unittest discover -s scripts -p test_benchmark_single_edit.py
```

Full native evidence stays in ignored `build/single-edit-root-cause-2026-10-21/`;
the committed report binds it and the frozen caches by SHA-256. Three diagnostic
tests verify completion veto ordering, isolated penalty attribution without
changing ranking/margin, and literal/short-input guard handling. The two original
benchmark tests also pass.
