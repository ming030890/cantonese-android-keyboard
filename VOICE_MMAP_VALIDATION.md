# Cold paging, mmap validation and preparation before recording

**Superseded by [the acceptance decision](VOICE_LATENCY_DECISION.md).** Staged
loading and preparation-mode switches have been removed. The measurements below
are historical and do not describe the retained loading policy.

Current policy: [retained loading path and accepted Stop fix](VOICE_LATENCY_DECISION.md).
Keyboard-start full-model warming was replaced after the memory trade-off was
reviewed and tested on the user's Pixel 9. Measurements below retain their original
configuration unless explicitly identified as current.

Measured on Pixel 9 on 2026-10-18 using the real IME, a freshly killed/restarted
side-by-side debug app, and real-time WAV replay. These tests follow the earlier
[page-fault report](VOICE_PAGE_FAULTS.md). Production still uses one ASR worker,
400 ms chunks, an 800 ms backlog threshold and the existing CPU policy.

## Finding

**Cold model paging is a substantial cause of slow first text. The prefetch flag
works; starting the work only after the mic tap leaves it on the critical path.**
Weights-only preparation is a useful causal control: it changes when mapped
weights are populated, while leaving tokenizer/scratch preparation on demand.
Across two cold-process runs per condition on `ptt_guardrail.wav`:

| Preparation before mic | First text after tap | First prefill wall time | First prefill major faults |
| --- | ---: | ---: | ---: |
| None | 3.70–3.95 s | 1.70–1.95 s | 23–26 |
| Weights only, waited to finish | 1.87–1.90 s | 358–361 ms | 0 |
| Tokenizer/scratch memory only | 3.63–3.66 s | 1.80 s | 25–29 |
| Both, waited to finish | 1.73–1.74 s | 345–346 ms | 0 |

In the weights-only comparison, first-prefill wall time minus thread CPU time
fell from 881–1,165 ms to 6–7 ms. That difference includes scheduling and other
waits, so it is not an exact kernel fault-duration measurement. However, together
with the controlled preloading, storage reads, residency checks and zero major
faults, it provides much stronger evidence than fault counts alone.

The no-preparation controls read about 1.87–2.05 GB during the initial encoder
prefetch plus native session. Weights-only controls read about 4 MB in those same
post-tap windows. Weight loading was moved earlier, not eliminated. The control
still uses the new shared tokenizer on warm repeats; first-query comparisons
start with a fresh engine and empty tokenizer cache.

## What flag actually runs

The files are mapped `PROT_READ | MAP_PRIVATE`. We do not apply `MAP_POPULATE`
to entire shards, which would pull in unused weights too. Selective prefetch
uses `madvise(MADV_POPULATE_READ=22)`, falling back to `MADV_WILLNEED` on error.

Debug diagnostics now record requested bytes, populate success/failure, errno,
fallback success/failure, and residency immediately before/after each advised
range using `mincore`. Observed on the Pixel 9:

| Range | Requested bytes | Successful populate calls | Failed calls / fallbacks |
| --- | ---: | ---: | ---: |
| Encoder | 335,670,336 | 1 | 0 / 0 |
| Selected decoder weights/output head | 1,727,216,896 | 1,809 | 0 / 0 |

All measured pages were resident immediately after each advised range, with zero
residency-query failures. In a cold encoder example, residency increased from
2,351 of 81,951 pages to 81,951 of 81,951. Decoder ranges covered 421,771 pages.
Before counts are sampled progressively, not as one simultaneous snapshot.
These checks confirm successful population; they do not lock pages against later
reclaim. Residency and syscall checks run only with debug tracing enabled.

