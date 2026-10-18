# Voice latency fix — Pixel 9, 2026-10-17

Historical results: this experiment used fastest-core preference and two ASR
threads. Those two changes have since been reverted at the user's request.
See [retained changes](VOICE_LATENCY_KEPT_CHANGES.md) for the cleaned implementation;
the measurements below have not been relabeled as results of that configuration.

The main bottleneck was native inference. Decoder prefill and token decoding
outpaced the real-time audio producer, built up a queue, and delayed editor
updates. The original mixed-language cold query spent 4.01 seconds in prefill
and 2.48 seconds decoding; model loading was about 0.14 seconds and callback
dispatch at most 0.029 seconds. See [the original breakdown](VOICE_LATENCY_RESULTS.md).

## Changes

- Apply CPU affinity on the actual JNI inference worker. Previously affinity was
  applied on the model-loading coroutine and parallel helpers; the serial worker
  could still run on efficiency cores. Select the highest-frequency performance
  class from sysfs, with the existing performance-core policy as fallback. Pixel 9
  logs confirm CPU 7 for the main worker; helpers retain the performance-core mask.
- Use two ASR threads in the app. One, two and four were exercised through the
  app; two gave the best overall result on this phone.
- Use Kleidiai's single-row SDOT kernel for single-token decode, with matching
  activation packing and the existing packed weights. The previous 16-row I8MM
  path wasted work on a single row. Parallelize large output projections.
- Correct pool resizing: inactive helpers acknowledge dispatch without executing
  work, every spawned helper must finish before the closure expires, and newly
  spawned helpers start at the current generation rather than an expired job.
- Issue bounded, page-aligned readahead hints for the packed decoder tensors and
  output head. Avoid reading the large, mostly unused token embedding table.
  Prefetch begins with recording, rather than on ordinary keyboard opening.
- After Stop, consume every queued audio sample through VAD while deferring
  replaceable preview inference. Finalize the remaining tail in one inference
  pass instead of encoding a full chunk and then a short remainder separately.

Model weights, language, streaming chunk duration, rollback configuration and
VAD silence threshold are unchanged. Final-tail batching and preview scheduling
are intentional computation changes. The tested reference clips retain their
final words; this small set does not establish corpus-wide accuracy equivalence.

## Measurements

Results below use real-time PCM replay through the actual IME service,
production coordinator, packaged optimized JNI, VAD and InputConnection. The
process is killed before each first query, followed by a warm repeat. All
experimental overrides are empty, so measurements exercise source defaults.

The baseline has one cold/warm pair per clip; the final build has two pairs
per reference clip. After values show the full range across those two pairs,
not the fastest trial. These are engineering checks on one phone, not a
statistical performance study. Times start at recording; initial process/IME
startup and model loading are separate probe milestones.

| Clip / query | First text: before → after | Record → idle: before → after | Stop → idle: before → after |
| --- | ---: | ---: | ---: |
| ptt / cold | 6.56 s → 4.24–4.27 s | 9.34 s → 6.32–6.40 s | 5.48 s → 2.45–2.54 s |
| ptt / warm | 2.75 s → 2.38–2.42 s | 8.30 s → 6.42–6.49 s | 4.44 s → 2.56–2.63 s |
| cv001 / cold | 4.14 s → 2.26–2.43 s | 12.12 s → 7.99–8.17 s | 7.35 s → 3.24–3.41 s |
| cv001 / warm | 1.89 s → 1.74–1.85 s | 9.97 s → 7.50–8.70 s | 5.21 s → 2.74–3.95 s |

The final words match the baseline reference clips (the mixed-language warm
repeat retains its extra comma). Warm streaming still takes about two
seconds to show text; this change does not make recognition instantaneous.

| Native stage, mixed-language query | Baseline cold | Fixed cold | Baseline warm | Fixed warm |
| --- | ---: | ---: | ---: | ---: |
| Encoder | 1.50 s | 1.26–1.29 s | 1.60 s | 1.44–1.47 s |
| Prefill | 4.01 s | 2.45–2.50 s | 3.38 s | 2.26–2.29 s |
| Token decode | 2.48 s | 1.21–1.26 s | 1.97 s | 1.32–1.34 s |
| Processing calls, including finalization | 8.20 s | 5.56–5.79 s | 7.15 s | 5.23–5.26 s |
| Maximum audio queue residence | 2.83 s | 1.69–1.84 s | 2.59 s | 1.28–1.35 s |

Final corpus transcripts:

