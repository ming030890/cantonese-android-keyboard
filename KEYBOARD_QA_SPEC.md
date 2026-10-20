# Keyboard QA specification

Version 1, dated 2026-10-13. This is the behavioral contract for regression testing.

## Status and ownership

- **Agreed**: explicitly requested by the user. Tests must enforce this behavior.
- **Baseline**: a documented preservation expectation from the current product. This is
  not a claim that the user has approved every existing interaction. Surprising or
  ambiguous policies are listed separately as **Pending decision**.
- **Pending decision**: requires a user answer before becoming an acceptance requirement.
  Existing tests of the current behavior are characterization tests until that answer.
- **Integration gap**: requires the real Android IME, provider, microphone, or model.
  A passing fake-boundary scenario must not be reported as verification of that integration.

Codex owns writing/running tests, inspecting intentional screenshot changes, and reporting
failures. The user supplies product decisions rather than performing routine manual QA.

## State vocabulary

A session is a focused editor with the keyboard visible. Reopening the keyboard or focusing
another editor starts a fresh visible session; routine selection/cursor updates do not.

State is the combination of: visible/hidden session; Quick/English/handwriting/numbers layout;
committed text and composing text; empty/code candidates/next-word predictions; clipboard
availability and sensitivity; autofill availability; handwriting idle/recognizing/results/error;
and voice idle/loading/downloading/recording/transcribing/error. UI controls additionally
include shift, symbol page, emoji panel, and candidate page.

Fake model output is fixed for examples: Quick `of` -> `你`, `係`; after selecting `你` ->
`好`, `哋`; after selecting `好` -> `嗎`. These words test state transitions, not language
model quality or a requirement that real models return this exact ranking.

## Clipboard

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| CB-01 | Supported nonempty text is copied; open a text layout before typing | Show the clipboard chip, including when idle predictions exist or the editor already contains text. | Agreed; `cb01`, initial scenario screenshot |
| CB-02 | Chip visible; enter a letter, number, punctuation, emoji, select a word, or begin a handwriting stroke | Hide the chip when input begins; candidate results and status can occupy the toolbar. | Agreed for typing; baseline for other input; `cb02`, after-typing screenshot |
| CB-03 | Chip visible; change layout, shift, symbol page, settings, or candidate page; move cursor | These controls must not mark the user as having typed or discard clipboard availability. | Baseline; `cb03`, existing sequence tests |
| CB-04 | Press a text key, then cancel it without key-up | Do not insert text or dismiss the chip. | Baseline; `cb04` |
| CB-05 | Clipboard is absent, empty, or has no advertised plain-text version | No clipboard chip; normal candidates/status/empty toolbar remains available. HTML-only and image-only clips do not qualify. | Baseline; `cb05`, `ClipboardSuggestionTest` MIME cases |
| CB-06 | Clipboard marked sensitive | Show generic Paste without including the sensitive text in preview/accessibility text. Paste inserts the actual clipboard text. | Baseline; `cb06`, existing sensitive screenshot; Android accessibility inspection gap |
| CB-07 | Press clipboard chip down, then release | Down must preserve its target; up inserts text exactly once and dismisses the chip. | Baseline; existing `pasteKeyDownDoesNotDismissChipBeforePasteKeyUp` |
| CB-08 | New clipboard text arrives before any input | Update the chip to the new supported text. | Baseline; `cb08` |
| CB-09 | After input, delete everything or copy new text | Deleting all input alone does not restore the chip. A newly copied supported clip restores it, including copying the same text again. The next input hides it again. | Agreed D-02; `cb09`, restored-by-new-copy screenshot |
| CB-10 | Copy rich text containing plain-text and HTML versions | Preview and paste the plain-text version; never display or insert HTML markup. | Agreed D-06; `cb10`, rich-clipboard screenshot |

