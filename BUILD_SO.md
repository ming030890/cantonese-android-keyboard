# How to Build `libqwen_asr.so` for Android

This guide explains how to compile the JNI-enabled shared native library `libqwen_asr.so` from the `QwenASR` Rust codebase targeting Android ARM64 devices (`arm64-v8a`).

It leverages static OpenBLAS linking and dot-product acceleration to achieve optimal performance, and bypasses standard Android NDK 30 toolchain wrapper bugs using the workspace-native tool wrapper scripts.

---

## Prerequisites

1. **Android NDK**: NDK version `30.0.14904198` (or custom version) must be installed at `~/Library/Android/sdk/ndk/`.
2. **OpenBLAS Static Library**: The static Android-compiled OpenBLAS baseline library (`libopenblas.a`) must be generated and located at `/private/tmp/OpenBLAS/`.
   - If missing, compile it inside the `QwenASR` repository using:
     ```bash
     bench/build-openblas-android-baseline.sh --force
     ```

---

## Step-by-Step Compilation

We bypass `cargo ndk` to avoid target API level mismatches (such as linking errors with `stderr` on API level 21) and toolchain bugs. Instead, we use the custom workspace wrappers `android-aarch64-clang` and `ld.lld` located in the `QwenASR` root directory which explicitly target **Android API level 35**.

1. Navigate to the `QwenASR` root directory:
   ```bash
   cd QwenASR
   ```

2. Execute the compilation command with the required NDK, OpenBLAS, and compiler flags:
   ```bash
   CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=/Users/tony/Documents/keyboard/QwenASR/android-aarch64-clang \
   CC_aarch64_linux_android=/Users/tony/Documents/keyboard/QwenASR/android-aarch64-clang \
   AR_aarch64_linux_android=/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar \
   RANLIB_aarch64_linux_android=/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ranlib \
   RUSTFLAGS="-C target-feature=+dotprod" \
   PATH="/Users/tony/.rustup/toolchains/stable-aarch64-apple-darwin/bin:/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH" \
   OPENBLAS_ANDROID_LIB_DIR=/private/tmp/OpenBLAS \
   OPENBLAS_ANDROID_STATIC=1 \
   cargo build --release --target aarch64-linux-android --features "blas android" -p qwen-asr
   ```

3. Locate the compiled library output:
   The compiled shared library is generated at:
   `target/aarch64-linux-android/release/libqwen_asr.so`
   *(It should have a file size of approximately **934 KB**, which indicates successful JNI API and OpenBLAS static linkage).*

---

## Deploy to Keyboard Project

Copy the compiled library directly into the Android keyboard project structure:
```bash
cp target/aarch64-linux-android/release/libqwen_asr.so ../cantonese-voice-keyboard/app/src/main/jniLibs/arm64-v8a/libqwen_asr.so
```
