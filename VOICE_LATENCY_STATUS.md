# Voice latency: status, testing, and next work

Updated: 2026-10-17. This is a handoff for the current working-tree implementation;
the changes have not been published to Play.

Cleanup on 2026-10-18: experimental preview cancellation, fastest-core preference
and the two-thread production change were removed. The original one-thread
setting is restored. Existing chunk/backlog tuning, asynchronous prefetch and
restart handling are preserved. See [retained changes](VOICE_LATENCY_KEPT_CHANGES.md).

Historical snapshot: later source changes introduced 400 ms inference chunks,
an 800 ms backlog threshold, asynchronous decoder prefetch and serialized
per-recording engine ownership. The measurements below retain their original
configuration. See [the 2026-10-18 Pixel 9 validation](VOICE_LATENCY_IDEAS_VALIDATION.md)
for current-code experiments and the status of the five proposed optimizations.

## Status quo

The actual Android IME can now be benchmarked from a command. A debug activity
opens an EditText and drives the real service/coordinator. Only microphone capture
is replaced with a WAV replay, paced in real time in 64 ms PCM chunks. JNI,
queueing, VAD, inference, callbacks and editor insertion remain on the app path.
The Rust simulator's audio-position metrics do not measure this user-visible path.

Native compute was the main bottleneck. The implementation now has:

- A single-row decode kernel, parallel large projections and corrected thread-pool
  resizing/lifetime handling.
- Selective decoder prefetch, starting with recording.
- Two ASR threads and explicit affinity on the actual native inference worker.
  The serial worker prefers the highest-frequency performance-core class detected
  from sysfs, with a performance-core fallback. Pixel 9 CPU 7 is an observed
  outcome, not a hardcoded core ID.
- Deferred intermediate previews after Stop, while consuming all queued PCM
  through VAD, followed by one final inference pass over the remaining tail.
- App/native timing logs, live log capture, complete-audio checks and verification
  that the packaged native library matches the freshly stripped local build.

The CPU policy and fixed two-thread setting were evaluated on one Pixel 9.
Dynamic core detection does not prove that these choices are optimal on other
SoCs, or under sustained thermal load. Treat them as device tuning requiring
broader validation. Kernel parity tests support numerical correctness; they do
not establish end-to-end accuracy across a large speech corpus.

The current streaming defaults are 600 ms inference chunks, two-token rollback,
adaptive buffer draining and preview deferral when the JNI queue contains at
least 200 ms of audio. Capture chunk size and inference chunk size are different.
The app publishes a hypothesis after a processing call returns; generated tokens
are not individually delivered to the editor. Model weights, language and VAD
silence threshold were not changed by the latency work.

## What improved, and what remains

These measurements use one baseline cold/warm pair and two final-build pairs per
reference clip. After values show the range of both final pairs. Times begin at
recording, after the initial editor/IME setup; model loading has separate events.

| Clip / query | First text: before → after | Record → idle: before → after | Stop → idle: before → after |
| --- | ---: | ---: | ---: |
| Mixed-language / cold | 6.56 → 4.24–4.27 s | 9.34 → 6.32–6.40 s | 5.48 → 2.45–2.54 s |
| Mixed-language / warm | 2.75 → 2.38–2.42 s | 8.30 → 6.42–6.49 s | 4.44 → 2.56–2.63 s |
| Cantonese / cold | 4.14 → 2.26–2.43 s | 12.12 → 7.99–8.17 s | 7.35 → 3.24–3.41 s |
| Cantonese / warm | 1.89 → 1.74–1.85 s | 9.97 → 7.50–8.70 s | 5.21 → 2.74–3.95 s |

Streaming responsiveness is only partly optimized. In the final mixed-language
warm runs, visible update gaps were approximately 1.9–2.1 seconds. The native
kernel changes improved processing speed, but we have not systematically tuned
the streaming/batching tradeoff or measured actual decoder inter-token intervals.

