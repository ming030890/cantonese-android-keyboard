# Selected keyboard model: shared V2 CNN (experimental)

The app stages `cnn_repair_002` at its selected 35M-exposure checkpoint as one
INT8 causal CNN for Cantonese, English, mixed Quick ranking and handwriting
language priors. The model includes its exact canonical vocabulary and a list
of TRAIN-attested English output token IDs. It does not require an English
spelling dictionary or a second English LM.

English shows prefix completions, next-word suggestions and spelling alternatives.
Spelling candidates come from the model's existing TRAIN-attested English tokens,
using bounded insertion/deletion/substitution/adjacent-swap edits. The same LM ranks
them with an edit penalty. Users may select a suggestion or accept a clear spelling
correction on Space; Backspace restores the original. Known vocabulary spellings
and uncertain alternatives are preserved. No old dictionary/model is restored.
The experimental gates allow only one-edit automatic corrections, require a score
margin of4 (6 without left context) and raw LM probability >=0.0001;
these are heuristics, not fitted confidence probabilities. Words missing from the
model's English token set cannot be proposed by this generator.

The packed model is 12,675,264 bytes, SHA-256
`915bc80d53d225469e8bfe90d328ac1565647181e485cb3f0e9c32f47c946322`.
Prediction resources including Quick dictionaries and handwriting labels total
13,395,704 bytes. Quick dictionaries still map codes to candidates; the handwriting
visual model still maps strokes to characters. Their language scores come from
the shared CNN. Quick and handwriting share immutable native CNN weights while
maintaining separate context caches.

`config/language_model/selected_model.json` binds the model, architecture, fusion
hash and native verification report. `scripts/verify_language_model_assets.py`
rejects packaged legacy English/spelling assets or mismatched inputs. Deployment
verifies staged assets instead of rebuilding an English dictionary or restoring
an older LM. To restage, run `scripts/stage_shared_lm.py` with the model, a passing
native verification report, and a new SSD backup directory.

This is an internal integration, not an accepted production replacement. V2
improves development contextual accuracy but fails English-prefix baseline
retention and mixed-prefix INT8 retention guards. V1 warm-start weights retain
historical held-out exposure, so these are development results. Handwriting fusion
uses an explicit initial neural weight of 1.0, not a fitted calibration. The model
uses BOS plus the last 32 canonical left tokens; right context is ignored by this
causal model. Cursor edits and sliding context rebuild the cache.

Native Rust probabilities matched the frozen NumPy export across 70 contexts:
maximum log-probability difference 0.000283, exact top-three agreement 100%.
Host timing is not phone latency. No phone was connected during integration.


Actual-bundle native smoke checks also confirm shared weight allocation and identical
scores between cached and fresh contexts after appends, cursor edits and a sliding
window (maximum difference 0). See `config/language_model/shared_cnn_native_smoke.json`
and `shared_cnn_native_parity.json`. Historical correction code/tests remain as
legacy fixtures, but the production loader constructs only `SharedLmEnglishEngine`;
old spelling resources are neither staged nor packaged.

Integration verification (2026-10-19): `:app:verifyKeyboard` passed unit/scenario,
64 screenshot, and debug assembly checks. Nine focused shared-model tests passed
with actual host JNI enabled, including handwriting initialization, language context
and a stroke inference smoke check (`shared_cnn_jni_smoke.json`). That smoke check
establishes the native path works; it does not establish handwriting quality.
The APK resource verifier confirmed the shared-model checksum, current native
library checksums and absence of all `assets/english/` and `assets/typing/` entries
(`shared_cnn_package_verification.json`). No device installation was performed.


Version57 spelling audit: 5,946 synthetic single-edit cases from development English
words, with the intended word in the top8 in99.01%. Automatic correction applied to
1,936 cases (32.56%);1,925 were correct (99.43% of applied corrections). None of6,481
unchanged corpus words was altered. Gates were tightened using these development
checks; this is not independent held-out qualification or a calibrated confidence.
See `shared_cnn_spelling_audit.json`; replay with `scripts/evaluate_shared_spelling.py`.
The same shared weights/vocabulary and original V2 quality limitations remain.
