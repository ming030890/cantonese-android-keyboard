# Selected keyboard language model

Release 46 uses the repaired model selected at 24,000 support-phase steps.
Automatic early stopping ended training at 29,000 steps after five evaluations
without a qualifying overall improvement. Selection considers count agreement
and representative contextual typing accuracy, with phrase and regression gates.

The architecture is unchanged: 34,081 tokens, embedding 128, residual width 256,
two residual blocks and FFN 512. The INT8 CNG1 export is 5,860,722 bytes.
One neural model handles empty, one-token and longer contexts. No count table,
context lookup, separate parent model or inference fallback is packaged.

Model SHA256:
`e650467f9f2ba35aefc478f8fa93f9e3151af04f10e6b673f8dba5c94126581c`.
Immutable SSD source:
`/Volumes/SSD/cantonese_ngram/experiments/count-coverage-repair-v14-support/audit-overall-final/model.cng`.

`config/language_model/selected_model.json` binds architecture, model checksum,
fusion checksum and native commit. `deploy.sh --release` stages this source and
`handwriting_fusion_v14.json`, rebuilds the shared native engines, and verifies
the actual AAB against its selected inputs. Handwriting keeps the frozen neural
weight 1.25, with zero count weights and no calibration fitting.

See [the repair and evaluation report](LANGUAGE_MODEL_COUNT_REPAIR_V14.md).
The new model ranks 麵 first after 雲吞. Common-phrase TRAIN count top-one improves
from 23.49% to 55.97%; prospective confirmation phrase top-three with two preceding
tokens improves from 17.95% to 21.70%. These are distinct metrics. Full-prefix
natural-text top-three changes from 30.03% to 29.53%, a slight regression.