A warm query can show text earlier yet finish later. For example, first text
improved from 4.24 to 2.42 seconds between consecutive queries, while total time
increased from 6.32 to 6.49 seconds. Logs show two streaming inference calls before
finalization in the cold query versus four in the warm query. The adaptive policy
therefore changes the work schedule between runs. This is consistent with the
timing difference; it is not a controlled proof of its cause. Page faults,
scheduling and thermal variation also matter.

The existing benchmark waits for the previous query to reach Idle and then waits
300 ms before the next query. **It does not test an immediate Stop → Start.**
Code inspection found a lifecycle concern: `start()` does not wait for the
previous finalization job; the wrapper clears its session handle before the old
native worker has joined. A new session can therefore be started against the
same engine while the old worker is finishing. The coordinator also mutates
prompt state during startup. This needs a reproduction and an ownership fix;
it has not been demonstrated as a newly introduced regression or fixed yet.

## Existing latency breakdown

The following is extracted from the final-build [mixed-language result](build/voice-benchmark/fixed-defaults/ptt/result.json)
and [its live log](build/voice-benchmark/fixed-defaults/ptt/logcat.txt). The input is
3.858 seconds long. These are one concrete cold/warm pair, rather than averages;
the ranges in the preceding table include the second verification pair.

### Native processing stages

| Whole-query wall-time counter | Cold | Warm | Meaning |
| --- | ---: | ---: | --- |
| Mel features | 0.008 s | 0.011 s | Audio feature preparation |
| Encoder | 1.263 s | 1.466 s | Encoding audio across the processing passes |
| Decoder prefill | 2.500 s | 2.290 s | Processing audio/context before incremental decode |
| Token decode | 1.260 s | 1.340 s | Aggregate decode-loop work across passes |
| Logged native processing calls, including finalization | 5.790 s | 5.265 s | Enclosing wall-time total, including other work |
| Maximum queued-audio residence | 1.836 s | 1.352 s | Longest measured wait for an audio message |

Prefill remains the largest stage. In the original cold baseline it took
4.012 seconds, with 2.481 seconds in token decode and 1.504 seconds in the encoder.
The current cold stage counters total about 5.031 seconds; the enclosing logged
processing total is 5.790 seconds. The approximately 0.759-second difference
includes decoder prefetch and other unseparated work, rather than another decode
stage. Very short native processing calls are omitted from the log total.

Kernel counters identify the expensive work inside those stages:

| Kernel wall-time counter | Cold | Warm |
| --- | ---: | ---: |
| Packed matrix multiplication | 3.276 s | 3.168 s |
| Convolution | 0.646 s | 0.902 s |
| Output head | 0.572 s | 0.355 s |
| Bidirectional + causal attention | 0.201 s | 0.289 s |
| Float matrix multiplication (`sgemm`) | 0.072 s | 0.109 s |
| Activation packing | 0.040 s | 0.056 s |

These are nested/overlapping wall-time counters. Do not add kernel totals to
stage totals, queue residence to inference time, or processing time to audio
duration. Audio capture, queueing and native execution overlap. These numbers
also do not measure individual token latency or pure CPU execution time.

### Startup and delivery overhead

In this cold trace, asset resolution took approximately 39 ms and model
load/configuration 72 ms. Both occurred before simulated recording began, so they
are outside the record-to-idle figures. The benchmark's model wait at recording
was below 0.1 ms because loading had already completed. This is not a claim that
a cold model loads in less than 0.1 ms.

The native worker reached `worker_ready` after 168 ms cold versus 0.7 ms warm;
this covers worker setup, including VAD initialization, without separating each
component. Decoder prefetch hint calls then took 615 ms cold versus 4.8 ms warm.
The cold prefetch duration is already included in the enclosing native processing
total above. Returning from prefetch does not guarantee that every page is resident.

