# English autocorrect: learned typing channel

The accepted local implementation improves the frozen aggregate from
**0.8736146656392934 to 0.8939210767471755** (+0.020306411107882116).
It adds 17 correct fixes and removes five wrong corrections, with clean-word
damage unchanged. This is an improvement in the measured system, not a claim
that natural English or mixed chat is solved.

| Frozen panel | Correct fixes before → after | Wrong corrections before → after | Clean changes before → after |
| --- | ---: | ---: | ---: |
| Aalto mobile | 511 → 520 | 43 → 40 | 7 → 7 |
| Phone touch | 364 → 369 | 14 → 13 | 1 → 1 |
| Authored hard | 28 → 31 | 1 → 0 | 0 → 0 |

## What the error analysis changed

The old decoder chooses a winner using LM probabilities and five fixed edit
costs, then asks its acceptance network whether to use that winner. It cannot
recover a better candidate rejected by those edit costs. Its roughly 34,000
canonical LM spellings also omit valid forms.

The new complementary decoder searches 169,575 US/UK dictionary forms. A
letter-specific probabilistic channel learns substitutions, insertions,
deletions, repeated letters and transpositions from **8,193 TRAIN word pairs**
containing 2,123 substitutions, 1,297 deletions, 533 insertions and 493 swaps.
It scores actual edit paths rather than inferring an operation from length.
For example, `tommorow → tomorrow` needs an extra and a missing repeated letter;
the old scorer treated its equal length as costly substitutions.

Each proposal combines its TRAIN unigram prior, learned edit likelihood, and
English-mass-conditioned contextual gain over the empty-context LM prior.
The unchanged input competes explicitly. Only a highly confident replacement
overrides the existing decision; otherwise the existing decoder remains active.
The chosen log-odds cutoff is 5.478294541583238. Scale and cutoff selection use
TRAIN calibration with separate source-family wrong/clean budgets.

Proposal pruning is label independent: union the best 64 unigram/channel
proposals at four fixed scales. Up to 256 participate in contextual scoring.
The current CNN distribution is reused; there is no second language model or
extra CNN inference pass. A bounded 256-input cache shares lexical work.
No word-specific exception or user-example fitting was added.

The dictionary combines the existing SCOWL forms and explicit British roots.
Existing priors are TRAIN counts; absent British roots use an explicitly
imputed empty-context LM pseudocount, with floor one. These are not measured
new corpus frequencies. Dictionary source, notices and LGPL/GPL texts are
included in the APK. Total audited prediction data, including dictionary
source/notices, remains about 19.15 MB, below the 30 MB limit.

## Independent checks and realistic replay

The source-disjoint English development panel improved **460 → 467 / 512**
correct fixes, with 15 wrong corrections and zero clean-control changes in both.
The previously unscored confirmation panel improved **449 → 454 / 512**, with
13 wrong corrections and zero clean-control changes in both. Its grouped paired
F0.5 delta interval was [0.00050, 0.00498]. These are automatically inferred
repository author edits, not spontaneous phone/chat typing.

The final isolation audit caught one frozen clean control (`This is the`) in
the author-data addition. The character fitter had used only its individual
letter counts, not its context, but it still violated exact-input isolation.
It was removed; the author-data builder now excludes the frozen suite as well
as all supplemental folds. The identical TRAIN-only calibration was rerun.
All 13,482 calibration/development/regression decisions stayed unchanged.
The final artifact was checked against the already exposed confirmation; that
recheck is explicitly **not another fresh confirmation**. See the repair report
for exact input-overlap and decision-equivalence checks. Inherited CNN/unigram
training exposure is not fully audited by this exclusion.

Closed-loop public phone replay, where earlier outputs feed later context,
improves **390 → 394 / 525** corrected typos, **11 → 10** wrong corrections and
**337 → 340 / 480** exact messages; no clean words are damaged. Static word
metrics and complete-message outcomes are different measurements. The authored
controller sessions remain at English 12/15 and mixed 6/9 per layout.

All 39 native tests and the complete `:app:verifyKeyboard` gate pass. The real
JNI regression commits and undoes both `tommorow → tomorrow` and
`deosnt → doesn't` in English, Quick3 and Quick5. The new screenshot was rendered,
inspected and verified separately. The ARM64 library and local APK were rebuilt;
asset verification checks every embedded channel resource and packaged library.

Native Mac latency is measured separately on 1,024 fixed frozen inputs, three
alternating runs per binary. See the final latency report for the actual numbers;
final measurements were median 1.42 → 2.39 ms and p95 3.43 → 6.82 ms.
These exclude model loading and UI, and are not Android device measurements.
Fresh-process startup plus model/trie initialization (warm OS file cache) rose
from median 34.1 to 103.1 ms across seven alternating runs.
Channel fitting and calibration run on this Mac's CPU. The rejected tiny neural
experiments also used only this Mac; no remote training was used.

## Remaining weaknesses

Mixed-language scoring is unchanged. Its confirmation counts are identical;
this experiment does not improve mixed recall. Real-word confusions, informal
chat forms, and withheld top-ranked candidates remain important gaps. The
reported `Today is tue → the` error also remains: a strong KEEP vote currently
cannot veto the old decoder. A TRAIN-calibrated symmetric KEEP veto was tested
and produced no development improvement, so it was not added.

Future experiments should preserve this accepted checkpoint, collect or annotate
source-disjoint chat controls with confirmed intent, and learn a reliable
bidirectional replacement/KEEP decision. The newly exposed confirmation must not
be reused as fresh evidence for another tuned model.

Reports: [accepted objective](config/english_iteration/2026-10-30/noisy-channel/accepted-score.json),
[confirmation](config/english_iteration/2026-10-30/noisy-channel/confirmation-report.json),
[isolation repair](config/english_iteration/2026-10-30/noisy-channel/repair-report.json),
[latency](config/english_iteration/2026-10-30/noisy-channel/latency-report.json).
