# Candidate and autocorrect evaluation protocol

**Current optimization directive (2026-10-24):** report ONE headline score from
[`AUTOCORRECT_OBJECTIVE.md`](AUTOCORRECT_OBJECTIVE.md): the equal-weight mean
of F₀.₅ on full historical mobile, real-touch replay and the active authored hard
set. The authored set now has 46 errors and 46 domain-balanced clean controls;
v3's 204 controls were reduced without consulting predictions. Use the frozen
aggregate for hill climbing/model selection, as requested by the user. This
supersedes older selection restrictions and primary-panel-only acceptance below
for these exposed development panels. Train on TRAIN, keep evaluation examples
out of fitting, and keep the objective weights/cohorts fixed. Preserve detailed
diagnostics and latency artifacts; do not claim the score is population accuracy
or fresh confirmation. Historical v2/v3 instructions below describe prior versions.

Historical authored stress panel (2026-10-24): [v3 hard cohort](AUTOCORRECT_EVAL_V3.md)
removes 162 ordinary single-edit errors from v2. Retain 46 errors involving
multiple edits, apostrophes, missing spaces or real-word confusions, plus all
204 clean controls. Use this 250-case panel for current stress results. The
original natural panels and historical results stay intact. The redesign was
requested after v2 results were exposed; this is not fresh confirmation.

Historical expanded evaluation (2026-10-24): [separate observed, copied-control and mobile
challenge panels](AUTOCORRECT_EVAL_V2.md) cover 5,581 observations, including 412
new authored stress/preservation cases. The original 2,042-case cohort and
unambiguous-neighbour primary evaluation remain unchanged. Keep the new panels
separate: they provide coverage, uncertainty and overlap audits, not a pooled
product accuracy claim. Freeze candidates before scoring; do not fit on these
exposed labels. Real-touch replay retains 46 ambiguous observations in whole-trial
CER and all missing-candidate targets in word-metric denominators.

Current compiled acceptance update (2026-10-24):
[Gboard-supported training](GBOARD_ACCEPTANCE_TRAINING.md) uses a 22-input FFN
with SCOWL spelling evidence. Freshly rebuilt starting/current mobile evaluation:
F₀.₅ **0.85258 → 0.86657**, correct fixes **502 → 504**, wrong fixes **48 → 37**,
clean changes **8 → 6 / 1,298**. Warm host median/p95 is 2.5435/4.698 →
2.5360/4.721 ms; first-call dictionary initialization adds about 1.6 ms.
The historical unambiguous-neighbour panel improves 0.93477 → 0.95628; its
63 short-word typo fixes improve 23 → 27. Retain the short-input recall tradeoff
and target-word overlap limitations in the report. Candidate generation and the
shared LM are unchanged. All earlier dated baselines below remain historical.

Updated 2026-10-24. Keep this protocol for future iterations. The acceptance
metrics requested by the user are **latency and F₀.₅**.

Current mixed-candidate iteration: [protocol, generalization checks and BOS
ablation](MIXED_CANDIDATE_RANKING.md). That iteration retained autocorrection F₀.₅ 0.79407;
candidate retrieval improves overall, with explicitly recorded Chinese
regressions and increased full-pool ranking latency. Keep BOS enabled. User
examples are regression tests, excluded from calibration targets. Do not retune
the four coefficients against the exposed evaluation/generalization panels.

## Acceptance

Every change to autocorrection candidate generation, ranking, acceptance,
dictionary evidence or touch scoring must cover the frozen objective in
`AUTOCORRECT_OBJECTIVE.md` before handoff. Use freshly built baseline/candidate
probes and report one headline score and its delta. Keep component panels,
short-word/multi-edit slices, clean changes, whole-trial CER, uncertainty,
overlap and latency in the detailed report. The aggregate is a development
selection metric; historical benchmarks and primary panels remain diagnostics.