The first captured PCM chunk arrived after approximately 65 ms, matching the
64 ms capture pacing. Maximum JNI push time was 0.27 ms cold and 1.21 ms warm.
Maximum callback-to-editor dispatch was 26.1 ms cold and 2.3 ms warm. These maxima
are not per-query additive costs. Native inference and scheduling dominate the
remaining seconds of delay.

### Visible streaming timeline

All timestamps below are seconds since recording started, taken from the editor
events rather than inferred from audio sample positions.

| Event | Cold | Warm |
| --- | ---: | ---: |
| First text inserted | 4.243 | 2.421 |
| Next changed hypothesis inserted | 6.250 | 4.554 |
| Last changed hypothesis inserted | 6.250 | 6.418 |
| Stop requested | 3.872 | 3.863 |
| Idle | 6.322 | 6.494 |

The cold query had two visible updates, both after recording stopped; its first
inference call produced no nonempty editor text. The warm query had three visible
updates, with gaps of 2.134 and 1.864 seconds. It therefore streamed some text
while recording, but did not deliver smooth token-by-token output. A stable-delta
callback can occur without a changed visible hypothesis, so stable-delta counts
must not be treated as counts of visible updates.

### Where time goes after Stop (directly measured milestones)

Earlier reports approximated the interval before final inference by subtraction
(`utterance_complete - stop_requested - finalize_ms`). The implementation now logs
explicit monotonic milestones across both Kotlin and the native engine:
- `recorder_stopped` and `stop_requested`
- `engine_lock_acquired` / `engine_lock_released`
- `native_stop_call`
- `native_stop_received` / `queued_audio_drained`
- `final_inference_start` / `final_inference_end` (`finalize_ms`)
- `utterance_complete`
- `native_worker_joined` / `native_stop_return`
- `last_editor_update` and `idle`

Direct measurements on Pixel 9 (battery 20.5–22.9°C, thermal status 0) show:

| Direct Stop-to-Idle milestone / interval | Standalone Warm | 0 ms Restart (Q1) | 50 ms Restart (Q1) | 200 ms Restart (Q1) |
| --- | ---: | ---: | ---: | ---: |
| Stop tap → native stop call | 0.8 ms | 0.9 ms | 0.4 ms | 0.8 ms |
| Native stop call → queued audio drained | 448 ms | 452 ms | 422 ms | 448 ms |
| Final inference pass (`finalize_ms`) | 1746.7 ms | 1614.2 ms | 1638.3 ms | 1651.2 ms |
| Final inference end → native worker joined | 66.4 ms | 68.7 ms | 75.3 ms | 57.7 ms |
| Native worker joined → JNI return | 82.9 ms | 63.7 ms | 71.6 ms | 64.9 ms |
| JNI return → last editor update & idle | 1.1 ms | 2.1 ms | 2.7 ms | 2.3 ms |
| **Direct stop_to_last_text_ms** | **2302.5 ms** | **2647.8 ms** | **2067.1 ms** | **2116.8 ms** |
| **Direct stop_to_native_stop_return_ms** | **2383.6 ms** | **2706.2 ms** | **2135.4 ms** | **2182.2 ms** |
| **Direct stop_to_idle_ms** | **2384.7 ms** | (overlapped) | (overlapped) | (overlapped) |

These direct measurements verify:
1. Outstanding audio draining takes ~420–450 ms after Stop is called.
2. Final inference takes ~1.61–1.75 s of compute.
3. Native thread join and state release take ~60–80 ms.
4. Total delay from Stop tap until final text is visible in the editor is directly 2.02–2.65 s.

### What the breakdown cannot establish yet

- Actual generated-token intervals, or when each spoken word first becomes
  visible relative to its acoustic position.
- Immediate Stop → Start handoff cost; existing warm repeats start after Idle.
- The separate contributions of page faults, scheduling, thermal throttling and
  arithmetic inside each stage. The recorded page-fault counters cover the main
  worker during logged audio-processing calls, excluding helpers and finalization.
