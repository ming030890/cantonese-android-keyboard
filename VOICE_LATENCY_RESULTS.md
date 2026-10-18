# App voice latency baseline — Pixel 9, 2026-10-17

This records the original one-thread implementation before tuning. See
[the fix report](VOICE_LATENCY_FIX.md) for the changes and measurements afterward.

The app reproduces the reported delay. The largest measured cost is native decoder
prefill, followed by token decoding and audio encoding. Inference falls behind
real-time audio delivery, which reduces the number of streaming updates and adds
several seconds of work after recording stops.

These are measurements through the actual IME service, production voice
coordinator, optimized packaged JNI library, VAD queue, and InputConnection.
Only microphone capture is replaced with real-time file PCM. Each cold run starts
a fresh process; its warm repeat shares the model and process. Model packs were
already extracted. Times below start at simulated record-start, after the
keyboard has appeared. “First text” is the first nonempty editor insertion;
“finished” is the coordinator returning to Idle.

| Clip | Audio | Query | First text | Finished | Stop → Idle |
| --- | ---: | --- | ---: | ---: | ---: |
| ptt_guardrail | 3.86 s | Cold | 6.56 s | 9.34 s | 5.48 s |
| ptt_guardrail | 3.86 s | Warm | 2.75 s | 8.30 s | 4.44 s |
| cv_yue_001 | 4.75 s | Cold | 4.14 s | 12.12 s | 7.35 s |
| cv_yue_001 | 4.75 s | Warm | 1.89 s | 9.97 s | 5.21 s |

## Where the time goes

On `ptt_guardrail`, the first native prefill alone took **2.28 s cold versus
0.34 s warm**. Its first streaming inference produced no text. By the time the
next hypothesis reached Kotlin, the entire audio clip had already arrived.
The cold run then had only one editor update; the warm repeat had two.

| Native stage, whole ptt_guardrail query | Cold | Warm |
| --- | ---: | ---: |
| Mel | 0.01 s | 0.01 s |
| Encoder | 1.50 s | 1.60 s |
| Prefill | 4.01 s | 3.38 s |
| Token decode | 2.48 s | 1.97 s |
| Measured native processing calls | 8.20 s | 7.15 s |
| Maximum audio queue residence | 2.83 s | 2.59 s |
| Explicit native finalization work | 2.73 s | 1.86 s |

The native worker is serial. Even the warm query needs about 7.15 s of processing
for 3.86 s of incoming audio. The JNI backlog policy defers replaceable intermediate
inference while catching up, explaining the sparse streaming updates.
The processing-call total and stage counters measure wall time, not isolated CPU
time; the total also includes VAD, allocation and other work.

The cold model load job was 143 ms, including approximately 115 ms in model load
and configuration. Maximum JNI audio-push duration was below 1 ms. Maximum
callback-to-editor dispatch was 29 ms cold and 5 ms warm for this clip. Native
inference is the clear priority for optimization. Merely changing UI dispatch
will not address the measured delay.

The first-prefill slowdown is isolated, but these counters do not distinguish
page faults, kernel initialization and actual math inside that stage. No inference
configuration, model, decoding policy or accuracy tradeoff was changed as part of
this diagnostic work. The app uses its production setting of one ASR thread.

## Evidence and reproducibility

Run from the Android project:

```sh
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav
```

The runner detects the installed side-by-side debug package, force-stops it before
the test, records one cold query plus one warm repeat, captures logs live, and
restores the previous keyboard. See [probe documentation](VOICE_LATENCY.md).

Full results and logs:

- [ptt_guardrail results](build/voice-benchmark/ptt-final/result.json), [log](build/voice-benchmark/ptt-final/logcat.txt)
- [cv_yue_001 results](build/voice-benchmark/cv001-final/result.json), [log](build/voice-benchmark/cv001-final/logcat.txt)

Both final experiments verified all PCM was fed, zero chunks were rejected,
the editor received callbacks, both native traces were complete, and the packaged
native library matched the locally built stripped library:
`f7e99b7fc78e7bf3fb29bc60760f7d8fde951df39058a548fa0028a8dab27bee`.
The side-by-side package preserves the Play-installed app and its data. Android
reported thermal status 0, battery 37.6°C, and battery saver disabled during the
final experiments.

The filesystem page cache is retained across process restarts. This is a cold
process experiment, not a storage-cold reboot. Debug Kotlin/JIT and a simple
EditText can differ from a release app and another editor. The timings measure
InputConnection insertion, not display-frame presentation. Earlier harness
experiments with incomplete audio or editor restarts are marked invalid; only
the final complete traces are used in the tables.

## Verification

- Android debug bundle built and deployed; all five model packs resolved ready.
- App unit suite: 210 passed, 16 skipped, no failures (226 total).
- Replay tests cover AudioRecord-compatible buffer position, complete bytes,
  absolute capture pacing and stopping.
- Native JNI/VAD configuration test passed; Android native library built with the
  normal deployment flags (`blas android kleidiai`, DOTPROD, optimized release).
- `:app:verifyKeyboard` was run; its screenshot suite reported 22 baseline
  mismatches out of 63 tests in existing UI areas. Those baselines were not changed
  by this work. The unit suite and compilation passed.
