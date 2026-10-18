# Rejected staged voice loading: historical Pixel 9 experiments

**Superseded by [the acceptance decision](VOICE_LATENCY_DECISION.md).** Staged
loading and preparation-mode switches have been removed. The measurements below
are historical and do not describe the retained loading policy.

Updated 2026-10-18. This supersedes the keyboard-start full-model warming in
[VOICE_MMAP_VALIDATION.md](VOICE_MMAP_VALIDATION.md). Changes are in the source and
side-by-side diagnostic app; the installed Play app has not been replaced.

## Current behavior

1. **Keyboard opens:** resolve/map the model and read its metadata/small setup
   weights. The mappings remain demand-paged. Do not prefetch the full model or
   commit large ASR scratch buffers during ordinary typing.
2. **Mic tap:** start capture immediately. Start cancellable encoder-only prefetch
   on a background thread and prepare the shared tokenizer/bounded first-pass
   scratch concurrently. PCM buffers through the existing per-recording channel.
3. **VAD detects speech:** start decoder prefetch immediately, while more audio is
   accumulating and the encoder runs. This starts earlier than waiting for the
   first encoder chunk to begin computation. Read the existing selected packed
   decoder weights/output head in their existing sequential file order.
4. **Inference:** wait for encoder readiness before the first encoding pass.
   Decoder computation can proceed while its prefetch is running; normal demand
   paging handles any weights that have not arrived yet. There is no wait for
   the entire decoder prefetch before starting the encoder.
5. **Stop:** finish the normal final inference, then cancel remaining prefetch
   requests and join the task. With silence/no encoding, cancel the waiting
   decoder stage without starting it. Cancellation is checked between 1 MiB
   requests, including during encoder prefetch. Kernel readahead can fetch beyond
   a request; this is not a hard byte cap on storage reads.

A new recording owns a new gate/task and fresh VAD/audio/hypothesis state. The
immutable tokenizer and scratch allocations are reused within the loaded engine.
The task owns an `Arc` to immutable mapped files. The session adopts its task
before worker startup; engine release cancels/joins a task that was never adopted.
Existing engine ownership, finalization, CPU policy, one worker, 400 ms inference
chunks and 800 ms backlog threshold remain in place.

Already-used model pages remain available for follow-up queries under the existing
engine-release lifecycle; they are reclaimable, not pinned. An actual decoder
pass still uses approximately the full model. Staging changes *when* it becomes
resident and avoids reading decoder weights when there is no voice to transcribe.

## Why the decoder starts at speech onset

The initial prototype waited until encoder computation began and reordered
requests into computation order. Pixel 9 testing rejected that combination:
first text was about 4.5 s versus 3.7–3.8 s for the existing mic-time path.
The reordered traversal was removed and the sequential read order retained.

With the same sequential policy in the revised experiment, waiting for encoder
computation still gave 3.905–3.912 s first text, versus 3.641–3.643 s when speech
onset released the decoder prefetch. Waiting wastes part of the interval used to
accumulate the first speech chunk. These are two runs per condition on one clip;
they do not establish a universal optimum.

One final cold PTT trace, relative to the mic tap:

| Milestone | Time |
| --- | ---: |
| Encoder prefetch starts | 13 ms |
| Encoder prefetch ready | 491 ms |
| VAD speech onset / decoder prefetch starts | 579 ms |
| First mel/encoder work | 771–868 ms |
| Decoder prefetch ends | 2,769 ms |
| First editor text | 3,927 ms |

The trace establishes about 97 ms of overlap with the first mel/encoder pass,
plus overlap with audio accumulation and later inference. The remaining cold
read cost has not disappeared. The first streaming pass can produce no text;
first editor text also includes subsequent streaming inference.

## Resident memory: measured without confusing it with mapping size

Pixel 9, fresh process, debug app, real IME. `--idle-only` opens the actual keyboard
without starting a recording. `/proc/self/smaps` measures model-file RSS separately
from the whole app; `/proc/self/smaps_rollup` supplies whole-process figures.

| State | Resident model pages | Whole debug-app RSS |
| --- | ---: | ---: |
| Keyboard idle, no mic tap: final default | 14.6 MiB | 343.5 MiB |
| Earlier eager keyboard-start control | 1.91 GiB | 2.24 GiB |
| Four seconds of silence: staged default | 326.1 MiB | 742.2 MiB |
| Four seconds of silence: parallel decoder control | 1.71 GiB | 2.11 GiB |
| After normal dictation | 1.92 GiB | 2.36 GiB |

The virtual model mapping was about 1.92 GiB in all cases. It did not imply that
all mapped pages were resident. Whole-app RSS includes the keyboard, ART/JIT,
other application models and native heap, so the 14.6 MiB figure is not total app
memory. File-backed pages can be reclaimed later; snapshots are not memory limits.

