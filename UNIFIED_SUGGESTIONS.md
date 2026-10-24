# Unified keyboard suggestions

The production keyboard now owns one cancellable composing request for Quick and
English. Quick composition bookkeeping remains in `QuickInputCoordinator`;
`EnglishInputCoordinator` owns the shared request so a word boundary can await
its correction decision. `NativeSharedSuggestions` performs one JNI request and
returns Chinese candidates, English correction evidence and the final display row.
The controller no longer combines independently arriving production candidate rows.

The shared CNN already produces scores for its full vocabulary. Code matching
filters that score table, then orders the matches; sorting only matches gives
the same best N without sorting unrelated tokens. Short Quick codes preserve
characters missing from the single-token vocabulary as a dictionary-frequency
tail. Complete two-letter codes keep all Chinese matches ahead of English.
English retains its literal candidate, spelling-neighborhood eligibility,
completion scoring and fitted correction/apostrophe policies.

Longer Quick input still needs phrase search: the probability of a continuation
changes after each character. Each retained prefix filters its full distribution
before choosing the best segment transitions. This removes the previous initial
32-item dictionary-frequency cutoff. Join the resulting phrase pool with English
and apply final bilingual ranking once. This is one native request, not a claim
that a multi-token phrase can be scored with one neural forward pass.

A request-local score cache pins the initial context and bounds distribution
storage to 8 MiB. Conditional tables are reused for English decisions, phrase
ranking and language evidence; eviction may recompute later tables. Completed,
failed and cancelled requests release their cached tables. No second LM weights,
new dictionary, fitted coefficients or ranking exceptions are introduced.

## Cancellation and presentation

New input/context cancels the current coroutine and its native request token.
Native cancellation uses a separate registry, without acquiring an inference lock.
Checks occur between CNN token steps/distributions, lexical enumeration and phrase
expansions. Interrupting a CNN projection clears partial recurrent buffers before
the next request. Queued work also releases its token even if its body never starts.

Both the controller request generation and Quick's code/version request generation
must still match before publication. Keep the previous completed row visible
while computing a replacement, reject taps against an obsolete row, then publish
the replacement once. Session/layout changes cancel requests; repeated callbacks
with identical context reuse a valid pending/completed Quick request. Boundary
waiting still times out after 40 ms, commits literally and cancels inference.
Disabled Quick correction does not wait for Chinese inference at Space.

## Verification

The final keyboard gate passes 233 unit/scenario/JNI tests, 83 screenshot checks
and debug APK assembly. The ARM64 library is rebuilt from the current native
source; its cancellation/composition exports are present in the packaged library.
The 28 native Rust unit tests pass. No tests in the keyboard gate were skipped.

- The frozen 2,042-case evaluation has exact English candidate/score/correction
  parity, preserving F₀.₅ 0.82154 (418 correct, 32 wrong, 8 clean changes).
- The short-code panel retains identical candidate sets. Completed native request
  median/p95: 145.253/480.650 → 1.748/3.802 ms, 112 paired requests. `df` performs
  one full-distribution computation, shared by Chinese and English.
- The phrase panel median/p95: 117.297/362.695 → 86.510/336.234 ms, 96 pairs.
  `humo` after `I use ` replaces tail candidates `先歌`, `禿一人` with `税一人`,
  `税兩` on both versions; the top eight in those cases are unchanged. Other
  phrase candidate sets in this panel are identical. This is a bounded development
  panel, not a broad Cantonese quality qualification.
- Regression scenarios cover rapid typing, stale taps, cancellation before and
  during lookup/native inference, context changes, session hide, layout round-trip,
  boundary timeout/following-key order, disabled correction, spacing, correction
  and undo on both layouts. The retained-row screenshot is visually inspected.

The timing panel measures native work to a completed row. The former pipeline
also displayed an earlier Chinese preview; these results do not measure that
first-paint boundary or Android input-to-display latency. Phone latency and actual
IME rendering remain unmeasured for this change. No device installation is implied.

Reproduce with the current shared assets and native source:

```sh
CARGO_TARGET_DIR=/private/tmp/unified-candidates-native cargo build \
  --manifest-path ../QwenASR/crates/cantonese-ngram/Cargo.toml \
  --release --features android --bin suggestion_probe --lib
python3 scripts/verify_unified_suggestions.py \
  --probe /private/tmp/unified-candidates-native/release/suggestion_probe \
  --output config/pipeline_audit/2026-10-22/unified-suggestions.json
./gradlew :app:verifyKeyboard \
  -PenglishNativeLibrary=/private/tmp/unified-candidates-native/release/libcantonese_engine.dylib
```