| Clip | Editor text |
| --- | --- |
| cv000 | 我急求方法啊！ |
| cv001 | 唔做嘢嘅佔多數咯。 |
| cv002 | 證據嘅話真係冇啊～ |
| cv003 | 你睇下呢部劇。 |
| cv004 | 仲要提供乜嘢資料？ |
| ptt | Hello你好嘛，已經坐低咗啦。 |
| two-utterances | 我急求方法啊！仲要提供乜嘢資料？ |

Raw reports and live logs (each directory also contains override metadata):

- [ptt report](build/voice-benchmark/fixed-defaults/ptt/result.json), [log](build/voice-benchmark/fixed-defaults/ptt/logcat.txt)
- [cv001 report](build/voice-benchmark/fixed-defaults/cv001/result.json), [log](build/voice-benchmark/fixed-defaults/cv001/logcat.txt)
- [cv000 report](build/voice-benchmark/fixed-defaults/cv000/result.json), [log](build/voice-benchmark/fixed-defaults/cv000/logcat.txt)
- [cv002 report](build/voice-benchmark/fixed-defaults/cv002/result.json), [log](build/voice-benchmark/fixed-defaults/cv002/logcat.txt)
- [cv003 report](build/voice-benchmark/fixed-defaults/cv003/result.json), [log](build/voice-benchmark/fixed-defaults/cv003/logcat.txt)
- [cv004 report](build/voice-benchmark/fixed-defaults/cv004/result.json), [log](build/voice-benchmark/fixed-defaults/cv004/logcat.txt)
- [two-utterances report](build/voice-benchmark/fixed-defaults/two-utterances/result.json), [log](build/voice-benchmark/fixed-defaults/two-utterances/logcat.txt)
- [ptt-repeat report](build/voice-benchmark/fixed-defaults/ptt-repeat/result.json), [log](build/voice-benchmark/fixed-defaults/ptt-repeat/logcat.txt)
- [cv001-repeat report](build/voice-benchmark/fixed-defaults/cv001-repeat/result.json), [log](build/voice-benchmark/fixed-defaults/cv001-repeat/logcat.txt)

Verified packaged native SHA-256:
`4e826f3b404d4e840d47473db89f93e9847c1a5f652d09d4100e1ebac80f3194`.


## Validation and limits

- Final JNI library built with the normal Android release flags, DOTPROD,
  OpenBLAS and Kleidiai. The runner hashes the actual packaged library and
  verifies it matches the freshly stripped local build.
- All 13 valid final-build queries across six single-utterance clips plus a two-utterance recording reached the
  editor with complete PCM, zero rejected chunks and complete native traces.
  The combined recording preserves both utterances without duplicate text.
- On-device native suite: 18 passed, including old/new packed-kernel numerical
  comparison across matrix sizes, output tails, positive/mixed inputs and thread
  counts. Tolerance is `1e-4 * (1 + abs(expected))`.
- Host native library/audio/kernel tests: 34 passed. These validate correctness;
  performance figures come from the phone app.
- Android unit/scenario suite: 210 passed, 16 skipped, zero failures. Replay tests
  cover AudioRecord-compatible buffer position, full bytes, pacing and stopping.
- `:app:bundleDebug -PvoiceBenchmark=true` passes. Required `:app:verifyKeyboard`
  was run and remains red on 22 screenshot mismatches out of 63 in existing UI
  areas. No screenshot baselines were changed by the voice work.

The app remains slower than real time on some clips. Packed matrix multiplication
is still the largest kernel cost. Page faults remain a source of cold-run
variation; selective prefetch reduces exposure without guaranteeing residency.
Stage and kernel counters are wall times, and overlapping counters must not be
summed. `thread_work` CPU/page-fault counters cover the main worker during logged
audio processing calls, not helpers or the finalization call.

These are cold-process runs with extracted model packs and Android's filesystem
cache retained, not storage-cold reboots. Replay excludes microphone hardware and
acoustic preprocessing. First text measures an InputConnection call returning,
not a display frame. Debug Kotlin and the simple editor can differ from a release
build and another editor. Native code uses optimized deployment settings.
Thermal conditions were not held constant: the original baseline reported status
0 and battery 37.6°C; final cleanup reported status 1 and battery 39.6°C. The
slower warm Cantonese repeat is retained in the reported range. One repeat
attempt failed before ASR because the device was asleep; its setup-only trace is
retained in `build/voice-benchmark/fixed-defaults/ptt-repeat-invalid-sleep/` and
excluded from inference results. The runner now wakes the display before launch.

The side-by-side diagnostic package is installed with all five model packs ready.
The original Play app and its data are preserved; the runner restores the
previous IME and closes the test app. These source changes have not been
published to Play.

Run again without screen automation:

```sh
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav
```

See [probe usage and A/B controls](VOICE_LATENCY.md).