For the 2026-10-21 neighbouring-replacement repair, the user explicitly requests
**unambiguous cases only** as the primary typo evaluation. Define these before
inference labels: unknown dictionary input, 4+ letters, exactly one dictionary
repair across all single replacements/insertions/deletions/swaps, and that repair
is a neighbouring-key replacement. Include missing-model targets in the denominator.
Keep clean-word preservation and the complete frozen cohort as separate regression
reports; do not blend ambiguous typos into the primary fix ratio. See
[the edit-cost calibration](EDIT_POSITION_COSTS.md).

- Use **F₀.₅** as the primary automatic-correction quality metric. Accept an
  improvement over the current accepted baseline; there is no separate 95%
  precision cutoff. Report precision, recall, correct/wrong replacements and
  unwanted changes to clean controls alongside it.
- Measure **latency** alongside quality: report median and p95 for first
  selectable candidates, plus full-ranking latency separately. Lower is better.
  For latency-only changes, preserve correction quality and candidate coverage.
- Report any quality/latency tradeoff explicitly. No numeric phone latency
  budget or combined latency/F₀.₅ weighting has been agreed; do not invent one
  or quietly hide a latency regression behind a quality gain.
- Fit on TRAIN, calibrate cutoffs on TRAIN calibration groups, and select on
  selection groups. Freeze the candidate before evaluation. Do not tune against
  evaluation labels or change cohort membership to improve the score.
- Rebuild the actual native library and run production-path checks. Old-engine
  experiments, skipped tests, or a stale packaged library do not establish a pass.

## Fixed autocorrection evaluation set

Source: SSD Aalto mobile typing data at
`/Volumes/SSD/cantonese_ngram/raw/aalto-ite-v88/`:

- `ac_words_en.csv`: observed events. `TYPED_WORD` is the observation;
  `ORIGINAL_WORD` supplies the initial reference word. **`AC_WORD` is another
  keyboard's output, not ground truth.**
- `sentences.csv`: reference sentences for conservative target-span alignment.
  Remove the observed suffix from `CURRENT_INPUT` before using left context;
  never feed reference text into inference.

Extraction is defined by `scripts/audit_candidate_pipeline.py::mobile_cases`:
seed `occam-pipeline-20261020-v1`, default `reserved=False`, at most eight events
per prompt, deterministic ordering, 6,000 events plus 6,000 source-copy controls.
The separate hash-reserved prompt partition is not part of this evaluation.
`scripts/align_mobile_targets.py::reference_target` excludes unmatched/ambiguous
spans and resolves uniquely aligned multiword targets. Controls retain their
original labels. Alignment retains 9,728 cases across all partitions.

Partition with `scripts/iterate_english_policy.py::split`, seed
`english-iteration-20261020-v1`: hash modulo 10 gives TRAIN 0–5, selection 6–7,
evaluation 8–9. The grouping key is the sentence/prompt ID, **not a participant**.
Within TRAIN, `hashed("calibration:" + group)` modulo 5 equal to zero selects
cutoff calibration; the remainder fits coefficients.

The fixed **evaluation partition has 2,042 cases: 744 typos and 1,298
preservation controls**. Missing candidates and abstentions remain in the
denominator. Do not evaluate only examples whose gold target is in the vocabulary.

Identity checks (SHA-256):

| Input | SHA-256 |
|---|---|
| Full local `cases.jsonl`, including prefix examples | `5f51fa164e2bf2b59080fd44affb20b8340557a498b822e6423d3b20f1d8f958` |
| `ac_words_en.csv` | `bbfff38259647e652f350c0c6821029c958adafbe245ece60e888953bcb01cc2` |
| `sentences.csv` | `506741069ee7f2b0238f8e9f4035677d762b6aa119641396af3d1db2dc059d2a` |
| Sorted evaluation IDs, joined by newline with trailing newline | `2a6361cda8ee35c00bfed060b409561d4abe6f863597df8eb029edea18271e5c` |
| Sorted `(id, aligned_target)` pairs, compact UTF-8 JSON, no trailing newline | `ab9432f658fd7be9647f6e782d820ed75ac781266a5dba090152c746bb6ac1a3` |

