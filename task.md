# Cantonese Voice Input Keyboard Progress Checklist

This checklist tracks our step-by-step implementation progress. We will tackle these checkboxes one by one as directed.

---

## 1. Native Build & Rust-JNI Bridge
- [x] Set up the standard `jni` crate dependency in `Cargo.toml` of `QwenASR` project.
- [x] Refactor `crates/qwen-asr/src/jni_api.rs` to replace the raw null-pointer stubs with real implementations.
- [x] Implement `nativeLoadModel` in Rust to load the model context and return the heap pointer as `jlong`.
- [x] Implement zero-copy `nativeTranscribePcmDirect` in Rust using direct pointer access via `GetDirectBufferAddress`.
- [x] Implement language/segment configurations (`nativeSetLanguage`, `nativeSetSegmentSec`) and memory release (`nativeFree`) in Rust.

## 2. Kotlin Engine Wrapper
- [x] Create JNI folder structures (`app/src/main/jniLibs/arm64-v8a`) inside the Android project to host the compiled native library.
- [x] Implement the Kotlin engine wrapper class `QAsrEngine.kt` to load the `.so` library and expose JNI methods.

## 3. Android Input Method Editor (IME) Service
- [x] Create standard keyboard registration resources (`app/src/main/res/xml/method.xml` and key metadata).
- [x] Add the `RECORD_AUDIO` permission and register the IME service inside `app/src/main/AndroidManifest.xml`.
- [x] Implement `CantoneseInputMethodService.kt` extending `InputMethodService`.
- [x] Implement visible-only memory optimization (loading in `onStartInputView` and freeing in `onFinishInputView` / `onWindowHidden`).
- [x] Build the Jetpack Compose keyboard interface featuring the Tap-to-Speak button, sound wave animation, and editing keys.

## 4. Setup & Sandbox Companion Application
- [x] Build the 3-step setup walkthrough inside `MainActivity.kt` using Jetpack Compose (Permissions, Activation, Picker).
- [x] Implement the interactive Cantonese voice dictation sandbox input field inside `MainActivity` to test the keyboard.

## 5. Deployment & Verification Script
- [x] Create the comprehensive compilation, asset synchronization, building, and running shell script `deploy.sh` in the project root.
- [x] Implement a full on-device instrumented End-to-End Test `QAsrEngineE2ETest` to verify transcription correctness and measure latency (Successfully verified: transcribed "Hello，你好嗎？已經坐低咗啦。" in 3.27s for 3.86s of audio!).
- [ ] Configure the Compose Screenshot Testing plugin and write screenshot verification tests for key visual states (Idle, Recording, Transcribing).

## 6. Save Post-VAD Audio Feature
- [x] Implement audio saving in Rust JNI (`jni_api.rs`) by writing raw PCM16 samples to WAV format inside the worker thread.
- [x] Update Kotlin wrapper (`QAsrEngine.kt`) to support passing a directory path to the VAD session.
- [x] Update IME service (`CantoneseInputMethodService.kt`) to resolve/create the `saved_audio` directory and trigger saving post-VAD audio.
- [x] Verify compilation, deployment, and correctness (saving post-VAD segments into the app directory).

## 7. Cantonese Words Edge TTS Dataset
- [x] Add a resumable words.hk key-to-audio dataset generator using zh-HK-HiuMaanNeural with validated mono 16 kHz WAV output and a two-field manifest.

## 8. Cantonese Words ASR Text Filter
- [x] Add and apply a soft-delete ASR text filter that excludes entries without Chinese, unsupported punctuation/symbols, single-character text, and overly long entries while retaining mixed-script Cantonese and every WAV file.

## 8. Hong Kong Proper-Name Graph Dataset
- [x] Implement the deterministic text-only Cantonese Wikipedia graph extractor and ranked proper-name export on SSD.

## 9. Proper-Name Export Normalization and Dataset Index
- [x] Export entity-only rows, strip long-keyword `香港` prefixes, and document all dataset workflows in `dataset/README.md`.

## 10. Proper-Name Recall Tuning
- [x] Remove the short-title ranking penalty, lower the default export cutoff, add equal-weight restaurant anchors, and regenerate the entity list.

## 11. Conditional Station Suffix Normalization
- [x] Remove terminal `站` only when the corresponding suffix-free entity is also eligible, then regenerate the entity list.
