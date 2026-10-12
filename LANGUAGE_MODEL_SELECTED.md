# Selected keyboard language model

Release 44 selects the completed two-block model following the repaired v13
[architecture comparison](ARCHITECTURE_COMPARISON_V13.md). It uses 34,081 actual
tokens, embedding 128, residual width 256, two residual blocks and FFN width 512.
The INT8 CNG1 asset is 5,860,722 bytes. One token-input network predicts unigrams
from empty context, bigrams from one token, and longer-context predictions.
No count table or context lookup is packaged.

The committed `config/language_model/selected_model.json` binds its source,
architecture, model checksum and fusion checksum. Model SHA256:
`38f7b0f066e218e4d1e1703c02f070152006e1f0020f796d9e84e2c5efdaf0ca`.
The external SSD source is
`/Volumes/SSD/cantonese_ngram/experiments/dense-short-context-v11-small-complete/audit-final/model.cng`.
Generated assets and native libraries remain gitignored; `deploy.sh --release`
stages this model and the frozen `handwriting_fusion_v11.json`, rebuilds the
unified Rust engine, removes obsolete libraries/count assets, and verifies the
actual AAB asset/native hashes against its build inputs and selected manifest.

Handwriting retains the previously frozen neural fusion weight 1.25, with zero
count weights. Quick and handwriting load `libcantonese_engine.so`; both use
neural scores for empty/one-token context. Existing legacy-format support in the
runtime does not add extra inference tables to this asset.

On repaired development evaluation, the selected model has Quick5 one-token
context top-1/top-3 78.92%/93.40%, handwriting top-1 76.65%, and natural text
next-token top-3 30.028%. Count teacher top-1/top-3 81.07%/96.85% measures training
memorization, not typing accuracy. Extra depth/embedding size showed no consistent
application advantage. Wider FFN improved count/Quick top-1 with mixed regressions.
The user chose the smaller model. Reserved confirmation remains unscored.
