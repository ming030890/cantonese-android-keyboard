# Cantonese Voice Keyboard

## Repository scope

This repository contains the Android IME and its packaging/deployment flow. The native ASR implementation is maintained in the sibling repository:

- `../QwenASR` — Rust Qwen ASR engine and Android JNI implementation.
- `app/src/main/java/com/madeinhk/cantonesevoicekeyboard/` — keyboard service, recording lifecycle, and text insertion.
- `app/src/main/java/com/qwenasr/QAsrEngine.kt` — Kotlin wrapper for the native JNI API.
- `model_pack_a/` and `model_pack_b/` — Play Asset Delivery model packs.
- `deploy.sh` — canonical build, model-staging, packaging, and deployment entry point.
- `BUILD_SO.md` — manual native-library build reference.
- `RUN_TEST.md` — instrumented QAsrEngine test workflow.

Keep changes in the correct repository. Rust/JNI changes belong in `../QwenASR`; Android service, wrapper, deployment, and packaging changes belong here.

## Audio and ASR contract

The keyboard records 16 kHz, mono, PCM16 audio with `AudioRecord`, writes chunks into a direct `ByteBuffer`, and passes them to `QAsrEngine.pushPcm16Chunk()`.

The Android native path currently uses the Rust `silero_vad_rust::RawDetector` implementation in `../QwenASR/crates/qwen-asr/src/jni_api.rs`. It is not the CLI RMS VAD path and should not be described as Earshot unless the live Rust dependency is changed again.

The native VAD owns segment state, queues each completed segment as an `AsrTask`, and the worker transcribes `task.samples` independently. `segment_id` identifies the callback; Kotlin receives a complete transcript for that segment and commits it once with `InputConnection.commitText()`.

Do not add transcript deduplication as the primary fix for repeated text. The previous root cause was native prompt feedback: the worker appended the prior segment transcript to the ASR prompt, so a later segment could return the whole phrase and Kotlin would correctly commit the returned text. The prompt-feedback code was removed in QwenASR commit `39db1f5`. If repeated text reappears, first verify the packaged `.so` was rebuilt from the current `../QwenASR` checkout and inspect the native task/prompt path.

The current native stop path still clears the ASR prompt before joining the worker. Treat changes around stop/finalization carefully: the worker and stop call can touch the same native engine context, so verify ordering and thread safety before changing final-flush behavior.

## Build and deployment

`deploy.sh` is the source of truth for normal deployment. It always rebuilds `../QwenASR` for `aarch64-linux-android`, copies `libqwen_asr.so` into `app/src/main/jniLibs/arm64-v8a/`, stages the model shards, builds the app bundle, and installs the appropriate APK set for the selected mode.

Use:

```bash
./deploy.sh
```

Use `./deploy.sh --release` for a Play-uploadable AAB. The deployment script requires the sibling QwenASR checkout, Android NDK/toolchain paths, the OpenBLAS Android library, the configured model source or already-staged model shards, and a connected device for debug installation.

Generated native libraries, APKs, AABs, extracted split APKs, model-pack payloads, and pulled/debug audio should be treated as build artifacts. Check `git status --short --ignored` before staging so generated payloads are not accidentally committed.

## Verification

For deployment-script-only changes:

```bash
bash -n deploy.sh
```

For Android code changes, run the narrowest relevant Gradle check first, then the appropriate assemble or connected test. For native changes, build the JNI library through `deploy.sh` or the command documented in `BUILD_SO.md`; do not rely on an old `.so` already present in `app/src/main/jniLibs/`.

When debugging streaming VAD, record the segment boundary and `segment_id`, inspect the actual native task audio if available, and compare the callback text with the segment text. Do not infer whole-audio replay merely from a repeated callback; confirm PCM overlap or prompt/context reuse first.