The report binds the model and probe hashes. Rebuild the ARM64 library using the
LM build section in `deploy.sh` before APK assembly; host JNI tests do not verify
an older packaged `.so`.

## Immediate Space correction (2026-10-23)

The user reports that correction requires a pause after the last letter.
The composing request collects Chinese candidates and ranks the bilingual row
before returning English evidence. The boundary's existing 40 ms wait therefore
includes unrelated work; on expiry it cancels inference and commits literally.
Word-decision F₀.₅ does not exercise this application deadline.

When Space encounters an unfinished joined request, cancel it and request only
the English decision on a separate worker and the existing independent native
English inference state. Completed joined decisions are reused. The new JNI
entry point participates in native request cancellation. Model weights, spelling
scores, acceptance policy, the 40 ms deadline, two-letter Chinese priority, Undo,
and rejection of stale post-boundary results are preserved.

The exact sentence `do you kniw whay you are talking about` corrects to
`do you know what you are talking about` through real JNI and the production
controller on English and both Quick versions. Every key and Space is sent
without advancing the test scheduler, with the composing worker occupied.
Separate regressions cover following-key ordering, immediate Undo/literal
recommit, and independent-boundary timeout with late-result rejection. Native
boundary candidates and correction decisions match the shared English scorer
on the existing context/word panel.

Verification: 239 unit/scenario tests, with no failures or skips, screenshot
validation and debug assembly pass through `:app:verifyKeyboard`. Host and ARM64
libraries were rebuilt; the APK contains the new boundary JNI export. No physical
device is connected, so this is controller/native verification, not measured
phone input-to-display latency or an installed-phone fix. English inference
itself can still exceed its unchanged deadline.

## Bilingual preview ranking and continuous composition (2026-10-24)

The former preview computed English candidates but concatenated Chinese first;
only full inference applied mixed ranking. Both stages now share the same row
assembly and bilingual sequence scorer, with the existing two-letter Chinese
priority. Chinese generation still differs: the preview uses a greedy path and
full inference searches phrase beams, so later refinement remains possible.
No spelling/acceptance weights changed.

Remove the six-character cap, twelve-key lookup truncation, and length-triggered
automatic Chinese commit. Beam bookkeeping grows with input length; existing
probability pruning, per-character-count beam widths and cancellation remain.
Selection consumes the complete decoded composition. Fetch enough editor text
to strip the entire composition before extracting left context, including inputs
longer than the former 512-character context window.

The frozen objective scores **0.854569**, baseline delta **0.000000**, using fresh
before/after native binaries and the fixed v4 suite. Report:
`build/autocorrect-eval-v4/bilingual-preview-20261024/score.json`.
Host timing panel (3 repetitions, Quick3/Quick5, median milliseconds):

| Input | Previous preview | Ranked preview | Previous full | Current full |
| --- | ---: | ---: | ---: | ---: |
| problem | 7.6 | 7.2 | 294.3 | 295.8 |
| hion | 3.2 | 7.6 | 43.9 | 43.0 |
| humomomo | 9.9 | 52.8 | 495.2 | 495.7 |
| hionhionhionhion | 7.4 | 24.6 | 346.8 | 503.3 |

The last row previously decoded only twelve keys; current inference decodes all
sixteen. The timing panel is observational and subject to host cache/scheduling
noise. Raw queries/results: `build/bilingual-preview-latency.json`. These are native
host timings, not Android input-to-display measurements.

Fresh host JNI checks verify `problem` ranks first in both stages for both Quick
versions, Chinese stays in the candidate pool, and eight-character Chinese
selection consumes all sixteen keys. Native decoder tests cover root-only
compositions up to 24 characters. Scenario tests cover retained composition,
explicit selection, supersession and left-context extraction beyond 512 keys.
The two added screenshot fixtures were rendered and visually inspected. The
Android ARM64 engine was rebuilt and copied into the assembled APK. No device
is connected; no installation or device latency verification occurred.