## Quick codes and next-word predictions

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| QK-01 | Type Quick letters `o`, then `f` | Editor composes `o`, then `of`; no committed letters yet; show candidates for the current code. Lookup receives surrounding text with the composing code removed. | Baseline; `qk01` |
| QK-02 | Choose a displayed Quick candidate | Commit the chosen Chinese text once. Preserve unconsumed keys beyond the decoded twelve-key prefix; otherwise clear composition and request/show next-word predictions using the updated context. | Agreed; `qk02`, next-word screenshot, long-word selection scenario |
| QK-03 | Choose a displayed next-word prediction | Append that prediction once; remain out of composition; refresh next-word predictions again. | Baseline; `qk03` |
| QK-04 | Cursor/selection callback while a code is active | Keep composing text and code candidates; do not replace them with idle predictions. | Baseline; `qk04` |
| QK-05 | Backspace with an active code and previously committed text | Remove one composing letter per press. Preserve committed text until composition is empty; a subsequent Backspace edits committed text. | Baseline; `qk05`; deleting all input alone does not restore clipboard |
| QK-06 | Rapid code changes while lookup is pending | Only the latest requested code/version/context result may be displayed. Cancelled work must not restore older candidates. | Baseline; `qk06`, existing real-worker cancellation test |
| QK-07 | Insert a whole English next-word prediction between English words | Add the needed left/right word separators; do not add separators to ordinary Chinese Quick selection. | Baseline; `qk07`, existing coordinator tests |
| QK-08 | Space, Enter, or switch layout while composing Quick letters | Space/Enter preserve literal letters when autocorrect is disabled or no confident English correction exists. Enabled autocorrect follows AC-01. Layout switches remain literal. Never choose Chinese implicitly at these boundaries. | D-01 amended by D-07; `qk08`, autocorrect scenarios |
| QK-09 | Compose more than a single two-letter code | Continue phrase lookup. Following input may commit a resolved six-character Chinese phrase only with fresh, strong LM Chinese evidence. Missing, pending or uncertain evidence preserves raw composition and unconsumed suffixes. Dictionary prefix membership does not decide the language. | D-09; `QuickLanguageScenarioTest`, native real-model regression, length/cap tests |
| QK-10 | Paste, press Record, or enter punctuation while a Quick code is unfinished | Commit literal letters first. Paste inserts copied text once and clears Quick composition, then refreshes predictions. Record transitions to voice; punctuation follows the literal letters. Chinese conversion requires explicit candidate selection at these boundaries. | Rule D-05 derived from confirmed literal policies; `qk10` |
| QK-11 | Type a complete two-letter Quick code such as `df`, including in English context | Keep all Chinese code matches in their Quick ranking ahead of English suggestions. Retain literal English for explicit selection. Longer input continues bilingual ranking. | Agreed 2026-10-19; `twoLetterQuickCodeKeepsChineseBeforeEnglishInEnglishContext`, two-letter scenario screenshot |
| QK-12 | Type a short Quick code while full/English ranking is pending | Show a selectable preview using one model distribution for known characters, followed by rare matches in dictionary order. Full sequence ranking then refines the order. Display Chinese results without waiting for mixed ranking; two-letter codes never request mixed ranking. Cancelled previews/results must not restore an old code. | Agreed latency fix 2026-10-20; `shortCodePreviewIsSelectableBeforeFullRankingAndRejectsLateResults`, `chineseCandidatesAppearWhileEnglishWorkerIsStillPending`; final candidate screenshots unchanged |

## Other text input and editor context

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| ED-01 | Type text with autocorrect disabled, or on the number layout | Commit exact selected text without starting Quick composition or querying Quick candidates. English with autocorrect enabled follows AC-01. | Baseline with English backend disabled; `ed01` |
| ED-02 | Commit text with characters after the cursor | Preserve right-of-cursor text and query predictions using both sides of the cursor. | Baseline; `ed02`, existing context test |
| ED-03 | Enter in an editor with a configured action | Execute the editor action; otherwise insert newline. Custom action takes precedence. | Baseline; `EditorActionResolverTest`, `ImeEditorTest`; OS integration gap |
| ED-04 | Backspace with an editor selection | Delete the selection; otherwise delete the preceding character. | Baseline; `ImeEditorTest` decision helper; real InputConnection integration gap |
| ED-05 | Backspace after an emoji | One press deletes the entire preceding visible character, including surrogate pairs, skin tones, flags, variation selectors and joined emoji. Preserve adjacent text and any text after the cursor. | Agreed 2026-10-19; `ImeEditorTest`, `ed05` sequence |
| ED-06 | Type literal Latin letters (including two-letter Quick codes), then punctuation | Insert ASCII punctuation after literal Latin input. Use Chinese punctuation after selected Chinese text. Key labels and symbol panels reflect the same context decision; update after typing, selection, deletion and cursor changes. Autocorrect must use the normalized delimiter. | Agreed 2026-10-19; `SmartPunctuationScenarioTest`, Quick ASCII/Chinese scenario screenshots |

