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
| QK-02 | Choose a displayed Quick candidate | Commit the chosen Chinese text once, clear composition, then request/show next-word predictions using the updated committed context. | Agreed; `qk02`, next-word screenshot |
| QK-03 | Choose a displayed next-word prediction | Append that prediction once; remain out of composition; refresh next-word predictions again. | Baseline; `qk03` |
| QK-04 | Cursor/selection callback while a code is active | Keep composing text and code candidates; do not replace them with idle predictions. | Baseline; `qk04` |
| QK-05 | Backspace with an active code and previously committed text | Remove one composing letter per press. Preserve committed text until composition is empty; a subsequent Backspace edits committed text. | Baseline; `qk05`; deleting all input alone does not restore clipboard |
| QK-06 | Rapid code changes while lookup is pending | Only the latest requested code/version/context result may be displayed. Cancelled work must not restore older candidates. | Baseline; `qk06`, existing real-worker cancellation test |
| QK-07 | Insert a whole English next-word prediction between English words | Add the needed left/right word separators; do not add separators to ordinary Chinese Quick selection. | Baseline; `qk07`, existing coordinator tests |
| QK-08 | Space, Enter, or switch layout while composing Quick letters | Space/Enter preserve literal letters when autocorrect is disabled or no confident English correction exists. Enabled autocorrect follows AC-01. Layout switches remain literal. Never choose Chinese implicitly at these boundaries. | D-01 amended by D-07; `qk08`, autocorrect scenarios |
| QK-09 | Compose more than a single two-letter code | Continue phrase lookup. Six Chinese characters remain selectable; following input may commit the resolved six-character phrase. Do not commit purely because a code is long or results are missing. | Current baseline; existing `QuickInputCoordinatorTest` length/cap tests |
| QK-10 | Paste, press Record, or enter punctuation while a Quick code is unfinished | Commit literal letters first. Paste inserts copied text once and clears Quick composition, then refreshes predictions. Record transitions to voice; punctuation follows the literal letters. Chinese conversion requires explicit candidate selection at these boundaries. | Rule D-05 derived from confirmed literal policies; `qk10` |

## Other text input and editor context

| ID | Given / event sequence | Expected behavior | Status / coverage |
| --- | --- | --- | --- |
| ED-01 | Type text with autocorrect disabled, or on the number layout | Commit exact selected text without starting Quick composition or querying Quick candidates. English with autocorrect enabled follows AC-01. | Baseline with English backend disabled; `ed01` |
| ED-02 | Commit text with characters after the cursor | Preserve right-of-cursor text and query predictions using both sides of the cursor. | Baseline; `ed02`, existing context test |
| ED-03 | Enter in an editor with a configured action | Execute the editor action; otherwise insert newline. Custom action takes precedence. | Baseline; `EditorActionResolverTest`, `ImeEditorTest`; OS integration gap |
| ED-04 | Backspace with an editor selection | Delete the selection; otherwise delete the preceding character. | Baseline; `ImeEditorTest` decision helper; real InputConnection integration gap |

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
| VO-02 | Text input while recording/transcribing | Interrupt voice before inserting the user's text. Preserve previously committed editor text. | Baseline; `vo02`; real voice callback invalidation integration gap |
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
| UX-01 | Too many candidates for toolbar width | Page in original order; arrows consume space only when needed; no omitted or duplicated candidate indices. | Baseline; `ux01`, existing first/middle/last-page screenshots |
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

Implemented under the user-approved 2026-10-13 plan; independent English and Quick
preferences default on. Existing legacy scenarios without an English backend verify
literal fallback. `EnglishAutocorrectScenarioTest` exercises the production controller
with fixed English model results; Rust tests verify the actual search and confidence gate.

| ID | Sequence / condition | Expected behavior | Coverage |
|---|---|---|---|
| AC-01 | Type `teh`, then Space, punctuation, or newline on English/Quick | Compose the word; show literal input plus spelling alternatives; commit `the` and the exact boundary when the current result is confident. Pending/failed searches commit literally. | `boundaryCorrectionAndUndoOnBothLayouts`, `staleLookupAndNativeFailureNeverRewriteText` |
| AC-02 | Immediate Backspace after correction | Remove delimiter and restore original composition; recommit without repeating the correction. Guard undo against cursor/selection changes. | boundary and selection scenarios |
| AC-03 | Type English prefixes in Quick beyond six letters | Protect plausible English from automatic Chinese phrase conversion; preserve conversion for non-English sequences. Chinese candidate order remains intact and selection stays explicit. Exact one/two-root codes remain literal unless tapped. | Quick conversion and selection scenarios |
| AC-04 | Switch to symbols and return, or enter punctuation there | Keep originating English/Quick word ownership. Punctuation resolves the word; digits and identifiers finish literally. | `numberPanelPreservesWordForPunctuationButDigitsStayLiteral` |
| AC-05 | Paste, Record, emoji, layout switch, keyboard hide | Finish literal composition exactly once and cancel pending English results. | lifecycle scenarios |
| AC-06 | Password, number, URL/email editor, no-suggestions, disabled layout preference | No English correction. Preferences are independent for English and Quick. | policy and preference scenarios |
| AC-07 | Tap letters, uppercase, resize, rollover or canceled key | Use drawn letter centers in key-width units. Evidence accompanies dispatched characters only; missing geometry falls back to adjacency. Preserve case; unsupported tokens stay literal. | geometry/input scenarios; touch integration tests |

Rust uses the pinned wmkeyboard English seed dictionary, FuzzyBeamSearch penalties,
and Gaussian TouchModel. A packed trie and reusable search workspace limit mobile
memory/CPU. Existing bilingual NN context scores rerank only dictionary candidates;
known words, completions, expensive edits and ambiguous candidates are not silently
replaced. Actual device latency and InputConnection integration require a connected
Android device; none was connected during this implementation.

## Product decisions

Confirmed by the user on 2026-10-13:

- **D-01**: Originally literal letters for Quick Space, Enter, and layout switching; D-07 permits confident English correction on Space/Enter while keeping layout switches literal.
- **D-02**: Show the clipboard chip again when new text is copied after typing.
- **D-03**: Backspace cancels voice and deletes a character together. Direct controller
  calls and real key down/up must have the same behavior.
- **D-04**: Closing the keyboard or moving focus commits unfinished Quick input literally.

- **D-06**: Rich clipboard contents use their plain-text version.
- **D-07**: Enable conservative English autocorrect on English and Quick, using the wmkeyboard reference in Rust with touch evidence and the existing bilingual NN. Correct at Space/punctuation/Enter; allow Backspace undo. No implicit Chinese selection. Settings-screen redesign is deferred.

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

New acceptance tests live in `KeyboardQaContractTest.kt`; names start with the IDs above.
Existing scenario/coordinator/touch tests supply the coverage explicitly named in the tables.
A matrix test reports the failing combination in its assertion message.

Run `./gradlew :app:verifyKeyboard`: unit contracts, all screenshot comparisons, debug build.
Create intentional new baselines separately, inspect them, then run validation. Pending
product decisions and integration gaps remain visible even when this command is green.
Do not update a reference image merely to make a failure pass.
