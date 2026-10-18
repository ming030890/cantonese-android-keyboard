# App voice latency probe

For the current handoff and prioritized follow-up work, see
[status, testing, and next work](VOICE_LATENCY_STATUS.md).
For the 2026-10-18 Pixel 9 experiments and corrections to the proposed savings,
see [validation of the five latency proposals](VOICE_LATENCY_IDEAS_VALIDATION.md).

The debug-only `VoiceBenchmarkActivity` opens a normal EditText and the real
`CantoneseInputMethodService`. It invokes that service's production
`VoiceInputCoordinator`; only microphone reads are substituted with PCM from a file.
Model asset resolution/loading, the 2048-byte capture chunks (64 ms), RMS/debug
recording work, packaged JNI library, native worker queue, Silero VAD, streaming
inference, hypothesis callbacks, main dispatcher and InputConnection insertion
remain on the production path. Replay uses absolute monotonic deadlines, so
inference does not artificially slow the audio producer.

## Run

Install a current debug build with the normal `./deploy.sh` flow. Then:

```sh
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav
```

Every invocation force-stops and restarts the target app by default, then runs
one first query and one warm repeat. `--runs 3`, `--serial SERIAL`, `--output DIR`
and `--keep-running` are supported. The script selects the target IME and restores
the previous selection afterward. By default it also closes the debug app after
capturing results, releasing the test editor’s keep-screen-on flag. No screen automation or test APK is required.
The runner wakes the display before launching to establish the editor/IME session.
The input must be uncompressed 16 kHz mono PCM16 WAV. Input and results live in the
debug app's private `files/voice-benchmark/` directory and are transferred with
`run-as`; no storage permission or arbitrary public file reader is added.

If the installed Play app uses a different signing key, build a side-by-side
package with `./gradlew :app:bundleDebug -PvoiceBenchmark=true`, package/install
that bundle using bundletool `--local-testing` (including all five asset packs).
The runner automatically detects this copy when the normal package is not debuggable.
To select it explicitly, add:

```sh
--package com.madeinhk.cantonesevoicekeyboard.voicebenchmark
```

The side-by-side copy runs the same source and native library, with its own
settings and model packs. It preserves the installed app and its data. The
benchmark entry activity is absent from release manifests; injection additionally
checks `BuildConfig.DEBUG`.

## Cache and memory measurements

The experimental staged and keyboard-start prefetch policies were removed after
Pixel 9 testing failed to establish a latency improvement. There is one loading
path: lazy model mapping, encoder prefetch at mic start, and the existing
asynchronous packed-decoder prefetch. No full-model warming occurs on keyboard open.
See [the acceptance decision](VOICE_LATENCY_DECISION.md) for measurements.

The debug runner supports `--idle-only` to measure model/whole-app RSS without a
mic tap, `--cold-cache` to request model-file cache eviction, and
`--prepare-lead-ms 3000` to measure a delay between readiness and mic tap.
There is no preparation-mode switch. Cache eviction is advisory; verify storage
reads and `prefetch_advice` residency/success counters. Cache eviction requires
tracing and cannot be combined with `--no-trace`.

## Output

`build/voice-benchmark/` contains `result.json`, `metadata.json`, and `logcat.txt`. Logcat is captured live so a busy device cannot overwrite
the early timing events before the run finishes.
A run fails if audio is dropped or incomplete, or if the editor session changes.
The runner also checks the packaged native hash against the local stripped library.
Each run reports model wait, first audio, first nonempty editor insertion, stable
text delta, stop request, JNI stop return, idle, total time, chunk acceptance,
maximum JNI push time, and maximum callback-to-editor dispatch time. Repeated
hypothesis events show update cadence; the transcript is saved with each result.
`editor_text` is the first InputConnection call returning, not a display-frame
presentation timestamp. `speech_onset_to_first_editor_ms` uses the native VAD
start position, which includes its configured lookback padding; it is not a
manually annotated acoustic onset.

`VoiceLatency` JSON log lines use device monotonic time. Native `VoiceNative`
lines require **both a debug APK (`BuildConfig.DEBUG`) and
`debug.qwen_asr.latency=1`** (the script restores its previous value).
The optimized Rust library receives the APK build flag through JNI; setting the
property cannot enable detailed traces in a release APK. Kotlin log output uses
the same gate, refreshed at each recording. Normal sessions skip detailed metric
collection. Explicit test replay still collects the result JSON without log output
when `--no-trace` is supplied. Ordinary operational/error logs are unchanged.

`resources` events additionally report checked `getrusage` deltas for encoder
prefetch, decoder prefetch, the complete native worker, finalization, and streaming
VAD initialization, tokenizer/prompt setup, mel/encoder, embedding/prefill
preparation, prefill and decode stages.
`capture_total` starts before the first encoder-prefetch JNI call and covers
encoder/decoder prefetch and worker join through prompt cleanup. `session_total` uses process-wide counters from before encoder prefetch through
worker/prefetch join and prompt cleanup. The coordinator's earlier separate
`preloadEncoder()` call is outside that native-session window. It includes short
audio calls and other
app threads, but excludes the earlier model load. Its `/proc/self/io` `read_bytes`
delta measures storage reads charged to the process, not fault duration.
Process, worker and stage scopes overlap: do not sum them. Missing counters are
marked invalid or omitted, rather than reported as zero.

For a control run without detailed tracing, append `--no-trace`; the app still
checks complete audio, editor output and native-library identity. Native-stage
metrics are intentionally absent in this mode.

Detailed trace events report VAD initialization, decoder prefetch, audio position,
queue residence, backlog, deferred inference, mel/encoder/prefill/decode time,
speech start/end and explicit finalization. They exclude transcript text.
Kernel counters break down packed matrix multiplication, output head, attention,
convolutions and activation packing. `thread_work` records the main native
worker's CPU time and page faults; CPU time excludes parallel helper threads.
The kernel wall-time counters can overlap enclosing stage counters and must not
be added to them.

The retained production path uses the original one ASR thread, applies the existing
performance-core policy to the serial worker, uses the single-row decode kernel,
prefetch the packed decoder weights, and defer replaceable previews after Stop.
There are no runtime optimization switches for prefetch policy, preview cancellation,
worker placement, thread count, kernel selection or chunk/backlog tuning. The only
adb properties consumed by this integration are the explicit debug diagnostics
and test-only cache-eviction controls. Previous experiment settings in historical
reports are not supported by the retained build.
See [baseline measurements](VOICE_LATENCY_RESULTS.md) and [the fix report](VOICE_LATENCY_FIX.md).

A process restart measures cold process/model state. Android's filesystem page
cache is retained, so it is not a reboot/storage-cold experiment. File replay also
excludes microphone driver and acoustic preprocessing latency. Debug Kotlin/JIT
and the simple editor can differ from a release app and a third-party editor;
native inference is built with the normal optimized deployment flags.

## Why the Rust simulator is insufficient for UI latency

`simulate_vad_stream` pushes frames synchronously without real-time pacing or the
JNI producer/consumer queue. Its TTFC fields come from **audio sample positions**
in stable `TextDelta` events, rather than wall-clock editor insertion. Its
`tail_latency_ms` is `audio_duration_ms - last_speech_audio_ms`, which measures
remaining audio rather than stop-to-result processing. Model loading and Android
callback/editor dispatch are also outside that measurement. Use it for model and
VAD comparisons, and use this app probe for perceived latency.