## Handwriting

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| HW-01 | Idle handwriting predictions; start a stroke | Clear old predictions, dismiss clipboard availability, and interrupt voice input. | Baseline; existing real coordinator stroke test and session scenario |
| HW-02 | Recognition pending with no candidates | Show recognizing status; do not present stale candidate results. | Baseline; toolbar matrix, recognizing scenario screenshot |
| HW-03 | Recognition returns candidates | Display those candidates; selecting one commits the selected text once and requests next-word predictions. | Baseline; real `hw03` coordinator sequence; snapshot scenario only mocks recognition output |
| HW-04 | Handwriting inference error | Show the handwriting error on the handwriting layout. Do not show that error on English/Quick/number layouts. | Baseline; `hw04`, error scenario screenshot |
| HW-05 | Multiple strokes, late results, changed characters, session closes | Cancel obsolete recognition; no late result may edit a different session. | Integration gap for recognizer callback wiring; existing recognition queue/debouncer unit tests cover their components |

## Voice

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| VO-01 | Voice loading/downloading/recording/transcribing/error | Show the corresponding toolbar status ahead of clipboard/candidates/autofill. Recording meter reflects supplied volume. | Baseline; toolbar matrix, existing voice screenshots; real audio integration gap |
| VO-02 | Text input while recording/transcribing | Interrupt voice on key-down before inserting the user's text. Finish the visible voice composition once, show Idle immediately, and reject late hypothesis/completion/error callbacks even if finalization already started. Repeated interrupts must preserve the new typed composition. | Agreed 2026-10-19; `vo02`, `VoiceRestartScenarioTest.typingInterruptsRecordingAndFinalizationAndRejectsLateCallbacks` uses real coordinator/controller with fake native ASR; microphone/native integration gap |
| VO-03 | First Backspace during recording/transcription | Cancel voice and delete one preceding committed character in the same action. Following presses delete normally. Down/up must not delete twice. | Agreed D-03; `vo03`; real voice composition cleanup integration gap |
| VO-04 | Finish/hide/destroy a session | Stop voice activity; reject callbacks from old sessions; no transcript inserted into a newly focused field. | Integration gap for real callbacks; fake-session cleanup assertions cover orchestration only |
| VO-05 | Press Record while Quick composition exists | Commit literal Quick letters before invoking voice startup. Do not treat fake toggling as verification of actual model startup. | Rule D-05; `qk10`; real startup integration gap |

## Toolbar and controls

Baseline precedence, highest first: voice status, handwriting error on its own layout,
autofill, clipboard, candidates, recognizing status, empty center. The settings control is
shown unless the candidate strip uses that space. Voice control remains present on text
layouts. Emoji uses its own panel rather than this toolbar.

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| TB-01 | Multiple eligible toolbar contents simultaneously | Apply the precedence above, without changing clipboard's session eligibility merely because a status temporarily occupies the slot. | Baseline; complete `tb01` matrix |
| TB-02 | Handwriting state while another layout is selected | Ignore handwriting candidates, recognizing state, and error on that layout. | Baseline; `tb02` |
| UX-01 | Too many candidates for toolbar width | Pack measured label widths in ranked order; arrows consume space only when visible; no omitted or duplicated indices. English words and Chinese phrases remain on one line. | `ux01`, measured-width tests, normal/narrow long-word and phrase screenshots |
| UX-02 | Candidate set shrinks or changes | Clamp/reset the page to a valid page; obsolete candidate keys must not be tappable. | Baseline; existing key-unmount tests; Compose page-reset interaction gap |
| UX-03 | Text key versus control key, key release/cancel/repeat | Text dispatches once on release; cancel inserts nothing. Delete occurs on down/repeat, not an extra time on up. | Baseline; `ux03`, existing touch/dispatcher tests |
| UX-04 | Shift/symbol/emoji controls | Change the visible panel/key labels without committing text. Emoji selection commits selected emoji once. | Static preview coverage and existing emoji commit scenario; full Compose interaction gap |

