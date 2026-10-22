# Unified keyboard suggestions

The production keyboard now owns one cancellable composing request for Quick and
English. Quick composition bookkeeping remains in `QuickInputCoordinator`;
`EnglishInputCoordinator` owns the shared request so a word boundary can await
its correction decision. `NativeSharedSuggestions` performs one JNI request and
returns Chinese candidates, English correction evidence and the final display row.
The controller no longer combines independently arriving production candidate rows.

The shared CNN already produces scores for its full vocabulary. Code matching
filters that score table, then orders the matches; sorting only matches gives
the same best N without sorting unrelated tokens. Short Quick codes preserve
characters missing from the single-token vocabulary as a dictionary-frequency
tail. Complete two-letter codes keep all Chinese matches ahead of English.
English retains its literal candidate, spelling-neighborhood eligibility,
completion scoring and fitted correction/apostrophe policies.

Longer Quick input still needs phrase search: the probability of a continuation
changes after each character. Each retained prefix filters its full distribution
before choosing the best segment transitions. This removes the previous initial
32-item dictionary-frequency cutoff. Join the resulting phrase pool with English
and apply final bilingual ranking once. This is one native request, not a claim
that a multi-token phrase can be scored with one neural forward pass.

A request-local score cache pins the initial context and bounds distribution
storage to 8 MiB. Conditional tables are reused for English decisions, phrase
ranking and language evidence; eviction may recompute later tables. Completed,
failed and cancelled requests release their cached tables. No second LM weights,
new dictionary, fitted coefficients or ranking exceptions are introduced.

## Cancellation and presentation

New input/context cancels the current coroutine and its native request token.
Native cancellation uses a separate registry, without acquiring an inference lock.
Checks occur between CNN token steps/distributions, lexical enumeration and phrase
expansions. Interrupting a CNN projection clears partial recurrent buffers before
the next request. Queued work also releases its token even if its body never starts.

Both the controller request generation and Quick's code/version request generation
must still match before publication. Keep the previous completed row visible
while computing a replacement, reject taps against an obsolete row, then publish
the replacement once. Session/layout changes cancel requests; repeated callbacks
with identical context reuse a valid pending/completed Quick request. Boundary
waiting still times out after 40 ms, commits literally and cancels inference.
Disabled Quick correction does not wait for Chinese inference at Space.

## Verification

The final keyboard gate passes 233 unit/scenario/JNI tests, 83 screenshot checks
and debug APK assembly. The ARM64 library is rebuilt from the current native
source; its cancellation/composition exports are present in the packaged library.
The 28 native Rust unit tests pass. No tests in the keyboard gate were skipped.

- The frozen 2,042-case evaluation has exact English candidate/score/correction
  parity, preserving F₀.₅ 0.82154 (418 correct, 32 wrong, 8 clean changes).
- The short-code panel retains identical candidate sets. Completed native request
  median/p95: 145.253/480.650 → 1.748/3.802 ms, 112 paired requests. `df` performs
  one full-distribution computation, shared by Chinese and English.
- The phrase panel median/p95: 117.297/362.695 → 86.510/336.234 ms, 96 pairs.
  `humo` after `I use ` replaces tail candidates `先歌`, `禿一人` with `税一人`,
  `税兩` on both versions; the top eight in those cases are unchanged. Other
  phrase candidate sets in this panel are identical. This is a bounded development
  panel, not a broad Cantonese quality qualification.
- Regression scenarios cover rapid typing, stale taps, cancellation before and
  during lookup/native inference, context changes, session hide, layout round-trip,
  boundary timeout/following-key order, disabled correction, spacing, correction
  and undo on both layouts. The retained-row screenshot is visually inspected.

The timing panel measures native work to a completed row. The former pipeline
also displayed an earlier Chinese preview; these results do not measure that
first-paint boundary or Android input-to-display latency. Phone latency and actual
IME rendering remain unmeasured for this change. No device installation is implied.

Reproduce with the current shared assets and native source:

```sh
CARGO_TARGET_DIR=/private/tmp/unified-candidates-native cargo build \
  --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml \
  --release --features android --bin suggestion_probe --lib
python3 scripts/verify_unified_suggestions.py \
  --probe /private/tmp/unified-candidates-native/release/suggestion_probe \
  --output config/pipeline_audit/2026-10-22/unified-suggestions.json
./gradlew :app:verifyKeyboard \
  -PenglishNativeLibrary=/private/tmp/unified-candidates-native/release/libcantonese_engine.dylib
```

The report binds the model and probe hashes. Rebuild the ARM64 library using the
LM build section in `deploy.sh` before APK assembly; host JNI tests do not verify
an older packaged `.so`.