Local cases/cache: `build/english-iteration-2026-10-20/` (ignored build output).
The committed report records their hashes. Preserve this frozen baseline cache:
it contains pre-change scores; rebuilding it with the current scorer and then
adding back the old completion penalty would invalidate comparisons.

This is development evaluation with historical exposure, not a fresh independent
test. Prompt-disjoint splits are not participant-disjoint, and source-copy controls
are not a complete clean-typing dataset. Do not open protected LM confirmation,
test or reserve files for this work.

## F₀.₅ definition and accepted baseline

TP = correct automatic replacements. FP = all wrong automatic replacements,
including changes to clean controls. FN = typo cases minus TP. A wrong replacement
on a typo contributes both FP and FN. Abstaining on a typo contributes FN.

```text
precision = TP / (TP + FP)
recall    = TP / (TP + FN)
F₀.₅      = 1.25 TP / (1.25 TP + FP + 0.25 FN)
```

Use zero for an empty denominator, as in the evaluator.

| Rule | TP | FP | FN | Precision | Recall | F₀.₅ |
|---|---:|---:|---:|---:|---:|---:|
| Original heuristic | 399 | 112 | 345 | 78.08% | 53.63% | 0.71557 |
| Earlier precision-focused rule | 224 | 12 | 520 | 94.92% | 30.11% | 0.66351 |
| Two-feature F₀.₅ rule | 336 | 28 | 408 | 92.31% | 45.16% | 0.76364 |
| Literal-aware word rule | 361 | 23 | 383 | 94.01% | 48.52% | 0.79167 |
| Word + apostrophe restoration | 364 | 23 | 380 | 94.06% | 48.92% | 0.79407 |
| Withdrawn neighbour-fallback experiment | 372 | 25 | 372 | 93.70% | 50.00% | 0.79760 |
| Shared-rule refit | 397 | 33 | 347 | 92.33% | 53.36% | 0.80560 |
| **Current five-cost calibration** | **418** | **32** | **326** | **92.89%** | **56.18%** | **0.82154** |

The shared-rule refit changed **8/1,298** preservation controls (originally 5).
It gains 33 correct fixes and adds 10 wrong replacements, including three clean
changes. The primary evaluation still reports only unambiguous neighbouring
replacements, with clean controls and the full real-typing cohort separately.

That earlier refit changed only the existing six-feature coefficients and global cutoff. The separate
single-edit fallback and runtime dictionary were removed at the user's request.
Per-length constants were tested on TRAIN/selection, but the selected global
refit performed better under the agreed metrics. There is no runtime length branch.

The earlier compiled decisions matched the frozen shared-rule prediction on **24,567** cases,
including 4,848 new held-out synthetic word-group cases. Model SHA-256:
`915bc80d53d225469e8bfe90d328ac1565647181e485cb3f0e9c32f47c946322`.
Current policy SHA-256:
`1d5733cc3f09778485318cb5c579086f67977b19dc52577cd6dbd298ae4784da`.
Prior word-policy SHA-256:
`d97608cbbbd36bc257858760c3e70ed38269efae9efb9c16e0a236fe73947ffd`.

Earlier shared-rule reports: [selection](config/english_iteration/2026-10-21/acceptance-refit/selection.json),
[evaluation](config/english_iteration/2026-10-21/acceptance-refit/evaluation.json),
[native/generalization verification](config/english_iteration/2026-10-21/acceptance-refit/native-verification.json).
The earlier [refit report](ACCEPTANCE_REFIT.md) records length ablations and remaining errors.
The [lexical fallback](UNAMBIGUOUS_NEIGHBOR_REPAIR.md) is historical and withdrawn.

Current restoration policy SHA-256:
`0de96ace3507ddb0aeb9944b0ece3cd389aa434f2bc87640bd9044be4cf25d96`;
TRAIN surface/count data SHA-256:
`610b016b874e823de73b41a1c9fb62705e7af15a463f6e3a5542e3102cdbc9a8`.
Current [restoration report](APOSTROPHE_RESTORATION.md) records the frozen
[evaluation](config/english_iteration/2026-10-21/apostrophe-evaluation.json).
Restoration gains three evaluation fixes with no additional wrong changes, while
selection F₀.₅ declines from 0.80452 to 0.80185. Retain both results.

