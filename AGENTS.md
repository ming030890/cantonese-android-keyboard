# Cantonese Voice Keyboard

For candidate/autocorrect changes, read `TYPING_EVALUATION.md` first. It records
the frozen evaluation cohort, current baseline, and the user's acceptance metrics:
latency and F₀.₅. Accept F₀.₅ improvements without a separate 95% precision cutoff.

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

## UI verification and efficient iteration

- Before changing spacing, identify which element owns the unused space and whether system insets are included. Check the proposed change against the user's height and sizing requirements; moving a gap elsewhere does not resolve it.
- Verify navigation-bar overlap in the actual keyboard service with a focused text field. An ordinary preview activity or screenshot preview cannot establish that the IME clears system navigation. Identify the connected device before installing a build.
- For apps using Play Asset Delivery, a standalone APK is not a complete deployment. Use the local-testing bundle installation and verify the app reports the model ready before handing it back; do not assume an APK update preserves access to the packs.
- Update screenshot baselines first, inspect them, then run baseline verification separately. Combining update and verification caused a task-dependency error in this setup.
- Inspect the specific defect in the rendered image before accepting a baseline. If preview configuration changes, check for obsolete generated baselines before staging files.
- Deliver a short playable capture when the user asks to review animation. If the capture uses a simulated recording state, say so; a still screenshot only verifies layout.
- Use longer waits and compact output for slow builds or uploads. Increase the wait interval when repeated polls return no new information.

## Keyboard behavior regression workflow

Read `KEYBOARD_QA_SPEC.md` for expected behavior, confirmed decisions, and coverage gaps.
Confirm pending product decisions before changing their behavior; keep the spec and tests
in sync. For keyboard state or event handling changes, add a regression sequence using
`app/src/scenarioTest/java/com/madeinhk/cantonesevoicekeyboard/KeyboardScenario.kt`.
Exercise the production controller and key receiver with fake platform/model dependencies.
Assert committed/composing editor text, resulting candidates, and toolbar presentation.
Use an injected test dispatcher and advance virtual time for asynchronous sequences.

When visible behavior changes, render the resulting scenario snapshot in screenshot tests.
Update only the intended baselines, inspect the generated images yourself, then validate
separately. Never update baselines just to silence a failing test. Do not hand routine
state/sequence QA back to the user.

Run `./gradlew :app:verifyKeyboard` before handing off keyboard changes. Normal deployment
runs this same gate and stops on unit, screenshot, or build failures. This does not replace
on-device verification of Android integration such as navigation insets, asset delivery,
clipboard permissions, inline autofill inflation, microphone capture, or native inference.