- Display-frame presentation, microphone-driver latency or behavior in another
  app's editor. The current timestamps measure this debug editor's InputConnection.

## Run the app benchmark now

Run from this Android repository. Requirements: Python 3, adb, an authorized
connected ARM64 device and the debug app with all five model packs available.
Input must be uncompressed 16 kHz, mono, PCM16 WAV.

The diagnostic copy already installed on the tested phone is
`com.madeinhk.cantonesevoicekeyboard.voicebenchmark`. The original Play package
and data are preserved. Substitute the serial returned by `adb devices` when
using another device.

```sh
cd /Users/tony/Documents/keyboard/cantonese-voice-keyboard
adb devices
export VOICE_DEVICE=4C070DLAQ000NM

# Empty overrides select the current source defaults.
for key in worker_pin prime_core threads matmul_m1 bounded_prefetch defer_stop; do
  adb -s "$VOICE_DEVICE" shell setprop "debug.qwen_asr.$key" '""'
done

python3 scripts/benchmark_voice.py \
  ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial "$VOICE_DEVICE" \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --runs 2 \
  --output build/voice-benchmark/status-ptt

python3 scripts/benchmark_voice.py \
  ../QwenASR/bench/android-assets/cv_yue_001.wav \
  --serial "$VOICE_DEVICE" \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --runs 2 \
  --output build/voice-benchmark/status-cv001
```

Each invocation force-stops the target before the first query. `--runs 2` means
one cold query and one warm query, not two cold starts. Run the command again with
a new output directory for another cold/warm pair. `--keep-running` skips process
force-stops; it is not the immediate Stop → Start test. Do not run benchmarks
concurrently against the same phone. The runner wakes the display, captures
logcat live, restores the previous IME and trace setting, and closes the test app
after a normal cold-process invocation.

The loop above clears prior experimental overrides. The runner records these
properties but does not clear them automatically. Omit that loop only when
deliberately testing named overrides from [the A/B controls](VOICE_LATENCY.md).

Use a new output directory for every experiment; these commands write into the
specified directory. `result.json` contains transcripts, milestone times, native
stage/kernel totals and validity flags. `metadata.json` contains audio/native
hashes, process restart status and overrides. `logcat.txt` retains every emitted
hypothesis/editor event for update-gap analysis.

Require `status=ok`, `audio_complete`, `editor_complete`,
`native_trace_complete` and `native_matches_local_build` before accepting a run.
Inspect the transcript as well: complete audio does not establish recognition
accuracy. Invalid setup attempts must be kept separate from performance samples.

## Build and regression checks

Gradle does not build the Rust library. After native edits, rebuild and copy
`libqwen_asr.so` using the native build section of [deploy.sh](deploy.sh), retaining
`blas android kleidiai`, DOTPROD, static OpenBLAS and the configured Kleidiai
library. The older [manual guide](BUILD_SO.md) omits the Kleidiai feature in its
example; that example alone does not reproduce the measured build.

For an updated diagnostic copy, after refreshing JNI and staging the model packs:

```sh
./gradlew :app:verifyKeyboard
./gradlew :app:bundleDebug -PvoiceBenchmark=true

java -jar .tools/bundletool-all-1.18.1.jar build-apks \
  --bundle=app/build/outputs/bundle/debug/app-debug.aab \
  --output=build/voice-benchmark-full.apks \
  --overwrite --local-testing --connected-device \
  --adb="$(command -v adb)" --device-id="$VOICE_DEVICE"

java -jar .tools/bundletool-all-1.18.1.jar install-apks \
  --apks=build/voice-benchmark-full.apks \
  --adb="$(command -v adb)" --device-id="$VOICE_DEVICE"
```

The bundle must include all five asset packs; verify model readiness in the app
logs. These are diagnostic packaging commands. The required keyboard gate is
currently failing on existing screenshot mismatches; the successful bundle build
does not mean that gate passed. Resolve intended UI differences independently and
never update baselines merely to silence failures. Normal `./deploy.sh` targets
the original package rather than the diagnostic suffix and stops at a failed gate.

