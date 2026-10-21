# Common-word single-edit reproduction

Follow-up: [traced root causes and controlled ablations](SINGLE_EDIT_ROOT_CAUSE.md).

2026-10-21. Correction behavior is unchanged. This is a diagnostic benchmark,
not a policy calibration set or a replacement for `TYPING_EVALUATION.md`.

Select the 1,000 highest-frequency ASCII words (lengths 1–20) from the existing
SCOWL-expanded dictionary with canonical TRAIN counts. This source reflects the
project's corpus, including brands and local usage; it is not a general-English
frequency survey. Selection precedes inference and does not filter by the current
model vocabulary. The exact [word list](config/english_iteration/2026-10-21/single-edit/words.tsv)
and [report with content hashes](config/english_iteration/2026-10-21/single-edit/report.json)
are retained.

For each word, select one deterministic hash-chosen error from each operation:
arbitrary letter substitution, QWERTY neighbour substitution, deletion, insertion,
and adjacent transposition. Equal-letter swaps are excluded; nine words have no
distinct swap. QWERTY and arbitrary substitutions are separate, potentially
overlapping panels. Transposition is one typing operation, not one Levenshtein
edit. Include the 1,000 unchanged words as controls. All inputs have empty left
context; the target is never supplied to inference. Total: 4,991 corruptions and
1,000 controls.

The release probe was rebuilt from current native source and loaded the current
packaged model. Results use the production engine's `english_suggestions` and
its returned automatic correction. Android editor/space handling is not exercised.

| Error | Cases | Correct automatic fixes | Wrong replacements | Left unchanged |
|---|---:|---:|---:|---:|
| Arbitrary substitution | 1,000 | 306 (30.6%) | 27 | 667 |
| QWERTY substitution | 1,000 | 442 (44.2%) | 12 | 546 |
| Missing letter | 1,000 | 39 (3.9%) | 39 | 922 |
| Extra letter | 1,000 | 176 (17.6%) | 17 | 807 |
| Adjacent swap | 991 | 500 (50.5%) | 7 | 484 |

Overall: 1,463 correct fixes, 102 wrong replacements, 3,426 unchanged errors.
One clean control changes (`its → it's`). Including controls, precision is
93.42%, recall 29.31%, F₀.₅ 0.64993. These describe this synthetic mixture;
they do not estimate precision on naturally occurring typing. Corruptions that
produce other valid words are ambiguous without context: 641 cases are recognized
as known words, with four intended fixes. Among 4,350 unknown-word corruptions,
1,459 are fixed (33.54%).

The intended word is returned in 4,983/4,991 corruption cases. Of the 3,528 missed
intended fixes, 8 lack a returned target, 1,511 have it below first place, and
2,009 have it first but do not accept it. This partitions misses by target rank;
it is not a causal policy ablation. Within the last group, 47 have inputs shorter
than three letters, 20 have targets shorter than three, and 110 are marked as
completions (checked in that order). The remaining 1,832 need policy/guard analysis.
Missing-final-letter errors can be classified as completions, which the automatic
gate rejects. Other failures include first-ranked `plizza → pizza`,
`butger → burger`, `piza → pizza`, and `gruop → group`, all left unchanged.

Finding edit-distance-one neighbours is straightforward. Selecting the intended
word and deciding when to change it remain imperfect here, even for nonwords.
The measured failures justify investigating ranking and acceptance rather than
assuming dictionary coverage is the main issue. No thresholds or coefficients
were changed. Future changes must fit on TRAIN, freeze before evaluation, and
report F₀.₅ and latency against the established real-typing baseline as well.

Host engine timing on this run: typo median 1.131 ms, p95 2.031 ms, after 16
warmups. The diagnostic requests up to 4,096 suggestions. This is one host run,
not a paired performance comparison or phone input-to-display measurement.

Reproduce from the repository root:

```sh
CARGO_TARGET_DIR="$PWD/app/build/english-host" cargo build --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml --release --bin ngram_probe
python3 scripts/benchmark_single_edit.py --words /Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt --probe app/build/english-host/release/ngram_probe --output build/single-edit-final-2026-10-21
python3 -m unittest discover -s scripts -p test_benchmark_single_edit.py
```

Full case outputs, candidates, ranks and timings are in the ignored
`build/single-edit-final-2026-10-21/predictions.jsonl`; its hash is in the report.
The two benchmark tests verify actual one-operation corruptions (including short
and repeated-letter words) and F₀.₅ accounting for wrong replacements and controls.
