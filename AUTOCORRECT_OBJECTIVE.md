# Autocorrection evaluation

## Primary evaluation going forward

As agreed on 2026-10-31, evaluate autocorrect using **whole-message ITE mobile
typing plus mixed Cantonese/English script**. These two panels guide experiments
and acceptance. The old isolated-word v4 F₀.₅ is a regression check, not the
primary optimization objective. Report the panels separately; do not invent a
pooled score or let the larger English panel hide mixed-script failures.

| Primary panel | Frozen development inputs | Accepted baseline |
| --- | --- | --- |
| English mobile whole messages | [ITE manifest](config/autocorrect_eval_v5/ite/manifest.json): 421 attempts | Complete lexical recovery **88/229 (38.4%)** typo-bearing attempts; clean lexical damage **2/96** |
| Mixed Cantonese/English controller sessions | `language=mixed_cantonese_english` in [product sessions](config/product_eval_v1/sessions.jsonl): 9 scenario groups, each in English/Quick3/Quick5 | Exact recovery **1/4** correction scenarios; preservation **5/5**; overall **6/9** per layout |

All three layouts currently produce identical outcomes in each panel. Layout
variants are correlated; count each attempt/scenario once when describing sample
size, and report any future layout differences. Mixed-script coverage is currently
a small, exposed authored development set, not natural chat or fresh confirmation.
It is the starting mixed-script panel and must be expanded with frozen,
model-independent cases covering inline switching, noisy English context,
multiple typos, contractions, names/abbreviations, identifiers, punctuation,
Chinese candidate selection and literal override. Version expansions before
inference and retain the existing cases; never select cases just to lower scores.

Use production controller/JNI replay. For ITE, score the captured submit payload:
complete lexical recovery by one/two/three-plus input word errors, exact-text
recovery separately, word-error reduction, and damage to clean/formatting-only
controls. For mixed script, report exact intended final text, correction and
preservation separately, Chinese selection/literal-override correctness, and
English/Quick3/Quick5 slices. Follow the
[ITE scoring/reproduction protocol](ITE_MESSAGE_EVAL_20261031.md) and
[mixed-session reproduction commands](PRODUCT_AUTOCORRECT_EVAL.md#reproduce).
Labels never enter inference. Virtual scheduling does not measure phone latency.

Keep all evaluation examples and derived variants out of training. Exclude ITE
evaluation participants and prompts from future source extraction. The 421-attempt
ITE development fold is exposed; its separate **417-attempt confirmation fold
remains closed** until a candidate and acceptance rule are fixed. The current
mixed-script panel has no fresh confirmation fold; freeze new disjoint scenario
families before claiming mixed-script confirmation. Preserve copy-task versus
authored provenance and flag valid-word/paraphrase ambiguities; prompt recovery
alone is not proof that an automatic edit is appropriate.

## Acceptance gate

- Improve complete-message recovery on at least one primary panel without
  reducing recovery on the other, compared with the same accepted engine and
  frozen cases. Report paired gains, losses and denominators, not just a score.
- Do not increase clean-text damage on either panel or regress Chinese candidate
  selection/literal override. Review individual new unwanted edits; aggregate
  counts must not conceal them.
- Retain the frozen v4 cases/weights and run the word-level regression check;
  its accepted **0.893921** must not fall. A v4 gain alone does not qualify a model.
- Pass known behavior regressions and `./gradlew :app:verifyKeyboard` before
  shipping keyboard changes. Run these before opening fresh confirmation.
- Report latency tradeoffs separately from quality. Latency-only changes may
  retain quality if faster. Do not equate Mac timings with physical-phone latency.

## Legacy word-level regression datasets

| Dataset | Cases |
|---|---|
| Aalto mobile typing | 744 errors + 1,298 clean controls |
| Public phone-touch replay | 525 errors + 2,556 clean words + 46 ambiguous observations |
| Authored hard set | 46 errors + 46 clean controls; multiple edits, apostrophes, missing spaces and real-word confusions |

Frozen suite: `build/autocorrect-eval-v4/frozen/`.
[Manifest](config/autocorrect_eval_v4/manifest.json).
Ambiguous touch observations count toward sentence CER, not word F₀.₅.
Keep cases and weights fixed; keep evaluation examples out of training.

## Run the legacy regression score

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
`report.json` contains diagnostics. Current accepted baseline: **0.893921**
(exact score and previous-baseline delta in the
[2026-10-31 typing-channel acceptance report](config/english_iteration/2026-10-30/noisy-channel/accepted-score.json)).

This exposed legacy development score is retained for regression checks. Do not
use it as the sole hill-climbing or model-selection objective; the primary
whole-message and mixed-script panels above now govern quality improvements.

For coverage limitations, exact message outcomes, actual-controller session
diagnostics and the fresh-data protocol, see
[Product autocorrect evaluation](PRODUCT_AUTOCORRECT_EVAL.md). The frozen v4
cases and weights remain unchanged. Passing v4 alone does not establish
real-user or bilingual chat quality.

The [whole-message ITE development/confirmation protocol](ITE_MESSAGE_EVAL_20261031.md)
adds source-recorded mobile attempts with disjoint actors and prompts. Keep
both frozen folds, their participants/prompts and derived variants out of
training. Development is exposed after baseline scoring; confirmation remains
closed until a candidate and acceptance rule are fixed. Promoting this message
benchmark to primary evaluation does not change the v4 cases or weights.

The [2026-10-30 experiment iteration](AUTOCORRECT_ITERATION_20261030.md) added
source-disjoint supplemental evaluation and a cheap existing-behavior preflight.
Its best scored candidate failed a required ambiguity assertion and was
withdrawn. Run known-regression and controller/JNI checks before exposing a new
confirmation cohort; a benchmark gain does not override those requirements.

The [2026-10-31 bidirectional KEEP/rescue experiment](KEEP_RESCUE_EXPERIMENT_20261031.md)
reached 0.903246 in development but failed its new natural-chat confirmation
guard and was withdrawn. The accepted baseline remains **0.893921**. Its chat
confirmation is now exposed and must not be reused as fresh evidence.

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
not replace the primary controller evaluation or establish production editor behavior.
