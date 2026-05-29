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
- [x] Implement fake streaming dictation with Android VAD and queued batch ASR.

## 4. Setup & Sandbox Companion Application
- [x] Build the 3-step setup walkthrough inside `MainActivity.kt` using Jetpack Compose (Permissions, Activation, Picker).
- [x] Implement the interactive Cantonese voice dictation sandbox input field inside `MainActivity` to test the keyboard.

## 5. Deployment & Verification Script
- [x] Create the comprehensive compilation, asset synchronization, building, and running shell script `deploy.sh` in the project root.
- [x] Implement a full on-device instrumented End-to-End Test `QAsrEngineE2ETest` to verify transcription correctness and measure latency (Successfully verified: transcribed "Hello，你好嗎？已經坐低咗啦。" in 3.27s for 3.86s of audio!).
- [ ] Configure the Compose Screenshot Testing plugin and write screenshot verification tests for key visual states (Idle, Recording, Transcribing).

