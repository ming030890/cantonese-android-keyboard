# Literal-aware acceptance iteration — 2026-10-20

The error analysis identified acceptance as the largest loss. This iteration
adds original-word evidence to the same small logistic rule, with no additional
LM, dictionary, forest, search pass or editor component.

## Accepted evaluation improvement

The fixed [evaluation protocol](TYPING_EVALUATION.md), cohort and language model
are unchanged. Fit and calibration use separate TRAIN prompt groups. Three
feature families and three regularization strengths are compared on selection;
the winner is frozen before revealing evaluation metrics.

| Metric | Previous rule | Literal-aware rule |
|---|---:|---:|
| Selection F₀.₅ | 0.76977 | **0.80452** |
| Evaluation F₀.₅ | 0.76364 | **0.79167** |
| Correct automatic replacements | 336 | **361** |
| Wrong automatic replacements | 28 | **23** |
| Precision | 92.31% | **94.01%** |
| Recall | 45.16% | **48.52%** |
| Preservation controls changed | 9/1,298 | **5/1,298** |

This satisfies the user's F₀.₅ acceptance rule without a separate precision
cutoff. These are developmental results with historical exposure, not a fresh
independent test. The compiled scorer matches the reference decision on all
9,728 aligned cases and reproduces the evaluation results exactly.
The feature families were motivated by prior error analysis of this same cohort;
the evaluation is therefore exposed development data, not an untouched holdout.

## What changed

The fitted rule uses six features: winner score, runner-up margin, known-word
indicator, original word's LM score, winner-minus-original score, and an
observed-apostrophe indicator. Coefficients, normalization and cutoff are fitted
from data. The best selection model uses regularization 0.001 and 2,644 eligible
TRAIN fit cases. Calibration and selection cases do not fit coefficients.

For known words, the original score comes from the same LM distribution already
computed for candidates, taking the best token-case variant as candidate ranking
does. No extra CNN inference is required. Unknown originals have zero-valued
literal features and a false known-word indicator; byte-sequence probabilities
are not treated as interchangeable with whole-word scores.

The blanket known-word veto is replaced by learned original-versus-replacement
evidence. A known word is still preserved if its literal evidence is unavailable.
Minimum lengths and the prohibition on automatic prefix completion remain.
Candidate generation, edit penalties and display ranking are unchanged.

The apostrophe feature learns preservation rather than introducing a word-specific
blacklist. All five previous clean-control apostrophe-removal errors are avoided.
The model still has **zero apostrophe-containing word tokens**, so this does not
repair contraction generation. That needs a separate vocabulary/tokenization
experiment with corresponding model calibration.

Compared with the previous rule, 37 correct fixes are recovered and 12 previously
correct fixes are missed, for a net gain of 25. Of the recovered fixes, 29 were
known-word typos. Seven wrong replacements are avoided and two new ones occur,
for a net reduction of five. A remaining weakness is names: `Jim → him` is a new
clean-control error. `Gascoigne → gasoline`, dialect changes and splitting also
remain. Aggregate improvement is not a claim that every individual case improves.

## Runtime and numeric verification

Native serde dimensions enforce six feature means/scales and seven coefficients.
The serialized cutoff is four float64 ULPs below the fitted probability boundary
to include mathematically tied calibration examples consistently across NumPy,
Python and Rust sigmoid rounding. One calibration case exposed this difference;
evaluation decisions are unchanged by the numeric encoding. The calibrated
threshold and export rule are recorded in the native policy provenance.

The native parity report compares with the frozen baseline candidate cache.
It records three near-tied candidate-order differences; this cache reconstructs
completion scores from the old float32 penalty. The paired before/after runtime
latency benchmark additionally checks exact candidate-list equality on its panel.

## Behavior coverage

The real-JNI scenario verifies `hou → you` after “let me know if” in English and
Quick, including candidate visibility, Space, Backspace undo and suppressed
re-correction after undo. Native tests compare strong and weak literal evidence.
The installed-engine smoke test checks known-word correction and preservation of
`don't`; it is compiled here but requires a device to execute.

`ScenarioSharedLmKnownWordCorrection` renders the same state with a fake native
response. Its new screenshot baseline was rendered and visually inspected.
All 21 native unit tests, 10 focused real-JNI scenarios, `:app:verifyKeyboard`
and Android instrumentation Kotlin compilation passed with the rebuilt library.
No phone is connected, so no phone latency or deployment is claimed.

## Latency results

Benchmarks ran without concurrent builds or model-scoring jobs. The English
panel uses 256 fixed hashed cases, four alternating before/after process runs,
16 warmups per process and 1,024 measured calls per binary. Startup and Android
input/rendering are excluded.

| Host engine metric | Before | After |
|---|---:|---:|
| English suggestion median | 2.759 ms | 2.775 ms |
| English suggestion p95 | 5.623 ms | 5.616 ms |
| Full `df` median | 155.862 ms | 157.055 ms |
| Full `df` p95 | 207.846 ms | 208.474 ms |

English median increases 0.016 ms while p95 decreases 0.007 ms; these differences
are within the variation across individual rounds. No device latency conclusion
is supported. Current `df` preview is 0.712 ms median / 0.739 ms p95. The Quick
panel retains all 112 full candidate lists and preview candidate sets; the English
panel retains all 1,024 paired candidate lists. The quality improvement is accepted
with these small measured host timing differences explicitly recorded.

Reports: `literal-english-latency.json` and `literal-quick-latency.json` under
`config/english_iteration/2026-10-20/`.

## Reproduce

```sh
python3 scripts/iterate_literal_acceptance.py --iteration build/english-iteration-2026-10-20 --probe /path/to/ngram_probe --baseline-policy config/english_iteration/2026-10-20/acceptance-policy-v1.json --output build/literal-selection.json
python3 scripts/iterate_literal_acceptance.py --iteration build/english-iteration-2026-10-20 --probe /path/to/ngram_probe --baseline-policy config/english_iteration/2026-10-20/acceptance-policy-v1.json --output build/literal-evaluation.json --evaluate --frozen-selection build/literal-selection.json
python3 scripts/verify_english_acceptance.py --iteration build/english-iteration-2026-10-20 --probe /path/to/current/ngram_probe --output build/literal-native-verification.json
python3 scripts/benchmark_english_latency.py --before /path/to/before/ngram_probe --after /path/to/after/ngram_probe --cases build/english-iteration-2026-10-20/cases.jsonl --output build/literal-english-latency.json
./gradlew :app:verifyKeyboard :app:compileDebugAndroidTestKotlin
```

The fitter requires NumPy. Raw candidate/literal caches remain in ignored
`build/`; hash-bound reports and the previous policy are retained in
`config/english_iteration/2026-10-20/`. The frozen error analysis remains a record
of the previous rule; its script explicitly uses the archived v1 policy.
