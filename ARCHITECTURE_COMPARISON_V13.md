# Architecture comparison on the repaired v13 evaluation

The earlier pilots compared the options, but the original coarse evaluation did not establish a best architecture. This report re-evaluates existing frozen exports on one repaired development suite. No training, calibration, confirmation scoring, asset switch or mobile benchmark occurred.

All tested models use 34,081 actual tokens, residual width 256, tied output embeddings and one network for zero, one and longer contexts. There are no per-context tables or inference count fallbacks. Sizes below are decimal MB.

## Matched 1,000-step capacity screening

Embedding 128/224 × two/three blocks, FFN 512. Same function at initialization, seed, data order, objective, optimizer and step budget. The original learning curves are BF16 trainer count agreement, not held-out keyboard accuracy. The quality tables below use the actual INT8 export, so their count endpoints can differ slightly.

| Architecture | step 200 top-1 | 400 | 600 | 800 | 1,000 | late mean (600–1,000) |
|---|---:|---:|---:|---:|---:|---:|
| e128-h256-b2-f512 | 76.08% | 80.39% | 79.17% | 80.37% | 80.11% | 79.89% |
| e128-h256-b3-f512 | 75.68% | 80.27% | 79.09% | 79.69% | 80.75% | 79.84% |
| e224-h256-b2-f512 | 76.30% | 80.41% | 78.13% | 80.27% | 80.09% | 79.50% |
| e224-h256-b3-f512 | 76.38% | 80.35% | 79.49% | 79.63% | 80.09% | 79.74% |

There is no consistent separation of the curves. The original three-block choice used its rising late trend and retention guards; that was suggestive, not demonstrated architectural superiority. See the [original curves](config/language_model/v12_learning_curves.png).

## Matched FFN screening at 600 steps

The FFN 512/1,024 pilot budget was 600, not 1,000 steps. Keep this cohort separate. Both curves rose rapidly and then fluctuated near the same count agreement; neither stayed ahead. The later 1,000-step record for 1,024 belongs to its full continuation, not a saved matched two-option 1,000-step pilot. See the [FFN curves](config/language_model/v11_learning_curves.png).

## Repaired candidate and text quality

Text: full-vocabulary top-3 for the next token with up to eight left tokens, 50,000 natural positions. Quick: actual Rust Quick5 candidate top-1/top-3 with one left token, 5,000 cases. Handwriting: actual Rust fusion top-1 with one left token, 2,000 fixed unique designated-test-source trajectories, frozen neural weight 1.25. Counts: actual Rust teacher-mode agreement across all 4,988 training/lexical contexts. These are different tasks and denominators; do not merge them into one accuracy.

### Capacity pilots: 1,000 updates

| Model | MB | Count top-1 | Count top-3 | Text top-3 | Quick5 top-1 | Quick5 top-3 | HWR top-1 |
|---|---:|---:|---:|---:|---:|---:|---:|
| e128_b2 | 5.861 | 79.79% | 95.65% | 30.144% | 78.34% | 93.18% | 76.50% |
| e128_b3 | 6.131 | 80.65% | 96.01% | 30.154% | 78.22% | 93.26% | 76.75% |
| e224_b2 | 9.453 | 80.01% | 95.77% | 30.202% | 78.56% | 93.24% | 76.50% |
| e224_b3 | 9.723 | 80.15% | 96.19% | 30.302% | 78.50% | 93.20% | 76.60% |

| Model | English text top-3 | Rare Han text top-3 | Added text top-3 | Count mean TV |
|---|---:|---:|---:|---:|
| e128_b2 | 1.680% | 2.608% | 0.308% | 0.3860 |
| e128_b3 | 1.800% | 2.651% | 0.285% | 0.3877 |
| e224_b2 | 1.680% | 2.693% | 0.285% | 0.3847 |
| e224_b3 | 1.760% | 2.693% | 0.308% | 0.3832 |

Selected paired comparisons below use document-group intervals for text/Quick and stroke-source-group intervals for handwriting. All are nominal 95% intervals; multiple metrics are exploratory, and seed uncertainty is absent.