The full native-enabled gate exposed an existing working-tree characterization
mismatch: `actualSharedNativeModelCompletesEnglishAndRanksQuick` expects
`exmaole -> example` automatically, while both saved pre-change and current
probes return no correction, with identical candidates/scores. This work preserves
that policy and the existing test expectation; it does not claim that broader
native suite passes.

Final verification: the standard `:app:verifyKeyboard` gate passes, including
unit/scenario checks, screenshot validation and debug assembly. The 65 affected
scenario/coordinator tests also pass with fresh host JNI enabled (zero skips),
and six native Quick decoder tests pass. The broader native-enabled suite has
the baseline mismatch documented above. APK inspection confirms it contains
the rebuilt/staged ARM64 engine.

## Exact latency optimization (2026-10-24 follow-up)

The full row was too slow (about 270–473 ms in the measured empty-context cases).
Reuse eight exact causal CNN prefix states when candidate branches diverge.
Retain normalized 128-float output projections plus their exact softmax normalizer,
so repeated target sets avoid full-vocabulary projection and normalization. Read
only needed token scores from full cached distributions. A bounded 128-entry
worker-local projection cache also reuses stable prefixes across keystrokes.
No candidates, beams, model weights or ranking features are removed or changed.

Additional bounded storage: about 1.46 MiB of causal states and 0.1 MiB of compact
output evidence per model instance, plus about 1 MiB of compact numeric evidence
per active request (container metadata adds overhead). Existing full-distribution
cache remains capped at 8 MiB. Request caches are released on completion/error/
cancellation. Causal states match the exact BOS/window prefix, and changing BOS
clears both persistent caches.

Same-policy host benchmark: five repetitions, both Quick versions, separate
preview/full processes, model load excluded. Median milliseconds:

| Input / context | Preview before | Preview after | Full before | Full after |
| --- | ---: | ---: | ---: | ---: |
| problem / empty | 6.33 | 5.67 | 270.59 | 66.75 |
| problem / I have a | 7.13 | 6.06 | 406.60 | 62.52 |
| hion / empty | 6.88 | 2.31 | 40.30 | 17.64 |
| hion / 今日返工之後 | 13.13 | 2.91 | 56.23 | 13.56 |
| humomomo / empty | 49.84 | 8.14 | 455.83 | 229.84 |
| hionhionhionhion / empty | 23.05 | 8.68 | 473.06 | 233.73 |
| hion / long English context | 16.30 | 3.80 | 246.36 | 23.11 |
| exmaole / this is an | 20.37 | 7.28 | 227.03 | 57.71 |

For consecutive-key preview requests, `problem` median improves 5.60 → 2.94 ms
(P95 11.49 → 5.66), `humomomo` 12.29 → 4.27 ms (P95 49.84 → 36.28), and
sixteen-key Cantonese 19.65 → 7.41 ms (P95 28.14 → 14.33). These include cold
and reused prefixes. Cold complex previews can still take about 48 ms; full
phrase refinement can still take around 234 ms. Host results do not measure
phone scheduling, simultaneous workers, rendering, or Android end-to-end latency.

All **540 timing queries** match exactly, including candidate scores, display
ordering and correction decisions. Raw inputs/results, binary/model/policy hashes:
`build/suggestion-latency-20261024/policy-matched.json`.
Reproduce the panel using `scripts/benchmark_suggestion_latency.py --baseline
/path/to/baseline/suggestion_probe --candidate /path/to/current/suggestion_probe
--output /path/to/report.json` from this repository.

The frozen objective is **0.850874**, matching the same-policy baseline exactly:
**delta 0.000000**, all **5,261 outputs identical** (excluding timing).
`build/autocorrect-eval-v4/suggestion-latency-policy-matched-20261024/score.json`.
During this run `cnn_spelling.rs` changed independently. Against the earlier
saved executable, the score changed from 0.854569 and nine acceptance decisions
differed despite all candidate scores staying identical. To isolate latency,
build a source copy with the current acceptance implementation and only these
latency optimizations disabled. Its results match the optimized build exactly.
The baseline copy is `/private/tmp/keyboard-latency-policy-matched-baseline`;
the disabling patch is saved alongside the timing report. These latency changes
do not alter the concurrent acceptance edit or claim credit for its score.