## Session and asynchronous work

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| AS-01 | Lookup pending; keyboard hidden/finished/destroyed | Clear visible suggestions/candidates, cancel pending Quick lookup, and prevent late results from returning. | Baseline; `as01`, existing cancellation tests |
| AS-02 | Settings disable the previously selected layout | Select an enabled fallback on the next session, while numbers remain available. | Baseline; `as02` |
| AS-03 | Quick version changes between sessions | Subsequent candidate requests use the configured version. | Baseline; `as03` |
| AS-04 | Keyboard closes or focus finishes with unfinished Quick input | Commit literal letters exactly once before session cleanup. Repeated finish/hide callbacks must not duplicate text; reopening predicts from the committed text. Service destruction must not write stale composition into a different editor. | Agreed D-04; `as04`; real editor handoff integration gap |

## English autocorrect

2026-10-16 internal contextual update: short/familiar words are eligible under
fitted finite thresholds; dictionary membership is evidence, not a veto. The
separate Quick one/two-letter Chinese-code overlap veto is bypassed only for this
profile's approved English decision; uncertain input stays literal and Chinese
selection stays explicit. Retrospective history extends to four following words
and uses the profile's confidence requirement, preserving new-evidence/editor/Undo
guards. See `AUTOCORRECT_RUNTIME_V80.md`; the reported whole sentences still fail.
Earlier rollout-specific descriptions below remain historical where they differ.

Implemented under the user-approved 2026-10-13 plan; independent English and Quick
preferences default on. Existing legacy scenarios without an English backend verify
literal fallback. `EnglishAutocorrectScenarioTest` exercises the production controller
with fixed English model results; Rust tests verify the actual search and confidence gate.

| ID | Sequence / condition | Expected behavior | Coverage |
|---|---|---|---|
| AC-01 | Type `teh`, then Space, punctuation, or newline on English/Quick | Compose the word; show literal input plus spelling alternatives; commit `the` and the exact boundary when confident. A pending lookup resolves through the bounded boundary queue; subsequent typing waits in order. Cursor changes, layout/hide, failure, timeout, and submitting Enter prevent delayed replacement. | `boundaryCorrectionAndUndoOnBothLayouts`, `pendingBoundaryQueuesNewInputAndSupportsUndo`, real JNI timing test |
| AC-02 | Immediate Backspace after correction | Remove delimiter and restore original composition; recommit without repeating the correction. Guard undo against cursor/selection changes. | boundary and selection scenarios |
| AC-03 | Type English prefixes in Quick beyond six letters | Protect plausible English from automatic Chinese phrase conversion; preserve conversion for non-English sequences. Chinese and English candidates share LM ranking; selection stays explicit. Exact one/two-root codes remain literal unless tapped. | Quick conversion and selection scenarios |
| AC-04 | Switch to symbols and return, or enter punctuation there | Keep originating English/Quick word ownership. Punctuation resolves the word; digits and identifiers finish literally. | `numberPanelPreservesWordForPunctuationButDigitsStayLiteral` |
| AC-05 | Paste, Record, emoji, layout switch, keyboard hide | Finish literal composition exactly once and cancel pending English results. | lifecycle scenarios |
| AC-06 | Password, number, URL/email editor, no-suggestions, disabled layout preference | No English correction. Preferences are independent for English and Quick. | policy and preference scenarios |
| AC-07 | Tap letters, uppercase, resize, rollover or canceled key | Capture drawn letter centers in key-width units with dispatched characters only. Preserve case; unsupported tokens stay literal. The active shared engine currently ignores captured touch evidence and uses fixed QWERTY costs; touch-aware legacy tests do not establish live touch scoring. | geometry/input scenarios; active touch integration gap |
| AC-08 | Chinese and English candidates compete in Quick | Complete two-letter codes follow QK-11. For longer input, shared bilingual LM ranks one combined pool, including Chinese phrases; retain all candidate texts and literal spelling. English can rank before Chinese in English context. | `mixedCandidateBarUsesContextRankingAndBothLanguagesRemainSelectable`, shipped-model JNI test, mixed screenshot |
| AC-09 | Centre-key mistaps from the user's report | Clear `engkish`, `shoukd`, `chineae`, `candjdates`, `kehboard` typos correct; severe `ebgkisb` offers `english` without automatic replacement. | Rust centre-touch regression, real JNI/controller scenarios on both layouts |
| AC-10 | WM replacement rules: `keyboar`, `gwllo`, `tyoing` then Space | A single missing final letter can correct with a 4:1 margin and insertion penalty; two-edit and edited/completed suggestions do not compete for replacement. `gwllo` offers `hello` for a tap; ambiguous `tyoing` remains literal. Backspace restores `keyboar`. | Native shape/confidence tests and real JNI/controller scenarios on both layouts |
| AC-11 | Uncertain word followed by more completed words | Keep literal initially. Reassess at Space using up to three recent untouched English words and their original touch snapshots. Revise only with separately approved context-revision calibration and confidence at least 0.999; otherwise keep literal. Preserve later text; immediate Backspace restores the original suffix and records rejection. Typing, cursor changes, hide/layout/session changes, edits, punctuation, explicit selections and disabled autocorrect invalidate pending writes/history as appropriate. | `ContextRevisionScenarioTest`, uncertain-company screenshot |