The current [error analysis](AUTOCORRECT_ERROR_ANALYSIS.md) attributes missed
fixes to generation, ranking and acceptance, with fixed diagnostic ablations.

Source reports: [frozen selection](config/english_iteration/2026-10-20/literal-selection.json),
[evaluation](config/english_iteration/2026-10-20/literal-evaluation.json),
and [native parity](config/english_iteration/2026-10-20/literal-native-verification.json).
See [the literal-aware iteration](LITERAL_ACCEPTANCE_ITERATION.md) for behavior,
latency and remaining weaknesses. Older dated reports retain previous baselines.
Update this accepted baseline and its hashes when a new improvement is accepted;
retain dated previous reports.

## Latency evaluation

The product metric is **input event to first displayed, selectable candidates**.
Measure cold initialization separately from warm typing. Keep first-preview and
full-ranking timestamps separate; include worker contention and rendering in
device measurements. Report device/build, model/library hashes, sample count,
contexts, input sequence and median/p95. Include rapid typing, cancellation and
an occupied English worker so engine speed does not conceal UI waiting.

Repeatable host panel: `scripts/benchmark_quick_short_codes.py`, both Quick
versions, codes `d`, `df`, `of`, `hi`, `ab`, `zz`, `DF`, and contexts
`("", "")`, `("I use ", "")`, `("我想", "")`, `("你", "好")`.
Default two alternating process runs produce 112 paired comparisons. Require
identical full candidate lists and identical preview candidate sets.

Recorded release-host `df` baseline: full ranking median **159.1 ms**, p95
**212.7 ms**; preview median **0.72 ms**, p95 **0.75 ms**. These engine-call
numbers exclude startup, Android scheduling/editor calls and rendering. **Phone
input-to-display latency remains unmeasured.** Do not compare host numbers with
phone numbers as if they measured the same boundary. See
[QUICK_CANDIDATE_LATENCY.md](QUICK_CANDIDATE_LATENCY.md).

For the current literal-aware acceptance iteration, the paired English engine
panel measures 2.759 → 2.775 ms median and 5.623 → 5.616 ms p95 (1,024 calls per
binary); current `df` preview is 0.712 ms median / 0.739 ms p95. See
[LITERAL_ACCEPTANCE_ITERATION.md](LITERAL_ACCEPTANCE_ITERATION.md) and its reports.

Real-context candidate panels must come from SSD development data:
`/Volumes/SSD/cantonese_ngram/experiments/keyboard-eval-v13-final/development.jsonl`.
Keep selected IDs/document groups fixed across comparisons. Prefix development
data is `data/shared-multilingual-v1-stage/eval/finalist_suite.jsonl` under the same
SSD root; top-1/top-8 retrieval metrics supplement correction F₀.₅.

## Commands

```sh
python3 scripts/fit_english_acceptance.py --iteration build/english-iteration-2026-10-20 --output build/new-policy-fit.json
python3 scripts/verify_english_acceptance.py --iteration build/english-iteration-2026-10-20 --probe /path/to/current/ngram_probe --output build/new-policy-native.json
python3 scripts/benchmark_quick_short_codes.py /path/to/before/ngram_probe /path/to/after/ngram_probe --output build/quick-latency.json
bash scripts/test_english_autocorrect.sh
./gradlew :app:verifyKeyboard :app:compileDebugAndroidTestKotlin
```

The fitter writes an experimental report; it does not install coefficients.
For the current literal-aware family, use `scripts/iterate_literal_acceptance.py`
with `--baseline-policy config/english_iteration/2026-10-20/acceptance-policy-v1.json`.
First write a selection report; reveal evaluation only with `--evaluate` and
`--frozen-selection` pointing to that report. This fitter requires NumPy.
After accepting a fit, update the native policy, rebuild host/ARM64 libraries,
verify native parity and the packaged APK, and retain the before/after reports.

