# Typewise review — 2026-10-25

Follow-up: the [noisy-context experiment](NOISY_CONTEXT_ACCEPTANCE.md) now integrates
a small acceptance-network improvement after frozen scoring, preservation checks
and keyboard QA. The remainder below records the original audit before that change.

The [Typewise article](https://www.typewise.app/blog/inside-the-ai-how-we-build-our-autocorrect-emoji-and-text-prediction-engine)
emphasizes avoiding unwanted edits, learning from correction undo, touch-location
evidence, conversational training data with synthetic input noise, and local
inference. Its sentence-context discussion describes a planned improvement;
our shared model already consumes multiple preceding tokens. It provides no
specific emoji-prediction method to implement.

## Applied: measure damage from prior typos

Added `scripts/audit_noisy_context.py` and a supplemental step in
`AUTOCORRECT_OBJECTIVE.md`. It changes one character in the nearest eligible
ASCII word in left context using deletion, substitution, duplication or adjacent
transposition. The current typed word and target never change. Perturbations
depend only on input and a fixed seed, and are frozen before inference. Only
input fields reach the native process. Reports bind the suite, script, scorer,
model and executable hashes; runs refuse to overwrite existing output.

The current source was rebuilt with the documented release probe command. Of
5,261 frozen cases, 4,145 labelled cases have eligible context, producing 16,580
pairs / 33,160 native requests. The source contexts may already contain errors;
“original” does not mean clean. This is synthetic stress testing on exposed
development data, not measured user traffic or next-word prediction evaluation.

| Prior-context condition | Correct automatic fixes / 1,118 current-word typos | Clean current words changed / 3,027 | Intended typo target in top 8 |
| --- | ---: | ---: | ---: |
| Original | 760 | 7 | 1,005 |
| Delete a character | 591 | 11 | 984 |
| Substitute a character | 554 | 6 | 985 |
| Duplicate a character | 563 | 7 | 988 |
| Transpose two characters | 535 | 4 | 980 |

The much larger loss in accepted correct fixes than in target availability
suggests contextual ranking/acceptance is the main sensitivity. This does not
separate the language model's contribution from the acceptance network's, and
does not establish a remedy. Some contexts also recover fixes. The report keeps
regressions, recoveries, wrong fixes and clean-word damage separate, by operation
and original panel. Operation cohorts are repeated observations, not independent
samples. They do not replace the frozen aggregate or add a new quality gate.

Saved [full diagnostic report](config/typewise_review/2026-10-25/noisy-context-report.json)
and [frozen provenance](config/typewise_review/2026-10-25/noisy-context-frozen-run.json).
Detailed pairs and native responses remain under ignored
`build/noisy-context-20261025/`. Never use those evaluation variants for training.

## Remaining implementation priorities

1. **Improve robustness to prior errors using TRAIN-only input corruption.**
   Earlier sibling training experiments already have noisy-context support
   (`../dataset/keyboard-lm/train_english_v69.py`, `train_robust_v83.py` through
   `train_robust_v85.py`). Preserve clean intended targets and the bilingual
   retention checks. Validate candidate ranking and acceptance together on the
   current model; do not simply increase correction aggressiveness to recover
   the lost fixes. Use the new audit for diagnosis and the existing frozen
   aggregate for acceptance.
2. **Make undo feedback effective across later occurrences.**
   `EnglishInputCoordinator.delete()` restores the original and suppresses the
   immediate recommit. Its `engine?.rejected()` call reaches a default no-op in
   `EnglishCandidateEngine`; `SharedLmEnglishEngine` does not override it, and
   unified inference has no feedback method. This is immediate undo, not learned
   personalization. A follow-up needs a bounded local rejection policy shared
   by preview, full and boundary decisions, with repeated-word, case, layout,
   context and reset tests. Undo alone is insufficient evidence for changing
   key locations or learning a permanent global spelling preference.
3. **Revisit touch evidence with word-level calibration.**
   The UI captures touches, but the active shared scorer ignores them. The
   experimental native channel already exists. Its historical TSI selection
   result slightly worsened word correction, so enabling it solely because key
   classification improved would be unsupported. See
   [native touch verification](config/english_iteration/2026-10-22/tsi-touch/native-verification.json).
   New work must jointly evaluate touch ranking and acceptance, missing-touch
   fallback, actual layout coordinates and device latency.

Keep the existing local inference and learned original-word versus replacement
evidence. Names, slang and language evolution need coverage and personalization
evidence; they do not justify a blanket known-word rule or replacing our layout.

## Verification and runtime scope

No production code, model, policy, candidate pool or UI changed. Six new audit
tests and four existing objective tests pass. The unchanged frozen objective
and scorers were used with a freshly rebuilt current native probe:

- Aggregate **0.857262417162**; documented accepted baseline **0.797611**, delta
  **+0.059651417162**. This is pre-existing engine behavior, not a gain from this
  evaluation-only change. The previous build report's 0.861578 is a different
  executable; it is not substituted for this fresh result.
- Objective native-call latency: median **1.002 ms**, p95 **2.708 ms**, 5,261 calls.
- Interleaved context audit: median **1.162 ms**, p95 **3.187 ms**. Repeated
  contexts and cache reuse preclude treating this as a before/after timing test.

These host runs overlapped, so timings are descriptive, not isolated speed
comparisons or phone input-to-display measurements. This change adds no runtime
work. Saved [objective score](config/typewise_review/2026-10-25/objective-score.json)
and [run identity and timing](config/typewise_review/2026-10-25/objective-run.json).
The Android gate was not run because Android code and assets are unchanged.