Rust uses the pinned wmkeyboard English seed dictionary, FuzzyBeamSearch penalties,
and Gaussian TouchModel. A packed trie and reusable search workspace limit mobile
memory/CPU. Existing bilingual NN context scores rerank only dictionary candidates;
known words, longer completions, multiple edits and ambiguous candidates are not
silently replaced. A clean one-letter final completion counts as an insertion. Actual device latency and InputConnection integration require a connected
Android device; none was connected during this implementation.

## Product decisions

**D-12 (2026-10-16):** Correctly typed `typo` was changed to `type`. Exact
version51 JNI replay attributes it to `existing_boundary_correction`: the
legacy fallback overrides the new model's abstention because the small seed
dictionary lacks `typo`. The fix protects validated English root words at both
the new policy and old fallback, and reports `valid_literal_preserved`/known
input. Native-only and real InputConnection regressions preserve `typo was `
while keeping clear corrections and undo. The guard uses pinned SCOWL en_US
root data shipped with source/notice files, not a word-specific exception or
a new candidate-frequency table. Compound-only roots and guessed inflections
are excluded. This fixes overcorrection; `srill`/`lor`/`od` missed corrections
remain a separate ranking/confidence weakness. Version52 keeps new autocorrect
enabled for internal testing.

**D-11 (2026-10-16):** The user explicitly requests enabling the new autocorrect
on Play internal testing after version50 left it disabled. Version51 uses the
explicit `INTERNAL_TYPING_TRIAL=true` release build setting. On first upgrade,
the new decoder and English/Quick autocorrect turn on; later opt-outs persist.
The visible "New autocorrect" switch is available in that internal release.
Ordinary release builds still default to the production qualification gate.
Internal-trial profiles retain `heldout_approved=false` and
`production_approved=false`; loading requires explicit trial authorization,
the exact model and expanded dictionary hashes and the matching raw-sum ranker.
This is internal-test authorization, not a claim that quality gates passed.

The internal profile loads the own v22 byte-fallback LM for typing. Quick and
handwriting keep their existing model. Combined model/vocabulary/English/Quick
candidate resources are21,087,094bytes. New fitted six/nine-feature policies use
the actual app dictionary; v42 head coefficients are not reused. Existing clear
boundary corrections remain available when the new classifier abstains. Later
revisions still require new context evidence, exact editor state and safe undo.
Real packaged-model checks verify `kbow`→`know` after "could you please let me "
and `pleade`→`please` after " provide ", plus unfamiliar-name preservation.
Twelve ARM64 emulator checks include real InputConnection correction and undo,
upgrade initialization, model/policy loading and touch dispatch. The screenshot
`InternalTypingSettingsScreenshot` shows all three correction toggles enabled.

Production qualification also protects the debug-only decoder preview: a saved
preview preference cannot activate it in a release build. The shipping decoder
must retain correction-at-boundary and Backspace undo on English and Quick;
`ReleaseDecoderGateScenarioTest` covers restored preference state. Settings
screenshots include the complete typing card and narrow large-text diagnostics,
with explicit notice that the current preview offers suggestions only.