| Pair (second minus first) | Task | Delta pp | 95% interval pp |
|---|---|---:|---|
| e128_b2__e128_b3 | Text next top-3 | +0.010 | [-0.106, +0.125] |
| e128_b2__e128_b3 | Quick5 one-left top-1 | -0.120 | [-0.418, +0.165] |
| e128_b2__e128_b3 | Quick5 one-left top-3 | +0.080 | [-0.102, +0.280] |
| e128_b2__e128_b3 | HWR one-left top-1 | +0.250 | [-0.051, +0.590] |
| e128_b2__e224_b2 | Text next top-3 | +0.058 | [-0.063, +0.181] |
| e128_b2__e224_b2 | Quick5 one-left top-1 | +0.220 | [-0.106, +0.536] |
| e128_b2__e224_b2 | Quick5 one-left top-3 | +0.060 | [-0.161, +0.276] |
| e128_b2__e224_b2 | HWR one-left top-1 | +0.000 | [-0.347, +0.350] |
| e128_b2__e224_b3 | Text next top-3 | +0.158 | [+0.025, +0.287] |
| e128_b2__e224_b3 | Quick5 one-left top-1 | +0.160 | [-0.156, +0.471] |
| e128_b2__e224_b3 | Quick5 one-left top-3 | +0.020 | [-0.201, +0.260] |
| e128_b2__e224_b3 | HWR one-left top-1 | +0.100 | [-0.157, +0.394] |
| e128_b3__e224_b2 | Text next top-3 | +0.048 | [-0.070, +0.171] |
| e128_b3__e224_b2 | Quick5 one-left top-1 | +0.340 | [-0.027, +0.687] |
| e128_b3__e224_b2 | Quick5 one-left top-3 | -0.020 | [-0.245, +0.199] |
| e128_b3__e224_b2 | HWR one-left top-1 | -0.250 | [-0.653, +0.197] |
| e128_b3__e224_b3 | Text next top-3 | +0.148 | [+0.024, +0.271] |
| e128_b3__e224_b3 | Quick5 one-left top-1 | +0.280 | [-0.035, +0.594] |
| e128_b3__e224_b3 | Quick5 one-left top-3 | -0.060 | [-0.262, +0.149] |
| e128_b3__e224_b3 | HWR one-left top-1 | -0.150 | [-0.458, +0.196] |
| e224_b2__e224_b3 | Text next top-3 | +0.100 | [-0.020, +0.212] |
| e224_b2__e224_b3 | Quick5 one-left top-1 | -0.060 | [-0.353, +0.228] |
| e224_b2__e224_b3 | Quick5 one-left top-3 | -0.040 | [-0.235, +0.141] |
| e224_b2__e224_b3 | HWR one-left top-1 | +0.100 | [-0.249, +0.442] |

| Model | Quick median ms | HWR prior median ms | Full-vocab top-3 median ms |
|---|---:|---:|---:|
| e128_b2 | 0.0943 | 2.3127 | 2.4486 |
| e128_b3 | 0.1148 | 2.3369 | 2.4805 |
| e224_b2 | 0.1308 | 2.4076 | 2.6894 |
| e224_b3 | 0.1511 | 2.4350 | 2.7367 |

Latency uses actual release Rust, 1,800 requests per model/flow, six alternating rounds and changing contexts. Measurements run after quality inference. They include desktop pipe/serialization overhead and are not Android or end-to-end keyboard latency.
Canonical frozen artifacts: `/Volumes/SSD/cantonese_ngram/experiments/architecture-screen-v13-1000`.

### FFN pilots: 600 updates

| Model | MB | Count top-1 | Count top-3 | Text top-3 | Quick5 top-1 | Quick5 top-3 | HWR top-1 |
|---|---:|---:|---:|---:|---:|---:|---:|
| f512 | 5.861 | 79.33% | 95.59% | 30.198% | 78.92% | 93.14% | 76.45% |
| f1024 | 6.393 | 79.39% | 95.87% | 30.218% | 78.74% | 93.02% | 76.65% |

