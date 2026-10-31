# Autocorrect error analysis — 2026-10-30

## Decision

The current priority is **immediate correction quality and representative
evaluation**. The subsequent recorded closed-loop audit finds 107 of 135
remaining touch misses occur with all earlier words already correct; repairing
every labelled earlier error recovers only four. The synthetic noise stress
below establishes a real weakness in LM scoring and acceptance, but does not
establish that it dominates production failures. See
[Product autocorrect evaluation](PRODUCT_AUTOCORRECT_EVAL.md) for the updated
recorded-stream attribution, candidate experiment and evaluation coverage gaps.

There is also a distinct **bilingual display-ranking weakness**: adding full
Chinese phrase candidates frequently hides English answers that the preview
already showed. It affects discoverability and response time, while the native
English automatic-correction decision remains identical between preview and full.

Do not start by training a large English-only correction teacher. The available
machine is an Apple M4 Mac mini with 16 GB unified memory. The current shared
English/Cantonese CNN has 11,527,808 tensor parameters and a 12,675,264-byte INT8
export. Its floating-point checkpoint and MPS trainer are available locally.
Capacity expansion has not been established as the primary bottleneck.

Priority qualification: the synthetic prior-error audit deliberately leaves the
earlier error in context. A good immediate corrector removes some such errors
before the next word arrives. The audit establishes sensitivity, not how often
that weakness is encountered after our own corrections. Before a substantial
robustness-training investment, replay the current release in a closed loop,
feeding its actual committed words into subsequent inference, and attribute
remaining context failures to missed earlier corrections, wrong earlier
corrections, and valid rare/mixed-language text. Existing closed-loop revision
reports use an older checkpoint and cannot supply current-release attribution.
Improving immediate candidate coverage and error-channel ranking directly
addresses the upstream missed corrections and can reduce downstream exposure.

## Evidence and boundaries

Rebuilt the current native `ngram_probe` and `suggestion_probe` locally. Replayed
all 5,261 frozen autocorrect cases with **zero output differences from version
74**, excluding timing. The unchanged official aggregate is **0.8736146656392934**,
delta **0.000000** from the accepted baseline. No model, policy, production code,
threshold, or dataset labels were changed.

The frozen suite contains 1,315 labelled typos, 3,900 clean cases, and 46 ambiguous
unlabelled cases. Counts pooled below are diagnostic workload counts, not an
estimate of real user traffic or a replacement for the official weighted score.

Also ran 1,086 full bilingual requests and 1,086 preview requests. These include
the existing development cohorts, plus **255 deterministic synthetic errors from
64 natural English-in-Chinese-context examples**, each tested in Quick3 and
Quick5. Those two versions are paired observations, not independent samples.
Each synthetic error is a deletion, adjacent horizontal key substitution,
duplication, or transposition. No current output was used to choose the error.
Future/right context was removed from every request. These cases are diagnostic
only and must not be fitted or used to select training hyperparameters.

The existing prior-context audit was reused after verifying the identical model
and native probe hashes. It is a synthetic stress test on exposed development
data, not independent real-world accuracy. Chinese target selection and prefix
completion are reported separately from automatic English correction.

## 1. Where the 412 missed corrections go

| Earliest observed obstacle | Cases | Share of misses |
| --- | ---: | ---: |
| Correct target first, not committed | 142 | 34.5% |
| Target generated but ranked below first | 141 | 34.2% |
| Target absent from generated results | 129 | 31.3% |

The middle row includes 126 targets at ranks 2–8 and 15 found below rank 8 by
expanding diagnostic output to 4,096 candidates. None of those diagnostic calls
hit that cap. There were 903 correct automatic fixes, 50 wrong changes on typo
cases, and 8 unwanted changes to clean text.

This corrects an important limitation of a top-eight oracle: absence from the
top eight does not establish failure to generate the target. Expanding the
diagnostic output separates ranking from generation without changing decisions.

The 129 targets absent from generated output break down as follows:

- **66 outside the edit radius.** Radius is one for observed lengths 1–3, two
  for lengths 4–5, and three for lengths 6+. For example, `toy` → `you` needs two
  substitutions and is excluded at length three. Training the LM cannot change
  this search boundary.
