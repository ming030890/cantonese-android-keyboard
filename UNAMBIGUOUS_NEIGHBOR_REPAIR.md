# Withdrawn unambiguous-neighbour fallback

**Status: withdrawn at the user’s request.** The runtime branch and dictionary
were removed. [The current fix](ACCEPTANCE_REFIT.md) refits the existing acceptance
coefficients and cutoff. Everything below records the earlier experiment, not
the current product behavior.

2026-10-21. Fixes an acceptance failure without changing the learned cutoff:
unknown words with a first-ranked, dictionary-unique neighbouring-key repair can
now autocorrect. On a new 4–5-letter word cohort, the fix ratio improves from
**61/181 (33.7%) to 173/181 (95.6%)**, with no wrong replacements in that subset.

## Root cause and implementation

The previous gate treated low contextual word probability or a modest margin
over another suggestion as insufficient evidence to autocorrect, even when the
typed nonword had just one dictionary repair. It did not explicitly represent
that lexical uniqueness. This explains correct first-ranked answers rejected
in [the root-cause investigation](SINGLE_EDIT_ROOT_CAUSE.md).

The native fallback in `QwenASR/crates/cantonese-ngram/src/cnn_spelling.rs` applies
only when the existing gate abstains and all of these checks pass:

- Input is unknown to the model, consists of ASCII letters, and is 4–32 letters.
- Input is absent from the pinned dictionary; the candidate is present.
- The first-ranked model suggestion differs by exactly one neighbouring key.
- No other dictionary word is reachable by any single replacement, insertion,
  deletion, or adjacent swap. Apostrophe alternatives also count.

The fallback cannot override an existing correction, change a known input, accept
a completion, or promote a lower-ranked word. Candidate generation and order,
the LM, and both learned policies remain unchanged. There are no per-word
exceptions, newly fitted coefficients, or hand-selected score cutoffs.

The distinction between neighbouring-replacement uniqueness and full single-edit
uniqueness matters: `grup` has the neighbouring repair `grip`, but insertion also
produces `group`. It is excluded as ambiguous. `butger` has competing `burger`
and `butter` repairs and stays on the existing decision path.

The lexicon has 120,321 entries, compiled from the existing pinned SCOWL affix
expansion. It supplies ambiguity and preservation evidence, not word frequencies
or extra model candidates. Its SHA-256 is
`ba939858f719641d910bcacc3174855abba3f32cbd394f849098f9cf52af9cfb`.
Native `resources/english_neighbor_lexicon.json` records source provenance, and
SCOWL's complete notice is retained in the repository and packaged APK assets.

## Evaluation definition

The user requested that primary typo evaluation include **unambiguous cases only**.
Define membership from the input and full source dictionary, independent of LM
ranking, whether correction succeeds, and reference labels: there is exactly one
single-edit dictionary repair, and it is a neighbouring-key replacement. A case
then belongs to the target task when the supplied reference is that repair.
Correctly spelled words remain separate preservation controls. Missing model
targets and existing wrong decisions stay in the denominator.

The earlier 33% number mixed ambiguous and unambiguous 4–5-letter inputs.
The stricter original unambiguous subset has 88 cases and a 63.6% baseline.
Do not compare that baseline with the old 33% as if cohort membership were equal.

The new cohort uses the next 2,000 highest-TRAIN-frequency dictionary words after
excluding every word in the exposed original top-1,000 list. Generate one
deterministic neighbouring-key replacement per word, plus 2,000 unchanged-word
controls, before inspecting outputs. Of the 2,000 corruptions, 1,129 meet the
unambiguous definition. The policy was frozen before this cohort was inspected.
This is new development evidence from the same dictionary/domain, not a fresh
natural-typing study or a model-training holdout.

| Primary unambiguous panel | Cases | Correct before | Correct after | Wrong before → after |
|---|---:|---:|---:|---:|
| Original, 4–5 letters | 88 | 56 (63.6%) | 87 (98.9%) | 0 → 0 |
| Original, all 4+ letters | 343 | 284 (82.8%) | 342 (99.7%) | 0 → 0 |
| New words, 4–5 letters | 181 | 61 (33.7%) | 173 (95.6%) | 0 → 0 |
| New words, 6–7 letters | 497 | 366 (73.6%) | 490 (98.6%) | 0 → 0 |
| New words, 8+ letters | 451 | 383 (84.9%) | 445 (98.7%) | 1 → 1 |
| New words, all 4+ letters | 1,129 | 810 (71.7%) | 1,108 (98.1%) | 1 → 1 |

All 2,000 new-word controls retain the same before/after decisions: one existing
unwanted change remains, with no additional changes. No new wrong replacements
occur anywhere in this 4,000-case new synthetic panel.

The 21 remaining new unambiguous failures are retained: 11 targets are absent
from model candidates, eight are below first, one input is treated as known by
the model, and one existing wrong correction is deliberately preserved.

## Selection and broader regression check