| Model | English text top-3 | Rare Han text top-3 | Added text top-3 | Count mean TV |
|---|---:|---:|---:|---:|
| f512 | 1.640% | 2.779% | 0.308% | 0.4020 |
| f1024 | 1.640% | 2.993% | 0.308% | 0.4026 |

Selected paired comparisons below use document-group intervals for text/Quick and stroke-source-group intervals for handwriting. All are nominal 95% intervals; multiple metrics are exploratory, and seed uncertainty is absent.

| Pair (second minus first) | Task | Delta pp | 95% interval pp |
|---|---|---:|---|
| f512__f1024 | Text next top-3 | +0.020 | [-0.089, +0.126] |
| f512__f1024 | Quick5 one-left top-1 | -0.180 | [-0.484, +0.146] |
| f512__f1024 | Quick5 one-left top-3 | -0.120 | [-0.309, +0.061] |
| f512__f1024 | HWR one-left top-1 | +0.200 | [-0.099, +0.542] |

| Model | Quick median ms | HWR prior median ms | Full-vocab top-3 median ms |
|---|---:|---:|---:|
| f512 | 0.0946 | 2.3396 | 2.4305 |
| f1024 | 0.1350 | 2.3867 | 2.4792 |

Latency uses actual release Rust, 1,800 requests per model/flow, six alternating rounds and changing contexts. Measurements run after quality inference. They include desktop pipe/serialization overhead and are not Android or end-to-end keyboard latency.
Canonical frozen artifacts: `/Volumes/SSD/cantonese_ngram/experiments/architecture-screen-v13-ffn600`.

### Completed runs: 10,000 updates

| Model | MB | Count top-1 | Count top-3 | Text top-3 | Quick5 top-1 | Quick5 top-3 | HWR top-1 |
|---|---:|---:|---:|---:|---:|---:|---:|
| b2_f512 | 5.861 | 81.07% | 96.85% | 30.028% | 78.92% | 93.40% | 76.65% |
| b3_f512 | 6.131 | 81.26% | 96.85% | 30.074% | 79.10% | 93.16% | 76.65% |
| b2_f1024 | 6.393 | 82.20% | 97.37% | 30.040% | 79.42% | 93.34% | 76.35% |

| Model | English text top-3 | Rare Han text top-3 | Added text top-3 | Count mean TV |
|---|---:|---:|---:|---:|
| b2_f512 | 1.440% | 0.770% | 0.332% | 0.3198 |
| b3_f512 | 1.460% | 0.470% | 0.332% | 0.3132 |
| b2_f1024 | 1.480% | 0.684% | 0.332% | 0.3113 |

Selected paired comparisons below use document-group intervals for text/Quick and stroke-source-group intervals for handwriting. All are nominal 95% intervals; multiple metrics are exploratory, and seed uncertainty is absent.

| Pair (second minus first) | Task | Delta pp | 95% interval pp |
|---|---|---:|---|
| b2_f512__b3_f512 | Text next top-3 | +0.046 | [-0.070, +0.163] |
| b2_f512__b3_f512 | Quick5 one-left top-1 | +0.180 | [-0.165, +0.510] |
| b2_f512__b3_f512 | Quick5 one-left top-3 | -0.240 | [-0.473, -0.018] |
| b2_f512__b3_f512 | HWR one-left top-1 | +0.000 | [-0.396, +0.402] |
| b2_f512__b2_f1024 | Text next top-3 | +0.012 | [-0.111, +0.132] |
| b2_f512__b2_f1024 | Quick5 one-left top-1 | +0.500 | [+0.121, +0.892] |
| b2_f512__b2_f1024 | Quick5 one-left top-3 | -0.060 | [-0.299, +0.185] |
| b2_f512__b2_f1024 | HWR one-left top-1 | -0.300 | [-0.654, +0.000] |
| b3_f512__b2_f1024 | Text next top-3 | -0.034 | [-0.145, +0.076] |
| b3_f512__b2_f1024 | Quick5 one-left top-1 | +0.320 | [-0.038, +0.709] |
| b3_f512__b2_f1024 | Quick5 one-left top-3 | +0.180 | [-0.063, +0.432] |
| b3_f512__b2_f1024 | HWR one-left top-1 | -0.300 | [-0.697, +0.050] |

