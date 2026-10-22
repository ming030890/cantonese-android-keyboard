# Compact keyboard language model v12

The repaired [architecture comparison](ARCHITECTURE_COMPARISON_V13.md) evaluates
all completed capacity/FFN options on the same development cases. It supports
128 embeddings as a compact choice, but does not establish three blocks as a
universal winner. Wider FFN improves count agreement and Quick top-1 with mixed
results elsewhere. This file records the shipped selection; the asset is unchanged.

The selected model uses128-dimensional token embeddings, residual width256,
three residual blocks and FFN512, with34,081actual vocabulary tokens and tied
output embeddings. It is one ordinary token-input network: empty input learns
unigrams, one preceding token learns bigrams, and longer left/right input uses
the same weights. There is no whole-context lookup, embedded parent model or
separate count-table asset. V7 is a training-only teacher.

Actual CNG1 INT8-storage export: **6,131,426bytes (6.13MB)**, under the
10,000,000-byte limit. SHA256: `01955e2824ee8597709f9061563e39e131a33327238a0609f56cb070a241858f`.
Canonical model/checkpoint/vocabulary/frozen audits:
`/Volumes/SSD/cantonese_ngram/experiments/dense-capacity-v12-full/audit-final`.
Workspace pointer: `../dataset/cantonese-ngram/SELECTED_DENSE_MODEL_V12.json`.

## Architecture choice from learning curves

Four matched1,000-update trials compared token embeddings128/224 and two/three
residual blocks, keeping FFN512, residual width256, vocabulary, data order,
optimizer, objective and seed fixed. All32checked starting logit cases match
exactly. No forced48k vocabulary is used. Embedding256was excluded because its
two-block export would be10.65MB;224fits both depths.

The curves rise sharply through400updates, then flatten. Late mean count top1
at600/800/1000is79.89%,79.84%,79.50%,79.74%for128/2,128/3,224/2,224/3.
Thus there is no decisive count-accuracy winner.128/3was chosen as a modest
capacity increase: its late top1keeps rising79.09→79.69→80.75%, and all three
late context guards pass. It adds270,704bytes; larger embeddings add about3.6MB
without a consistent accuracy lead. This single-seed trend is suggestive,
not proof of architectural superiority. Selection did not use endpoint loss
or final test. Full checkpoint selection uses count-distribution fidelity
subject to contextual retention; the selected checkpoint is at10,000updates.

See `config/language_model/v12_pilot_comparison.md`,
`config/language_model/v12_learning_curves.png`, and SSD
`experiments/dense-capacity-v12-1000/curve_comparison.json`.
The selected pilot continued with saved optimizer/RNG/count cursor to10,000total
updates. All pilot/full checkpoints and bulk data remain on external SSD.

## Frozen exported-model results

| Metric | v7 | Previous v11 (128/2/512) | Selected v12 (128/3/512) |
|---|---:|---:|---:|
| Actual Rust bigram teacher top1 |58.04%|81.07%|81.26%|
| Actual Rust bigram teacher top3 |73.48%|96.85%|96.85%|
| Text next-word top3 |30.65%|30.34%|30.37%|
| Text infill2top3 |46.78%|46.07%|46.29%|
| Worst contextual text top3 loss vs v7 |—|0.71pp|0.49pp|
| Reused handwriting TRAIN audit top1 |77.40%|78.60%|78.53%|
| Export MB |4.98|5.86|6.13|

Full-run gains are modest: contextual worst loss improves from0.71to0.49pp,
count top1improves0.18pp, and handwriting top1falls0.07pp relative to v11.
There is no claim of a broad or statistically established improvement.
Bigram teacher diagnostics cover all4,988declared TRAIN/lexical one-token
contexts. They measure approximate count memorization, not typing accuracy;
exact probability memorization is not achieved. Actual Rust unigram KL is
0.002337, bigram KL0.583965. V7's smaller vocabulary
strongly affects its count-distribution KL, so that comparison is not a clean
capacity-only measure.

Context validation passes CE<=+0.10 and top3>=−1pp for every mode using1000rows
per source. The text test uses2000rows/source, macro-averaged over five sources.
Architecture and checkpoint were frozen before this test; no test-based tuning
was performed. The shared text test had prior downstream handwriting use, and
public lexical-frequency overlap is possible; it is not wholly untouched.
Development was repeatedly used. Handwriting calibration uses only cached TRAIN
text/trajectories, with weights frozen before a writer/document-disjoint training
audit:500unique audit strokes across six conditions produce3000cases, not3000
independent strokes or a fresh production benchmark.

