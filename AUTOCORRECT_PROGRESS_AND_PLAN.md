# Autocorrect: current progress and implementation plan

Updated: 2026-10-17. This is the handoff for the English, Cantonese and mixed
Traditional Chinese/English keyboard work.

## Intended result

Correct ordinary typing errors while preserving intentional words, unfamiliar
names and valid words. Show useful English and Cantonese candidates together.
Use the language model to rank linguistic plausibility and the typing channel
to explain the observed keys/taps. When more context arrives, reconsider an
uncertain recent word through the same scoring rule.

Constraints from the user:

- Support English chat/business language, Cantonese and mixed input.
- Use native Traditional Chinese sources; do not convert Chinese script.
- Keep deployed models, vocabulary and supporting resources below 30,000,000 bytes.
- Improve general common-term coverage; do not fit a special case for 譚仔米線.
- Keep the decision inputs simple and evaluate actual unwanted replacements.
- **Do not upload yet.** This remains the latest publication instruction.

## Current enabled state

Play internal remains version 51, whose internal autocorrect trial is enabled.
The local version 52 upload was canceled. Later experiments described here have
not been installed on the user's phone or published to Play.

The local default internal profile remains v80. The newer simple-policy and
Gaussian experiments have not replaced its assets. Ordinary release builds
still default to the internal trial being off unless explicitly configured.
Do not confuse an enabled older trial with the latest experimental model.

No joint ranking training has started. The last turn proposed that direction
and inspected the existing training/scoring code; it was interrupted before
implementation. The Gaussian experiment is complete and has no running job.
No commit or upload was made during the recent v82–v87 work.

## Completed implementation

- Native spelling search supports bounded multi-edit candidates, short words,
  actual tap positions and a retained literal/original candidate.
- Grouped LM inference reuses identical prefixes. The frozen host comparison
  measured about 1.6× improvement with score parity; this is not phone latency.
- Whole-word LM evidence avoids the old byte-token averaging error.
- An optional simple decoder ranks with contextual LM evidence plus a native
  typing score. Its retained diagnostic classifier uses only two score margins:
  winner over original, and winner over the next alternative.
- That optional path removes dictionary-familiarity rules, the spelling head,
  tree classifier and legacy correction fallback from ranking/acceptance.
- Later context uses the same rule. Revision guards, editor/cursor checks,
  bounded pending corrections and Undo remain in place.
- Failed isolated pipeline runs now retain fresh JUnit/editor outputs instead
  of stopping before the diagnostic report is written.

These mechanisms exist, but the latest candidate has not passed the accuracy
checks needed to replace the current profile.

## Data and model progress

The v82 campaign generated 363,675 requests: 236,313 string-corruption/copy
requests, 117,523 physical-touch simulations and 9,839 existing recorded-touch
requests. Document variants stay together. There are only eight recorded TRAIN
participants and four historical evaluation participants; synthetic rows do not
increase the number of independent people.

Corruptions include neighbouring and distant substitutions, insertion, deletion,
repeat, adjacent swap, multiple errors, short-word and valid-word errors, noisy
surrounding context, clean preservation and unknown-word copy controls. Physical
sensor simulation samples taps from intended keys and derives observed keys
from those taps. String-edit samples instead often place taps around the wrong
observed key; they are a different error process and must not be conflated.

The main LM remains the existing compact single-network architecture with a
69,632-token vocabulary. The retained v85 step200 model improves quantized
noisy-context development CE from 8.2932 to 7.7233 while passing the declared
English/Cantonese/mixed language-retention checks. The separate Cantonese
Quick/handwriting model remains unchanged. Better CE alone has not established
better autocorrection.

The v86 fresh confirmation contains 22,873 requests. At its TRAIN-selected .95
cutoff, the simple policy makes 1,071 correct and 19 wrong replacements:
98.26% precision, 7.35% typo recall and three clean-input false changes.
Historical recorded-touch results are 43 correct / 3 wrong, from four people.
The profile does not pass the existing group-reliability guard.

