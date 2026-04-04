---
name: asr-benchmark
description: Use when benchmarking Cantonese ASR performance in this repo, especially to separate model install or engine startup cost from warm transcription cost and to determine whether slowdown is in Kotlin, JNI marshaling, or native qwen-asr inference.
---

# ASR Benchmark

Use this skill for on-device ASR latency investigation.

## Scope

- Benchmark the Android app's local qwen-asr path.
- Use the existing instrumented benchmark test instead of inventing ad hoc timing code.
- Treat Kotlin and JNI as suspects only after reading the printed breakdown.

## Files

- Benchmark test: `app/src/androidTest/java/com/example/cantonesevoiceinput/benchmark/AsrInferenceBenchmarkTest.kt`
- JNI timing hook: `app/src/main/cpp/NativeQwenAsrJni.cpp`
- Kotlin wrapper: `app/src/main/java/com/example/cantonesevoiceinput/inference/NativeQwenAsrEngine.kt`
- Benchmark audio asset: `app/src/androidTest/assets/benchmark/PTT-20241208-WA0011-s16le.pcm`

## Prerequisites

1. Connect one Android device over `adb`.
2. Confirm it is visible with `adb devices -l`.
3. Ensure model assets already exist in app assets via the normal repo setup.

## Build

Compile the benchmark app and test APKs:

```bash
./gradlew :app:assembleBenchmark :app:assembleBenchmarkAndroidTest
```

## Run

Prefer the targeted report tests first. They finish faster than the repeated microbenchmark.

Warm setup breakdown:

```bash
ANDROID_SERIAL=<device> ./gradlew :app:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.cantonesevoiceinput.benchmark.AsrInferenceBenchmarkTest#reportWarmSetupBreakdown
```

Warm transcription breakdown:

```bash
ANDROID_SERIAL=<device> ./gradlew :app:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.cantonesevoiceinput.benchmark.AsrInferenceBenchmarkTest#reportWarmStreamBreakdown
```

Full repeated benchmark:

```bash
ANDROID_SERIAL=<device> ./gradlew :app:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.cantonesevoiceinput.benchmark.AsrInferenceBenchmarkTest#benchmarkWarmStreamTranscribe
```

## Readout

Look for these printed lines in Gradle or `adb logcat`:

- `ASR_SETUP runs=... warmInstallMs=... engineCreateAndWarmupMs=...`
- `ASR_BREAKDOWN label=warm_stream runs=... audioSec=... wallMs=... kotlinOutsideJniMs=... jniCopyMs=... nativeInferenceMs=... jniOverheadMs=... realtimeX=...`

Interpret them as follows:

- `warmInstallMs`: cost of `ModelAssetInstaller.ensureInstalled` after the model is already present on device.
- `engineCreateAndWarmupMs`: cost of creating `NativeQwenAsrEngine` and running one warm streaming transcription.
- `kotlinOutsideJniMs`: host-side Kotlin overhead outside the JNI call.
- `jniCopyMs`: cost of copying the `FloatArray` into native memory.
- `nativeInferenceMs`: time spent inside `qwen_transcribe_stream`.
- `jniOverheadMs`: JNI work not accounted for by the array copy.
- `realtimeX`: audio duration divided by wall time. Higher is better.

## Decision Rule

- If `nativeInferenceMs` dominates, optimize native qwen-asr execution first.
- If `jniCopyMs` is material, reduce or eliminate the full-array copy across JNI.
- If `kotlinOutsideJniMs` is material, inspect recording, buffering, coroutine dispatch, or pre-processing code.
- If `engineCreateAndWarmupMs` is large but warm inference is acceptable, focus on model load or startup behavior rather than steady-state decoding.

## Notes

- The benchmark transcript may vary slightly at the first token. The stable expected suffix is `，你好吗？已经坐低咗啦。`
- The repeated `benchmarkWarmStreamTranscribe` test can take a long time because `BenchmarkRule` collects many samples.
- If the benchmark runner fails to launch, verify the `benchmark` build variant still packages `androidx.benchmark.IsolationActivity`.
