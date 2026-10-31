# Quick capitalization regression — 2026-10-31

Typing `how are you` through the real JNI/controller path now produces
`How are you` in English, Quick3 and Quick5. The first word remains lowercase
while composing; Space applies the capitalization correction. Shift stays off.
Backspace restores the lowercase original, and recommitting keeps that literal.

The release-75 merge removed sentence-start Shift behavior. Native inference
missed empty left context, and both Android prediction parsers discarded
capitalization-only alternatives using case-insensitive equality. The fix retains
those alternatives and applies native start-of-text casing after lexical
acceptance. This preserves the spelling decision. Automatic replacements read
the capitalization setting, preserve acronyms, and keep explicit candidate
selection intact.

## Verification

- Freshly built host JNI, including the production unified suggestion parser.
- `unifiedCapitalizationSurvivesJniParsingAndQuickBoundaries` covers the reported
  phrase, candidates, toolbar, boundaries, undo and literal recommit across all
  three layouts.
- `unifiedAutomaticCasingRespectsSettingAndExplicitChineseSelection` covers the
  setting, spelling correction, acronyms and Chinese selection.
- Final `:app:verifyKeyboard -PenglishNativeLibrary=… --no-watch-fs` passed:
  unit/scenario/JNI tests, screenshot validation and debug assembly.
- Inspected the new `ScenarioQuickCapitalizationCorrection` screenshot before
  separate validation.

## Quality checks

Fresh native builds from the starting source and changed source both score
**0.8946978214** on the frozen v4 regression suite: paired delta **0**. These
fresh builds exceed the older documented 0.893921 regression floor. Cached
binaries were replaced with source-built baselines for this comparison.

All 1,290 frozen development sessions completed: 421 ITE attempts in each of
English/Quick3/Quick5, plus nine mixed-script groups in each layout. Each ITE
slice recovered **91/229** typo-bearing messages, versus **88/229** in the saved
baseline: five paired gains and two losses. Clean lexical damage was **1/96**,
versus **2/96**. Exact typo-message recovery was **54/229**, versus **52/229**.
These saved-baseline differences include the existing source/model state and
must not be attributed to capitalization alone; the native paired spelling
check above isolates this patch.

Mixed-script slices each achieved **7/9** exact success: **2/4** correction
scenarios and **5/5** preservation scenarios. The saved baseline is 6/9,
1/4 and 5/5 respectively. These authored development groups provide limited
mixed-language coverage. Confirmation data remained closed.

The combined replay exceeded its five-minute JUnit timeout after writing its
complete report. It is recorded as a timeout, not a passing test. The separate
final keyboard gate passed. A final-build follow-up of all 27 mixed sessions
and six acronym-affected ITE sessions passed; its only text changes were
`uS → US`, with unchanged lexical results. Raw reports, scores, source-built
probes and follow-up inputs are under `build/quick-caps-regression/`.

The new native surface operation adds no inference inside that operation;
physical-phone latency and Android/editor integration remain unmeasured. The
fix has not been deployed or installed. Only an emulator without this keyboard
installed was connected during this session.
