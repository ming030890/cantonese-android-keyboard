# Model casing repair — 2026-10-31

The decoder restores casing from the existing model vocabulary and contextual
scores. Runtime code contains no added brand list, name mapping or word-specific
exception. Model weights, vocabulary and lexical acceptance network are retained.

The actual unified JNI/controller replay of lowercase input
`i just got an iphone from apple.` now produces
`I just got an iPhone from Apple.` in English, Quick3 and Quick5.
`I ate an apple.` stays lowercase. The same model-driven alternatives can be
selected manually under the cursor with autocorrect off.

## Implementation

Exact-word case choices are resolved after the lexical spelling decision. This
keeps internal capitals available even when the spelling neighborhood omits a
known exact word, and prevents winner surfaces such as `Apple` being lowered.
One-letter byte-token labels render as letters. Sentence caps preserve internal
capitals and retain their normal first-letter fallback.

For uncased input, model-driven case restoration requires the literal word's model
score to beat or tie the strongest edit-penalized alternative. Existing user
capitalization remains a casing cue. This parameter-free ranking guard prevents
weak acronym matches from changing the context and spoiling later corrections;
it has no acronym list. Explicit alternatives remain model-ranked.

Quick permits case-only repairs when Chinese code matches exist: the literal
letters stay the same, and Chinese candidates remain explicitly selectable.
Undo restores the original and literal recommit suppresses the same edit.

## Verification

- `ModelCasingScenarioTest` exercises the actual unified JNI path across all three
  layouts: the exact sentence, internal capitals, ambiguous nouns, weak acronym
  matches, sentence starts, byte letters, candidate/toolbar presentation, undo,
  literal recommit and explicit cursor selection with autocorrect off.
- Final `:app:verifyKeyboard` passes with the freshly built JNI and the entire
  frozen primary session file. Unit/scenario tests, screenshots and debug
  assembly pass. The new `ScenarioModelCasing` image was inspected before separate
  validation.
- Android ARM64 JNI was built with the engine command from `deploy.sh`. The
  packaged APK's native-library SHA-256 matches the standard NDK-stripped
  output of that build. No device installation
  or physical-editor integration test was performed.

## Paired quality checks

Both runs use the same frozen inputs and unchanged model, with source-matched
starting/final native binaries. Layout variants are correlated; the counts below
apply separately and identically to English, Quick3 and Quick5.

| Primary English ITE, 421 attempts | Starting decoder | Final decoder |
| --- | ---: | ---: |
| Complete lexical recovery, 229 typo-bearing attempts | 91 | 91 |
| Exact recovery, 229 typo-bearing attempts | 54 | 54 |
| All-attempt exact success, 421 attempts | 149 | 150 |
| Remaining word errors, initially 419 | 210 | 210 |
| Clean lexical damage, 96 attempts | 1 | 1 |
| Clean text damage, 96 attempts | 1 | 1 |

Paired complete-lexical gains/losses: **0/0** among 229
error attempts. All-attempt exact gains/losses: **1/0**
among 421 attempts. Individual output changes are preserved in
`build/model-casing-fix/primary/comparison-accepted.json`.

The mixed Cantonese/English panel retains **7/9** exact successes per layout:
**2/4** correction scenarios and **5/5** preservation scenarios. Chinese selection
and literal override retain their expected behavior; clean damage remains zero.
The panel has only nine exposed authored groups and is not natural-chat confirmation.

Frozen v4 spelling aggregate: **0.8946978214 → 0.8946978214**,
paired delta **+0.0000000000**; above the documented 0.893921 regression floor.
All evaluation cases remained outside training. Confirmation inputs remained closed.

The initially unconditional casing prototype was discarded after review found
weak acronym edits and one extra downstream word error. Its raw results are
retained separately; the final ranking guard removes those unwanted changes.

## Supplemental casing diagnostics

Twelve authored groups were frozen before replay and run in each layout.
Exact success improves **9/12 → 11/12**;
all eight already-correct noun controls remain intact in both runs.
The model still leaves `windows` lowercase in `I installed windows.`. That
remaining preference is reported rather than overridden with a name list.
These exposed diagnostics are not independent confirmation or a population score.

## Host latency

Alternating starting/final native probes on 120 frozen cases, two passes per run
and two runs per decoder, during concurrent host replay:

| Native Mac probe | Starting median / p95 | Final median / p95 |
| --- | ---: | ---: |
| First pass | 2.752 / 7.978 ms | 2.779 / 7.997 ms |
| Repeated pass | 1.575 / 3.949 ms | 1.581 / 3.950 ms |

The casing pass reuses model scores. These measurements are host diagnostics,
not physical-phone or real-editor latency. Raw timings, frozen inputs, binary
hashes and controller reports are under `build/model-casing-fix/`.