Actual controller/JNI replay still misses `teh → the` and all 14 complete
reported user sentences. Two of four isolated JUnit methods fail. The existing
default-profile gate passes 212 tests, screenshots and debug build; those
mechanism/default results are separate from the experimental accuracy failures.

Experimental deployed resources total 27,115,632 bytes. Sampled native versus
MPS scoring gives 512/512 identical automatic actions and 511/512 identical
alternative winners. Scores are not numerically identical; retain native
verification before integrating any later candidate.

## Latest experiment: Gaussian touch likelihood, v87

Implemented and tested direct candidate-minus-original Gaussian touch scores,
with edit alignment for extra, missing and swapped taps. Compared a fixed
Gaussian, fitted shared geometry, and fitted geometry with an outlier mixture.
The classifier still takes two margins; raw coordinates are not its features.

Fitting uses 5,353 distinct clean TRAIN-rank taps from five participants.
The comparison holds the LM and candidate pools fixed and refits each arm using
the same disjoint TRAIN partitions. It reads 319,281 TRAIN/development/historical
rows and excludes the old confirmation rows. Development and historical cohorts
were already examined; there is no new independent confirmation in this ablation.

On 44,802 development requests:

| Channel | Correct / wrong replacements | Typo recall | Clean false changes |
| --- | ---: | ---: | ---: |
| Existing heuristic, refitted control | 2,288 / 64 | 8.08% | 8 |
| Fixed Gaussian | 2,342 / 67 | 8.27% | 18 |
| Fitted Gaussian | 711 / 25 | 2.51% | 9 |
| Fitted Gaussian + outliers | 1,841 / 66 | 6.50% | 22 |

The existing heuristic wins the declared utility comparison. Four numerical
checks pass, including an analytic likelihood ratio and randomized C++ versus
Python alignment parity. Keep the Gaussian code as an experiment, not a default
switch. Full details: [GAUSSIAN_TOUCH_V87.md](../dataset/keyboard-lm/GAUSSIAN_TOUCH_V87.md).

## Next implementation: train for candidate decisions

The next experiment should optimize the decision the keyboard actually makes:
choose the intended candidate, or preserve the original. Previous LM adaptation
optimized token CE, while ranking weight and acceptance were fitted separately.
A candidate-level objective directly measures the desired competition.

This is a hypothesis to implement and compare, not a claim that joint training
is sufficient or already implemented.

### 1. Freeze the baseline and measure the candidate ceiling

Reuse immutable model/data artifacts and record exact model, dictionary,
scorer and input hashes. Trace retrieval, ranking, acceptance and editor output
separately on the same requests. Report intended-candidate recall before any
model fit, including short/valid-word errors, unknown names and mixed context.
Missing candidates stay in the end-to-end denominator; never insert the answer
into a held-out candidate pool.

If the intended candidate is missing, repair generation before expecting a
ranking objective to recover it. Keep generation broad and bounded, with the
literal always available; dictionary membership should not itself decide whether
a replacement is safe.

### 2. Prepare decision training with realistic error processes

Use existing source/document-disjoint TRAIN data and the original rank/fit/cutoff
partitions. Reserve new confirmation groups before any further selection.
Keep user-reported sentences as regression checks, never fitted examples.

Keep physical mistaps, non-spatial spelling errors and absent-touch examples
explicitly distinguishable in the data audit. Use observed touches as inference
inputs, never intended-key centres substituted as observations. Include enough
clean and unfamiliar-word requests to train preservation alongside correction.
Deduplicate repeated contexts/taps and weight sources/groups so synthetic rows
do not overwhelm the few real participants. Add natural Traditional Cantonese
and mixed clean controls without script conversion or phrase-specific seeds.

### 3. Implement a bounded candidate-level training pilot

Start from the retained single LM and current typing channel. Score complete
candidate sequences consistently with the native runtime. Include the original
in the candidate competition and train against actual retrieved alternatives,
including plausible wrong words. Keep decision inputs to LM evidence and typing
likelihood/edit alignment; do not restore dictionary flags or redundant counts.

