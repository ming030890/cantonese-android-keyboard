# Autocorrect evaluation and experiment iteration — 2026-10-30

The later [learned typing-channel experiment](ENGLISH_NOISY_CHANNEL_20261031.md)
was accepted at **0.893921**, after a training-isolation repair and native/JNI
verification. The rejected studies below describe the earlier checkpoint.

## Earlier outcome

**No experimental engine change was adopted.** The best scored candidate
improved both the old objective and the supplemental confirmation counts, but
failed an existing ambiguity requirement: it automatically changed `butger` to
`burger` where `butter` is also plausible. The assertion was retained and the
candidate rejected. All experimental native-source hunks were saved as a patch
and withdrawn; the accepted engine and model manifest were restored.

The final accepted aggregate is **0.8736146656392934**, delta **0.000000**. The
evaluation harness, source-disjoint supplemental datasets, training/experiment
scripts and rejected results are retained. Nothing was uploaded or deployed.

## Stronger development evaluation

The [GitHub Typo Corpus](https://github.com/mhagiwara/github-typo-corpus) provides
real repository edits. The original download returned 404; the archived v1.0.0
file was recovered and hash-bound:

```
98219d275a49ad28fca832891876c255581b890d893b6d9c974ceb454ba2909d
```

The extraction retains single-word nonword edits in documentation/text, with
unchanged surrounding text, upstream typo probability at least 0.95, and an
English dictionary target. Observed spellings recognized by either the pinned
dictionary or shared model vocabulary are excluded from this nonword panel.
No candidate output or model accuracy selected the cases. These are inferred
typo labels from real edits, **not manually confirmed phone/chat errors**.
Corrected counterparts form a separate preservation panel, not an estimate of
natural clean-word prevalence.

The mixed-language panel uses original written Cantonese/Chinese web and wiki
selection text. ASR-derived contexts were excluded during preflight before any
inference. Current LM training fingerprints are excluded directly. One Latin
span per source group is retained for preservation, including names and loan
words. Synthetic errors are generated only for English dictionary targets,
using operation patterns from recorded phone **fit-fold** errors. No synthetic
error frequency is presented as a population typing distribution.

| Panel | Development | Confirmation |
| --- | ---: | ---: |
| Real English repository nonword edits | 512 | 512 |
| Corrected-source preservation controls | 512 | 512 |
| Mixed-text preservation controls | 128 | 128 |
| Synthetic errors in mixed written context | 280 | 241 |
| **Total** | **1,432** | **1,393** |

The folds have disjoint repository/original-document groups, paragraph hashes
and exact inference inputs. Development has 569 groups (441 repositories plus
128 mixed groups); confirmation has 558 (430 plus 128). Variants from a mixed
document stay together. The confirmation fold remained unscored through model
development and was opened once after freezing a candidate.

Important coverage limits remain:

- The English nonword panel favors long words: 450/512 development and 459/512
  confirmation errors are six letters or longer. It complements existing
  short/real-word phone and challenge panels; it does not replace them.
- Mixed errors are synthetic. Preservation labels assert retention of published
  source spans, not independently reviewed user intent.
- Mixed text comes from an existing LM selection partition. Direct current
  training overlap is checked, but complete inherited-LM exposure is unproven.
- There are still no fresh spontaneous bilingual phone-typing sessions with
  confirmed intent or measured physical-phone deadlines.

Thus this is a substantially stronger **development/diagnostic** evaluation,
not real-product qualification. The 24 authored controller scenarios remain a
separate diagnostic panel, outside fitting and checkpoint selection.

## Thirty-eight scoring/training configurations

All training used the local Mac CPU and NumPy. No cloud GPU or large LM was
trained. There were 28 fitted tiny-policy variants plus ten scoring profiles.

| Iteration | Configurations | Result |
| --- | ---: | --- |
| Partial English-probability conditioning, with accepted/expanded search | 10 | Small mixed-context gains; stronger conditioning adds wrong edits |
| Matched 24-weight data-only vs English-mass-input fits, original cutoff | 8 | No candidate passed the development guard |
| Same small fits with group-separated operating-point calibration | 8 | No candidate passed |
| Full tiny 22-input/23-input FFN fits with calibration and legacy anchoring | 12 | No candidate passed |

The normalization study shifts ordinary single-word acceptance evidence by
`-weight × log P(English token | preceding context)`. It preserves candidate
ranking scores and the separately calibrated byte/apostrophe, split and
completion paths. Weights 0, 0.125, 0.25, 0.5 and 1 were declared before inference.
The model and original acceptance weights remain fixed in this study.

The supervised studies use 768 current-LM TRAIN mixed documents: 768 clean
examples and 1,481 synthetic errors, plus 3,110 source-stratified legacy TRAIN
anchors. New evaluation groups and exact inputs are excluded. Later studies
restart from the original policy and split these into **4,028 fit** and **1,331
calibration** rows by group. Calibration chooses observed-score cutoffs under
the parent's false-edit budgets; it never fits on supplemental development or
confirmation. Matched data-only controls distinguish additional supervision
from adding an English-language-probability feature.

The development selection guard requires, in every panel, at least the parent's
correct fixes and no extra wrong edits or clean damage. Eligible variants are
ranked by mean recall across the two typo panels, with the unchanged parent
eligible. This is a predeclared conservative research operating point, not a
population-optimal utility function.

## Best candidate and its rejection

The development survivor combined expanded search with weight 0.25. Its legacy
aggregate was **0.878942920387507**, delta **+0.005328254748214**.

On the single supplemental confirmation pass:

| Measurement | Accepted engine | Candidate |
| --- | ---: | ---: |
| Real English fixes | 444/512 | 445/512 |
| Wrong changes on English typo inputs | 18 | 17 |
| Corrected English controls damaged | 0/512 | 0/512 |
| Mixed-context synthetic fixes | 133/241 | 135/241 |
| Wrong changes on mixed typo inputs | 3 | 3 |
| Mixed preservation controls damaged | 5/128 | 5/128 |

These are three extra correct fixes and one fewer wrong correction. The paired
cluster-bootstrap F₀.₅ difference intervals reach zero, so the improvement is
small and statistically inconclusive, not a large demonstrated product gain.

End-to-end verification then caught the `butger` ambiguity regression. It
outweighs the apparent metric gains under the existing product requirements.
The candidate was withdrawn without modifying that requirement or adding a
word-specific runtime exception. The earlier radius-only candidate also failed
the new development recall guard and was withdrawn with it.

The unchanged five mixed-text preservation failures include names/brands such
as `Hooman`, `Desserant`, `Kary` and `Aspinal`, plus the valid German term
`Implikatur`. All were capitalized in the source. This supports further study
of original typed spelling/case and explicit KEEP evidence. The native scorer
currently canonicalizes spelling before acceptance, so original casing is not
an acceptance input. This is a hypothesis for a subsequent study, not a rule
or model fitted to those confirmation examples. A casing study must also
preserve case realistically when generating TRAIN errors.

## Evaluation-loop correction

The confirmation pass happened before the full JNI suite. That ordering was
insufficient: an already known regression consumed a holdout pass. A new
`check_autocorrect_regressions.py` preflight promotes ten existing spelling/
ambiguity requirements into a cheap native check. The confirmation script now
rejects this candidate **before opening confirmation data**. Full JNI/controller
checks must also precede future confirmation passes.

The original confirmation results are retained, marked exposed, and cannot be
treated as fresh evidence during further model selection. The dataset builder
now supports `--exclude-suite` to omit every prior group, paragraph and exact
input when replenishing a study. Replenishment was tested without inference.

Recommended order for the next iteration:

1. Freeze fit/calibration/development and new confirmation groups and sampling
   rules. Keep real observations, source-copy controls and synthetic stresses
   visibly separate.
2. Fit and compare on training/development, retaining the unchanged parent.
3. Run cheap existing-behavior guards, native parity, the unchanged legacy
   objective, and full controller/JNI verification.
4. Freeze one candidate; reveal confirmation once. Report gains, regressions,
   uncertainty and latency without silently weakening the predeclared guard.
5. Adopt only if all requirements pass. Once used for further decisions, retire
   confirmation from the fresh-evidence role and replenish it.

## Final verification and artifacts

After withdrawing the prototypes, the accepted native source is clean. A fresh
native build has the original probe hash and reproduces the accepted aggregate
with delta zero. All **38 native tests**, **285 unit/scenario tests** (zero
failures/skips), screenshot verification and Android debug assembly pass.

- [Protocols, numerical results and final decision](config/english_iteration/2026-10-30/bilingual-eval-iteration/)
- Raw source archive, frozen cases and confirmation outputs:
  `build/independent-autocorrect-eval-20261030/`
- Scoring profiles: `build/conditional-english-scores-20261030/`
- TRAIN-only data and all fitted/rejected policies:
  `build/bilingual-acceptance-20261030/`
- The withdrawn native patch is preserved in
  `build/conditional-english-scores-20261030/withdrawn-engine.patch`.

The experiment builder was also rebuilt from the restored accepted source and
reproduces the rejected ambiguity decision entirely in an isolated checkout.
No native experimental behavior remains enabled in the shared source tree.
