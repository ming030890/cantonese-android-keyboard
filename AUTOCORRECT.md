# English autocorrect

English and Quick layouts share a Rust spelling engine in `libcantonese_engine.so`.
It uses wmkeyboard's pinned English dictionary, fuzzy trie search and Gaussian
touch model. The existing mixed Cantonese/English NN adds a bounded context bonus
to supported spelling candidates through the same native Quick engine. It does
not load a second NN or require ASR/Play model packs.

Space, punctuation and Enter apply only confident complete-word corrections.
Known words, mixed case, identifiers, URLs/email, short/ambiguous input and
two-edit results remain literal. Suggestions can be tapped explicitly.
Immediate Backspace restores the original word and removes the delimiter;
editor actions that submit a field clear undo. Paste, voice input, language
switching and keyboard closure finish literal input.

Quick keeps Chinese candidate order, appending English alternatives. Exact
Quick codes are one/two roots; longer inputs are Chinese phrase searches.
Plausible English prefixes are protected from the six-letter automatic phrase
conversion, while non-English phrases retain that behavior. English after
Cantonese needs no separating space. Symbols retain the originating language's
word until a boundary or digit is entered.

`KeyboardSettings.isAutocorrectEnabled/setAutocorrectEnabled` store independent
English and Quick preferences, defaulting on. The settings-screen redesign and
its visible controls are deferred.

## Build and verification

`deploy.sh` regenerates `assets/english/en.bin` from the pinned source in the
sibling Rust crate before building the native engine. The asset is also checked
in for ordinary Gradle builds. The source revision and MIT notice ship alongside it.

```sh
cargo test --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml --lib
./gradlew :app:verifyKeyboard
```

Rust golden tests compare 30 inputs against the pinned Kotlin search/touch
reference, including every candidate's score and edit/completion metadata.
Controller scenarios exercise both layouts and symbol transitions, undo,
selection changes, lifecycle, mixed text, stale requests and native failure.
Two scenario screenshots cover English and mixed Quick candidate toolbars.
`EnglishNativeEngineTest` verifies the packaged JNI wrapper and shared bilingual
model on an ARM64 Android device; it can run independently of ASR evaluation.

The packed dictionary is 977,692 bytes and the in-memory trie uses 1,303,576 bytes.
A warm host search probe measured p95 below 0.2 ms without NN ranking. Phone
latency and real InputConnection behavior still require device verification;
no Android device was connected during implementation.
