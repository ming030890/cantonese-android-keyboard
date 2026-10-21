# Quick autocorrect contention fix — 2026-10-21

The reported Quick/速成 session left even confident English typos unchanged.
The native model already accepts `whrn` → `when`, `rrally` → `really`, and
`whay` → `what` in the supplied contexts. The production path had two shared
locks: the Kotlin Quick engine monitor and the Rust Quick inference mutex.
English suggestions/completion/mixed ranking waited behind full Chinese ranking.
A suggestion unfinished 40 ms after Space is deliberately committed literally.

## Change

One registry entry now owns independent Quick and English inference mutexes and
mutable context caches. Both CNN instances reuse the same `Arc<Weights>`, vocabulary
and accepted policy. The Kotlin registry ID is a volatile read, independent of the
Quick monitor. Closing removes the entry; in-flight native calls retain an Arc.
No extra dictionary, new model weights, threshold, deadline or fallback decision
rule is introduced. One/two-letter Quick codes retain their Chinese protection.
Cold loading can still delay the first lookup; a saved per-layout opt-out also
continues to disable automatic correction.

## Evidence

- Before the change, `englishSuggestionDoesNotWaitForQuickMonitor` fails with a
  timeout while the Chinese monitor is held. Afterward it passes.
- A native regression holds the Quick inference lock and traverses the production
  English ranking registry path successfully on another worker; closed IDs fail.
- A real-JNI production-controller regression shows selectable `when` in Quick,
  commits `when ` on Space while the Chinese worker is occupied, and restores
  `whrn` on Backspace. All 13 shared-model scenarios pass with no skips.
- The representative Quick screenshot shows literal `whrn` and selectable `when`.
  This render uses a fixed model response; JNI behavior is tested separately.

Host contention panel (macOS arm64, 32 warmed calls per binary, before then after):

| English JNI elapsed during full Quick `df` ranking | Before | After |
|---|---:|---:|
| Median | 277.55 ms | 1.17 ms |
| p95 | 301.12 ms | 1.37 ms |

The same Kotlin wrapper is used for both binaries, isolating the native lock
change in this panel. Chinese gets a 5 ms head start. English repeatedly requests
`whrn` with empty left context; Chinese uses `I use item {index} ` so it reranks
each time. Repeated English context benefits from its private cache. This is one
host run per binary, not device input-to-display latency or whole-session F₀.₅.
Samples and binary hashes: [contention report](config/english_iteration/2026-10-21/quick-contention.json).

The 9,728-case native check preserves the accepted policy decisions. Frozen
2,042-case evaluation remains **361 correct / 23 wrong / F₀.₅ 0.79167**, including
5 changed preservation controls. The probe's three candidate-order differences
are relative to the archived pre-literal cache, as in the previous accepted
iteration. Model and policy hashes are unchanged. See
[native verification](config/english_iteration/2026-10-21/native-verification.json).

This reproduces a production failure mechanism, not a diagnosis from logs on the
user's phone. No device was connected. The fix does not promise every word in the
reported sentence: `dont` remains a known token, and `meant` is a valid word;
adding apostrophes and grammar correction remain model limitations.
