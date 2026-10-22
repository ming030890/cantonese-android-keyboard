# Keyboard language model v9

Historical integration, superseded by [compact v11](LANGUAGE_MODEL_V11.md).
The current keyboard uses the6.39MB single-network model, not this31.84MB model.

Quick typing and handwriting now load the selected verified CNG2 artifact at
`app/src/main/assets/language_model/model.cng`. Its vocabulary embeddings and
context features are128-dimensional; its memory MLP is128wide. The frozen
contextual v7 backbone is256wide. A256-dimensional vocabulary embedding was
not tested; the earlier width comparison changed the memory MLP.

The model SHA256 is
`0f0f74ee928da0a92de6e201118d17131e2f9be1d709425609c7cb0e940e79d6`.
The source is the verified `paired-width-comparison/width128/export/model.cng`
under `/Volumes/SSD/cantonese_ngram/experiments/count-distillation-128-256-v9`.
Weights occupy31840749bytes. Exact-key learned features use95%memory probabilities
and5%v7; unseen contexts retain v7 neural predictions.

The shared Rust adapter supports both CNG1 and CNG2. CNG2 ranks supported
candidates neurally from one known context token, including right-only infill.
Empty Quick input preserves dictionary frequency order. Unavailable neural
characters preserve dictionary slots and existing handwriting priors; newly
added vocabulary tokens remain unavailable in v7-only unseen contexts.
Raw count bigram backoff is no longer used for supported CNG2 candidates.

Handwriting fusion is bound to this exact model and the unchanged recognizer,
labels and count assets. `config/language_model/handwriting_fusion_v9.json`
is the deployment source; Kotlin checks the same SHA bindings at load time.
Calibration uses only v7TRAIN text and the previously verified unique OLHWDB
TRAIN stroke cache, with1000fitting and500writer/document-disjoint audit pairs.
No LM test text or handwriting test arrays are used to fit fusion weights.
This reused training audit is not a fresh production handwriting benchmark.

`scripts/verify_language_model_assets.py` rejects mismatched assets before
packaging. `deploy.sh` defaults to this CNG2 export and matching calibration,
and rebuilds both Quick and handwriting native libraries. It preserves the
existing ASR build/model-pack flow. No Play publication is part of this change.

Integration tests cover CNG2 bigram routing, learned-key/unseen-base behavior,
old CNG1 compatibility, Quick3/5 candidate ordering, unavailable output tokens,
and preservation of unsupported dictionary slots. ARM64 native libraries are
rebuilt from the modified Rust sources.

## Completed verification

The debug APK and full debug AAB build successfully. The AAB includes all five
ASR model packs and passes bundletool validation. Package verification checks
the SHA256 of the actual embedded LM, fusion configuration, handwriting inputs,
Quick dictionaries, and all three ARM64 native libraries against their staged
assets or stripped build outputs. Pack payload presence and sizes are checked.
`deploy.sh` runs this verification after building the AAB as well as checking
asset bindings before packaging.

All 11 LM Rust tests, 4 handwriting Rust tests, and 16 focused Android tests pass.
A 12-case smoke check runs the actual CNG2 model, count assets, and Quick3/5
dictionaries against the v7 baseline: empty dictionary ordering and candidate
sets are unchanged, and supported one-token left/right contexts use neural
scoring. `ngram_probe` supports both artifact formats for this check.
Handwriting calibration verification covers 18 contexts and all 12,362 classes
with maximum absolute error 0.0 against the Python priors. The reused training
audit has top1/top4/top10 accuracy 78.233%/88.667%/91.967%; these figures do not
establish fresh production accuracy.

Recorded checks are in `config/language_model/v9_package_verification.json`,
`v9_keyboard_smoke.json`, and `v9_runtime_verification.json`. Full calibration
artifacts remain on the SSD at
`/Volumes/SSD/cantonese_ngram/experiments/handwriting-rust-calibration-v9-memory128`.

Build outputs are `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/bundle/debug/app-debug.aab`. The APK alone is not a complete
ASR deployment; normal installation uses `deploy.sh` and bundletool local-testing
asset packs. No connected Android device was available, so installation, device
latency, and keyboard interaction remain unverified.
