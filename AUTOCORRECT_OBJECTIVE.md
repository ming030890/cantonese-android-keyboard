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
`report.json` contains diagnostics. Current accepted baseline: **0.797611**.

## Acceptance gate

- Quality changes: score must exceed the accepted baseline on the same frozen suite.
- Required checks must pass; shipping keyboard changes require `./gradlew :app:verifyKeyboard`.
- Report latency changes. Latency-only changes may keep the score unchanged if faster.

No separate precision cutoff or per-dataset score gate. Use this aggregate for
hill climbing and model selection; it is a development score, not a fresh test.