**D-10 (2026-10-15):** The user requests keeping uncertain words and revisiting
them as later context arrives. `ContextRevisionWindow` implements a bounded
three-word/256-character window and 40 ms asynchronous reassessment at completed
Space boundaries. It never rewrites an active composition or depends on an old
cursor alone: the exact suffix, selection, session revision and right text must
still match. One safe revision is applied per pass, then the retained window is
cleared. Correction scope is separate from boundary calibration; the loader
requires `typing/revision_calibration.json` with `correction_scope=context_revision`,
the exact model hash, `typing-v3-context-revision-nine-features` and approved
held-out evidence. It measures the original LM contrast and the new contrast:
the increase must be positive with at least one complete following English
word. Nonfinite scores abstain. A disabled category's threshold of exactly 1.0
stays disabled even when a calibrated probability rounds to 1.0. No such qualified artifact
is currently packaged, so the experimental backend cannot activate retrospective
corrections yet. Controller scenarios use fixed model outputs to verify mechanics;
they do not establish model accuracy. `ContextRevisionInputConnectionTest`
separately verifies correction/undo and weak-confidence preservation through a
real EditText/InputConnection on the ARM64 emulator, with fixed model outputs.
Its debug host suppresses the competing system IME via an explicit Intent extra;
normal preview behavior is unchanged. It does not establish full IME-service
tap replay or physical-phone latency. `KeyboardTouchControllerTest` verifies
unmounted keys cannot receive events while retaining intentional row expansion
for remaining keys. Latest full gate: 178 unit/scenario tests plus screenshot
verification and debug build; archived emulator checks: nine passed.

Confirmed by the user on 2026-10-13:

- **D-01**: Originally literal letters for Quick Space, Enter, and layout switching; D-07 permits confident English correction on Space/Enter while keeping layout switches literal.
- **D-02**: Show the clipboard chip again when new text is copied after typing.
- **D-03**: Backspace cancels voice and deletes a character together. Direct controller
  calls and real key down/up must have the same behavior.
- **D-04**: Closing the keyboard or moving focus commits unfinished Quick input literally.

- **D-06**: Rich clipboard contents use their plain-text version.
- **D-07**: Enable conservative English autocorrect on English and Quick, using the wmkeyboard reference in Rust with touch evidence and the existing bilingual NN. Correct at Space/punctuation/Enter; allow Backspace undo. No implicit Chinese selection. Settings-screen redesign is deferred.
- **D-08 (2026-10-14)**: Show possible English words in the candidate bar and rank Chinese and English together with the shared LM. Fix overly strict centre-tap rejection and lookup timing that skipped clear typo corrections.
- **D-10 (2026-10-14)**: Apply WM Keyboard’s ASCII QWERTY automatic replacement shapes and default 4:1 confidence margin, retaining bilingual ranking and explicit multi-edit suggestions.
- **D-09 (2026-10-14)**: Continuous Quick conversion must depend on LM evidence for English versus Chinese, not an English dictionary gate or length alone. Preserve uncertain/OOV input. Candidate UI must handle English words and multi-character Chinese phrases.

Derived rule, communicated to the user:

- **D-05**: The user noted that the keyboard cannot determine whether Quick letters are
  intended as English or Chinese. Together with D-01/D-04, use literal commitment at
  paste and Record boundaries rather than guessing a Chinese candidate. Punctuation may apply a confident English correction under D-07.
  Restored clipboard availability must not leave a stale composing code after paste.

No clarification questions remain pending for this version. Baseline preservation
expectations and the listed integration gaps remain distinct from explicitly confirmed
product decisions. Automatic phrase-length behavior in QK-09 is an existing code-defined
boundary, not newly introduced language-intent inference.

## Execution and acceptance

2026-10-16 phone-report follow-up: `typo` preservation is fixed locally in52;
the52 Play upload was canceled at the user's request and internal remains51.
`srill a lor od type` remains a failing accuracy regression. The new optional
word-evidence policy/API corrects the byte-mean comparison mechanism and can
evaluate short/legacy-valid words without a categorical WM membership veto,
but no newly fitted profile is enabled: independent calibration still rejects
the variants. `WordEvidencePolicyTest` checks mechanism/binding/failure safety;
`InternalTypingRealEngineTest` proves the actual native byte-mean versus whole-
word score discrepancy. These tests are not a claim that the complete phone
sentence is corrected. No candidate layout/baseline changes are intended.

Continued repair: rapid queued keys now allow a bounded retrospective decision
to finish; revising one word rebases other pending typos. Scenario tests verify
multiple later corrections and Undo. Native V4 preserves neighboring-key priors
at center taps, checked through actual host JNI. Word-evidence abstention cannot
fall through to the legacy automatic replacement path.192 tests, screenshots and
debug build pass via the full gate. These are source/mechanism fixes; the current
staged profile is unchanged and user-sentence accuracy is not yet fixed. English
model coverage training and independent confidence calibration remain ongoing.

