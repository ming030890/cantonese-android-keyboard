# English autocorrect

English and Quick layouts share a Rust spelling engine in `libcantonese_engine.so`.
It uses wmkeyboard's pinned English dictionary, fuzzy trie search and Gaussian
touch model. The existing mixed Cantonese/English NN adds a bounded context bonus
to supported spelling candidates through the same native Quick engine. It does
not load a second NN or require ASR/Play model packs.

Space, punctuation and Enter apply only confident complete-word corrections.
Centre taps on a mistyped key can correct when a one-edit spelling dominates;
expensive one-edit fixes require an 8:1 margin over equally close alternatives.
Known words, mixed case, identifiers, URLs/email, short/ambiguous input and
two-edit results remain literal. Suggestions can be tapped explicitly.
Immediate Backspace restores the original word and removes the delimiter;
editor actions that submit a field clear undo. Paste, voice input, language
switching and keyboard closure finish literal input.

Quick ranks Chinese and English together with the shared bilingual LM. Chinese
phrases and English words use comparable average token log probabilities.
The bar retains up to eight English alternatives plus the literal spelling;
broader multi-edit recovery is offered for explicit selection. Exact
Quick codes are one/two roots; longer inputs are Chinese phrase searches.
Following input commits a resolved six-character Chinese phrase only when the
current shared LM gives strong Chinese evidence. This decision does not use the
English dictionary's prefix flag. The model compares English token/prefix support
(including an unknown-word allowance) against Chinese sequence log probabilities.
These are relative hypothesis probabilities, not a separately trained language
classifier: the current gate requires at least 95% Chinese evidence and a geometric
mean token probability of at least 0.02. Weak, pending, failed or unavailable model
evidence keeps raw composition, including words longer than twelve keys. Lookup
uses the leading twelve Quick keys; conversion/selection preserves any remaining
keys. Inference runs on one worker so cancelled requests cannot queue parallel
native calls during rapid typing. English after
Cantonese needs no separating space. Symbols retain the originating language's
word until a boundary or digit is entered.

Candidate pages use measured text widths, with larger Chinese text and compact
English word labels. Labels stay on one line; visible arrows reserve their own
space, and intrinsically oversized labels ellipsize. Normal and narrow-screen
screenshots cover long English words, Chinese phrases and subsequent pages.

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
Those scenarios use fixed predictions. To verify actual committed output through
the production JNI wrapper, shipped dictionary, controller and key receiver on
macOS/Linux, run:

```sh
bash scripts/test_english_autocorrect.sh
```

`EnglishRealEngineScenarioTest` verifies English and Quick key sequences with
drawn key geometry and simulated touch coordinates, correction and Backspace undo.
It checks `teh` → `the`, `helllo` → `hello`, and `wprld` → `world`, including
centre taps, plus the reported `engkish`, `shoukd`, `chineae`, `candjdates` and
`kehboard` typos. `ebgkisb` offers `english` for explicit selection.
Scheduled lookup tests verify that a boundary before lookup completion can still
correct the untouched committed word and support undo. Further input, cursor
movement, layout switches and keyboard closure prevent that delayed replacement.
Submitting Enter does not defer correction past submission. The Android editor is simulated;
these host tests do not verify a phone's installed ARM64 library or InputConnection.
The shipped bilingual model is loaded in host JNI tests to verify joint ranking.
Failed loads retry on later requests and report errors in Android logcat.
The real-engine tests are skipped unless `englishNativeLibrary` is supplied;
the script builds the host JNI library and supplies that property automatically.
Two scenario screenshots cover English and mixed Quick candidate toolbars.
`EnglishNativeEngineTest` verifies the packaged JNI wrapper and shared bilingual
model on an ARM64 Android device; it can run independently of ASR evaluation.

The packed dictionary is 977,692 bytes and the in-memory trie uses 1,303,576 bytes.
A warm host search probe measured p95 below 0.2 ms without NN ranking. Phone
latency and real InputConnection behavior still require device verification;
no Android device was connected during implementation.
