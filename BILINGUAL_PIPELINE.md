# Bilingual keyboard runtime pipeline

This is a historical workflow for the retired internal contextual profile. The
live loader now constructs `SharedLmEnglishEngine`; it does not load those old
typing resources or use four-word context revision. See
[LANGUAGE_MODEL_SELECTED.md](LANGUAGE_MODEL_SELECTED.md) and the current
[pipeline review](CANDIDATE_PIPELINE_REVIEW.md). The commands and results below
require the historical asset bundle and must not be presented as current-model
qualification. No upload or physical-phone install occurred in that workflow.

One reproducible command now builds the host JNI library, loads the exact
keyboard assets with the production loaders, drives the real key receiver and
controller, and compares the final committed text. English, Traditional Chinese
Cantonese through Quick, and mixed input share this runtime check.

```sh
python3 scripts/verify_bilingual_pipeline.py --out /private/tmp/bilingual-check
```

Use a new output directory per run. Requirements: Rust with the Android JNI
feature supported on the host, Java/Android build dependencies already used by
`deploy.sh`, and the staged keyboard assets. This command does not upload or
install anything. It does not retrain models.

The script compiles the debug app with `-PinternalTypingTrial=true`, matching the
explicitly enabled internal profile. Normal debug/release builds retain their
existing default of false. Production approval is never manufactured by this
command. Setting `--assets /absolute/path/to/an/isolated/assets` tests a different
complete bundle without copying it into the app. Model, dictionary and policy
hash validation remains active, including support for changed validity resources.

## Checks and output

The pipeline writes `report.json`, `junit.xml`, native-build and Gradle logs.
The report binds the actual native binary and every English/Quick/typing/LM
resource, validity source/notices, handwriting labels and optional count table.
It enforces the user's strict 30,000,000-byte resource limit and checks that
assets did not change during inference. It rejects skipped native tests, missing
cases, duplicated cases and inconsistent claimed/actual outcomes.

Forty English cases cover the English and Quick layouts, ordinary and rapid
key scheduling, with and without center-touch evidence, boundary correction,
Backspace undo, valid `typo`, and an unfamiliar company name. Three additional
cases cover Traditional Chinese character selection, a complete generated/ranked
Cantonese phrase, and English correction within mixed text. These43 core checks
currently pass. Cantonese selection is explicit; Space does not guess Chinese.

Seven complete user typo sentences are independently replayed through the same
controller on both layouts (14 cases). These are regression targets only,
never model training/calibration labels. **All14 complete-sentence cases still
fail.** Individual words sometimes improve, but that does not satisfy the
sentence expectation. Consequently the default command exits2 and reports
`accepted=false`, even though JUnit's mechanism tests pass.

Exit codes:0 means the requested checks pass;1 means build/runtime/mechanism,
budget or asset-consistency failure;2 means reported correction quality fails.
`--mode diagnostic` collects the same failed cases and returns0 when the core
pipeline works, while retaining `reported_corrections_passed=false` and
`accepted=false`. It does not relax correction thresholds or qualify a release.

## Current result and limits

Captured run: `/private/tmp/bilingual-pipeline-v78-enabled`,43/43 core checks,
0/14 reported whole-sentence corrections,21,713,180 resource bytes. The existing
staged Cantonese model and internal typing model/policies were used. The newer
experimental English/spelling models remain unintegrated; this run must not be
described as their acceptance. See sibling dataset
`keyboard-lm/AUTOCORRECT_ROOT_CAUSE_V77.md` for search-deadline, confidence,
ranking and missing-vocabulary causes.

These are **host** end-to-end runtime checks. The OS/editor and unrelated voice,
handwriting and clipboard ports use scenario implementations; native candidate
generation, language evidence, LM ranking, policy decisions and keyboard control
flow are real. Coroutine virtual time verifies scheduling, not Android inference
latency. Android InputConnection/touch tests and phone/IME-service verification
remain separate. Data preparation, training, export and calibration retain their
existing dataset tools and quality gates; this command connects their finished
asset bundle to runtime output, not the entire training workflow.

The full ordinary keyboard gate remains:

```sh
./gradlew :app:verifyKeyboard \
  -PenglishNativeLibrary=/absolute/path/to/libcantonese_engine.dylib
```

Settings screenshot baselines describe the ordinary build. An explicitly enabled
trial build displays different explanatory copy; those baselines must not be
updated just to make an enabled-trial screenshot comparison pass.