- **59 outside ordinary single-word vocabulary/handled forms:** 28 single words,
  17 apostrophe forms, and 14 multiword targets. Examples missing from the LM's
  ordinary candidate vocabulary include `freaking`, `resend`, and `sitter`.
  Apostrophes and spaces have additional limited generation paths; these counts
  refer to targets those paths did not produce either.
- **4 rejected inputs containing commas**, such as `can,t` → `can't`. This is an
  input-format/pipeline coverage mismatch, not evidence of weak LM ranking. The
  live keyboard may process the comma as a boundary rather than one word.

Wrong inputs that are themselves known vocabulary words are particularly hard:
129/251 corrected (**51.4% recall**) versus 774/1,064 (**72.7%**) for unknown
inputs. This is a descriptive slice; the underlying error distributions differ.

## 2. Prior errors destabilize otherwise useful correction evidence

The same 1,118 typo cases with eligible preceding context are evaluated in each
row. Only a preceding word changes; the current typo and its gold target do not.

| Preceding context | Correct fixes | Recall | Target first | Target in top 8 |
| --- | ---: | ---: | ---: | ---: |
| Original | 772 | 69.1% | 896 | 1,005 |
| One deletion | 608 | 54.4% | 800 | 984 |
| One substitution | 575 | 51.4% | 800 | 985 |
| One duplication | 580 | 51.9% | 801 | 988 |
| One transposition | 569 | 50.9% | 803 | 980 |

The substitution test loses 217 previously correct typo fixes and recovers 20
others. Of those 217 lost fixes:

- **148 still rank the intended word first, but do not commit it.**
- 64 move the intended word to ranks 2–8.
- 5 lose it from the top eight.

Example: `Appointing Jeff as CFO is one important ` + `steo` corrects to `step`.
After `important` becomes `imoprtant`, `step` remains first but is withheld.
This is a much more specific failure than simply having too small an LM.

The earlier acceptance-only noise training has already been tried. It improved
the official score by only 0.000299; see `NOISY_CONTEXT_ACCEPTANCE.md`. Repeating
that exact intervention is not a strong next experiment. A model-level robustness
intervention should be distinguished from another threshold or gate-only fit.

## 3. Mixed Chinese/English needs its own correction and ranking measurements

For the 255 synthetic English errors in natural Chinese/mixed context, both
Quick3 and Quick5 make exactly the same English correction decisions:

| Native English decision | Chinese/mixed context present | Context removed |
| --- | ---: | ---: |
| Correct automatic fixes | 151 | 129 |
| Recall | 59.2% | 50.6% |
| Precision | 96.2% | 94.9% |
| Intended word ranked first | 192 | 164 |
| Intended word in English top 8 | 232 | 224 |

The Chinese context is useful. Dropping it or routing to an English-only model
would discard measured information. There are still 41 correct first-ranked
answers withheld and 40 targets at ranks 2–8. On the 64 uncorrupted English
inputs in Chinese context, one input is changed incorrectly.

There is a separate display problem:

| Intended English word visible in top 8 | Quick3 | Quick5 |
| --- | ---: | ---: |
| Preview | 207/255 | 207/255 |
| Full candidate result | 170/255 | 171/255 |

Full ranking demotes 38 previously visible targets below rank 8 in Quick3 and
recovers one; Quick5 demotes 37 and recovers one. For example, in the frozen
Chinese restaurant context, `motheer` → `mother` is the first preview suggestion,
and the native automatic correction is also `mother`. The full row pushes it
below eight Chinese phrase alternatives.

This does **not** establish that Space commits the wrong word: preview/full
English outputs are identical on all 1,086 paired requests. Candidate visibility
and automatic application are distinct. The current bilingual ranker has four
features and was fitted on only 150 feature rows; that is a plausible modelling
and data limitation, not a proven exclusive cause.

Chinese retention is also essential: the full row contains the intended Chinese
character in its top eight for 63/64 code cases, and the intended phrase for
59/64 phrase cases. Preview phrase coverage is only 44/64. Keeping the preview
forever or suppressing Chinese phrases would lose Chinese quality.

