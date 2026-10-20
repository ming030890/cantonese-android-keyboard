# English typing iteration — 2026-10-20

This records the earlier two-feature rule. The current accepted rule is in
[LITERAL_ACCEPTANCE_ITERATION.md](LITERAL_ACCEPTANCE_ITERATION.md), with baseline
identity and acceptance metrics maintained in [TYPING_EVALUATION.md](TYPING_EVALUATION.md).

F₀.₅ is now the primary correction metric, as requested by the user. Accept
improvements over the baseline without a separate 95% precision gate. Cutoffs
are fitted on TRAIN calibration groups; models are selected on selection groups,
then evaluated on the fixed evaluation groups. Do not tune on evaluation labels.

## Included in the rebuilt app

One logistic rule uses the highest candidate score and its margin over the
runner-up. It replaces the previous collection of acceptance gates. Coefficients,
standardization and cutoff are compiled into the native library from
`../QwenASR/crates/cantonese-ngram/src/english_acceptance.json`. The language model
and its vocabulary remain unchanged. Known words stay literal; completions remain
explicit choices. Boundary transactions and Backspace undo remain in Kotlin.

Aligned Aalto evaluation (2,042 cases: 744 typo events, 1,298 source-copy controls):

| Measure | Original rule | Earlier conservative rule | Current F₀.₅ rule |
|---|---:|---:|---:|
| F₀.₅ | 0.71557 | 0.66351 | **0.76364** |
| Correct automatic replacements | 399 | 224 | 336 |
| Wrong automatic replacements | 112 | 12 | 28 |
| Precision | 78.08% | 94.92% | 92.31% |
| Typo recall | 53.63% | 30.11% | 45.16% |
| Source-copy controls changed | 41 | 5 | 9 |

F₀.₅ = 1.25 TP / (1.25 TP + FP + 0.25 FN), with FN equal to typo events
minus correct replacements. The current rule avoids 84 wrong edits compared
with the original, at the cost of 63 missed fixes. Compared with the earlier
conservative rule it recovers 112 fixes while adding 16 wrong edits.
These labels come from reference spans, not the original keyboard's AC output.
Ambiguous or unmatched spans are excluded. Fitting and cutoff calibration use
different TRAIN prompt groups; selection and evaluation use separate prompt
groups. The corpus has historical development exposure, so this is not a fresh
independent test. Source-copy controls are not a full natural clean-typing panel.
The rebuilt native implementation matches the frozen rule on all 9,728 retained
cases, with zero decision mismatches, including the current evaluation above.

Removing the completion-length penalty separately improves top-1 from 81/192 to
89/192 and top-8 from 139/192 to 148/192 on the fixed prefix evaluation. Selection
uses a separate document group partition. This is a small developmental panel.

The retired Kotlin decoder, loader, forest, policies and local-learning code
have been deleted, along with their tests and source-set wiring. The old Rust
English module, JNI exports, dictionary fixtures and ten command-line tools
have also been deleted. The `legacy-typing` feature and internal typing trial
build/settings/deployment switch are gone. Current host/device smoke tests and
the latency benchmark use `SharedLmEnglishEngine`. Historical reports remain as
records; their retired-engine commands are no longer supported.

## Constants fitted in this iteration

| Quantity | Data and method | Outcome |
|---|---|---|
| Acceptance coefficients, normalization, cutoff | Aligned Aalto TRAIN fit/calibration, prompt-group selection | Adopted above |
| Completion penalty | Grouped SSD prefix examples; selection among candidate values | Zero selected and adopted |
| Substitution, extra tap, omitted tap, swap costs | Conditional candidate likelihood on 1,858 TRAIN cases with the target in the pool; regularization selected on separate groups | Fitted 2.236 / 2.800 / 0.989 / 0.342; not adopted because ranking worsened |
| Per-key touch bias and x/y variance | 14,680 retained TRAIN taps; shrinkage selected by calibration NLL | Fitted experimental model; not wired into production |

The four-cost edit model reduces evaluation top-1 from 573 to 518 and top-8 from
648 to 639, with the same generated pool. This simpler operation model loses
useful distinctions in the existing penalties. Its scores also invalidate the
adopted acceptance calibration. Learning a value does not justify deploying it.

## Touch evidence and layout

The active `SharedLmEnglishEngine` currently ignores the actual touches and
centres supplied by the coordinator. Its edit penalty uses fixed QWERTY
neighbourhoods. The UI already records actual drawn key centres and tap positions;
legacy touch tests exercise a retired engine, not the shared-model engine.

The recorded Google touch experiment uses actual keyboard-local coordinates.
Both axes are normalized using the recorded key/row pitch. It learns a diagonal
Gaussian bias/variance per key with shrinkage towards the population model.
The existing 8 TRAIN / 4 calibration / 4 selection participant split is preserved.
No reference text or upstream keyboard LM score is used as an inference feature.

On the 7,593 eligible selection taps, nearest-centre classification gets
7,110 correct (93.64%); the fitted Gaussian gets 7,181 (94.57%), 71 more correct.
The chosen shrinkage is 1,024 taps. Evaluation covers retained equal-length
alphabetic word spans with substitution-only OSA alignment, excluding
swaps, deleted/unaligned events; prompts can
overlap across participants. This is key classification rather than word
correction. A shared-LM word-level replay and joint acceptance recalibration are
required before connecting this model to live suggestions.

## Still heuristic or constrained

The following values are **not** claimed to be learned: edit-neighbour penalties,
length-based search radius, the one-character length-difference restriction,
multiword split proposal rules and their second-word weighting, minimum lengths
and the margin cap in the adopted acceptance model. Known-word preservation and
no automatic completion remain product safeguards. Input/candidate bounds,
candidate-strip capacity and the boundary deadline are engineering constraints;
they need device performance evidence, not only spelling labels.

Next evidence: fit a channel that retains substitution confusions and layout
distance, replay recorded touches through the shared candidate pool, then fit
acceptance jointly with any changed ranking. Keep selection/evaluation groups
fixed and report missed fixes as well as wrong replacements.

## Verification and reproduction

Native unit tests: 21 passed after removing retired-engine tests. Focused real-JNI shared-model scenarios: 10 passed.
`:app:verifyKeyboard` and instrumentation Kotlin compilation passed with the
rebuilt ARM64 library. No device was connected; phone latency and integration
remain unmeasured.

Reports with input hashes are in
[`config/english_iteration/2026-10-20`](config/english_iteration/2026-10-20).
Raw text/candidate caches stay under ignored `build/`.

```sh
python3 scripts/verify_english_acceptance.py --iteration build/english-iteration-2026-10-20 --probe /path/to/current/ngram_probe --output build/native-policy-verification.json
python3 scripts/fit_typing_evidence.py --iteration build/english-iteration-2026-10-20 --output build/typing-evidence.json
python3 -m unittest discover -s scripts -p 'test_fit_typing_evidence.py'
./gradlew :app:verifyKeyboard :app:compileDebugAndroidTestKotlin
```

The evidence fitter requires NumPy. Its baseline cache must be produced by the
pre-change probe used in `iterate_english_policy.py`; do not regenerate it with
the adopted scorer and then add back the old completion penalty a second time.

The current policy fit and native parity reports are `f05-linear-acceptance.json`
and `f05-native-policy-verification.json`. Older reports in this directory are
historical comparisons, not the current selected policy.
