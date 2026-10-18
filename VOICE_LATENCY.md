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
lines are enabled by `debug.qwen_asr.latency=1` (the script restores its previous
value). They report VAD initialization, decoder prefetch, audio position,
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
For controlled A/B experiments, `adb shell setprop debug.qwen_asr.NAME VALUE`
supports these overrides:

| NAME | VALUE |
| --- | --- |
| `worker_pin` | `0` disables explicit JNI worker affinity |
| `matmul_m1` | `0` uses the previous multirow kernel for single-token decode |
| `bounded_prefetch` | `0` uses the previous whole-shard prefetch hints |
| `defer_stop` | `0` permits intermediate preview decoding after Stop |
| `threads` | `1` through `4`, active only while latency tracing is enabled |
| `chunk_ms` | Inference audio chunk size, `200` through `1200`; current source default `400` |
| `backlog_ms` | Queued-audio threshold for deferring previews; current source default `800` |

An empty property uses production defaults. Restart the app after changing these.
The experimental preview-abort path and fastest-core preference were removed at
the user's request. The two-thread production change was also reverted; thread
overrides remain diagnostic controls only. Historical results using those settings
are not measurements of the cleaned source. See [retained changes](VOICE_LATENCY_KEPT_CHANGES.md).
The runner records all override strings in `metadata.json`; it does not silently
apply or clear them. Clear experimental overrides before measuring defaults.
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