Verification: 32 native Rust tests pass, including real-model bitwise comparisons
across branches, cache eviction, shifted context windows, BOS changes and
cancellation. All 66 affected scenario/coordinator tests pass with fresh host JNI.
The standard `:app:verifyKeyboard` gate passes (230 unit/scenario passes, 18 opt-in
skips, screenshot validation and assembly). The previous broader native-suite
`exmaole` characterization mismatch remains outside this change.
The optimized Android ARM64 library was rebuilt and its staged/packaged hashes
verified. No device deployment or phone latency measurement is claimed.

The earlier removal of composition limits also missed a 128-key JNI guard.
Remove that guard for both joined preview and full composition; native regression
coverage confirms both Quick versions accept a 129-key input without throwing.

## Cold inference kernel (2026-10-24 follow-up)

Optimize the uncached CNN math rather than warming inference state. A four-row
ARM NEON matrix-vector kernel shares activation loads across output rows while
preserving the former dot product's two accumulators, FMA order, reduction and
scalar-tail order. Apply scale/bias in place. Full-vocabulary scoring uses that
kernel directly without allocating or validating a vector of 65,536 target IDs.
The INT8 representation and existing bounded caches remain in use.

`cnn_latency_probe` decomposed fresh-context processing and vocabulary scoring;
context processing improves about 20–24% in the measured contexts. Primary
verification uses `scripts/benchmark_cold_suggestions.py`: a new process and model
inference state for **every** request, three repetitions, both Quick versions.
Process/model loading is outside the native timer, so this is first uncached
inference after load, not total application startup time. Median host milliseconds:

| Input / context | Cold preview before | Cold preview after | Cold full before | Cold full after |
| --- | ---: | ---: | ---: | ---: |
| problem / empty | 8.18 | 7.36 | 126.98 | 101.64 |
| problem / I have a | 8.87 | 7.90 | 116.53 | 94.37 |
| hion / empty | 8.29 | 7.08 | 34.21 | 28.51 |
| hion / 今日返工之後 | 11.44 | 9.53 | 27.33 | 22.25 |
| humomomo / empty | 48.38 | 40.26 | 211.70 | 171.72 |
| hionhionhionhion / empty | 22.57 | 18.43 | 224.90 | 179.38 |
| hion / long English context | 11.29 | 9.30 | 43.78 | 35.63 |
| exmaole / this is an | 18.81 | 16.06 | 91.55 | 74.44 |

The slow Cantonese preview improves 16.8%; long-phrase full ranking improves
20.2%. Cold complex previews can still exceed a display frame. No phone latency
or model-loading improvement is inferred from these Mac measurements.

All **96 cold requests** have identical outputs, and all **5,261 frozen-suite
outputs** match excluding timing. Aggregate objective **0.850874**, baseline
**delta 0.000000**. No policy/source mismatch occurred during this comparison.
Timing inputs/results, binary/model/policy hashes and isolated kernel patch:
`build/suggestion-cold-latency-20261024/tiled-kernel.json`.
Objective: `build/autocorrect-eval-v4/cold-kernel-20261024/score.json`.
Baseline executables/source snapshots: `/private/tmp/keyboard-cold-baseline-20261024`.
Phase diagnostics are saved alongside the cold timing report.

33 native Rust tests pass, including bitwise kernel comparisons for signed INT8
weights, row counts crossing tile boundaries, scalar tails and actual model matrix
widths. The real-model prefix/eviction/BOS/cancellation checks also pass. All 66
affected scenario/coordinator tests pass with fresh host JNI, and the standard
`:app:verifyKeyboard` gate passes (unit/scenario, screenshots and APK assembly).
The rebuilt/staged/packaged ARM64 engine hashes were checked. The earlier broader
native English characterization mismatch remains outside this optimization.
This update is built locally; no device installation is implied.

### 2026-10-24: Generate before ranking; indexed spelling lookup

Both composing stages now enumerate candidates before neural scoring. English
uses one compact trie shared with the model weights: prefix lookup descends directly
to the matching subtree; typo lookup shares the existing optimal-string-alignment
DP rows across prefixes and prunes unreachable branches. The same index handles
one-edit neighbors of split words. Vocabulary indices restore the original lexical
order, including completion-over-edit precedence, so pool membership and tie order
remain unchanged. The trie is built once per shared weight bundle.

Quick generates an O(key-count) dictionary lattice of every legal one/two-key edge;
edges reference the existing full dictionary lists. Ranking traverses this compact
representation with the original greedy preview / beam rules. A flat Cartesian
phrase pool prototype regressed recall and was rejected, rather than reducing the
candidate pool to achieve speed. No composition-length cap is reintroduced.

