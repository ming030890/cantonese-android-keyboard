Historical v11 control; current selection is [v12](LANGUAGE_MODEL_V12.md).

# Compact keyboard language model v11

The selected model is one token-input feed-forward neural network for empty
context (unigram), one preceding token (bigram), and longer left/right context.
It contains no per-context embedding table, embedded parent model, or count
probability table. The separate `handwriting/lm_csr.bin` is absent from the app.
The legacy CNG1/CNG2 policies remain supported for older exports; the selected
CNG1 artifact explicitly sets `neural_short_contexts` and ignores count inputs.

Architecture: 34,081 actual tokens, token embedding128, residual state256,
two residual blocks with FFN expansion512, and a tied vocabulary output table.
The vocabulary preserves existing IDs and adds missing handwriting label tokens.
The paper's numbers were reference hypotheses; no48k vocabulary padding is used.
The forced48k vocabulary preparation is an unused historical artifact on SSD.

Export: **5,860,722 bytes (5.86 MB)**, below the hard10,000,000-byte limit.
SHA256: `38f7b0f066e218e4d1e1703c02f070152006e1f0020f796d9e84e2c5efdaf0ca`.
Canonical model/checkpoint/vocabulary and audits:
`/Volumes/SSD/cantonese_ngram/experiments/dense-short-context-v11-small-complete/audit-final`.
Workspace pointer: `../dataset/cantonese-ngram/SELECTED_DENSE_MODEL_V11.json`.

## Short experiment and full run

Two matched600-update pilots compared FFN512 and1024 with the same residual
width, embedding, teacher data, sampling, optimizer, seed, and validation rows.
Widening the FFN uses zero added residual-output columns to preserve the shared
initializer's function. The initial residual-width128 experiment was stopped
and preserved; it is not the basis of this selected model.

| Pilot metric at600updates | FFN512 | FFN1024 |
|---|---:|---:|
| Bigram teacher top1 |79.17%|79.45%|
| Bigram KL |0.9444|0.9390|
| Unigram KL |0.00826|0.00890|
| Export size |5.86MB|6.39MB|
| Desktop median prediction |2.35ms|2.33ms|

The learning curves and trends are essentially tied: both improve rapidly,
then flatten, with neither consistently ahead. The user chose FFN512 because
it is smaller. The earlier marginal endpoint-KL selection of1024 is superseded.
Both architectures completed10,000updates; the smaller run resumed its saved
600-update pilot with optimizer, RNG and count cursor preserved. Its selected
checkpoint is at10,000updates. Desktop timings include Python pipe overhead;
the small difference is inconclusive and is not a mobile benchmark.
Curves: `config/language_model/v11_learning_curves.png`.

## Verified results against v7

| Metric | v7 | Compact v11 |
|---|---:|---:|
| Actual Rust bigram teacher top1 |58.04%|81.07%|
| Actual Rust bigram teacher top3 |73.48%|96.85%|
| Actual Rust bigram KL |133.8260|0.6053|
| Unigram KL, matched INT8/FP32 reconstruction |2.9884|0.00280|
| Frozen text test, next-token top3 |30.65%|30.34%|
| Frozen text test, infill2 top3 |46.78%|46.07%|
| Reused handwriting TRAIN audit top1 |77.40%|78.60%|

Count metrics cover all4,988declared one-token teacher contexts and are training
memorization diagnostics, not typing accuracy. The teacher combines empirical
TRAIN conditionals and explicit lexical supports; v7's smaller vocabulary
contributes substantially to its high KL. Exact count memorization is not
achieved. The actual v11 Rust unigram KL is0.00258; FP32 reduction order explains
the small difference from the reconstruction above.

The larger development audit passed each contextual mode's CE<=+0.10 and
top3>=-1percentage-point guard. Worst frozen text-test top3 regression is
0.71percentage points. The text test was frozen before evaluation but had prior
downstream handwriting use; it is not wholly untouched. Development was repeatedly
used and public lexical-frequency overlap remains possible. Handwriting uses
500unique training audit trajectories under six contexts; the resulting3000cases
are not3000independent strokes or a fresh production test.

Actual Rust numerical audit covers the whole bigram bank and compares33contexts
with FP32 reconstruction: maximum log-probability difference0.000219 under a
declared0.0005FP32 reduction tolerance. Neural-only handwriting deployment weights
are unigram0, bigram0, neural1.25; no count model is loaded. Exact configured priors
match Python on18contexts and all12,362recognition classes with maximum error0.0.

The completed1024control has bigram teacher top1/top3 of82.20%/97.37%,
handwriting TRAIN audit top1 of78.57%, and worst text-test top3 loss of0.56pp.
The smaller selected model saves532,487bytes (8.3%); these full-run differences
are reported without using the test to tune or change the user-selected architecture.

## Integration

Quick3/Quick5 and handwriting share the existing unified `libcantonese_engine.so`.
The native library was rebuilt from the current sources. All16Rust tests and
16focused Android tests pass; a12-case actual keyboard-asset smoke check verifies
zero-, one-, and longer-context neural routing. The debug APK and full five-pack
AAB build successfully. Packaged model, configuration, dictionaries, recognizer,
and native-library hashes match, and the obsolete count asset is absent.

`deploy.sh` defaults to the selected export and
`config/language_model/handwriting_fusion_v11.json`. It removes staged counts for
neural-short-context exports and verifies the asset and package bindings.
All training and calibration artifacts are on SSD. Generated Android outputs
remain ignored. A device was not connected, so installation and mobile latency
remain unverified. The standalone APK is not a complete ASR deployment; normal
installation uses bundletool local-testing packs through `deploy.sh`.