Recorded verification: 210 Android unit/scenario tests passed, 16 skipped;
18 native tests passed on the phone, including kernel parity and pool resizing;
34 host native tests passed. Thirteen valid final-build app queries covered six
single-utterance clips and a two-utterance recording. The bundle built successfully.
The full keyboard gate reported 22 screenshot mismatches out of 63 comparisons.
These are previous results, not checks rerun while writing this document.

## Next work, in order

This plan is based on the measured breakdown above. The work below is planned,
not implemented or benchmarked yet. Keep the current model, kernels, CPU policy,
thread count and decoding settings fixed while investigating scheduling; changing
all of them together would make attribution difficult.

| Evidence | Next target | Limit of the current evidence |
| --- | --- | --- |
| Warm Stop spends about 0.703 s before final inference, versus 0.363 s cold | Work already in flight and handoff into finalization | The waiting components are not individually timed yet |
| Final inference still takes about 1.85–2.01 s on the mixed-language pair | Final-pass prefill, encoder and decoding work | Removing the preceding wait alone cannot remove this cost |
| Warm editor updates are about 1.9–2.1 s apart | Preview scheduling and hypothesis publication | Actual decoder inter-token intervals are unmeasured |
| Cold decoder prefetch takes 0.615 s; worker setup takes 0.168 s | Recording-start critical path | Prefetch duration does not measure all page-fault costs |
| Packed matrix multiplication takes about 3.2 s per mixed-language query | Remaining native compute after scheduling changes | Nested counters cannot be added to enclosing stage totals |

### 1. Establish restart correctness and separate the Stop waits (COMPLETED)

**Status:** Completed and verified on device (Pixel 9, `4C070DLAQ000NM`).

**Implementation:**
- **Single-owner mutual exclusion:** `engineMutex` guards all native engine calls in `VoiceInputCoordinator.kt`. The engine is never released until the native worker joins.
- **Immediate capture buffering:** Each session has its own `CoordinatorSession` with an internal `Channel<ByteArray>(capacity = 256)`. If a new Start is tapped immediately after Stop while the previous session's native worker is still running, capture starts immediately and audio is buffered in the channel without blocking the UI thread or dropping chunks.
- **Transcript preservation & isolation:** Callbacks check whether their specific session was discarded. Session 1's final hypothesis and `onTranscriptComplete()` are committed into `editor.text` before Session 2 acquires `engineMutex` and begins composing. Old callbacks cannot affect the new composition.
- **Monotonic milestone logging:** Monotonic uptimes and elapsed session milestones added across Kotlin and native Rust (`jni_api.rs`, `vad_stream.rs`), including `session_id`, `pass_id`, `audio_start_ms`, `audio_end_ms`, `native_stop_call`, `native_stop_received`, `queued_audio_drained`, `final_inference_start`, `final_inference_end`, `native_worker_joined`, and `last_editor_update`.
- **Harness & scenario testing:** Extended `VoiceBenchmarkActivity.kt` and `scripts/benchmark_voice.py` to benchmark immediate restart delays (`--restart-delays 0,50,200`). Added `VoiceRestartScenarioTest.kt` with 7 scenarios verifying mutual exclusion, rapid repeated restarts, cancellation during engine-wait, editor session changes, and IME view hide. All 233 unit tests pass.

**Measured restart benchmark results (Pixel 9, battery 22.9°C, thermal status 0):**

