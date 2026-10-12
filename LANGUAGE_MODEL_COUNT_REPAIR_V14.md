# Common-phrase LM repair

Release 45 ranked 麵 eighth after 雲吞, despite the exact TRAIN count distribution
putting it first. The old driver supervised only one-token count contexts and
preserved an older model that also missed the phrase. Reconstructing the saved
10,000-step sampler confirmed no exact 雲吞→麵 supervision in that run.
The tokenizer passed both input tokens correctly; the defect was supervision.

## Change

The same 128/256/two-block/512 model now learns every count context through
separate non-dropping shuffled queues for one-token, two-token, longer-prefix
and infill contexts. Supported suffix rehearsal is training supervision only.
Natural TRAIN replay and parent distillation protect contextual behavior.
No hardcoded phrase, inference count lookup, extra model or larger vocabulary
is introduced. The export remains 5,860,722 bytes, under the 10 MB budget.

A fixed cleaned screen contains 5,000 natural positions balanced across five
sources and 1,000 separate English diagnostics. Six short/long context tasks
join the fixed count/phrase checks. The overall selection index averages count
macro top-one and contextual natural top-three; it is not a typing accuracy.
Three-evaluation smoothing and five-evaluation patience stop training when a
qualifying overall improvement of 0.005 is absent. All state resumes safely.
Training stopped at 29,000 steps and retained the 24,000-step checkpoint.

## Complete validation

Count agreement uses all 136,866 TRAIN/lexical contexts. It measures memorization,
not held-out typing accuracy. Support masses include words.hk term counts and
are not claimed to be sentence frequencies.

| Count panel | Release 45 top-one | Selected top-one | Release 45 top-three | Selected top-three |
| --- | ---: | ---: | ---: | ---: |
| One preceding token | 81.07% | 83.92% | 96.85% | 97.67% |
| Two preceding tokens | 18.27% | 58.55% | 28.22% | 70.58% |
| Longer prefix | 8.63% | 69.60% | 14.72% | 76.95% |
| Infill | 12.53% | 17.77% | 20.62% | 27.92% |
| Common Han phrases (9,329) | 23.49% | 55.97% | 36.87% | 69.81% |

Actual Rust inference ranks 麵 first after 雲吞 at 15.28%, versus eighth at 1.33%.
Removing every preceding token except the last reduces common-phrase top-one
by 39.43 points, versus 8.13 points in the published model. This directly checks
useful prefix dependence. Unigram total variation improves slightly, from
0.02885 to 0.02691; actual zero-context Rust inference is verified.

The full development benchmark retains 50,000 natural positions plus separate
English, rare-Han and added-token diagnostics, actual Quick3/5 ranking, and
2,000 frozen handwriting cases. Natural full-prefix top-three is 29.53% versus
30.03%; worst contextual drop is 0.834 points. Quick top-one/top-three changes
range from -0.293 to +0.541 points. Handwriting top-one changes range from
-0.15 to +0.25 points. Fusion weights remain frozen; no test calibration fitting.

After selection, a single frozen-finalist confirmation run scores 16,794
previously unscored, document-disjoint positions. Natural full-prefix top-three
drops 0.64 points; worst contextual drop is 0.744 points. The pre-defined phrase
slice contains 1,816 positions from 1,032 document groups: bare-two-token
top-three improves from 17.95% to 21.70%, +3.74 points (paired 95% interval
+2.65 to +4.83). Full-prefix phrase gains are smaller and uncertain. Confirmation
was not used for tuning or checkpoint choice. Historical validation exposure
cannot be ruled out; handwriting is synthetic text/stroke pairing.

Nineteen Python regression checks cover coverage, scoring sensitivity, overall
selection, early stopping and resume. Actual Rust probability parity and all
complete deployment gates pass. The newer Quick engine is separately tested
to preserve the evaluated single-character candidate order.

All bulk artifacts and immutable model/evaluation freezes are on the SSD:
`experiments/count-coverage-repair-v14-support/audit-overall-final`.
Training/evaluation source and detailed objective notes are in the sibling
`dataset/cantonese-ngram/COUNT_COVERAGE_REPAIR_V14.md`.
