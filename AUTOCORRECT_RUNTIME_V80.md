# Local internal autocorrect update, 2026-10-16

The user authorized removing blanket short/valid-word blocks, optimizing native
inference, assessing lower confidence thresholds and using more following words.
These changes are integrated locally. **No Play upload or physical-phone install.**
The complete reported typo sentences still fail acceptance; production approval
remains false. This is an internal trial, not a completed accuracy fix.

## Runtime changes

* The new contextual profile has finite enabled thresholds for both short and
  familiar words. SCOWL membership no longer categorically vetoes its decisions.
  Familiarity includes dictionary evidence and exact single-token LM support;
  familiar words have a higher threshold, rather than unconditional protection.
* Quick's separate one/two-letter Chinese-code overlap veto no longer blocks a
  confident English decision from this profile. Uncertain input remains literal,
  and explicit Chinese candidate selection still wins. Older profiles retain
  their previous behavior. A controller test exercises all four combinations.
* Keep five recent words and reconsider an unresolved word after up to four
  following words, extending the old two-word window. This includes familiar
  words when the profile permits it. Editor/cursor/lifecycle guards and suffix-
  preserving Undo remain. The existing confidence feature still clamps its word
  count to2 for compatibility; additional actual context enters the native LM.
  The LM has four right-context encoded-token slots, so this is not an unlimited
  context model and byte-fallback words can consume multiple slots.
* Revision uses its policy's required confidence instead of a second hardcoded
  99.9% check. Older outputs default to the old requirement. Positive new
  contextual evidence remains necessary.

## Native latency

The8ms limit belongs to candidate generation. The integrated profile uses the
complete bounded spelling-trie search and corrected SCOWL candidate vocabulary,
replacing the older heap search. Preparation of70,861 TRAIN/calibration requests
records p95 generation2.183ms and one budget exhaustion; the8ms safety ceiling was
not raised. Known-word alternatives are filtered before the bounded32-way LM
pool; the exact literal always remains eligible.

The LM now groups requested tokens by identical teacher-forced context. Each
distinct context is evaluated once, replacing a16-entry cache that repeatedly
discarded shared prefixes. Only one full-vocabulary distribution is live at a
time; metadata is bounded by128 candidates ×64 tokens. Original f32 summation
order is preserved and output-bias lookup is moved outside the row loop.

On95 paired warm native requests per model with identical unpruned candidate
pools, v22 runs1.62× faster (p95 106.70→56.89ms); the newer combined model runs
1.58× faster (p95 117.10→65.86ms). Every output score is identical, including the
English spelling head. These deliberately broader pools differ from the app's
pruned pool and are host measurements, not Android/phone latency claims. An
additional search-banding trial was slightly slower on2,025 cases, so it was
rejected; all completed candidate/score lists matched.

## Confidence and models

The staged typing model now contains own English-continuation model69 plus own
English spelling head73, packed together as75. The Cantonese Quick/handwriting
production model stays unchanged. No Chinese conversion. Dictionary77 restores
ordinary source-affix forms such as `is`; it does not invent suffixes.

The new confidence fit includes valid-word clean/error examples previously
excluded from fitting. Rank and confidence use disjoint TRAIN groups; probability
calibration uses participant/document-held-out folds and source/trial weights.
Thresholds are selected from a fixed grid on calibration, with wrong-correction
costs10/20, minimum precision95/98% and clean-false-change cap0.5%. User messages
never fit the model or thresholds. Full training/policy protocols remain on SSD.

At the fitted boundary threshold0.85, unfamiliar spelling proposals yield
2,086/2,128 correct accepted changes (98.03%); the99% threshold yields very few
changes. Familiar-word threshold0.975 yields17/17 accepted changes, with no
changes to5,010 clean cases. These are development calibration results, not a
production precision guarantee. Some short-word strata have little/no clean
support; their eligibility does not establish reliable correction coverage.

An explicit consensus path preserves established conservative boundary fixes
when the legacy engine and new ranker agree, the candidate outranks the literal,
the search completed and the input is not a validated spelling. This prevents
new confidence fitting from disabling previously working clear corrections.
It does not bypass model/dictionary hashes, malformed evidence, user rejection or
valid-word checks in the legacy path. **The calibration figures above describe
the learned path, not a claimed joint precision for this consensus path.**

All new policies remain `production_approved=false` and require an enabled
internal build. `config/typing_internal/profile.json` binds the model, dictionary,
validity and provenance sources; `stage_internal_typing.py` reproduces the bundle
and counts models, dictionaries, policies, labels, notices and manifest bytes.
Current total is28,147,484 bytes, below30,000,000.

## Verification and remaining failures

The integrated bundle passes43 core English/Cantonese/mixed host E2E output
checks, including normal/rapid keys, center touches, Undo, unknown names, valid
words and complete Traditional Cantonese phrase selection. The strict pipeline
continues to report `accepted=false`: all14 complete user-sentence regressions
still fail. Some words improve (`examplwa → examples`, for example); the complete
sentences do not meet expectations. These failures must not be hidden by a green
mechanism test suite or by saying merely that the categories are now enabled.

ARM64 emulator checks cover packaged model/dictionary/policy inference, actual
MotionEvents and real Android InputConnection output/Undo, including Traditional
Cantonese `我哋去食飯` selection. These are not taps through the actual IME service
on a physical phone. Source/calibration/runtime reports are archived under SSD
`experiments/typing-v80-integration`, `word-policy-v79`, `word-policy-v80` and
`inference-v79`. The prior staged assets are preserved for rollback.

Final verification:203 unit/scenario tests with no skips, full screenshot/debug
build gate,32 native tests,5 acceptance-parser tests and13 ARM64 emulator tests
pass. Whole-sentence correction acceptance remains failing as described above.