| Metric | 0 ms Restart Delay | 50 ms Restart Delay | 200 ms Restart Delay |
| --- | ---: | ---: | ---: |
| Overlap prevention verified | **true** (Q2 engine lock acquired 2.3 ms after Q1 released) | **true** (Q2 engine lock acquired 0.3 ms after Q1 released) | **true** (Q2 engine lock acquired 2.7 ms after Q1 released) |
| Query 1 PCM complete | 3857.8 ms (100%) | 3857.8 ms (100%) | 3857.8 ms (100%) |
| Query 2 PCM complete | 3857.8 ms (100%) | 3857.8 ms (100%) | 3857.8 ms (100%) |
| Query 2 rejected chunks | 0 | 0 | 0 |
| Query 1 final inference (`finalize_ms`) | 1614.2 ms | 1638.3 ms | 1651.2 ms |
| Query 1 Stop tap → native stop return | 2706.2 ms | 2135.4 ms | 2159.4 ms |
| Query 1 Stop tap → last editor update | 2647.8 ms | 2067.1 ms | 2078.2 ms |
| Query 2 capture start → engine available | 2710.3 ms | 2086.6 ms | 1995.7 ms |
| Query 2 engine available → first editor text | 1285.4 ms | 981.5 ms | 1017.2 ms |
| Query 2 capture start → first editor text | 3995.6 ms | 3068.1 ms | 3012.9 ms |
| Combined committed text | Identical, no corruptions or missed words | Identical, no corruptions or missed words | Identical, no corruptions or missed words |

### 2. Reduce outstanding preview work at Stop

The existing fix already suppresses new replaceable previews after native Stop
is requested. It does not interrupt an inference pass that is already running.
Investigate that boundary first, rather than merely reducing the 600 ms chunk
size. The warm query's longer pre-finalization interval is the immediate measured
target; recorder/lock/VAD work must be separated before attributing it all to a
preview.

Compare the current policy with a Stop-aware policy that checks for finalization
at safe inference boundaries. Evaluate finishing the in-flight preview versus
ending it early and reusing valid encoder/context caches for the final pass.
Cooperative interruption is a candidate experiment, not an assumed win: throwing
away work and recomputing it may worsen completion time. Never kill a worker
thread or discard queued PCM to obtain a better timing result. A final pass must
still cover all accepted audio.

Compare Stop just before a preview starts, during encoder/prefill/decode, and
when the worker is idle. Include utterance endpoints and long speech. Retain the
current one-pass final tail as the control.

Completion evidence: a directly measured reduction in pre-finalization wait and
Stop-to-last-text/Idle, with no accuracy or restart regression. On the measured
warm example, eliminating the approximately 0.70 s preceding finalization would
still leave roughly 1.85 s of final inference, plus exit overhead. That is an
illustrative upper bound on this opportunity for that run, not a speedup promise.

### 3. Tune streaming cadence against completion cost

Add actual decoder-token timestamps at the decode loop. The stable-token callback
runs after batching and cannot measure generation cadence. Report first-token
latency and inter-token intervals separately from first editor text and
p50/p95/max gaps between changed editor hypotheses. Distinguish revised text from
newly appended text. Also report inference count, audio covered per pass,
encoder/prefill/decode time, queue age, Stop-to-last-text and total duration.

Introduce diagnostic controls for inference chunk size, backlog threshold and
maximum preview age; these controls do not exist yet. Confirm encoder-window and
cache constraints before choosing candidate sizes. Compare the current adaptive
drain/200 ms backlog policy with policies that bound preview deferral. A maximum
preview age alone cannot guarantee an update deadline if an individual inference
pass takes longer than that age.

First test scheduling with the existing hypothesis publication mechanism. If
significant waiting remains between useful partial text being decoded and a
batch returning, separately prototype publishing revisable partial hypotheses
at safe boundaries. Preserve UTF-8 correctness, session ownership and the ability
to revise earlier words. Forwarding raw generated tokens is not sufficient.

Completion evidence: a comparison showing first-text time, update-gap distribution,
Stop-to-final time, total time, compute per audio second and transcript accuracy
for every candidate. Use repeated cold/warm runs and longer continuous speech.
Identify the tradeoff between responsiveness and completion instead of selecting
solely by total time. Choose acceptable regression budgets from the measured
tradeoff; no universal chunk size or latency guarantee is established yet.

