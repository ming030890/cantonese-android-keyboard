# Retained voice changes after experiment cleanup

Date: 2026-10-18. The user requested removing only our uncertain optimizations,
while preserving their pre-existing work.

## Removed

- Cooperative preview cancellation, its atomic hook, rollback helper, diagnostic
  property and cancellation-specific tests. Its benefits varied and it has not
  been qualified on a large speech corpus.
- Highest-frequency-core preference and its diagnostic property. The inference
  worker now uses the existing performance-core policy.
- Our fixed two-thread production change. The app again requests its original
  one ASR thread. Thread overrides remain available only for diagnostic tracing.
- No sample-count final token cap, consensus bypass or early Idle/resource-release
  change was introduced.

## Retained

- Single-row packed decode kernel, correct activation packing and shared packed
  weights, with numerical parity coverage across shapes and thread counts.
- Persistent thread-pool resize/lifetime correctness fixes. Inactive helpers do
  not execute the closure; every spawned helper acknowledges each dispatch; new
  helpers do not replay an expired dispatch.
- Explicit application of the existing performance-core policy on the actual JNI
  worker, rather than only on the model-loading thread or pool helpers.
- Bounded, page-aligned prefetch of used packed decoder weights and the output
  head, excluding the large mostly-unused token embedding table.
- Deferred replaceable previews once Stop is queued, without dropping PCM, plus
  a single inference pass for the complete final tail. No in-flight cancellation.
- Real-IME replay, per-stage/kernel/token timings, cold-process/warm-repeat and
  restart tests, full-audio checks, packaged-native hashing and device context.

These are supported by code-path analysis, native parity/correctness tests and
actual-app replay. They are not a guarantee of identical latency on every device.
The earlier published before/after timings included CPU/thread tuning that has
now been removed, and must not be presented as cleaned-build measurements.

## Existing work preserved

The user's 400 ms chunk setting, 800 ms backlog setting, asynchronous prefetch,
Android populated-page prefetch, and per-recording engine serialization/capture
buffering are unchanged by this cleanup. Unrelated Quick/autocorrect, emoji/UI,
screenshot baseline and evaluation work is excluded from the voice commits.

## Verification of the cleaned configuration

The fresh JNI build must be packaged into the diagnostic copy before accepting
results. Existing experiment output remains historical evidence; it is not
silently reused as a cleaned-build test.

Native source commit: sibling QwenASR `2de121e`
(`perf(asr): optimize packed decoding and finalization`).

- Native Android release build and diagnostic AAB build passed.
- On-device native suite: 18 passed. Host native library/audio/kernel tests:
  34 passed. Android unit/scenario tests: 217 passed, 16 skipped.
- Twelve cleaned-build app queries passed: cold/warm replay for the two reference
  clips and the two-utterance recording, plus restart pairs at 0, 50 and 200 ms.
  Full PCM, zero rejected chunks, complete native traces and matching hashes were
  verified. Both transcripts survived every immediate restart.
- Every native worker trace reports one thread and the existing performance-core
  mask `4-7` on Pixel 9. All retained override strings were empty.
- The restart app run completed successfully; logcat truncated a diagnostic line
  inside UTF-8. The repaired reader was validated against that saved raw log and
  the complete app-private JSON, without rerunning or changing the transcript.
  Missing per-recording Idle during overlap is now reported as null, not a
  negative latency. The previous recording's completion uses native stop return.

Cleaned-build measurements (one cold/warm pair each; no claim of universal speedup):

| Clip | Query | First editor text | Stop → Idle |
| --- | --- | ---: | ---: |
| ptt | cold | 3.48 s | 2.98 s |
| ptt | warm | 1.90 s | 2.09 s |
| cv001 | cold | 2.20 s | 3.08 s |
| cv001 | warm | 2.13 s | 2.96 s |
| multi | cold | 2.59 s | 2.78 s |
| multi | warm | 2.52 s | 2.46 s |

Fresh packaged native SHA-256: `06054948830725c1c682076b69d6383df63192b36d499ab126c8892ff0a2d04d`.

Evidence: [ptt](build/voice-benchmark/clean-retained/ptt/result.json),
[cv001](build/voice-benchmark/clean-retained/cv001/result.json),
[multiple utterances](build/voice-benchmark/clean-retained/multi/result.json),
[immediate restarts](build/voice-benchmark/clean-retained/restart/result.json).


The required `:app:verifyKeyboard` gate is run separately. Existing screenshot
mismatches are reported without changing unrelated reference images.

## Run again

From the Android repository with the cleaned diagnostic build and model packs ready:

```sh
python3 scripts/benchmark_voice.py \
  ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM --runs 2 \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --output build/voice-benchmark/clean-repeat
```

An invocation kills the process before its first query and then performs a warm
repeat. Source defaults use one thread and the existing performance-core policy;
clear any retained diagnostic overrides before measuring those defaults. Native
trace `worker_policy` records the effective thread count and allowed CPU mask.