The rule was checked on the fixed prompt-group TRAIN and selection partitions
before freezing its own selection report and revealing evaluation. No coefficient
fitting or cutoff search occurred. The initially tested variant could select
lower-ranked unique repairs. It added 19 correct/5 wrong TRAIN decisions. Requiring
the first-ranked candidate retains all 19 gains but reduces new wrong TRAIN
decisions to two; selection is unchanged at four gains/one new wrong decision.
The first-ranked variant is the adopted acceptance repair. The rejected variant
is retained in the reports; its full-evaluation F₀.₅ declines to 0.79352.

| Full real-typing regression panel | Correct before → after | Wrong before → after | F₀.₅ before → after |
|---|---:|---:|---:|
| TRAIN | 1,076 → 1,095 | 59 → 61 | 0.81036 → 0.81437 |
| Selection | 365 → 369 | 29 → 30 | 0.80185 → 0.80357 |
| Evaluation | 364 → 372 | 23 → 25 | 0.79407 → 0.79760 |

Full-evaluation precision changes 94.06% → 93.70%; recall 48.92% → 50.00%.
**Clean controls changed increase from 5 to 6 of 1,298.** The added clean error
is `unexploded → unexplored`: lexical uniqueness is relative to dictionary coverage,
not a guarantee of user intent. A multi-error reference such as `wuestion` intended
as `questions` also remains a risk for a unique single-edit repair (`question`).
These are explicit regressions outside the primary synthetic task, not removed
cases or word-specific exceptions. Historical evaluation exposure is retained;
this is developmental validation, not a newly independent test.

## Latency and packaging

Four alternating release-host process pairs, 1,024 warm calls per binary:

| General English engine panel | Before | After |
|---|---:|---:|
| Median | 2.768 ms | 2.783 ms |
| p95 | 5.665 ms | 5.695 ms |

For `gwme` specifically, 256 warm calls per binary measure median/p95
0.952/0.982 ms → 0.986/1.019 ms. Four first-call samples per binary measure
median 1.347 → 2.984 ms: initializing the lexical index costs about 1.64 ms on
this host. First-call timing excludes process start and model loading; a p95 from
four samples is only the maximum observation. The dictionary adds approximately
1.1 MB of native read-only data plus an in-memory index. These are explicit costs.
No phone input-to-display latency improvement is claimed.

## Verification

- Rebuilt host JNI/probe and Android ARM64 libraries.
- Compiled decisions match the frozen rule on all **15,719** original cases;
  candidate lists remain unchanged. The new 4,000-case panel also matches exactly.
- Five native spelling tests and eight Python diagnostic tests pass.
- Actual JNI scenario run: **18 tests, zero skips/failures**, including correction
  on Space, undo, and undo suppression on English, Quick3 and Quick5.
- `:app:verifyKeyboard :app:compileDebugAndroidTestKotlin` passes: 218 unit tests
  (10 JNI cases skipped in that default run and covered by the explicit JNI run),
  80 screenshot validations, and debug assembly. The new screenshot was updated,
  inspected, then validated separately.
- The rebuilt ARM64 engine matches the staged and merged library; the APK matches
  Gradle's stripped version of that library and contains the embedded lexicon and
  license. No phone installation was performed.

Reports: [selection](config/english_iteration/2026-10-21/neighbor-repair/first-selection.json),
[evaluation](config/english_iteration/2026-10-21/neighbor-repair/evaluation.json),
[compiled/native and new-word verification](config/english_iteration/2026-10-21/neighbor-repair/native-verification.json),
[accepted-policy parity](config/english_iteration/2026-10-21/neighbor-repair/accepted-native-parity.json),
[general latency](config/english_iteration/2026-10-21/neighbor-repair/latency.json),
[first/warm fallback latency](config/english_iteration/2026-10-21/neighbor-repair/neighbor-latency.json),
[packaging and QA](config/english_iteration/2026-10-21/neighbor-repair/package-verification.json).

## Reproduce

Keep the archived before probe/cache intact; do not overwrite the original
single-edit predictions with post-change outputs.

```sh
python3 scripts/iterate_unambiguous_neighbors.py --source /Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt --require-first --frozen-selection config/english_iteration/2026-10-21/neighbor-repair/first-selection.json --output build/neighbor-repair-2026-10-21/first-evaluation.json
python3 scripts/verify_unambiguous_neighbors.py --before build/neighbor-repair-2026-10-21/ngram_probe.before --after app/build/english-host/release/ngram_probe --source /Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt --output build/neighbor-repair-2026-10-21/native-verification.json
python3 scripts/verify_english_acceptance.py --iteration build/english-iteration-2026-10-20 --probe app/build/english-host/release/ngram_probe --output build/neighbor-repair-2026-10-21/accepted-native-parity.json
python3 -m unittest discover -s scripts -p test_unambiguous_neighbors.py
bash scripts/test_english_autocorrect.sh
./gradlew :app:verifyKeyboard :app:compileDebugAndroidTestKotlin
```