## Quick runtime contention regression (2026-10-21)

[Quick autocorrect contention](QUICK_AUTOCORRECT_CONTENTION.md) isolates inference
state while retaining shared weights and the accepted policy. Host English JNI
latency during Chinese `df` ranking improves from 277.55/301.12 ms median/p95 to
1.17/1.37 ms (32 calls per binary). This is separate from phone input-to-display
latency. Frozen F₀.₅ stays 0.79167; native policy decisions retain parity.

## Apostrophe restoration latency (2026-10-21)

The current pipeline restores TRAIN-attested missing apostrophes through the
existing CNN byte fallback. General host median/p95 changes from 2.878/5.865 ms
to 2.917/5.945 ms; the TRAIN apostrophe panel rises from 1.483/3.826 ms to
5.705/9.572 ms. This is an explicit latency cost for the F₀.₅ improvement.
See [method, quality and limitations](APOSTROPHE_RESTORATION.md). Phone latency
remains unmeasured.

## Withdrawn neighbour-fallback experiment (2026-10-21)

This experiment was removed at the user's request; its historical measurements
remain below. The [lexical fallback](UNAMBIGUOUS_NEIGHBOR_REPAIR.md) raised primary new-word,
unambiguous 4–5-letter recall from 33.7% to 95.6%. The full real-typing regression
F₀.₅ improves to 0.79760, with one additional clean change explicitly retained.
Release-host general English median/p95 changes from 2.768/5.665 ms to
2.783/5.695 ms. First fallback call median changes from 1.347 to 2.984 ms on
four process pairs; this includes lazy lexical initialization, excludes model
loading, and is not phone input-to-display latency.

## Shared acceptance refit, dictionary fallback removed (2026-10-21)

The [current refit](ACCEPTANCE_REFIT.md) changes only the existing acceptance
coefficients and global cutoff. Per-length constants were tested but did not win
the requested 4–5-letter selection comparison. No runtime length or edit-distance
branch is added. Full evaluation F₀.₅ is 0.80560, with clean changes 8/1,298.
The paired release-host median/p95 is 2.726/5.575 → 2.755/5.648 ms; candidate
lists are unchanged. These are engine timings, not phone input-to-display latency.

## Five existing edit costs calibrated (2026-10-21)

The [current edit-cost calibration](EDIT_POSITION_COSTS.md) keeps the accepted
six-feature policy/cutoff and tunes five operation penalties. Selection rejects
an extra first-character term. No new fallback or runtime word list is introduced.
Full real evaluation F₀.₅ is **0.82154**, 418 correct / 32 wrong corrections,
with 8/1,298 clean changes. Primary unambiguous-neighbour recall is 482/617
(78.12%); 4–5-letter recall is 22/63 (34.92%), with two wrong short-word fixes.
Selection and evaluation are frozen in
[the dated reports](config/english_iteration/2026-10-21/edit-position-costs/).
Model, word-policy and apostrophe-policy hashes above are unchanged. Native
ranking now changes through five constants in `cnn_spelling.rs`; use the new
verification report rather than the earlier coefficient-only parity report.

Current paired English release-host median/p95 is 2.735/5.553 → 2.752/5.589 ms,
1,024 calls per binary. Candidate ranking changes; candidate pools are preserved.
Phone input-to-display latency remains unmeasured.

## Additional-edit surcharges tested and retained (2026-10-22)

The [two-surcharge experiment](ADDITIONAL_EDIT_COSTS.md) compares four TRAIN-fitted
proposals with the v66 baseline while holding the five base costs and acceptance
policy fixed. None qualifies on selection: best real F₀.₅ is 0.82372 versus
0.82578; short primary fixes fall 32/72 → 25/72 for that proposal. The 30 lost
primary fixes retain their correct top-ranked candidate but lose acceptance
through smaller runner margins. Keep surcharges 3.0 and 4.5. No candidate
evaluation, native/runtime change, latency rerun or release was performed.
The accepted baseline remains v66, overall evaluation F₀.₅ 0.82154.