New acceptance tests live in `KeyboardQaContractTest.kt`; names start with the IDs above.

Deadline isolation (v77): `AutocorrectDeadlineScenarioTest` holds the model answer
constant and verifies10ms versus60ms worker delay, immediate versus paused Space,
later-context cutoff and no stale rewrite of new input. Both40ms application caps
can discard a valid answer. Actual staged JNI diagnostics separately expose8ms
native search exhaustion on reported examples. Uncapped experimental replays still
fail the typo sentences on confidence/ranking; passing these mechanism tests is not
an accuracy fix or phone-speed measurement.195 unit/scenario tests, screenshots
and debug build pass. No model/profile staged or Play upload; detailed report is
`../dataset/keyboard-lm/AUTOCORRECT_ROOT_CAUSE_V77.md`.

Existing scenario/coordinator/touch tests supply the coverage explicitly named in the tables.
A matrix test reports the failing combination in its assertion message.

Run `./gradlew :app:verifyKeyboard`: unit contracts, all screenshot comparisons, debug build.

`BilingualPipelineE2eTest` now loads the staged internal profile through the same
asset factories as production (no reflection-created engines or fitted constants
in the test). The real key receiver/controller/native engines produce43 asserted
English, Cantonese and mixed-output cases, including touches, rapid typing,
selection and undo. A separate14-case user-sentence report remains failing.
`scripts/verify_bilingual_pipeline.py` builds JNI, enables the trial build flag,
checks exact resources and the30MB limit, and returns a failing acceptance status
for those misses. See `BILINGUAL_PIPELINE.md` for command and host/Android scope.

Create intentional new baselines separately, inspect them, then run validation. Pending
product decisions and integration gaps remain visible even when this command is green.
Do not update a reference image merely to make a failure pass.


## Emoji picker (2026-10-17)

The picker uses the production Compose UI in previews as well as the IME. It shows
an eight-column scrolling grid, mint selected-category/emoji pills, nine categories,
recent selections and long-press variants. The offline index contains 1,790 base
emoji and 2,982 complete sequences from AndroidX 1.6.0 with CLDR 48 English names
and keywords. Existing AndroidX recent history is retained.

Search places its query/results panel above the existing `AlphaNumPanel` and
`AlphaNumBottomRow`. It uses the same touch dispatcher, key geometry, haptics,
Shift, delete/repeat, English symbols and key popups as normal English typing.
`EmojiSearchInputReceiver` redirects those events to the local query; text,
Space, Delete and Done must never edit the host field or dismiss clipboard
availability. Choosing a result commits one complete Unicode sequence through
the normal emoji callback. Done or the emoji key returns to browsing; the header
back control closes search. The standard layout-switch control exits search.

Browsing height is 480 dp (previously 369 dp), allowing roughly seven rows on
an uninset preview. Search height is 497 dp: the 176 dp results panel replaces
the normal 48 dp toolbar above the unchanged English key rows. System-owned
navigation/caption/cutout insets remain reserved inside the surface.

`EmojiCatalogTest` covers coverage/search/variants/query editing. The production
scenario receiver verifies search typing, Space, cancellation, held Delete,
Done, subsequent host typing, and whole-variant insertion. Android interaction
tests exercise actual English keys, symbols, query isolation and variants.
Screenshots cover tall/compact browsing, dark browsing and full English search
in both themes, including narrow and empty-result layouts.

Verification of the reused English keyboard: 224 unit/scenario tests pass, along
with three emulator interaction tests and all seven emoji screenshots. The
interaction test measures the normal and search English keys and confirms equal
width/height, then exercises Shift and held Delete without editing the host.
The actual IME service was opened with a focused `EditText` on the ARM64 emulator:
browsing shows six full rows with system insets; search shows results above the
normal English rows; both clear the navigation controls. Tapping `heart` into
search leaves the host field empty. This was a temporary UI integration install,
not a model-delivery deployment. The full gate still reports 15 existing Quick
punctuation screenshot mismatches from separate pending edits; their baselines
remain unchanged. The connected physical phone was not used or modified.


### D-18 (2026-10-19): Version56 completion-only behavior (superseded by D-19)

