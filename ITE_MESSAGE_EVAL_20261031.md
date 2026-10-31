# Whole-message mobile evaluation, 2026-10-31

As agreed on 2026-10-31, this whole-message ITE panel **plus mixed
Cantonese/English script** is the primary evaluation going forward. See
[the evaluation objective](AUTOCORRECT_OBJECTIVE.md) for both panels and the
acceptance gates. The old v4 word-level aggregate remains a regression check.
Report these panels separately, preserving their distinct provenance; an English
gain cannot conceal mixed-script regression or increased clean-text harm.

We already used a restricted subset of the [ITE Typing dataset](https://zenodo.org/records/12528163)
as the old Aalto mobile panel: isolated pre-intervention words from its AC-event
table. This iteration uses the processed English participant, trial and input
snapshot logs to recover whole unassisted mobile attempts. Other keyboards'
replacement words are never ground truth. The published copy prompt supplies
the task reference.

The dataset is mobile: participants used their own Android/iPhone devices.
It is English/Finnish copy typing, not Cantonese/English spontaneous messaging.
The [official data documentation](https://github.com/aalto-speech/ite-typing-dataset/blob/main/data/README-datasets.md)
explains the processed tables and timestamp ordering. A mobile source is useful
but does not by itself make the old isolated-word sampling representative.

## Frozen design

The [manifest](config/autocorrect_eval_v5/ite/manifest.json) binds the source
protocol, selected attempts, session files and traces. Development has **421
attempts from 398 participants and 157 prompts**; confirmation has **417 attempts
from 386 participants and 162 prompts**, with zero shared actors or prompts.
Confirmation has not been run through a model in this study. Neither fold nor
its derived variants may enter training; exclude their participants and prompts
from subsequent source extraction. Known old source-AC actors and used prompt
groups are excluded. Undocumented inherited model exposure cannot be ruled out.

Selection uses input/reference word edit distance and fixed hashes, before model
inference. It does not filter for model failures, vocabulary or target availability.
Development contains 96 clean, 96 formatting-only, 96 one-word-error, 93 two-word-error
and 40 three-or-more-word-error attempts. This balances engineering coverage;
it does not estimate error prevalence in product traffic. Word edit burden is
not a count of independent physical slips.

Participants must declare no typing assistance and have no observed AC or
prediction events. Admitted trials have no recorded backspaces, AC or prediction.
Snapshots are timestamp-sorted and must form an append-only trajectory matching
the trial's final input. System edits and ambiguous traces are quarantined with
attrition counts in the protocol. A short first snapshot may contain several
characters; we preserve its text without inventing physical keystroke timing.
Published C1/Windows-1252 punctuation artifacts are repaired consistently in
snapshots, observed text and references before sampling. Intermediate v1/v2
freezes are superseded; the v2 run was stopped before quality reporting.

## Accepted-engine development baseline

English, Quick3 and Quick5 produce identical submitted text on all 421 attempts,
using the accepted native library and shared model. The
[baseline manifest](config/autocorrect_eval_v5/ite/baseline-manifest.json) binds
the three reports; these are 421 distinct attempts, not 1,263. Some share a
participant or prompt, so even distinct attempts are not fully independent:

| Input word-edit burden | Complete lexical message recovery |
| --- | ---: |
| One | 54/96 (56.3%) |
| Two | 26/93 (28.0%) |
| Three or more | 8/40 (20.0%) |
| All typo-bearing attempts | **88/229 (38.4%)** |
| Equal word counts and unchanged numbers | **80/162 (49.4%)** |

Complete lexical recovery requires all reference words in order, ignoring case
and punctuation. Exact text recovery is separate: **52/229 (22.7%)** error-bearing
attempts. Total word edit distance falls **419 → 215 (48.7% reduction)**.
Two error-bearing attempts worsen. Clean input suffers **2/96** lexical changes;
including formatting-only inputs, **3/192** lexically valid controls are damaged.

These metrics describe a different task from the old **0.893921 F₀.₅**; their
numerical difference is not a regression. The production engine was unchanged.
Even the narrower word-substitution scope remains far from complete recovery.
It is defined without consulting model output and is not a claim that all its
cases should be automatically corrected.

Some source divergences are numbers, omissions, valid words or paraphrases.
For example, a copy-task word substitution may produce a perfectly plausible
sentence. Prompt recovery is measurable but does not establish that changing
that sentence would be a safe autocorrect action. All cases stay visible;
numeric and word-count changes are flagged separately rather than hidden.

Observed clean damage includes a possessive `Its` becoming `It's` and the
surname `Somerholder` being split into `Some holder`. Unrecovered cases include
ordinary misspellings, contractions, inflections, missing spaces and valid-word
divergences. These are diagnostic findings, not training examples or estimates
of population frequencies. Candidate-generation versus ranking versus acceptance
attribution needs instrumented comparison; message output alone cannot prove
which component is responsible.

## Replay and reproduction

`ProductAutocorrectEvalTest` drives production controller/JNI with a fresh
controller, editor, coroutine scope and native inference handle per attempt.
Only immutable model weights are retained across attempts. Labels stay outside
inference. Observed text becomes per-character receiver events; scheduling settles
at word boundaries and final submit. Scoring uses the payload captured **when
submit occurs**, rather than eventual editor contents. Each replay must submit
once and finish with no pending composition. This is not a physical-phone
latency measurement or a replay of the source's timing.
Automatic capitalization is disabled in these sessions to isolate correction;
case and punctuation differences are retained in the separate exact-text metric.

Run one layout at a time (substitute `quick3` or `quick5` for `english`):

```sh
./gradlew :app:testDebugUnitTest --tests '*ProductAutocorrectEvalTest' \
  -PenglishNativeLibrary=/absolute/path/to/accepted/libcantonese_engine.dylib \
  -PproductEvalSessions="$PWD/config/autocorrect_eval_v5/ite/development-english.jsonl" \
  -PproductEvalReport="$PWD/build/ite-english-controller.json"
python3 scripts/score_ite_messages.py \
  --metadata config/autocorrect_eval_v5/ite/development.jsonl \
  --sessions config/autocorrect_eval_v5/ite/development-english.jsonl \
  --report build/ite-english-controller.json \
  --output build/ite-english-score.json
```

The three layout variants share the same attempts and must not be treated as
independent samples. Bulk public CSVs remain on the SSD; member size, CRC32 and
SHA256 are bound in the source intake. `fetch_ite_processed.py` downloads only
the three processed English ZIP members using byte ranges, and verifies them.
`build_ite_message_eval.py` constructs the freeze; the saved known-prompt registry
must be restored to `build/representative-eval-v5/known-ite-prompts.json` when
reproducing extraction. Use a new output directory, never overwrite the freeze.

The remaining qualification gap is spontaneous mobile English and mixed
Cantonese/English typing with confirmed intent, real Chinese selections, undo
and cursor edits. Authored bilingual diagnostics and copied English attempts
must retain their separate provenance. Do not call either population chat quality.

[Verification](config/autocorrect_eval_v5/ite/verification.json): the complete
keyboard gate passed (287 unit/scenario tests, zero failures or skips, screenshot
verification and debug assembly). All 16 targeted Python tests passed. Frozen
source/session hashes and the accepted model asset were verified. No production
engine or model changed in this evaluation iteration.