`generation_complete` / `generation_forwards` counters verify **zero inference
before both pools are generated**. Ranking still needs multiple conditional model
contexts: one shared model does not mean one forward pass. For cold `problem`
with empty context, preview normalizes 4 contexts and full ranking 87; the latter
includes 80 contexts scored in 20 four-context output batches. Batched INT8 output
projection shares weight conversion across four contexts and preserves the scalar
FMA/reduction order exactly. Initial/full normalizers, bounded caches and cancellation
remain in place. Temporary batch output storage is bounded to four tables.

A concurrent acceptance-policy update and rebuild changed the shared target binaries
during validation. Final checks use immutable source snapshots and separate target
directories under `/private/tmp/keyboard-candidate-first-isolated`, recorded in
`build/candidate-first-20261024/manifest.json`. The original binary was retained;
its embedded JSON policy was recovered for an identical-policy comparison without
changing the working acceptance file. That comparison has **5,261 exact frozen
outputs, 768 exact full outputs and 768 exact previews**, excluding timing/counters;
objective **0.856864, delta 0.000000**.

With the final working policy, the original Quick/suggestion pipeline was rebuilt
from the saved pre-change files and serial sequence scorer, with the same final
policy/core weights as the candidate. It also has **5,261 exact frozen outputs and
768 exact full outputs**. Final aggregate **0.861578470789**, baseline
**0.861578470789**, **delta 0.000000**. The higher policy score belongs to the
separate acceptance update. No eval cases or weights were changed. Reports:
`build/autocorrect-eval-v4/candidate-first-current-policy-20261024/score.json`,
`build/candidate-first-20261024/current-policy-full-quality.json`, and
`isolated-{full,preview}-quality.json`. `scripts/verify_candidate_first.py` fails
on any output difference or nonzero inference during generation.

Cold timing below uses the original policy on both sides, a fresh process/model
state for every request, three repetitions, both Quick versions. These are Mac
native medians in milliseconds after model loading; they exclude application
startup and do not establish phone key-to-frame latency. Measurements ran after
builds/evaluation stopped. All **96 outputs match exactly**.

| Input / context | Preview before | Preview after | Full before | Full after |
| --- | ---: | ---: | ---: | ---: |
| problem / empty | 8.29 | 6.28 | 108.21 | 90.59 |
| problem / I have a  | 8.42 | 6.70 | 99.92 | 79.45 |
| hion / empty | 7.93 | 6.96 | 30.11 | 24.51 |
| hion / 今日返工之後 | 10.65 | 9.82 | 24.27 | 19.80 |
| humomomo / empty | 42.89 | 30.76 | 180.42 | 145.18 |
| hionhionhionhion / empty | 19.85 | 17.60 | 189.60 | 161.07 |
| hion / long English context | 10.29 | 8.92 | 38.12 | 30.03 |
| exmaole / this is an  | 17.50 | 12.68 | 79.53 | 63.60 |

Timing evidence: `build/candidate-first-20261024/isolated-cold.json`.
A separate warm/typing run has 324 exact outputs (`warm-trie.json`); long-phrase
steady preview median varied upward 6.92 to 7.34 ms, while its typing median
improved 6.36 to 4.38 ms and p95 12.10 to 8.36 ms. Complex previews/full rankings
can still exceed one frame; candidate generation does not remove conditional
phrase-scoring cost.

Verification: **37/37 Rust tests pass** with the final policy, including exhaustive
trie-vs-linear OSA/prefix search, real-vocabulary pool parity, bitwise batch/scalar
scoring, checkpoint/cache eviction and cancellation. **84/85 opt-in JNI scenarios
pass**. The existing `actualSharedNativeModelCompletesEnglishAndRanksQuick`
assertion expects automatic `exmaole -> example`; both baseline and candidate
abstain with the final policy. Baseline/candidate evidence and the original failure
XML are saved in `build/candidate-first-20261024/checks/`; this assertion/policy was
not changed by the optimization.

The required `:app:verifyKeyboard` gate **passes**, including standard unit/scenario,
screenshot verification and APK assembly. The final ARM64 engine was built from
the isolated current source, staged, and verified against the packaged stripped
engine (`checks/packaged-engine.json`). Source snapshots match the working Rust
source. Built locally; no device installation or phone latency measurement.
