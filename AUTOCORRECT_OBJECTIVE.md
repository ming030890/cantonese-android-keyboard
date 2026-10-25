# Autocorrection evaluation

## Datasets

| Dataset | Cases |
|---|---|
| Aalto mobile typing | 744 errors + 1,298 clean controls |
| Public phone-touch replay | 525 errors + 2,556 clean words + 46 ambiguous observations |
| Authored hard set | 46 errors + 46 clean controls; multiple edits, apostrophes, missing spaces and real-word confusions |

Frozen suite: `build/autocorrect-eval-v4/frozen/`.
[Manifest](config/autocorrect_eval_v4/manifest.json).
Ambiguous touch observations count toward sentence CER, not word F₀.₅.
Keep cases and weights fixed; keep evaluation examples out of training.

## Run and obtain the score

Run from this repository. Build the candidate from its current native source:

```sh
cargo build --offline --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml --release --features android --bin ngram_probe
python3 scripts/autocorrect_objective.py score \
  --suite build/autocorrect-eval-v4/frozen \
  --probe ../QwenASR/crates/cantonese-ngram/target/release/ngram_probe \
  --baseline-probe /path/to/saved-baseline/ngram_probe \
  --output build/autocorrect-eval-v4/new-candidate
```

Save the baseline binary before making changes. Use a new output directory for
each run. Omit `--baseline-probe` to score one binary.

The command prints one number:
`score = (mobile F₀.₅ + touch F₀.₅ + hard-set F₀.₅) / 3`.
Higher is better. `score.json` contains the score and baseline delta;
`report.json` contains diagnostics. Current accepted baseline: **0.857561**
(exact score and previous-baseline delta in the
[2026-10-25 acceptance report](config/english_iteration/2026-10-25/noisy-context/objective-score.json)).

## Acceptance gate

- Quality changes: score must exceed the accepted baseline on the same frozen suite.
- Required checks must pass; shipping keyboard changes require `./gradlew :app:verifyKeyboard`.
- Report latency changes. Latency-only changes may keep the score unchanged if faster.

No separate precision cutoff or per-dataset score gate. Use this aggregate for
hill climbing and model selection; it is a development score, not a fresh test.

## Supplemental context robustness audit

When changing context training or scoring, also run the paired prior-typo audit:

```sh
python3 scripts/audit_noisy_context.py \
  --suite build/autocorrect-eval-v4/frozen \
  --probe ../QwenASR/crates/cantonese-ngram/target/release/ngram_probe \
  --output build/noisy-context-UNIQUE-RUN
```

It freezes synthetic perturbations before inference and reports damage to clean
words, lost fixes and recovered fixes for each noise operation. Only left context
changes; the current input and intended target stay fixed. These are diagnostics
on exposed development cases, not an additional acceptance score. Do not train
on these variants or alter the frozen objective's cases or weights. See
[the Typewise review](TYPEWISE_TAKEAWAYS.md) for the implementation audit and results.

Delayed correction has a separate [word-boundary replay experiment](CONTEXT_REVISION_REPLAY.md).
Its closed-loop baseline feeds earlier corrections into later touch context;
mobile continuations are simulated clean prompt typing. Those replay scores do
not replace the static native objective or establish production editor behavior.