In the staged silence test there were **zero decoder-start events** and no
explicit decoder prefetch. Only the 335,670,336-byte encoder range was requested.
The parallel control requested the additional 1,727,216,896-byte decoder range
despite receiving no speech. Metadata/small decoder setup pages can already be
resident from model loading.

A 64 ms silent recording requested only 149 MiB of encoder prefetch before
cancellation, never started decoder prefetch, and reached Idle about 187 ms after
Stop. It did not hang waiting for a decoder stage that had never been released.

## Latency and transcript checks

In the revised counterbalanced cold comparison, the existing mic-time path
reported 3.68–3.90 s first text; speech-triggered staging reported 3.64 s in both
trials. A separate final-build run reported 3.93 s. Warm first text was roughly
1.7–1.8 s. Treat cold response as comparable, with variation in cache state and
scheduling; the retained benefit is avoiding large unused preloads and overlapping
work after clear voice intent. No stop-to-idle compute improvement is claimed.

All tests used the user's Pixel 9, serial `4C070DLAQ000NM`, the real service,
coordinator, native library, VAD and editor insertion. Only microphone reads were
replaced by real-time PCM replay. The original app/IME selection was restored.

Evidence under ignored build artifacts:

- `build/voice-benchmark/staged-ab/`: initial rejected prototype, two idle memory
  probes and eight cold/warm pairs (16 speech queries).
- `build/voice-benchmark/staged-vad/`: revised counterbalanced comparison, eight
  cold/warm pairs (16 speech queries).
- `build/voice-benchmark/staged-final/`: final idle memory probe and 17 capture
  queries covering silence, immediate stop, PTT, another Cantonese clip, multiple
  utterances, six rapid restarts and tracing disabled.

The first two experiment families used preceding native builds; the final family
uses this packaged native SHA-256:
`b0f8f793bdcb80226b73185037f9707f8ecd82adac2605e11de9c5a791780240`.

## Run the same tests

Use the current debug build and all five ready model packs. The runner kills the
app before its first query, checks complete PCM/editor integration/native-library
identity, and restores test properties and the prior keyboard afterward.

```sh
# No-mic memory probe, with an explicit cache-eviction request.
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --cold-cache --idle-only --prepare-lead-ms 3000 \
  --output build/voice-benchmark/staged-idle-repeat

# Actual default staged path: first/cold-process query plus warm repeat.
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --cold-cache --output build/voice-benchmark/staged-repeat
```

`--prepare-mode` offers debug-only controls:

| Mode | Behavior |
| --- | --- |
| `staged` | Default: encoder at capture, decoder at VAD onset |
| `encode` | Wait until encoder computation to release decoder prefetch |
| `parallel` | Start decoder as soon as encoder prefetch finishes, even in silence |
| `encoder` | Explicit encoder prefetch only; decoder uses demand paging |
| `none` | Existing synchronous encoder + per-session async decoder path |
| `memory` / `weights` / `all` | Earlier synchronous preparation controls |
| `background` | Earlier keyboard-start full warmup control |

An empty/unknown property, including restored `0`, selects production `staged`.
Overrides require a debug APK plus explicit tracing. `--cold-cache` requests
`POSIX_FADV_DONTNEED` before mapping this app's model files; verify actual reads
and residency because cache eviction is advisory. Process restart alone does not
evict the filesystem cache. Use equal lead times in paired comparisons.

`prefetch_stage` log markers show encoder start/readiness, speech onset and decoder
start/end. `capture_total` spans native capture preparation through worker/task
join and prompt cleanup, including encoder I/O that precedes native session start.
Do not add it to overlapping worker/stage counters. These logs and page-residency
checks remain debug-build-and-opt-in only. `--no-trace` runs the production staged
policy without detailed logs; it cannot apply diagnostic preparation/cache modes.

## Verification and remaining work

- 26 native tests passed on the Pixel 9, including gate cancellation for silent
  and early-stop sessions, early speech release, shared tokenizer state, preserved
  float bits and advice failure reporting. Host native suite: 41 passed.
- Android unit suite: 219 passed, 16 skipped; release Kotlin compilation passed.
- The required keyboard gate retains the same 22 pre-existing screenshot failures;
  unrelated edits and baselines were preserved.
- Final app runs passed audio completeness, editor integration and packaged-native
  identity. All three speech clips retained the baseline words; the two Cantonese
  clips matched exactly and PTT punctuation varied. Silent cases intentionally
  produced no editor text. Tracing-off runs
  emitted no detailed Kotlin/native lines.

Further work should measure sustained memory pressure and lifecycle behavior on
additional devices. Cold model reads and compute remain necessary for real speech;
layer timing cannot avoid using all decoder layers/output weights for a token.
Keep app-level first-text, update-gap and stop/finalization metrics alongside
fault counts when evaluating another optimization.
