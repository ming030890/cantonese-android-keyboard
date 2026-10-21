# Reversible typing preferences — exploration, 2026-10-21

Status: working offline prototype, not enabled in the keyboard. Existing ranking
and apostrophe work is committed as Android `52ff1cc` and native `c701de5`.

## Smallest useful mechanism

Keep a local selection memory beside the shared LM. Record the actual consumed
input, layout/Quick version, selected candidate and an event ID after a successful
explicit selection. Do not fine-tune the neural weights on each keystroke. The
memory is removable evidence; the LM remains the general language prior.

For example, selecting `兒歌` while composing `humo` records
`(Quick3, humo, 兒歌)`. It must not infer a preference for the prefix `h`, a
different Quick version, or every occurrence of the character `兒`. A future
context feature can distinguish different uses of the same code, but it should
be added only if replay data shows a benefit.

The prototype stores selection events in SQLite and caches aggregated counts
and the latest event for each exact layout/code/candidate. Its diagnostic policy
moves the latest explicitly selected candidate first when it is still present
in the current candidate set. This policy has no fitted constants, but is still
an unvalidated heuristic. It is deliberately outside the production source set.
Counts/recency can later feed a calibrated blend with the existing LM score.

## Learning and unlearning are different events

| Event | Proposed effect |
|---|---|
| Explicitly choose a shown candidate and commit succeeds | Add one selection event; refresh the in-memory preference. |
| Automatically show, predict or commit a word | No positive learning in the initial implementation. Avoid reinforcing the engine's own mistakes. |
| Undo/reject a verified recent selection | Remove that event only; preserve earlier valid selections. Repeated rollback is harmless. |
| Explicitly forget a learned input/candidate pair | Remove every personal event for that pair; retain the base candidate and LM knowledge. |
| Choose another candidate for the same code | Add its own event. The exploratory recency policy adapts immediately; a production blend needs replay calibration. |
| Cursor moves, editor changes, or later unrelated deletion | Invalidate the pending commit receipt; do not guess which old learning to reverse. |
| Private/password editor or no-learning flag | Neither read nor write personalized preferences. |

Deleting one character is not automatically proof that the entire learned word
was unwanted. Verify the committed span and editor/session before rolling back a
receipt. The prototype tests evidence rollback, not Android's text-undo behavior.
It does not change the current Chinese Backspace semantics.

An explicit "Forget learned preference" action should restore the general
ranking, not hide a legitimate word forever. Also provide disable/clear controls.
Time decay is a separate policy decision: do not pick a half-life or negative
penalty without chronological data. Event IDs must never be reused after forget
or clear, so a stale undo cannot remove a new selection.

## What the existing app supports and lacks

- `EnglishInputCoordinator.finish(selected=...)` already calls `selected(...)`.
  `delete()` calls `rejected(...)` when a guarded correction is undone.
- `SharedLmEnglishEngine` inherits no-op implementations of those callbacks.
  It currently persists no personal preference.
- Chinese candidate taps go through
  `KeyboardSessionController.selectCandidate` → `QuickInputCoordinator.selectCandidate`,
  which clears composing state without recording a choice. Learning only in the
  English wrapper would miss the reported Chinese case.
- Capture a selection snapshot at the controller boundary, including candidate
  request generation, actual consumed code and layout, before either path clears
  composition. Confirm the editor write before recording; avoid duplicate events
  across the English and Quick paths.
- `QuickInputCoordinator` can consume a bounded prefix and retain unconsumed
  keys. Expose the consumed code in a commit receipt; do not associate a selected
  phrase with an unrelated remaining suffix. Next-word prediction taps have no
  typed code and should initially be outside this mechanism.
- Personalization must work when autocorrection is disabled, and must not depend
  on an English candidate list being available. Apply cached evidence after both
  preview and full ranking, with the same cancellation/request guards.
- Keep disk writes off input/inference workers. Bound and compact the event
  history, handle reset while a writer is queued, and exclude this local store
  from backup/export. The prototype does not implement those production details.

Android has a dedicated
[`IME_FLAG_NO_PERSONALIZED_LEARNING`](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING).
The current correction eligibility check is not a complete learning policy;
add a separate guard when integrating. Avoid storing surrounding paragraphs or
raw touch traces for a simple input/candidate preference.

This design follows an established separation: Rime's
[memory component](https://github.com/rime/librime/blob/master/src/rime/gear/memory.cc)
records confirmed composition and supports discard/delete notifications;
[user dictionary transactions](https://github.com/rime/librime/blob/master/src/rime/dict/user_dictionary.cc)
can reverse recent learning. AOSP LatinIME also exposes
[user-history unlearning](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/master/java/src/com/android/inputmethod/latin/DictionaryFacilitatorImpl.java).
These are implementation references, not evidence that their thresholds suit
this keyboard. No third-party source code or timeout constants were copied.

## Prototype evidence

Files: `experimental/selection-memory/selection_memory.py`,
`test_selection_memory.py`, and `probe.py`. They are not compiled into the app.

Eight tests pass: exact input/layout separation, event-level rollback preserving
earlier history, reversing a later different choice, ignored automatic/failed/
unshown selections, private lookup, forget plus stale undo, absent-candidate
protection, and persistence/clear.

The native smoke check uses the real packaged model and Quick dictionaries:
`兒歌` moves from fifth (Quick3) or sixth (Quick5) to first after one experimental
selection; event rollback restores the complete original list exactly. Other
candidates remain available. This verifies mechanism, not general accuracy.

On the existing 256 frozen SSD candidate pools, 2,048 alternating cached-lookup
measurements gave 0.667 µs median / 0.834 µs p95, versus copying the baseline
list at 0.125 / 0.167 µs. This Python host microbenchmark excludes SQLite writes,
Android scheduling/rendering and cold loading. It is not phone typing latency.
[Content-bound results](config/english_iteration/2026-10-21/selection-memory-prototype.json).

```sh
python3 -m unittest discover -s experimental/selection-memory -v
python3 experimental/selection-memory/probe.py --probe /path/to/ngram_probe --output build/selection-memory-prototype.json
```

## Data required before enabling a ranking rule

The currently inspected SSD sources provide text, Quick codes, English typing
events and touch data. They do not establish this user's chronological Quick
selection/rejection history. Corpus targets are not observed candidate taps;
Aalto `AC_WORD` is another keyboard's output, not a personal preference label.
Do not turn the learn-then-repeat smoke check into an accuracy claim.

For a production comparison, replay ordered eligible events: score each candidate
list using only earlier choices; then reveal the selection/rejection and update
memory. Separate users and sessions, calibrate on TRAIN, freeze before selection
and evaluation, and retain repeated versus unseen inputs separately. Include
contradictory choices, mistaken taps, explicit forget, restart, model updates,
code changes, and absent candidates. Report top-1/top-8, mean selection position,
regressions and recovery after rejection for Chinese and English separately.

Retain the existing latency and F₀.₅ protocol. The first integration should
personalize candidate order only; never let a saved Chinese preference turn
Space into implicit Chinese selection. Autocorrection acceptance needs its own
evidence before personal preferences can affect automatic replacements. Measure
input-to-first-display and full ordering on device, with persistence queued and
rapid cancellation. The current app's F₀.₅ remains 0.79407 because this exploration
does not alter any production path.
