# Quick candidate first-display latency

2026-10-20: Short Quick codes now publish a selectable preview before full
sequence ranking. One model distribution ranks characters represented directly
in the vocabulary; rare byte-fallback characters follow in dictionary order.
Every match remains available. Full ranking then refines the order using the
existing algorithm. Neither stage commits text automatically.

Previously `df` waited for sequence scoring of hundreds of matches, including
many rare characters represented as several bytes. The preview avoids those
additional model evaluations. Two-letter codes also bypass mixed English ranking
while Chinese lookup is pending; other codes publish Chinese results while their
bilingual ranking runs.

Release host measurements with the packaged model and dictionaries:

| Engine stage | Median | p95 |
| --- | ---: | ---: |
| Previous `df` full ranking | 159.1 ms | 212.7 ms |
| `df` preview | 0.72 ms | 0.75 ms |
| Previous sampled short-code ranking | 140.9 ms | 469.9 ms |
| Sampled short-code previews | 0.72 ms | 1.17 ms |

These measure engine calls, excluding model initialization, worker contention,
Android editor calls and screen rendering. They are not phone touch-to-display
measurements. No phone was connected for deployment or device verification.

The benchmark uses two alternating process runs across both Quick versions,
seven codes and four contexts. All 112 full candidate lists matched the previous
engine; every preview retained exactly the full candidate set. Reproduce with
`scripts/benchmark_quick_short_codes.py BEFORE_PROBE AFTER_PROBE --output RESULT.json`.
The probes are release builds of `QwenASR/crates/cantonese-ngram`'s `ngram_probe`.

Regression coverage exercises preview selection before full ranking, cancelled
results, an occupied English worker, and the actual preview/full JNI calls with
packaged assets. The preview scenario has a separately rendered and inspected
screenshot baseline. Full `:app:verifyKeyboard` covers unit/scenario tests,
screenshot comparisons and debug assembly. Deploy through `./deploy.sh` when a
phone is connected so all required asset packs are installed.
