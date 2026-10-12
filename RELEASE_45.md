# Release 45: complete keyboard fixes

This release includes the compact neural LM integration from release 44 and all
remaining keyboard application fixes in the current workspace:

- Handwriting shows next-token predictions while idle, refreshes after candidate
  insertion, and separates predictions from active handwritten composition.
- Quick commits pending composition before punctuation/symbol insertion or layout
  changes, and routes letters through Quick code lookup.
- Typing, deleting, selecting candidates, changing layout and pasting interrupt
  active voice input so late voice results do not overwrite subsequent input.
- Clipboard suggestions disappear after typing and avoid covering active
  candidates or handwriting recognition.

The LM remains embedding128/residual256/two blocks/FFN512, 5,860,722 bytes, with
frozen neural handwriting weight1.25 and no packaged count table. Source and
checksums: `config/language_model/selected_model.json`.
Debug-only preview activities and regression tests are included in source;
release packaging excludes the debug activities through Android source sets.

Build with `./deploy.sh --release`. The release uses the full current application
source and versionCode45. Generated assets, signing inputs and build products
remain gitignored. Publication proof and the signed AAB are preserved on the SSD
under `/Volumes/SSD/cantonese_ngram/releases/keyboard-release45` after publication.

Validation: all 73 Android unit tests pass across 12 test classes; signed release
build and release lint pass. Actual AAB model/fusion/native hashes and all five
asset packs pass verification. Bundle manifest confirms version45 and excludes
debug preview activities. Native sources remain at the tested release44 commit.