The API meanings and limitations are documented in the Linux manuals for
[madvise](https://man7.org/linux/man-pages/man2/madvise.2.html),
[mincore](https://man7.org/linux/man-pages/man2/mincore.2.html) and
[posix_fadvise](https://man7.org/linux/man-pages/man2/posix_fadvise.2.html).

## Retained implementation

- While loading the model, construct and retain one immutable tokenizer per
  engine. Each new recording shares it via `Arc`; audio, VAD, token history and
  hypotheses still start fresh. Replacing/freeing the model releases its cache.
- Allocate and write-touch bounded first-pass encoder/decoder scratch and the
  first 64 KV positions per head before marking the model ready. This commits
  writable pages ahead of capture, rather than relying on zero-filled virtual
  allocations. Page stride comes from the OS, not Pixel-specific constants.
- Start encoder/selected-decoder population in a background task as preparation
  finishes. A recording adopts that task into its existing join lifecycle. It
  does not start a second decoder prefetch. If no task exists, the existing
  per-recording async prefetch remains the fallback.
- The task owns an `Arc` to immutable mapped files, rather than borrowing a
  mutable engine through a raw pointer. Freeing an unused engine joins its task;
  a started session joins its adopted task before releasing engine ownership.

Memory preparation moved roughly 43k–46k process minor faults into preparation.
Tokenizer/prompt setup during the first recording dropped from about 18.5k minor
faults to 0–1. This does not make recording allocation-free: audio-dependent
outputs and larger prefill capacity can still grow. The full maximum-length KV
cache is not eagerly committed.

Waiting synchronously for all weights made launch-to-first-text worse in the
controlled test. That is **not the production policy**. Synchronous `weights` and
`all` modes are debug-only controls. Production starts the task in the background
and permits capture/inference to overlap it.

## Final background-path results

Two cold-process trials per condition, with the same artificial pause after model
readiness in each condition:

| Mic timing | No eager preparation | Background preparation |
| --- | ---: | ---: |
| Immediately after readiness | 3.87–3.95 s first text | 3.50–3.60 s |
| 3 seconds after readiness | 3.65–4.26 s first text | 1.747–1.749 s |

Immediate-tap launch-to-first-text ranges overlapped: 4.92–5.26 s for the controls
versus 4.88–5.11 s for background preparation. With the equal three-second pause,
launch-to-first-text was 7.86–8.44 s versus 6.03–6.09 s. The pause represents time
with the keyboard ready before a user taps the mic; it is not removed from the
launch measurement. An immediate cold tap still needs storage reads, so it does
not receive the full benefit of early preparation.

After the three-second pause, first-prefill major faults were zero with background
preparation, versus 25–68 in controls. Some background reads can still overlap
capture; the relevant weights were available by the first prefill. Warm first
text remained about 1.72–1.75 s. Stop/finalization timings still varied with preview
scheduling; this work does not establish a stop-to-idle improvement.

These are two trials per condition on one device and a short reference clip,
not a broad performance/accuracy qualification.

## Reproduce

From the Android repository with the current debug app/model packs installed:

```sh
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --cold-cache --prepare-mode none --runs 2 \
  --output build/voice-benchmark/cold-control
```

Repeat with `--prepare-mode weights`, `memory`, `all` or `background`, using a new
output directory. To compare preparation during idle time, add the same
`--prepare-lead-ms 3000` to both control and background runs. Default production
preparation is `background` when no diagnostic override applies.

The runner restores its preparation/cache/latency overrides and the prior IME.
Cache eviction is a debug-only `POSIX_FADV_DONTNEED` request on this app's model
files before mapping; it is advisory. Verify `cache_control` results, actual
reads and residency. A process restart alone does not create a storage-cold run.
`--no-trace` cannot be combined with these diagnostic overrides, because they
require both a debug APK and the explicit trace flag.

Evidence under ignored build artifacts:

- `build/voice-benchmark/mmap-controlled/`: eight mode-comparison pairs (16 queries).
- `build/voice-benchmark/mmap-background/`: four immediate-tap pairs (8 queries).
- `build/voice-benchmark/mmap-lead/`: four equal-lead-time pairs (8 queries).
- `build/voice-benchmark/mmap-final/`: two additional clip pairs, six rapid-restart
  queries, and a tracing-off pair (12 queries).

Final installed native SHA-256:
`a66cbd60eceb764d6ea5a708dad3979d39c94a08c49b16f0eaa00c2717535c21`.
The controlled-mode and earlier immediate-tap experiments used preceding builds;
the final lead-time and additional validation runs use this hash.

## Verification and next work

All 44 app queries passed complete-audio, editor integration and packaged-native
identity checks. The two additional Cantonese clips matched their prior retained
build transcripts exactly. The PTT controls can differ in punctuation with preview
scheduling. Final tracing-off replay emitted zero detailed Kotlin/native lines.
Native tests: 22 passed on Pixel 9; host native tests: 37 passed. Android unit tests:
219 passed, 16 skipped. Release Kotlin compilation passed. The required keyboard
verification still reports the same 22 existing screenshot mismatches; existing
screenshot edits/baselines were preserved.

Next, assess preparation time and resident memory under keyboard open/close and
memory pressure on additional devices. Do not commit the entire maximum KV cache
or add a blocking full-model warmup to the mic path. Further stop/finalization
work should target compute and streaming scheduling; it remains slow even when
major faults are zero.