The separate prefix cohort is weak (18/64 intended completions in the top eight),
but this is completion, not automatic correction, and two-key Quick codes have
an explicit Chinese-first policy. It should not be counted as correction recall.

## 4. Timing is a separate potential phone issue

On this Mac, sequential English-only native calls across the frozen suite have
median **0.932 ms**, p95 **2.599 ms**, max **7.861 ms**. None exceed 40 ms.

Full bilingual search is much slower: on synthetic English-in-Chinese-context
inputs, full-result p95 is **268 ms (Quick3)** and **311 ms (Quick5)**; preview
p95 is **13.4 ms** and **4.5 ms** respectively. The English prediction is identical.

The production controller has a separate English-only boundary worker and a
40 ms deadline. Consequently, full-search timing is not proof that automatic
corrections time out. This run does not measure Android scheduling, rendering,
thermal throttling or actual boundary deadlines. Phone integration remains a
measurement gap; increasing model size could make it worse.

## 5. Component interventions: evidence beyond individual examples

The additional diagnostic script reproduces the compiled acceptance decisions
with zero mismatches on all **5,261 frozen outputs and 33,160 original/noisy
outputs**. It fits no weights and selects no thresholds. The exact results are
in `config/english_iteration/2026-10-30/error-analysis/component-diagnostics.json`.

### Ranking includes an error-channel problem

Of the 141 missed targets that are generated but ranked below first, **41 have a
higher raw LM score than the current winner but lose after the edit penalty**.
Another 99 lose on raw LM evidence too; one uses the separately calibrated
apostrophe path. This is a pairwise comparison with the actual winner, excluding
the incomparable byte-scored apostrophe path, not a claim that raw-LM-only
ranking would be better overall. The current error channel consists mainly of
five operation costs and fixed extra-edit surcharges. Further LM training alone
does not address all ranking failures.

### The acceptance gate protects many valid inputs

As a deliberately extreme diagnostic, unconditionally committing the first
suggestion raises correct typo fixes from **903 to 1,045** but changes **1,759
clean controls**, compared with eight currently. This is not a proposed policy
or threshold sweep. It establishes why the 142 withheld correct answers cannot
be treated as free recall gains. The gate needs evidence that distinguishes
them from wrong proposed edits.

### Noisy-context decisions change because of the LM features

Within the substitution stress test, 69 lost fixes retain the exact same
first/second candidate identities and unchanged spelling/dictionary features.
All 69 are eligible for correction. Restoring only their original-context LM
features restores acceptance in all 69. Restoring the candidate's absolute score
alone restores 39; restoring runner-up margins alone restores 40. Those counts
overlap. The median candidate-score drop in these lost cases is **5.41 log
points**, versus **1.56** among fixes retained with stable top-two candidates.

These are oracle feature substitutions using unavailable original-context
evidence, not deployable improvements. They isolate which inputs change the
gate; they do not establish that the gate or LM is solely responsible.

### Word-to-byte encoding is a specific LM weakness

The live tokenizer encodes a known English word as one token and an unknown
word as UTF-8 byte tokens. It discards whitespace and provides no explicit
end-of-word marker in this path. A typo can therefore change both the word and
its representation, while obscuring that typing has reached a word boundary.

In the substitution cohort, byte fallback increases in **910/1,118** contexts
and in **198/217** lost fixes. None of those 217 losses involves truncating the
32-token context. Context overflow is therefore not the explanation for these
losses.

A controlled representation intervention uses all **933** eligible substitution
cases whose original edited context word and intended next word have vocabulary
tokens. Without changing the preceding word's text, force just that word to use
its byte encoding. Compare this with ordinary encoding and actual substitution:

| Native LM diagnostic | Original encoding | Actual typo | Same original word forced to bytes |
| --- | ---: | ---: | ---: |
| Median intended next-word log-score change | 0 | -3.99 | -4.29 |
| Median probability mass assigned to byte tokens | 6.5% | 86.3% | 89.5% |

The independently reproduced normal/noisy context encodings match native token
IDs exactly. This intervention demonstrates a representation sensitivity even
without changing word content. The high byte mass is consistent with the model
continuing a byte sequence rather than confidently starting the next word.
Conditioning on a non-byte next token still leaves a median **2.09-log-point**
target-score drop with actual substitution. Probability allocation is therefore
only part of the problem; context interpretation/ranking also degrades.

