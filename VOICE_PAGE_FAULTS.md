# Voice page faults and diagnostic logging

**Superseded by [the acceptance decision](VOICE_LATENCY_DECISION.md).** Staged
loading and preparation-mode switches have been removed. The measurements below
are historical and do not describe the retained loading policy.

Current policy: [retained loading path and accepted Stop fix](VOICE_LATENCY_DECISION.md).
Keyboard-start full-model warming was replaced after the memory trade-off was
reviewed and tested on the user's Pixel 9. Measurements below retain their original
configuration unless explicitly identified as current.

2026-10-18 follow-up: controlled tests confirmed a substantial cold-paging cost,
verified successful `MADV_POPULATE_READ`, and added preparation before recording.
See [mmap validation and retained preparation](VOICE_MMAP_VALIDATION.md) for the
current implementation, reproducible controls and results. Earlier measurements
below retain their original configuration.

Measured 2026-10-18 on Pixel 9, using the real debug IME and the 3.858-second
`ptt_guardrail.wav`. Source defaults remain one ASR worker, 400 ms inference
chunks, 800 ms backlog threshold and asynchronous decoder prefetch.

## Logging gate

Detailed `VoiceLatency` and `VoiceNative` logs require **both**:

1. An Android debug build (`BuildConfig.DEBUG=true`, passed to Rust through JNI).
2. `adb shell setprop debug.qwen_asr.latency 1`.

Release builds pass false regardless of the property. The Rust library is built
with release optimization for both APK variants, so Rust `debug_assertions` would
be the wrong gate. The native gate defaults to false. Kotlin refreshes the gate
at every recording, including after a benchmark restores the property.

Normal sessions skip detailed metric collection. Explicit debug test replay can
still return benchmark JSON with tracing disabled. The injection activity exists
only in debug builds. An existing unconditional speech-onset DEBUG print is now
behind the engine's verbose level; the app loads with verbosity zero. Operational
and error logs remain available.

The runner enables tracing and restores the previous property automatically.
Use `--no-trace` for a control run; this deliberately omits native trace metrics.

## How counters are collected

`getrusage(RUSAGE_THREAD)` is sampled before and after each named thread scope.
`getrusage(RUSAGE_SELF)` measures the whole process for `session_total`, from
before encoder prefetch through worker/prefetch joins and prompt cleanup. This
covers short audio calls, initialization and finalization omitted by the previous
`thread_work` totals. Model loading occurs earlier and is outside this native
session scope; its wall time remains in the app trace. The coordinator also calls
`preloadEncoder()` before `nativeStartVadSession`; that earlier explicit prefetch
is outside this counter window. The `encoder_preload` row measures the repeated
prefetch inside native session startup. Include the earlier call before treating
these counters as the entire capture-to-idle process cost.

Both syscall results must succeed. Invalid measurements are flagged rather than
silently turned into zero. `/proc/self/io` `read_bytes` is sampled at the process
scope boundaries to record storage reads charged to the process.

Minor faults did not require disk I/O; they can come from new heap pages or page
table setup for resident file pages. Major faults required I/O. Neither count
measures fault duration or unique bytes. Prefetch/readahead can cause storage
reads without a corresponding large major-fault count. Process counters include
other app threads, including UI/JIT, and are not exclusive ASR attribution.

Process totals, worker totals, finalization and individual inference stages are
**overlapping scopes**. Do not add their totals. `mel_encoder` includes encoding
preparation/cache work; `prepare_prefill` includes embedding allocation/copies;
`stream_setup` covers lazy tokenizer loading and prompt preparation. Stage rows
sum that stage across all calls, including calls during finalization.

## Measured breakdown

Values are minor / major faults. Times are summed wall milliseconds, first / warm.
This is one diagnostic pair, not a statistically established performance change.

| Scope | First query faults | Warm query faults | Wall ms: first / warm |
| --- | ---: | ---: | ---: |
| `session_total` | 114,254 / 74 | 25,567 / 1 | 6769.8 / 7099.7 |
| `worker_total` | 67,375 / 31 | 18,939 / 0 | 6747.8 / 7088.8 |
| `encoder_preload` | 0 / 0 | 0 / 0 | 20.5 / 9.1 |
| `decoder_preload` | 30,500 / 0 | 2 / 0 | 2196.5 / 67.1 |
| `vad_init` | 3 / 0 | 2 / 0 | 0.1 / 0.1 |
| `stream_setup` | 18,561 / 0 | 16,921 / 0 | 150.1 / 176.8 |
| `mel_encoder` | 27,946 / 0 | 1,332 / 0 | 989.9 / 1243.5 |
| `prepare_prefill` | 201 / 0 | 314 / 0 | 0.6 / 0.8 |
| `prefill` | 19,180 / 31 | 162 / 0 | 3307.8 / 3166.1 |
| `decode` | 448 / 0 | 0 / 0 | 1473.7 / 1662.7 |
| `finalize` | 2,516 / 0 | 588 / 0 | 1976.0 / 1880.8 |

The process read **1.369 GB** on the first query and
**4.52 MB** on the warm repeat. The async decoder prefetch took
2196 ms / 67 ms. Those prefetch times overlap inference; they are not
extra time to add to end-to-end latency. Restarting the app does not evict the OS
page cache; different cold-process runs can therefore have very different reads.

