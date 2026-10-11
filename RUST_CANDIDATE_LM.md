# Rust Quick and shared candidate LM

Quick3/Quick5 lookup and context ranking now run in the standalone sibling
[`cantonese-ngram`](../QwenASR/crates/cantonese-ngram/README.md) Rust library.
The Rust BiLSTM handwriting engine depends on that same crate. Kotlin retains IME text
editing, lifecycle and candidate display.

Fallback is unigram with no useful context, character bigram with one known left token,
and neural scoring with at least two known context tokens. Right-only single-token input
uses unigram. Unknown tokens do not count toward the threshold. Short contexts are padded
with zero; eight tokens are not required.

`NativeQuickEngine.kt` loads `libcantonese_ngram.so`. Handwriting statically depends on the
same Rust crate within `libstust_handwriting_inference.so`. Both read the packaged
`language_model/model.cng` (4.98 MB) and the existing handwriting count assets. Quick's
empty-context candidate order remains identical to its dictionaries. Unsupported Quick
characters remain available in their original slots.

The deployment script builds/stages both native libraries and the model. `LM_SOURCE`
can select another compatible CNG1 export. No app installation is performed by unit tests
or `assembleDebug`.

Handwriting loads `language_model/handwriting_fusion.json`: unigram1.0, bigram1.5,
neural1.25. Its model/count/label hashes must match the packaged assets. These weights
were selected using unique OLHWDB training trajectories and v7 training text; see
`../handwriting-recognizer/RUST_LM_CALIBRATION_V7.md`. A writer/document-disjoint
training audit improved top-1 from68.5% to77.4%; it is not a production-context test.
The integration has not been tested in a running keyboard on a device.

Quick shows the top three next-token suggestions while no code is active. The shared
Rust library exposes raw neural and hybrid `predict_next` / `predict` APIs, searches the
full vocabulary, and applies count fallback for short contexts. Suggestions refresh on
selection, editor changes and cursor movement; composing code restores dictionary lookup.
Tokens are individual Han characters, whole normalized English words and punctuation.