### 4. Address the remaining final-pass compute and cold-start cost

Even with a faster handoff, final inference remains substantial. In the concrete
warm mixed-language trace its 1.851 s final pass includes approximately 0.560 s
encoder, 0.768 s prefill and 0.518 s token decode. Check whether the preceding
preview and final pass repeat work that can safely be reused. Validate cache
invalidation against revised hypotheses, final-tail length and utterance changes.
Do not assume the entire pass is redundant or remove finalization for speed.

For first-query latency, separately investigate the 0.615 s decoder-prefetch call,
0.168 s worker setup, first inference without visible output and remaining page
faults. Test whether bounded preparation can move off the recording critical path
without competing with inference or causing large eager reads for ordinary typing.
Measure launch-to-first-text as well as record-to-first-text, so moving work before
the recording timer is not mistaken for eliminating it.

Only pursue further matrix-kernel/thread/affinity changes as a separate experiment
with scheduling held fixed. Packed matrix multiplication remains the largest
kernel cost, but the existing fastest-core and two-thread choices are already
specific tuning requiring more device evidence.

Completion evidence: reduced final-pass or first-query wall time with explained
stage changes, preserved transcripts and reported memory/thermal costs. Keep
stage, nested kernel and queue timings separate.

### 5. Validate the combined change and portability

Use the actual IME path for all user-visible performance claims. Include short
and long continuous speech, pauses, mixed Cantonese/English, silence, noisy audio,
multiple utterances, immediate restarts and editor changes. Check complete PCM,
transcript accuracy, missing/repeated words, safe cancellation and stale callbacks.
Interleave baseline/candidate order, retain slower valid trials, and repeat cold
and warm runs with thermal context. Quantify tracing overhead before treating
small differences as improvements.

Evaluate multiple SoCs and sustained thermal conditions before generalizing CPU
placement or thread count. Compare the fastest-core policy with the performance
pool and several thread counts separately from the streaming-policy comparison.
Keep the original Play app intact during diagnostic installs. Run the required
keyboard gate and report the existing screenshot failures independently; changing
unrelated baselines is not a voice-latency fix.

The next deliverable is a measured Stop/Start timeline and a tested engine-ownership
fix, followed by a controlled Stop-aware scheduling comparison. The following
milestone is a streaming-versus-completion comparison that reports both objectives
and recognition quality, with remaining cold-start and compute costs identified.

## Evidence and limitations

- [Probe implementation and A/B controls](VOICE_LATENCY.md)
- [Original baseline breakdown](VOICE_LATENCY_RESULTS.md)
- [Implemented fixes, measured results and raw-log links](VOICE_LATENCY_FIX.md)
- Android: `VoiceInputCoordinator.kt`, `VoiceBenchmarkActivity.kt`,
  `VoiceAudioSource.kt`, `VoiceLatencyTrace.kt`, `QAsrEngine.kt` and
  `scripts/benchmark_voice.py`.
- Rust: sibling `../QwenASR/crates/qwen-asr/src/`, especially `jni_api.rs`,
  `vad_stream.rs`, `transcribe.rs`, `affinity.rs`, `kernels/` and `safetensors.rs`.

Cold means a fresh process with extracted model packs; Android's filesystem cache
is retained. Replay excludes microphone hardware/acoustic preprocessing. Editor
timestamps measure InputConnection calls returning, not display presentation.
Debug Kotlin and the simple editor can differ from release/third-party editors.
Thermal conditions were not controlled: baseline status was 0 with battery 37.6°C;
final cleanup was status 1 with battery 39.6°C. Kernel and stage wall-time counters
overlap; do not sum them. Main-worker CPU counters exclude parallel helpers and
the finalization call. Build-directory evidence and temporary build dependencies
are local artifacts and may need regeneration on another checkout.