Forced encoding is artificial. This does not prove that adding a boundary token
will fix the problem, or that the tokenizer alone accounts for every lost fix.
It makes boundary-aware byte-context training a much more specific experiment
than increasing capacity or fitting a KEEP gate around one abbreviation.

## Model robustness experiment to revisit after product evaluation

If representative evaluation justifies the investment, run a bounded
**boundary-aware, noise-robust fine-tune of the existing
11.5M-parameter shared CNN**, using its existing floating-point checkpoint and
MPS trainer. The training task must explicitly indicate when a byte-encoded
context word has ended and require prediction of the next word/Chinese unit.
Corrupt prior English spans in TRAIN-only English and mixed-language contexts,
and also byte-encode some unchanged English context words, retaining the
original next-word targets. Replay clean Cantonese, English and mixed examples;
Chinese context must remain visible.

Use a matched small experiment: current checkpoint; noise/encoding augmentation
with the existing boundary representation; and the same augmentation with an
explicit trained word-boundary signal. This distinguishes robustness training
from the boundary intervention. Adding a marker only at inference would be an
untrained distribution change and is not a fix. A marker variant requires
matching native tokenization/scoring and independently verified export parity.

Use one small pilot before a sweep, with an initial throughput/memory benchmark
to set a local wall-time budget. No rented GPU or large teacher is needed. Do not
promise an ETA from model size alone; activations and vocabulary projection also
matter. Existing MPS training configuration uses batch 64 and sequence length 64;
the pilot should lower these if current memory pressure requires it.

Measure the new LM first with the existing gate, then with acceptance calibration
fit only on separate TRAIN/calibration data. This distinguishes score robustness
from a changed correction threshold. Require the official aggregate to improve
and inspect recall at comparable precision, clean-word damage, noisy-context
performance, English-in-Chinese correction, Chinese character/phrase retention,
and quantized native latency. Preserve valid rare words, abbreviations and
names through independently sourced clean controls rather than a special-case
list derived from reports. The current test cases and their mutations must
remain excluded from fitting and checkpoint selection.

Treat bilingual display reranking as a separate small, inexpensive experiment:
fit on substantially more TRAIN-only English/Chinese competing candidate sets
and test English visibility and Chinese retention together. Do not hide the
problem by mixing its results into the automatic-correction score.

Candidate radius/vocabulary expansion is another independent experiment. The
129 unreachable targets establish a hard coverage limit, but expanding the pool
also adds distractors. It should be tested separately before attributing gains
to LM training. No correction gain is claimed by this diagnostic work.

## Artifacts and reproduction

- Compact report: `config/english_iteration/2026-10-30/error-analysis/report.json`.
- Component interventions: `config/english_iteration/2026-10-30/error-analysis/component-diagnostics.json`.
- Cases, exact native outputs, misses and timing: `build/error-analysis-20261030/`.
- Reused prior-context audit: `build/noisy-context-release74-check/`.
- Three diagnostic unit tests pass; native output parity against release 74 is
  exact. No Android/model change was made, so the Android build gate was not rerun.

```sh
CARGO_TARGET_DIR=/private/tmp/keyboard-release74-host cargo build --offline \
  --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml \
  --release --features android --bin ngram_probe --bin suggestion_probe
python3 scripts/analyze_autocorrect_errors.py \
  --probe /private/tmp/keyboard-release74-host/release/ngram_probe \
  --joined-probe /private/tmp/keyboard-release74-host/release/suggestion_probe \
  --output build/error-analysis-NEW-RUN
python3 -m unittest discover -s scripts -p 'test_analyze_autocorrect_errors.py'
# Requires NumPy and the existing local acceptance feature adapter:
python3 scripts/analyze_autocorrect_components.py \
  --tokenization-probe /private/tmp/keyboard-release74-host/release/ngram_probe
```

The script requires the preserved release-74 outputs, prior-context audit and
frozen bilingual cohorts. It checks their model/case bindings and records hashes;
it refuses to overwrite a new inference run. `--summarize-existing --output
build/error-analysis-20261030` recalculates derived diagnostics without inference.