Version56 used the same V2 CNN for English and Cantonese, with completion only.
The user subsequently clarified D-19. This implementation superseded D-07/D-08
and later automatic spelling-correction policies for the active shared-model path.
English shows literal input plus prefix completions; Space, punctuation and Enter
commit the typed spelling unchanged. Selecting a suggestion inserts that suggestion.
Quick Chinese/English ranking and next-word prediction use the shared LM. Keep Quick
code dictionaries and the handwriting visual recognizer: these map keys/strokes to
candidates and are not separate language models. Remove packaged English spelling,
validity and calibration assets. Settings expose suggestion toggles only.

V2 integration is experimental: English-prefix baseline retention and mixed-prefix
INT8 retention guards failed. Handwriting fusion uses an explicit initial neural
weight of 1.0 and needs development calibration. The causal model uses left context
only; cursor edits rebuild its context. Host verification does not establish phone
latency or handwriting recognition quality. SharedLmEnglishScenarioTest exercises
literal boundary commits and manual completion via the production controller;
ScenarioSharedLmEnglishCompletion renders the same behavior with a fake LM.


### D-19 (2026-10-19): Shared-LM spelling suggestions and automatic correction

User clarified that one LM should generate and rank spelling alternatives, with
both explicit selection and automatic correction on Space. This supersedes D-18's
completion-only behavior. Generate bounded insertion/deletion/substitution/adjacent-
swap neighbors from TRAIN-attested English tokens already embedded in the shared
model. Rank those and prefix completions by contextual LM log probability minus
2 per spelling edit. Keep literal input as a selectable candidate. Apply only an
unknown spelling's best one-edit whole-word correction with a score margin >=4
(>=6 without left context) and LM
probability >=0.0001; preserve known vocabulary spellings. These are experimental
heuristics, not fitted confidence probabilities. The existing boundary transaction
waits up to40ms and commits literally on timeout, with no stale post-boundary rewrite.
Backspace restores the original spelling; the next Space preserves that undo.
Quick's short Chinese code protection remains. No external English dictionary,
second model, spelling head, or old correction calibration is loaded. Vocabulary
coverage limits correction: words absent from the shared English token set cannot
be proposed by this generator.

### D-20 (2026-10-20): Data-fitted acceptance selected by F₀.₅

The user selected F₀.₅ as the primary correction metric and requested accepting
improvements; there is no separate 95% precision cutoff. This supersedes D-19's
numeric edit/margin/probability rules and the earlier precision-focused rule.
The shared native engine uses `english_acceptance.json`. On the fixed aligned
mobile development evaluation F₀.₅ improves from 0.71557 to 0.76364: 336 correct
replacements and 28 wrong ones versus 399 correct and 112 wrong originally.
Precision is 92.31%, recall 45.16%. The earlier conservative rule scored 0.66351.
An uncertain `varely` remains literal on Space and still offers `barely` for
selection. `teh` still corrects, and Backspace undo remains available. Prefix
completion no longer pays a spelling-error cost. These changes do not qualify
the full experimental LM for production.

`SharedLmEnglishScenarioTest` checks the real native decisions and controller
sequence. `ScenarioUncertainSharedLmSpelling` renders the literal and explicit
alternative with a fake native response. Captured touch coordinates still do not
enter this engine's scorer. See `ENGLISH_TYPING_ITERATION.md` for the touch-model
experiment, remaining constants and evaluation limits.

### D-21 (2026-10-20): Original-word evidence in fitted acceptance

The current rule replaces D-20's blanket known-word preservation with a fitted
comparison of original-word and proposed-replacement evidence. It reuses the
existing LM distribution and adds no model pass or separate dictionary. Six
fitted features include original score, score difference, known-word status and
an apostrophe indicator alongside winner score and runner margin. Prefix expansion
remains explicit. A known original without literal evidence is preserved.

The frozen evaluation improves F₀.₅ from 0.76364 to 0.79167: 361 correct and
23 wrong replacements, with five unwanted changes to preservation controls.
`hou` after “let me know if” can correct to `you`; immediate Backspace restores
`hou` and the next Space respects undo. `don't` after “I” stays literal. This
does not establish contraction generation coverage: the LM has no apostrophe
word tokens. Actual touch coordinates remain unused by the active scorer.

`SharedLmEnglishScenarioTest` covers real JNI/controller behavior on both layouts;
`ScenarioSharedLmKnownWordCorrection` renders the same state with fake model data.
See `LITERAL_ACCEPTANCE_ITERATION.md` for selection, evaluation and host latency.
