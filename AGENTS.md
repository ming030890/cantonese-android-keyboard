# Cantonese Voice Keyboard

## Repository scope

This repository contains the Android IME and its packaging/deployment flow. The native ASR implementation is maintained in the sibling repository:

- `../QwenASR` — Rust Qwen ASR engine and Android JNI implementation.
- `app/src/main/java/com/madeinhk/cantonesevoicekeyboard/` — keyboard service, recording lifecycle, and text insertion.
- `app/src/main/java/com/qwenasr/QAsrEngine.kt` — Kotlin wrapper for the native JNI API.
- `model_pack_a/` through `model_pack_e/` — Play Asset Delivery model packs.
- `deploy.sh` — canonical build, model-staging, packaging, and deployment entry point.
- `BUILD_SO.md` — manual native-library build reference.
- `RUN_TEST.md` — instrumented QAsrEngine test workflow.

Keep changes in the correct repository. Rust/JNI changes belong in `../QwenASR`; Android service, wrapper, deployment, and packaging changes belong here.

## Native boundary

The Kotlin wrapper and the sibling Rust JNI implementation are the source of truth for audio format, VAD policy, segment ownership, callback semantics, prompts, and stop/finalization behavior. Do not duplicate those details here; inspect the live code before changing or diagnosing them.

When debugging repeated text or missing words, trace the native segment queue, prompt/context handling, callback payload, and Kotlin insertion path before adding UI-side suppression. Confirm the actual packaged native library was built from the current `../QwenASR` checkout.

## Build and deployment

`deploy.sh` is the source of truth for normal deployment. Inspect it for the current native build, model staging, packaging, and installation steps.

Use:

```bash
./deploy.sh
```

Use `./deploy.sh --release` for a Play-uploadable AAB. Debug deployment requires the sibling QwenASR checkout and a connected device.

Generated native libraries, APKs, AABs, extracted split APKs, model-pack payloads, and pulled/debug audio should be treated as build artifacts. Check `git status --short --ignored` before staging so generated payloads are not accidentally committed.

## Verification

For deployment-script-only changes:

```bash
bash -n deploy.sh
```

For Android code changes, run the narrowest relevant Gradle check first, then the appropriate assemble or connected test. For native changes, build the JNI library through `deploy.sh` or the command documented in `BUILD_SO.md`; do not rely on an old packaged `.so`.
