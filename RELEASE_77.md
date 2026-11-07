# Release 77: multi-word Quick lattice ranking and words.hk LM rescue

Multi-word Quick (`速成`) decoding and the shared `KBCNN_INT8_V1` language model
(`model.cng`, 12,675,264 bytes) have been updated:

- **Offline `words.hk` (`existingwordcount.json`) Frequency Floor:** Words attested in
  `existingwordcount.json` with count >= 100 receive a minimum training frequency
  floor (e.g., `兒歌` count 487 -> floor 2,435), allowing `humo` to rank `兒歌` at `#1`
  without shipping any external dictionary JSON in the app bundle.
- **Boundary-Conditioned CNN Training:** Removed the 50% unconditioned prefix dropout
  during Quick/Cangjie prefix training so `prev_char` boundary context is consistently
  passed (`P(歌 | 兒, mo)` rises from 1.9% to 44.0%).
- **Quick Lattice Scoring & Pruning (`quick.rs`, `suggestions.rs`):**
  - Replaced unclamped word-boundary cohesion with non-negative `cohesion_bonus = cohesion.max(0.0)`
    to prevent cross-word boundary transitions from penalizing valid multi-word phrases.
  - Added `SINGLE_CHAR_WORD_PENALTY` (`0.42` for 2-char queries, `0.65` for 3+ char queries)
    to reduce unnatural single-character decompositions (`smyv` -> `羽絨`, `hqiw` -> `留低`).
  - Calibrated `PREFIX_PRUNE_DELTA = 10.0`, `CHAR_COUNT_PRUNE_DELTA = 11.0`,
    `GLOBAL_PRUNE_DELTA = 14.0`, and first-boundary `expand_cap = 12` (`start == 2`).
- **Emoji History Cap:** Recent emoji history retains the latest 24 unique selections
  across sessions.

Companion native source: `QwenASR` commit
`4312dca8d015b75fa542cc12770016167799ff79`.

Validation:
- 800-case Multi-Word Quick benchmark: Top-1 improves `62.25%` -> `64.75%` (`+2.50%`),
  Top-3 improves `80.00%` -> `80.63%` (`+0.63%`), Recall improves `84.88%` -> `86.12%`
  (`+1.25%`), and latency improves (`p50` `8.11 ms` -> `4.16 ms`, `p95` `19.29 ms` -> `12.69 ms`).
- 2,000-case Single-Word Quick benchmark: Top-1 `53.35%` -> `53.70%` (`+0.35%`),
  Top-5 `79.65%` -> `80.20%` (`+0.55%`), Recall `88.75%` -> `89.30%` (`+0.55%`).
- `:app:verifyKeyboard` and `scripts/verify_language_model_assets.py` (source tree and
  `--package app-release.aab`) pass with zero regressions.

Build and publish with `./publish_internal.sh`, using the established Google
Play internal testing track (`versionCode = 77`).
