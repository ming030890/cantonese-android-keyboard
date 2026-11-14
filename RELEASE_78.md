# Release 78 candidate: public release cleanup

- Remove unconditional handwriting stroke/prediction dumps and content logging.
  The next recognizer load also removes the old `last_input_strokes.json` artifact.
- Declare the vibration permission required by the existing haptic fallback.
- Correct horizontal inset subtraction when calculating keyboard touch widths.
- Show distinct Wi-Fi waiting and Play confirmation states for speech downloads,
  with the official Play confirmation action. Report fetch failures instead of
  leaving the screen indefinitely in preparation.
- Hide internal recording/review/challenge screens in public builds. Saved
  developer preferences cannot reactivate recording or folder sync in a release.
- Preserve emoji notices, include the native engine's MIT notice, and expose
  bundled notices from Settings. Verify notice contents inside the final AAB.
- Complete Traditional and Simplified Chinese debug strings, remove obsolete
  experimental decoder copy and unused Android Studio sample data/view-model
  scaffolding, and use the app-name resource consistently in setup copy.
- Add the `verifyRelease` gate (keyboard tests/screenshots/debug assembly, release
  tests, release lint, source notices). Production deployment requires a fresh
  ASR build rather than silently accepting an unverified prebuilt library.

Remaining publication decisions:

- Choose the mascot icon independently from the app name. Sparrow and siu mai
  concepts were prepared for review; neither replaces the launcher icon yet.
- The handwriting model currently comes from converted ML Kit weights. Confirm
  separate redistribution permission or migrate to the supported SDK before
  public distribution. Relevant terms: https://developers.google.com/ml-kit/terms
- The selected shared language-model metadata still says `experimental=true`
  and `production_approved=false`, and the existing verifier explicitly requires
  that internal qualification state. Passing asset hashes does not establish
  production quality. Resolve that qualification status with evaluation evidence;
  do not flip the approval fields merely to publish.
- Native build dependencies are now restored from pinned revisions in a persistent
  cache. Both JNI libraries have been rebuilt from current source; the temporary
  archive paths are no longer needed. See `BUILD_SO.md` and
  `build/native-build-provenance.json`.

No Play upload or production rollout is part of this cleanup.

Validation completed after optional-voice/dead-code cleanup (2026-11-08):

- `:app:verifyRelease` and `:app:assembleRelease` pass with isolated temporary build
  outputs. Both debug and release suites contain 312 passing tests, one opt-in
  test skipped, and no failures with the freshly built host JNI library.
- Screenshot comparison passes after inspecting the intentional voice/settings
  baselines. Release lint has no errors, 58 warnings, and no unused resources.
- Source/package language-model hashes and packaged native-library checks pass.
  All ten required distribution notices match the minified APK.
- R8 and resource shrinking are enabled in the freshly built release APK.
- A current AAB has not been generated after these latest source changes. The
  earlier intermediate bundle was removed to recover disk space during validation.

Review APK: `/private/tmp/keyboard-launch-build/app/outputs/apk/release/app-release.apk`.
This APK verifies minified code and packaging; complete speech-model delivery
requires a Play/local-testing bundle. It retains the current icon and app name.
The remaining icon/model publication decisions above still apply.


## R8 and library update (2026-11-08)

R8 and resource shrinking were already enabled. Broad package-wide keeps are now
replaced by JNI ABI/callback rules and the two WorkDatabase reflection classes.
Unused direct Compose ViewModel and Espresso declarations are removed; plain
lifecycle APIs are used where appropriate. WorkManager is retained and upgraded
because Play Asset Delivery uses it; only the training worker moves to debug.

Core 1.19.1, Lifecycle 2.11.0, Navigation3 1.2.1, Compose BOM 2026.09.00,
coroutines 1.11.0, WorkManager 2.12.0 and test JSON 20260814 are selected from
stable upstream metadata. Compilation uses installed SDK 37.0, with target 36
and minimum 32 retained. See `config/dependency_audit_20261108.json`.

Five rebuilt native kernel/thread-pool tests pass on the emulator. The minified
APK's Quick, English, handwriting and ASR configuration JNI entry points pass,
its generated WorkManager database class loads, and normal Activity startup stays
alive. No physical phone was changed and no Play upload was performed.


## Optional voice and dead-code cleanup (2026-11-08)

Voice is enabled by default and can be disabled independently of text layouts in
onboarding and Settings. All five speech packs use on-demand delivery. Model
requests require microphone permission and use an unmetered WorkManager constraint,
with a live network guard that cancels on metered connections. The UI never offers
a cellular-download override. Disabling voice cancels queued work and hides the mic.
Opening the keyboard leaves normal candidates/clipboard visible. A mic tap shows
model progress or a network wait; finishing a download does not start recording.

Removed the obsolete setup card, unused preference APIs, permanently disabled
text diagnostics, unused haptic/debug helpers, 32 unused string keys and template
backup XML. Replaced the unused direct Core KTX wrapper with plain AndroidX Core.
The active WorkManager, lifecycle, navigation, serialization, Compose and test
libraries are retained. New screenshot baselines cover voice disabled, mic-tap
network status, permission setup and two-step setup without voice.

The current source changes require a newly generated AAB before upload. Earlier
bundle checks do not qualify these later changes; the previously documented final
bundle path must not be treated as a current upload artifact.