Prototype a listwise candidate loss and jointly fitted channel weight, with
natural English/Cantonese/mixed replay and parent-model retention regularization.
Use MPS BF16 for neural training/scoring, with no CPU neural fallback. Preserve
optimizer, RNG, sampler state, checkpoints and all rejected trial results.

Handle unreachable targets explicitly: exclude them from supervised candidate
ranking when no correct candidate exists, retain them in evaluation denominators,
and train any abstention behavior without relabeling a wrong literal as a correct
word. Unknown originals that are intentional remain valid preservation labels.

Important design check: the LM sees linguistic context, while the typing channel
sees observed keys/taps. Joint training must not force the same context to learn
contradictory word labels merely because different observations were supplied.
Validate this before committing to a larger run. A normalized candidate softmax
is also not automatically a calibrated probability of a safe correction.

### 4. Compare the operating trade-off, then calibrate once

Compare old and new policies at matched unwanted-replacement rates, not just at
the same numeric confidence threshold. Report typo recall, accepted precision,
clean false-change rate, valid-word/name preservation, candidate recall and
participant/document-group intervals. Separate actual recorded taps, physical
simulation, string edits, Cantonese/mixed context and short-word slices.

Use disjoint TRAIN calibration/cutoff groups. A sigmoid or softmax value of .9
does not guarantee 10% errors. Choose the operating point from measured costs
and the existing reliability checks, and assess reliability on held-out groups.
Do not pick a new cutoff after seeing confirmation results.

The practical development objective is a substantial recall increase at the
control's clean-input mistake rate, with no regression in unfamiliar names or
Cantonese/mixed preservation. Retain the existing minimum precision/group guards
for a promotion decision; do not call a tiny or synthetic-only gain production
quality. Freeze the finalist before scoring a new confirmation cohort.

### 5. Integrate the winning scorer and verify the complete keyboard

Only after the comparison identifies a useful candidate, implement its exact
score and artifact binding in Rust/Android. Verify native/export parity, resource
budget and bounded inference/search latency. Run the actual InputConnection
pipeline for typing, Space, punctuation, rapid continuous input, mixed-language
candidates, later-context revision, Undo and cursor/lifecycle changes.

Require complete reported sentences to produce useful final editor text while
preserving clean controls. Run `:app:verifyKeyboard` plus appropriate native and
Android checks, then verify latency on an actual phone. Keep phone results
separate from host benchmarks. Package only after the final profile and tests
match. Upload remains paused unless the user authorizes it again.

## Where to resume

Read these sources first:

- `../dataset/keyboard-lm/train_robust_v85.py`: current LM adaptation and retention.
- `../dataset/keyboard-lm/score_robust_v86.py`: actual-export sequence scoring.
- `../dataset/keyboard-lm/hill_robust_v86.py`: data partitions, current two-margin
  policy fitting and measured reliability guards.
- `../dataset/keyboard-lm/try_gaussian_touch_v87.py`: completed paired touch ablation.
- `../QwenASR/crates/cantonese-ngram/src/english/spelling.rs`: native generation
  and current heuristic typing score.
- `app/src/main/java/com/madeinhk/cantonesevoicekeyboard/english/MinimalTypingPolicy.kt`:
  optional simple runtime decision path.
- [SIMPLE_AUTOCORRECT_EXPERIMENT.md](SIMPLE_AUTOCORRECT_EXPERIMENT.md): model/profile
  integration results and existing limitations.

Bulk artifacts live on `/Volumes/SSD/cantonese_ngram/experiments`:
`simple-policy-v82a`, `simple-policy-v82-sensor`, `simple-policy-v82-human`,
`robust-main-lm-v85`, `robust-policy-v86`, `robust-confirmation-v86`,
`gaussian-touch-v87`. Preserve completed runs; create a new experiment directory.

Dataset Python runtime:
`../dataset/cantonese-ngram/.venv/bin/python`.

The workspace contains substantial existing modifications across nested Git
repositories. Inspect each repository before staging; do not commit unrelated
changes, replace default assets, restart frozen campaigns or upload a release
as part of resuming this plan.