## Boundary timing and domain representativeness audit (2026-10-22)

Automatic replacement commits at a word boundary; composing-word suggestions
update while typing. Therefore a word-boundary event with its observed sentence
prefix is an appropriate core scoring unit. Keep per-key candidate updates,
boundary queues, timeout/cancellation and undo as production-controller integration
coverage. Full keystroke replay is supplementary, not required to define the
boundary-decision F₀.₅ metric. Earlier prefixes may still reflect another keyboard's
corrections, so this is not a closed-loop sentence evaluation of our own outputs.

The [domain audit](config/english_iteration/2026-10-22/domain-audit.json) uses the
source CSV's `SOURCE` labels and the existing frozen v66 native outputs. It
introduces no fitting or cohort changes. The evaluation contains 259 prompt
groups, with median eight words per prompt, and 2,042 scored cases:

| Source label | Cases | Share of cases | F₀.₅ |
|---|---:|---:|---:|
| email | 740 | 36.2% | 0.83060 |
| news | 525 | 25.7% | 0.81803 |
| memorable | 472 | 23.1% | 0.81694 |
| mobile | 305 | 14.9% | 0.81301 |

Prompt shares differ: mobile is 47/259 (18.1%). Source labels are provenance
categories, not a measured distribution of this keyboard's users' activities.
The prompt set has no non-ASCII text, URLs or at-signs; only 16/259 prompts
contain a question mark. Informal chat, slang, identifiers, emoji-adjacent words,
and Cantonese/English code switching are not qualified by this corpus. Of 1,298
preservation controls, 1,254 are constructed clean reference words and only 44
are recorded preservation events. The sampled error/control prevalence must not
be treated as natural mobile usage or used to claim deployment precision.

Retain this cohort as a fixed English correction regression benchmark, with
per-domain results alongside the overall score. Its F₀.₅ 0.82154 does not establish
representative quality for the full mobile keyboard. A mobile-product evaluation
needs separately sourced conversational messages/fragments, realistic clean/error
prevalence, names/addresses/URLs and mixed-language controls. Freeze source mix,
weights, grouping and labels before fitting; do not retrospectively reweight
this exposed cohort or infer product usage weights from these results.

## Verified public provenance and source-label clarification (2026-10-22)

