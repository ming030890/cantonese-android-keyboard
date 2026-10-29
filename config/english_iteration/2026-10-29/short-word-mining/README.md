# Short-word mining and NN experiments

For the later accepted update, see the
[subsequent short-word study](../../../../SHORT_WORD_NN_V2.md). This file records
the earlier exploratory runs.

No model was promoted. Production Rust sources and acceptance weights were
restored to the starting revision. No dictionary, length or context preservation
override was retained.

The user's intended `tue` is a supplemental preservation audit, excluded from
fitting and generated corruption targets. The five contexts are empty, `on `,
`see you on `, `next `, and `today is `. These are investigation examples, not a
recording of the user's original sentence. The frozen aggregate suite and its
weights were not changed.

## Data

The miner enumerates all ASCII dictionary forms of lengths one through three:
26 single letters, 341 two-letter forms and 1,277 three-letter forms. It excludes
`tue` from the generated dataset. Short forms include uncommon abbreviations and
initials; dictionary membership supplies a weak label, not a claim about ordinary
word frequency.

Clean controls deliberately retain each form with empty context, literal-reporting
contexts such as `I typed ` and `the word is `, and up to two authentic clean
TRAIN prefixes where available. A separate pool corrupts a known intended form
by nearby-key substitution, deletion, duplication or transposition. The source
form supplies its target; the model's answer never supplies the label. Valid-word
corruptions are omitted without authentic sentence context because their intent
is ambiguous. Exact frozen evaluation triples are excluded before inference.

The intended word determines the fit/calibration/selection split before variants
are generated. These splits are disjoint within the new augmentation; the parent
NN may previously have seen the word in its original training data. This is a
synthetic diagnostic, not a natural typo-frequency sample or a fresh test.

The final inventory contains **47,666 cases**. The baseline produces:

- **818 false corrections** of intentionally preserved short forms.
- **2,126 useful corrections** of generated typos to their known source targets.

All cases, baseline outputs and the two labeled pools are in
`build/tue-preservation-2026-10-28/short-mined-contexts/`. Hashes and counts are in
[mining-report.json](mining-report.json). Keep the selection split out of fitting;
do not train only on successes and treat the resulting precision as independent
evaluation. The filtered acceptance experiment separately distinguishes generated
typos whose intended target is already the top candidate from ranking failures.

## Findings

Starting frozen aggregate: **0.857561**. Full results and deltas are in
[results.json](results.json).

| Experiment | Frozen score | Delta | `tue` preservation |
|---|---:|---:|---:|
| Starting NN | 0.857561 | — | 1/5 |
| Existing 22 inputs, expanded short-word training | 0.859466 | +0.001905 | 2/5 |
| Expanded training plus contextual LM gain | 0.860017 | +0.002456 | 4/5 |
| Same feature, preserving existing ambiguity regressions | 0.854841 | −0.002720 | 3/5 |
| Fine-tune only the existing dictionary input weights | 0.852910 | −0.004651 | 1/5 |

The first improved NN loses the existing `Let me know if yyou ` + `heve` → `have`
JNI regression. The best aggregate candidate changes ambiguous `butger` to
`burger`, violating an existing abstention scenario. It preserves `tue` with
empty context, `on `, `see you on ` and `next `, but still changes `today is tue`.
Neither candidate qualifies for promotion, despite the aggregate improvements.

The added NN feature subtracts the candidate/literal log-probability advantage at
empty context from its advantage at the current context. It attempts to separate
sentence support from baseline word frequency. Its runtime prototype caches the
empty-context distribution once per shared model/BOS mode, adding an initial LM
pass and approximately 256 KiB for the production mode. It was rejected; no
latency or memory cost was added to the production implementation.

Data-only retraining, context length, contextual gain, real-cohort threshold
calibration, and restricted input-column fine-tuning were compared. Restricting
training uses an equivalent zero-centering of the dictionary input; updating its
column leaves non-dictionary decisions unchanged, without an inference branch.
That invariant has a numerical regression test. It did not meet the aggregate
gate. Synthetic no-context corruptions that happen to be dictionary words are
another source of ambiguous supervision; an experiment excluded 368 such original
fit rows while retaining observed real typing cases.

The useful next evidence is broader realistic short-form usage and contextual
real-word errors, rather than assuming this synthetic error mix establishes an
acceptance threshold. All NN changes remain experimental.

## Reproduce

Build the current baseline native probe using the project's normal Cargo target,
then choose new output names (tools refuse to overwrite datasets):

```sh
python3 scripts/mine_short_acceptance.py \
  --probe app/build/english-host/release/ngram_probe \
  --output build/short-mining-NEW
python3 scripts/audit_tue_preservation.py \
  --probe app/build/english-host/release/ngram_probe \
  --output build/tue-audit-NEW.json
```

`scripts/experiment_clean_acceptance.py` records its inputs, hashes, training
options, calibration scheme and selection metrics in each output directory. It
never installs exported weights. Optional additional-feature models require a
matching native implementation; the rejected runtime prototype is retained only
under the local build experiment directory. The training cache used here is
`build/noisy-acceptance-20261025/experiment-v5/`. Experiments and native binaries
are under `build/tue-preservation-2026-10-28/`.

Verification: seven Python tests pass. The experimental native model passed 34
unit tests with three fixture-dependent tests ignored. The Android verification
attempt could not create a Gradle cache lock under the filesystem sandbox; it
does not establish Android QA. No production code or model changes are retained,
so no phone installation or shipping claim is made.