Warm tokenizer/prompt setup accounts for **89% of main-worker minor faults**
(16,921 / 18,939) and about **177 ms**. VAD initialization caused only two faults.
The setup source reloads `vocab.json` into a new `StreamState` for each recording;
its tokenizer survives window resets, but not creation of a new recording session.
This implicates repeated tokenizer construction, rather than large per-pass
embedding allocations, as the first reuse candidate. The setup scope does not
separate tokenizer parsing from prompt preparation, so do not assign all 177 ms
to parsing without a narrower measurement.

Warm finalization still took about 1.88 seconds with zero major faults. Reducing
page faults alone will not solve stop-to-idle latency; inference compute and
preview/finalization scheduling remain separate targets.

First text was 2.82 / 1.88 seconds; Stop→Idle was
2.95 / 3.27 seconds. Small scheduling differences change preview count
and final tail size, so these runs do not establish tracing overhead or savings.

## Run again

```sh
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --runs 2 --output build/voice-benchmark/page-fault-check
```

Run again with `--no-trace` and a different output directory to verify that detailed
logging is off. Each invocation starts a fresh process. For overlapping restart
coverage, use `--restart-delays 0,50,200`. Installation and full probe limitations
are documented in [VOICE_LATENCY.md](VOICE_LATENCY.md).

Result files for this investigation (ignored build artifacts):

- `build/voice-benchmark/page-fault-scopes/setup/`: final scoped trace pair.
- `build/voice-benchmark/page-fault-scopes/setup-off/`: final tracing-off pair.
- `build/voice-benchmark/page-fault-scopes/trace/`: earlier pair before adding the
  tokenizer/VAD setup scopes; do not combine counts with the final pair.

Final scoped native library SHA-256:
`11b20380a247b10f8823a986d2534486f84654e43257b8fb3cafd34055a65285`.
Audio completeness, editor output and packaged-library identity passed.

## Is cold latency caused by page faults?

Cold model I/O is a strong suspect for a substantial part of first-response
latency, but the current counters do not establish its exact contribution.
In the final pair, first text was 2.82 s cold versus 1.88 s warm, with 1.369 GB
versus 4.52 MB of process storage reads. An earlier pair showed 4.26 s versus
1.97 s and 1.734 GB versus 4.47 MB. Cache state, prefix reuse and preview
scheduling also differed, so those gaps are not measured page-fault savings.

The final pair's summed prefill wall time minus worker CPU time was about
647 ms cold versus 28 ms warm (earlier pair: 1,286 ms versus 94 ms). This is
consistent with cold I/O stalls, but includes scheduling and other waits.
It is not a direct fault-latency measurement. The async prefetch wall time also
includes I/O/waits and overlaps prefill, so it must not be added to that gap.

The modest major-fault count does not mean little I/O: prefetch can read model
pages without charging each read as a major fault. Optimize first-text latency
and storage/wait behavior, rather than just reducing the major-fault counter.

For cold first response, prioritize a controlled test of starting the existing
selective prefetch once the model is ready, before the microphone is tapped,
instead of starting decoder prefetch only when recording begins. Check advice
success/fallback and which tensors still fault during the first prefill; preserve
coverage of the actually used packed weights without pulling in the entire
embedding table. Validate using equivalent cache states and measure both
launch-to-first-text and tap-to-first-text. Force-stopping alone does not reset
the filesystem cache. Moving I/O earlier only helps when it overlaps useful
startup/idle/audio-capture time; it does not eliminate true cold storage work.
A smaller on-disk model/weight representation is the direct way to reduce that
unavoidable read volume, and needs separate accuracy validation.

Warm stop latency is a different issue: finalization took 1.88 s with zero major
faults. Cold prefetch improvements will not by themselves remove that compute.

## Updated next work

1. Split tokenizer parsing from prompt preparation, then test retaining the
   immutable tokenizer for the loaded engine's lifetime. Preserve fresh audio,
   VAD, hypothesis, prompt and decoder state per recording. Invalidate on model
   replacement and release with the engine. Validate cold, warm and rapid restart
   behavior, transcript equality and retained memory before keeping the change.
2. Extend the process counter window to include the coordinator's earlier explicit
   encoder-prefetch call. Recheck cold prefetch coverage, advice success/fallback
   and storage reads.
   Major-fault counts alone miss prefetch I/O. Avoid treating every minor fault
   as a storage problem or adding another unconditional whole-model warmup.
3. Consider per-pass embedding scratch reuse only after measuring its allocation
   cost. Warm preparation here caused 314 faults and less than 1 ms summed wall
   time, much less than tokenizer setup. Encoder/decoder scratch already has reuse.
4. Continue streaming/batching experiments separately: compare first-text time,
   update gaps, Stop→final-text and Stop→Idle, with transcript equality and
   complete audio. The warm zero-major-fault finalization remains compute-heavy.

This change adds measurement and logging guards; it does not introduce another
unvalidated inference, affinity or buffer-lifetime optimization.

## Verification

- Final Pixel 9 trace-on and trace-off pairs: complete audio, editor text and
  freshly packaged native identity passed. Trace-off emitted **zero**
  `VoiceLatency` / `VoiceNative` lines.
- Native device tests: 19 passed, including the debug/opt-in guard matrix.
- Android unit tests: 219 passed, 16 skipped; the two new metric-gate tests passed.
- Release Kotlin compilation passed; bytecode passes literal false to JNI.
- `:app:verifyKeyboard` remains blocked by the same 22 screenshot mismatches
  present before this work. Existing screenshot changes/baselines were preserved.