The source is Aalto University's [ITE Typing dataset](https://zenodo.org/records/12528163),
published in 2024 from a 2019–2020 mobile typing study. Its
[official documentation](https://zenodo.org/records/12528163/files/README-datasets.md?download=1)
identifies `ITE_words/ac_words_en.csv` as English autocorrection events and
provides both sentence references and full keystroke logs upstream. Our current
local evaluation uses the extracted AC events and sentence table, not that full
keystroke log. The original study included 46,755 English participants using
their own mobile devices, with keyboard interventions labelled afterwards.

The source prompts include Enron email and Gigaword news. The
[Enron mobile phrase-set publication](https://www.keithv.com/pub/enronmobile/)
explains that its mobile sentences were obtained from genuine BlackBerry emails
and includes phrase sets selected for memorability. “Memorable” does not mean
invented text. Likewise the `mobile` SOURCE fraction describes a prompt-origin
bucket, not the fraction of typing observations collected on phones. The
previous informal characterization of this as mainly generic text rather than
published mobile typing research was incorrect.

Our 2,042-case evaluation is a locally constructed, filtered, prompt-grouped
benchmark derived from that public research dataset. Its sampling, conservative
alignment, added clean controls, splits and F₀.₅ scoring are our protocol, not an
official dataset leaderboard or a score directly comparable with other keyboards
without reproducing this protocol. Real mobile typing provenance does not remove
the transcription-task, selected-intervention, domain and exposure limitations
recorded above.

## Public real-touch evaluation and synthetic training (2026-10-22)

[Touch evaluation](TOUCH_EVALUATION.md) adds a frozen public phone-touch diagnostic:
24 participants, 480 sentences, 3,081 uniquely labelled observed words plus
whole-trial CER over all 480 sentences. Only normal-size, explicit-space,
visible-feedback phone records are admitted by metadata. Real taps never fit
the new coordinate/acceptance models; their fit/calibration/selection use 2,400
synthetic words from existing TRAIN text.

Production vs synthetic touch F₀.₅ is 0.86775 → 0.86945, but correct fixes fall
332 → 325 while wrong fixes fall 15 → 11; whole-trial CER worsens
2.8448% → 2.8650%. Participant-bootstrap uncertainty includes no improvement.
The separate synthetic text-only refit scores 0.85642. Keep all three results;
do not attribute acceptance refitting alone to touch. Primary unambiguous-neighbour
F₀.₅ is 0.95694 → 0.96154 (80 → 75 correct fixes, 3 → 1 wrong).

This is an exposed development diagnostic, not production qualification: older
English mobile-email copy typing does not cover Cantonese/English chat or our
layout, key outputs are reconstructed, and 137/480 trials share source prompt
text with synthetic training. Ambiguous labels stay out of word F₀.₅ but remain
in sentence CER. Do not remove difficult/missing-target cases or retune against
these results. The older `human-touch-v28` is the same Google TSI source, already
exposed across all 16 participants; it is not fresh confirmation either.
No shipping scorer/policy, Android asset or accepted baseline changes.

## Unified suggestion scheduling and score-table filtering (2026-10-22)

The [unified request](UNIFIED_SUGGESTIONS.md) retains exactly the same English
candidate scores/order and correction decisions on all **2,042** frozen evaluation
cases. F₀.₅ remains **0.82154**, with 418 correct / 32 wrong replacements and
8/1,298 changed preservation controls. No fitting or policy changes were made.

A paired host panel (both Quick versions, seven short codes, four contexts, two
alternating repetitions) preserves every short-code candidate set. Completed
native request median/p95 improves **145.253/480.650 → 1.748/3.802 ms** (112 pairs).
`df` computes one shared next-token distribution. This compares completed rows;
the old pipeline also published an earlier preview, so it does not establish an
improvement in first selectable candidates or Android input-to-display latency.

The separate six-code phrase panel improves **117.297/362.695 → 86.510/336.234 ms**
(96 pairs). Filtering by LM score before the segment shortlist changes two tail
phrases for `humo` in English context on each Quick version; top-eight results in
those cases remain unchanged. Phrase candidate coverage is therefore explicitly
not identical. The full panel and exact changed candidates are retained in
[the report](config/pipeline_audit/2026-10-22/unified-suggestions.json).

## Rapid-typing previews and handwriting priors (2026-10-23)

Current-input literals publish immediately; an independent fast preview precedes
the unchanged full composing result. The 208-request host panel covers both
Quick versions, four contexts, seven short codes and six word/phrase codes, in
two alternating process runs. Every final candidate list and English decision
matches the rebuilt before binary. Short-preview Chinese coverage and all preview
English decisions match their final results. Long-code preview phrases use a
greedy path and are approximate until full ranking finishes.

Host median/p95 is 2.720/295.665 ms for the former full request,
2.764/296.601 ms for the current full request, and 1.832/5.566 ms for the new
preview. These timings exclude initialization, Android scheduling and rendering;
they do not establish phone input-to-display latency. No acceptance fitting or
model changes were made; this panel supplements the frozen F₀.₅ protocol.

Handwriting fusion now assigns an unsupported class the weakest supported
neural prior when no finite count prior exists. Previously the missing negative
prior was skipped, giving unsupported alternatives an advantage. On all 181
existing exposed captures with their original empty context, top-1/4/10 changes
110/125/134 → 126/145/156; 16 first choices improve and none of the previously
correct first choices are lost. Missing model targets remain misses. This is a
development replay, not fresh evaluation or a contextual handwriting benchmark.
Inputs, native binaries and raw scores are bound in the
[report](config/pipeline_audit/2026-10-23/rapid-candidates-and-handwriting.json).