The actual release Rust runtime covers the full count bank and33FP32 parity
contexts. Max log-probability discrepancy:0.000226,
under the0.0005FP32 reduction tolerance. All eligible vocabulary outputs are checked for empty
input. Handwriting priors match exactly on18contexts×12,362classes with no count
table supplied. Frozen fusion weights are unigram0/bigram0/neural1.25.
An exact−1pp guard boundary now tolerates1e−12arithmetic roundoff; a meaningful
larger regression still fails. The8000-step full report hit this rounding edge,
but9000/10000pass without it and have better count fidelity, so selection is
unaffected.

## Keyboard integration

Quick and handwriting use the shared existing `libcantonese_engine.so`, whose
generic runtime supports the selected three-block configuration. Actual packaged
asset smoke tests cover12zero/one/right/long-context Quick3/Quick5/prediction
cases; every case uses the same neural model. The legacy count asset is absent.
`deploy.sh` stages the canonical v12model and
`config/language_model/handwriting_fusion_v12.json`; model/calibration hashes bind.
The debug APK and complete five-pack AAB are rebuilt and package-verified;
16focused Android tests pass. No device is connected, so device installation
and mobile latency are unverified. Normal ASR deployment uses the full bundle
with local-testing asset packs rather than the standalone APK.

## Follow-up diagnostics: small gains and latency

The four trials do not establish which bottleneck dominates. The current output
projects every context to128dimensions and scores through tied token embeddings;
an extra residual block does not widen that output space.224embeddings were
screened for1000updates only, so a later full-run advantage is not ruled out.
All setups used the same fixed learning rate and objective, without per-setup
hyperparameter tuning or gradient-conflict measurements. The longer-context
teacher KL coefficient is8, versus2for bigram CE and0.5for natural CE. Those
coefficients do not establish gradient dominance. Exact empty/one-left-token
inputs are excluded from preservation sampling, but shared weights can still
trade count fidelity against contextual retention. The late probability-fit
curves are still descending; the runs do not prove complete convergence.

The fixed frozen checkpoint has FP32 bigram top1of81.90% versus81.26% after
row-INT8 storage reconstruction: a0.64pp ranking loss, larger than the0.18pp
observed exported-model gain from adding a block. Top3is96.83% versus96.85%,
and distribution KL changes only0.58168→0.58402, so the precision effect is
primarily a small change in close top1rankings, not a large probability collapse.
This is an observed effect, not proof that quantization causes the remaining
memorization gap. The4988supervised contexts receive about257draws each on
average over10000updates; more data is not a demonstrated remedy for failure
to memorize this already-observed bank. Natural-context generalization may
still benefit from better/more varied data.

A desktop release-Rust benchmark uses6alternating-order rounds of300requests
per model/flow,60warmups, and alternating contexts to avoid projection cache hits.
It includes Python pipe/serialization overhead and does not measure Android or
whole keyboard/recognizer latency. Median two→three-block timings:
Quick a-code ranking0.087→0.106ms (+0.018ms,+21%); full-vocabulary next-word
2.369→2.382ms (+0.013ms,+0.55%); handwriting priors2.245→2.235ms (effect within
run-to-run noise). The Quick case has only two candidates. Mobile costs remain
unverified; calling the extra block definitively worthwhile was too strong.

Useful next diagnostic hypotheses are training-objective balance, optimization
convergence and quantization-aware recovery, before automatically adding capacity.
No additional training is started by this read-only diagnosis.
Detailed receipts: `config/language_model/v12_count_diagnostic.json` and
`config/language_model/v12_latency_comparison.json`.

Subsequent [paired evaluation audit](EVALUATION_SENSITIVITY_V12.md) finds no resolved
context-accuracy advantage for three versus two blocks: all nominal95%
development intervals include zero. The reported test differences are point
estimates and do not demonstrate that the third block improves quality.

Current evaluation protocol: [repaired v13](EVALUATION_V13.md). Its larger,
coverage-aware text panels and actual Quick/held-out-source handwriting tasks
replace the small aggregate benchmark as the basis for future decisions.
The model remains unchanged; improved evaluation exposes both real short-context
gains and weak English/new-token performance.