| Model | Quick median ms | HWR prior median ms | Full-vocab top-3 median ms |
|---|---:|---:|---:|
| b2_f512 | 0.0946 | 2.3479 | 2.4788 |
| b3_f512 | 0.1145 | 2.3887 | 2.4970 |
| b2_f1024 | 0.1347 | 2.3872 | 2.5078 |

Latency uses actual release Rust, 1,800 requests per model/flow, six alternating rounds and changing contexts. Measurements run after quality inference. They include desktop pipe/serialization overhead and are not Android or end-to-end keyboard latency.
Canonical frozen artifacts: `/Volumes/SSD/cantonese_ngram/experiments/architecture-screen-v13-full10000`.

## Coverage and limits

128 versus 224 embeddings and two versus three blocks have a matched 1,000-step comparison. FFN 512 versus 1,024 has matched 600-step pilots and completed 10,000-step controls. All three completed models have the same vocabulary, base/teacher hashes, seed, learning rate and objective; they are historical single-seed runs with differing validation-check schedules, not independent replications. No 224-embedding full run exists, so these pilots cannot rule out a late-training benefit.

The earlier residual-width-128 pilots were stopped and are not a completed fair basis for this choice. Residual 256 preserves the v7 contextual path; its necessity has not been proven by a repaired matched full-run width comparison. A 256-token-embedding export at residual 256 would be about 10.65 MB with two blocks or 10.92 MB with three, beyond the strict 10,000,000-byte cap. The 224 setting is the largest previously screened aligned option that fits. This is a storage constraint, not a claim that 256 performs worse.

English evaluation contains OOV misses. Added-token text quality is weak, and only 10 handwriting cases involve added tokens. Handwriting pairs isolated strokes with authentic validation contexts; it is not production capture and prior visual-model test exposure is unknown. Development data has historical reuse. The 16,794 prospectively reserved confirmation cases remain unscored.

The added embedding input/output paths, third residual block and widened FFN output columns are nonzero after training, although zero at initialization. This rules out completely inactive added capacity; it does not prove sufficient optimization. Audit: SSD `architecture-screen-v13-1000/trainability.json`.

All nine checkpoint/export pairs pass 144 actual-runtime tokenizer/log-probability parity checks each (1,296 calls total). Eight evaluation regression checks pass. Full-run prediction/rank caches reused only after matching model hashes and fusion weights; new comparisons recompute paired intervals. No architecture should be called universally superior from tiny isolated nominal gains.

## Decision

128 embeddings are supported as the compact trade-off: 224 adds roughly 3.6 MB without a consistent application-quality gain at 1,000 steps. Three blocks do not show consistent superiority over two; the original late-curve rationale is insufficient to call them best. Wider FFN improves full-run count agreement modestly; application-quality and latency results above determine whether that buys a useful keyboard trade-off. FFN 1,024 raises Quick5 one-left top-1 by 0.50 pp versus two-block FFN 512 (nominal paired 95% interval +0.121 to +0.892 pp), and Quick3 by 0.766 pp (+0.343 to +1.200 pp). Quick top-3 and natural-text next top-3 are unresolved, while rare Quick5 next top-1 falls 0.775 pp and added Quick5 infill top-1 falls 1.682 pp. Thus the wider FFN is a credible option if count agreement and first-suggestion accuracy take priority, but is not a universal improvement. FFN 1,024 adds 0.532 MB and about 0.040 ms to the desktop two-candidate Quick path; full-vocabulary top-3 adds about 0.029 ms. The absolute desktop cost is small, and Android cost is unmeasured. Two-block FFN 512 is the smallest balanced choice; current three-block v12 cannot be described as a demonstrated best architecture. The current v12 asset remains unchanged during this comparison.
